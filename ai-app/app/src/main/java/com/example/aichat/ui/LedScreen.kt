package com.example.aichat.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aichat.ble.BleRepository
import com.example.aichat.ble.BleState
import kotlinx.coroutines.launch

/**
 * 手动控灯页。
 *
 * 与聊天共用 [BleRepository] 的同一个连接，因此这里连上之后，
 * 聊天里发「打开 esp32 的灯」不需要重新扫描。
 */
@Composable
fun LedScreen(ble: BleRepository, modifier: Modifier = Modifier) {
    val state by ble.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }

    // 权限与蓝牙开关的申请结果都直接落回「开始扫描」，减少一次点击。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) {
            ble.startScan()
        }
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ble.startScan()
        }
    }

    fun requestScan() {
        if (!ble.hasPermissions()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            )
            return
        }
        if (!ble.isBluetoothEnabled()) {
            enableBluetoothLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
            )
            return
        }
        ble.startScan()
    }

    val connected = state as? BleState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "ESP32 灯控",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = statusLine(state),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("设备", connected?.deviceName ?: "—")
                Spacer(Modifier.height(8.dp))
                InfoRow("地址", connected?.address ?: "—")
                Spacer(Modifier.height(8.dp))
                InfoRow("灯光", ledLine(connected?.ledOn))
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = { requestScan() },
                enabled = !busy && connected == null,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state is BleState.Scanning) "扫描中…" else "扫描并连接")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = { ble.disconnect() },
                enabled = connected != null,
                modifier = Modifier.weight(1f),
            ) {
                Text("断开")
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        ble.setLed(true, autoConnect = false)
                        busy = false
                    }
                },
                enabled = connected != null && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("开灯")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        ble.setLed(false, autoConnect = false)
                        busy = false
                    }
                },
                enabled = connected != null && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("关灯")
            }
        }

        if (busy) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在下发指令…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "提示：连接成功后，可以直接在聊天里说「打开 esp32 的灯」，" +
                "App 会走本地指令，不消耗 API 额度。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun statusLine(state: BleState): String = when (state) {
    BleState.Idle -> "未连接。点下方「扫描并连接」查找 ESP32_BLE。"
    BleState.Scanning -> "正在扫描 ESP32_BLE…"
    is BleState.Connecting -> "正在连接 ${state.address}…"
    is BleState.Connected -> "已连接，可以控制灯光。"
    is BleState.Error -> "发生错误：${state.message}"
}

private fun ledLine(ledOn: Boolean?): String = when (ledOn) {
    true -> "已开启"
    false -> "已关闭"
    null -> "未知（等待读取）"
}
