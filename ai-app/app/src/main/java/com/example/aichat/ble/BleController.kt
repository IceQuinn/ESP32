package com.example.aichat.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE 连接状态。
 *
 * UI 与聊天指令都以这个状态为准，避免各自维护一份「连没连上」的布尔值。
 */
sealed interface BleState {
    /** 未连接，也没有在扫描。 */
    data object Idle : BleState

    /** 正在按服务 UUID 扫描 ESP32_BLE。 */
    data object Scanning : BleState

    /** 已发现设备，正在建立 GATT 连接。 */
    data class Connecting(val address: String) : BleState

    /** 已连接并发现 LED 特征；ledOn 为 null 表示还没读到状态。 */
    data class Connected(
        val deviceName: String?,
        val address: String,
        val ledOn: Boolean?,
    ) : BleState

    /** 任意环节失败，message 直接用于界面提示。 */
    data class Error(val message: String) : BleState
}

/**
 * ESP32 BLE 外设的控制器。
 *
 * 协议与固件保持一致：服务 12345678-...def0，特征 ...def1，
 * 写单字节 0x01 开灯、0x00 关灯，读一字节取当前状态。
 *
 * 所有状态变化通过 [onState] 回调抛出，回调可能来自 Binder 线程，
 * 但 [BleState] 是不可变数据且 MutableStateFlow 线程安全，因此可以直接转发。
 */
class BleController(
    private val appContext: Context,
    private val onState: (BleState) -> Unit,
) {

    companion object {
        val LED_SERVICE_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val LED_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

        /** 扫描超时，避免一直停留在「正在扫描」。 */
        const val SCAN_TIMEOUT_MS = 10_000L

        /** 单次写入等待写响应的上限。 */
        private const val WRITE_TIMEOUT_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter

    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var writeWaiter: CompletableDeferred<Boolean>? = null
    private var deviceName: String? = null
    private var currentState: BleState = BleState.Idle

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScanInternal()
            publish(BleState.Error("扫描超时，未找到 ESP32_BLE"))
        }
    }

    /** 状态出口：先记录本地副本，再通知订阅者。 */
    private fun publish(state: BleState) {
        currentState = state
        onState(state)
    }

    fun hasPermissions(): Boolean {
        val scan = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
        val connect = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        return scan == PackageManager.PERMISSION_GRANTED &&
            connect == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            publish(BleState.Error("缺少「附近设备」权限"))
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            publish(BleState.Error("本机不支持蓝牙"))
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            publish(BleState.Error("请先打开手机蓝牙"))
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            publish(BleState.Error("无法获取 BLE 扫描器"))
            return
        }

        stopScanInternal()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(LED_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        publish(BleState.Scanning)
        scanner.startScan(listOf(filter), settings, scanCallback)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 本 App 只控制一台设备，扫到第一台就停止并连接。
            stopScanInternal()
            deviceName = result.scanRecord?.deviceName ?: result.device.name
            publish(BleState.Connecting(result.device.address))
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.removeCallbacks(scanTimeout)
            publish(BleState.Error("BLE 扫描失败：$errorCode"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt?.close()
        ledCharacteristic = null
        gatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val code = status
                g.close()
                if (gatt === g) {
                    gatt = null
                    ledCharacteristic = null
                }
                publish(BleState.Error("连接失败，GATT 状态：$code"))
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!g.discoverServices()) {
                        publish(BleState.Error("无法启动服务发现"))
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    if (gatt === g) {
                        gatt = null
                        ledCharacteristic = null
                    }
                    publish(BleState.Idle)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publish(BleState.Error("服务发现失败：$status"))
                return
            }
            val characteristic = g.getService(LED_SERVICE_UUID)
                ?.getCharacteristic(LED_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                publish(BleState.Error("设备没有 LED 控制特征"))
                return
            }

            ledCharacteristic = characteristic
            publish(BleState.Connected(deviceName, g.device.address, null))
            // 连上后立刻读一次，界面不用等用户操作就能显示真实状态。
            readLedState()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic, value, status)
        }

        @Deprecated("Android 12L 及以下走这个重载")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: byteArrayOf()
            handleRead(characteristic, value, status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val waiter = writeWaiter
            writeWaiter = null
            waiter?.complete(status == BluetoothGatt.GATT_SUCCESS)
            // 写入成功后回读一次，确保界面显示的是设备真实状态。
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readLedState()
            }
        }
    }

    private fun handleRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (characteristic.uuid != LED_CHARACTERISTIC_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS || value.isEmpty()) return
        val connected = currentState as? BleState.Connected ?: return
        publish(connected.copy(ledOn = value[0].toInt() != 0))
    }

    /**
     * 写入开关指令并等待写响应。
     *
     * 返回 true 只代表 ESP32 对写请求回了确认，不代表灯一定被点亮；
     * 真实状态随后由回调里的回读更新。
     */
    @SuppressLint("MissingPermission")
    suspend fun writeLed(on: Boolean): Boolean {
        val g = gatt ?: return false
        val characteristic = ledCharacteristic ?: return false

        val waiter = CompletableDeferred<Boolean>()
        writeWaiter = waiter
        val payload = byteArrayOf(if (on) 1 else 0)

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }

        if (!started) {
            writeWaiter = null
            return false
        }

        val result = withTimeoutOrNull(WRITE_TIMEOUT_MS) { waiter.await() } ?: false
        writeWaiter = null
        return result
    }

    @SuppressLint("MissingPermission")
    fun readLedState() {
        val g = gatt ?: return
        val characteristic = ledCharacteristic ?: return
        g.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanInternal()
        ledCharacteristic = null
        gatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScanInternal()
        gatt?.close()
        gatt = null
        ledCharacteristic = null
        writeWaiter = null
        publish(BleState.Idle)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (scanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
        }
        mainHandler.removeCallbacks(scanTimeout)
    }
}
