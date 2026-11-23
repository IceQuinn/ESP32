package com.example.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichat.data.SettingsStore
import com.example.aichat.ui.AiChatTheme
import com.example.aichat.ui.ChatScreen
import com.example.aichat.ui.ChatViewModel
import com.example.aichat.ui.SettingsScreen

/**
 * 单 Activity + Compose：聊天页与设置页之间用状态切换，避免引入 Navigation 依赖。
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
}

@Composable
private fun AppRoot(
    chatViewModel: ChatViewModel,
    startOnSettings: Boolean,
) {
    var showSettings by remember { mutableStateOf(startOnSettings) }

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
    } else {
        ChatScreen(
            viewModel = chatViewModel,
            onOpenSettings = { showSettings = true },
        )
    }
}
