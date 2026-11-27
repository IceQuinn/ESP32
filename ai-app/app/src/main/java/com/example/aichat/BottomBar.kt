package com.example.aichat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 微信式绿：选中态的图标、文字、头像底色都用它。 */
val WeChatGreen = Color(0xFF07C160)

/** 未选中态：图标与文字同为深灰。 */
private val UnselectedGray = Color(0xFF3A3A3A)

private data class BottomTab(
    val label: String,
    val icon: ImageVector,
)

private val Tabs = listOf(
    BottomTab("聊天", Icons.AutoMirrored.Filled.Chat),
    BottomTab("IOT", Icons.Filled.Lightbulb),
    BottomTab("我", Icons.Filled.Person),
)

/**
 * 底部导航：仿微信观感。
 *
 * 这里自绘 Row 而不用 Material NavigationBar，原因：
 *  - NavigationBar 选中态会画 pill 形 indicator，微信风格里没有这个色块；
 *  - 它的选中/未选中色由主题派生，做不到「图标、文字、头像三者同时变绿」。
 * 固定 56dp 高度 + 手势条留白，比 Material 默认 80dp 更紧凑。
 */
@Composable
fun WeChatBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val tint = if (selected) WeChatGreen else UnselectedGray
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        // selectable 提供读屏语义与焦点，点击区域天然限定在格子内。
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(index) },
                            role = Role.Tab,
                            interactionSource = null,
                            indication = null,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = tab.label,
                        color = tint,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/**
 * 「我」页：微信风格的个人中心。
 *
 * 目前只放基本信息、AI 设置入口和灯控连接概览，
 * 后续如果要加头像/昵称，直接在顶部头部块里扩展即可。
 */
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 头部：绿色圆底头像 + 应用名
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(WeChatGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = "本机用户",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "DeepSeek Chat · ESP32 灯控",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        ProfileCell(title = "AI 设置", subtitle = "API Key、模型选择", onClick = onOpenSettings)
        ProfileCell(title = "关于", subtitle = "AI 对话 v1.0", onClick = null)
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileCell(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onClick != null) {
                    Modifier.selectable(
                        selected = false,
                        onClick = onClick,
                        role = Role.Button,
                        interactionSource = null,
                        indication = null,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Text(
                    text = "›",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 20.dp),
    )
}
