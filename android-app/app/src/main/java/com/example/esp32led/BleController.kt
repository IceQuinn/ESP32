package com.example.esp32led

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

class BleController(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatusChanged(status: String)
        fun onDeviceFound(name: String, address: String)
        fun onReady()
        fun onLedStateChanged(isOn: Boolean)
        fun onError(message: String)
    }

    companion object {
        val LED_SERVICE_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val LED_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

        private const val SCAN_TIMEOUT_MS = 10_000L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter
        get() = bluetoothManager.adapter

    private var scanning = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            listener.onError("扫描超时，未发现 ESP32_BLE")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()

            val name = result.scanRecord?.deviceName
                ?: result.device.name
                ?: "ESP32_BLE"
            listener.onDeviceFound(name, result.device.address)
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.removeCallbacks(scanTimeout)
            listener.onError("BLE 扫描失败：$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.close()
                bluetoothGatt = null
                ledCharacteristic = null
                postError("连接失败，GATT 状态：$status")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    postStatus("已连接，正在发现服务…")
                    if (!gatt.discoverServices()) {
                        postError("无法启动服务发现")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    bluetoothGatt = null
                    ledCharacteristic = null
                    postStatus("连接已断开")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postError("服务发现失败：$status")
                return
            }

            val service: BluetoothGattService? =
                gatt.getService(LED_SERVICE_UUID)
            val characteristic =
                service?.getCharacteristic(LED_CHARACTERISTIC_UUID)

            if (characteristic == null) {
                postError("设备没有 LED 控制特征")
                return
            }

            ledCharacteristic = characteristic
            mainHandler.post {
                listener.onStatusChanged("已连接，可以控制")
                listener.onReady()
            }
            readLedState()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleLedRead(characteristic, value, status)
        }

        @Deprecated("Used on Android 12L and lower")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleLedRead(characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != LED_CHARACTERISTIC_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                readLedState()
            } else {
                postError("灯控指令写入失败：$status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            listener.onError("请先打开手机蓝牙")
            return
        }

        stopScan()
        listener.onStatusChanged("正在扫描 ESP32_BLE…")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(LED_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("无法获取 BLE 扫描器")
            return
        }

        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (scanning) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
        }
        mainHandler.removeCallbacks(scanTimeout)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        listener.onStatusChanged("正在连接…")
        bluetoothGatt?.close()
        ledCharacteristic = null
        bluetoothGatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setLed(isOn: Boolean) {
        val gatt = bluetoothGatt
        val characteristic = ledCharacteristic
        if (gatt == null || characteristic == null) {
            listener.onError("ESP32 尚未连接")
            return
        }

        val value = byteArrayOf(if (isOn) 1.toByte() else 0.toByte())
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            listener.onError("无法发送灯控指令")
        }
    }

    @SuppressLint("MissingPermission")
    fun readLedState() {
        val gatt = bluetoothGatt ?: return
        val characteristic = ledCharacteristic ?: return
        if (!gatt.readCharacteristic(characteristic)) {
            listener.onError("无法读取灯状态")
        }
    }

    private fun handleLedRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (characteristic.uuid != LED_CHARACTERISTIC_UUID) return

        if (status != BluetoothGatt.GATT_SUCCESS || value.size != 1) {
            postError("读取灯状态失败：$status")
            return
        }

        mainHandler.post {
            listener.onLedStateChanged(value[0].toInt() != 0)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        ledCharacteristic = null
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        ledCharacteristic = null
    }

    private fun postStatus(status: String) {
        mainHandler.post { listener.onStatusChanged(status) }
    }

    private fun postError(message: String) {
        mainHandler.post { listener.onError(message) }
    }
}

