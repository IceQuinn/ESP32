package com.example.aichat.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE 连接的唯一入口。
 *
 * 聊天页与灯控页必须共用同一个 GATT 连接，否则会各连一次，
 * 既浪费 ESP32 的连接槽，也会出现两个页面状态不一致。
 * 因此这里做成应用级单例，只持有 applicationContext，不会泄漏 Activity。
 */
class BleRepository private constructor(appContext: Context) {

    private val _state = MutableStateFlow<BleState>(BleState.Idle)

    /** 供 Compose 收集的连接状态。 */
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val controller = BleController(appContext) { newState ->
        _state.value = newState
    }

    companion object {
        @Volatile
        private var instance: BleRepository? = null

        fun get(context: Context): BleRepository =
            instance ?: synchronized(this) {
                instance ?: BleRepository(context.applicationContext).also {
                    instance = it
                }
            }
    }

    val isConnected: Boolean
        get() = _state.value is BleState.Connected

    fun hasPermissions(): Boolean = controller.hasPermissions()

    fun isBluetoothEnabled(): Boolean = controller.isBluetoothEnabled()

    fun startScan() = controller.startScan()

    fun disconnect() = controller.disconnect()

    fun close() = controller.close()

    /**
     * 确保已连接；未连接时自动扫描并等待结果。
     *
     * 返回 null 表示超时、缺少权限或被拒绝，调用方据此给出提示。
     */
    suspend fun ensureConnected(timeoutMs: Long = 12_000L): BleState.Connected? {
        (_state.value as? BleState.Connected)?.let { return it }
        if (!controller.hasPermissions()) return null
        if (_state.value !is BleState.Scanning && _state.value !is BleState.Connecting) {
            controller.startScan()
        }
        val settled = withTimeoutOrNull(timeoutMs) {
            // Idle 只会在断开或初始状态出现，同样视为本次等待失败。
            _state.first { it is BleState.Connected || it is BleState.Error || it is BleState.Idle }
        }
        return settled as? BleState.Connected
    }

    /**
     * 控灯：必要时自动连接，然后写入开关并等待写响应。
     */
    suspend fun setLed(on: Boolean, autoConnect: Boolean = true): Boolean {
        if (_state.value !is BleState.Connected) {
            if (!autoConnect) return false
            if (ensureConnected() == null) return false
        }
        return controller.writeLed(on)
    }

    /** 供聊天回执使用的失败原因。 */
    fun failureReason(): String = when (val current = _state.value) {
        is BleState.Error -> current.message
        BleState.Scanning -> "正在扫描 ESP32_BLE，请稍后重试"
        is BleState.Connecting -> "正在连接 ESP32，请稍后重试"
        is BleState.Connected -> "ESP32 已连接但没有响应，请重试"
        BleState.Idle -> if (!controller.hasPermissions()) {
            "缺少蓝牙权限，请到「灯控」页授权"
        } else {
            "未连接到 ESP32，请确认设备已上电"
        }
    }
}
