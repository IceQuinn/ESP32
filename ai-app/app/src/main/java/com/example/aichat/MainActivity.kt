package com.example.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.aichat.ble.BleRepository
import com.example.aichat.data.SettingsStore
import com.example.aichat.ui.AiChatTheme
import com.example.aichat.ui.ChatScreen
import com.example.aichat.ui.ChatViewModel
import com.example.aichat.ui.LedScreen
import com.example.aichat.ui.SettingsScreen

/**
 * 单 Activity + Compose。
 *
 * 主界面是「聊天 / 灯控」两个底部 Tab，设置页仍用状态切换覆盖在上层，
 * 这样不引入 Navigation 依赖也能保持返回栈语义。
 */
class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // 白色系界面：状态栏/导航栏图标统一使用深色，避免白图标不可见。
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        // 首次启动若没有 Key，直接进入设置页引导配置。
        val firstRunNeedsSetup = !SettingsStore(this).isConfigured

        setContent {
            AiChatTheme {
                AppRoot(
                    chatViewModel = chatViewModel,
                    startOnSettings = firstRunNeedsSetup,
                )
            }
        }
    }

    override fun onDestroy() {
        // 真正退出时释放 GATT，避免设备端一直占着一个连接槽。
        if (isFinishing) {
            BleRepository.get(this).close()
        }
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(
    chatViewModel: ChatViewModel,
    startOnSettings: Boolean,
) {
    var showSettings by remember { mutableStateOf(startOnSettings) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current
    val ble = remember { BleRepository.get(context) }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            onBack = {
                chatViewModel.refreshSettings()
                showSettings = false
            },
            onSaved = {
                chatViewModel.refreshSettings()
                showSettings = false
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 状态栏留白由各页面自己处理，这里只负责底部导航栏的高度。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "聊天",
                        )
                    },
                    label = { Text("聊天") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = {
                        Icon(
                            Icons.Filled.Lightbulb,
                            contentDescription = "灯控",
                        )
                    },
                    label = { Text("灯控") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> ChatScreen(
                viewModel = chatViewModel,
                onOpenSettings = { showSettings = true },
                modifier = Modifier.padding(padding),
            )

            else -> LedScreen(
                ble = ble,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
