package com.example.aichat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 白色系配色，参考 DeepSeek / ChatGPT 移动端的观感：
 *  - 页面背景纯白，AI 回复用浅灰底块，不加描边
 *  - 用户消息用浅灰气泡（而非深色实心），与 AI 区块形成层次而非对比
 *  - 强调色为中性深灰，仅少量用于按钮与选中态
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F0FF),
    onPrimaryContainer = Color(0xFF0B2A66),
    secondary = Color(0xFF5C6470),
    secondaryContainer = Color(0xFFF0F1F3),
    onSecondaryContainer = Color(0xFF2A2F36),
    // 页面背景：极浅的灰白，让纯白的输入区与消息区分开一点
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    // AI 回复气泡 / 次级容器
    surfaceVariant = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFF6B7280),
    outlineVariant = Color(0xFFE8EAED),
    error = Color(0xFFD93025),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF8C1D18),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EA6FF),
    onPrimary = Color(0xFF00205C),
    primaryContainer = Color(0xFF1E3A6E),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFFB9C0CB),
    secondaryContainer = Color(0xFF262A30),
    onSecondaryContainer = Color(0xFFE2E4E8),
    background = Color(0xFF1A1B1E),
    onBackground = Color(0xFFE8E9EB),
    surface = Color(0xFF222327),
    onSurface = Color(0xFFE8E9EB),
    surfaceVariant = Color(0xFF2C2D31),
    onSurfaceVariant = Color(0xFF9AA1AC),
    outlineVariant = Color(0xFF3A3C42),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF5C1B17),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun AiChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
