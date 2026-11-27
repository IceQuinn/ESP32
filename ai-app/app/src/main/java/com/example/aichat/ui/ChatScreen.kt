package com.example.aichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aichat.WeChatGreen
import com.example.aichat.data.ChatMessage
import com.example.aichat.data.Conversation
import com.example.aichat.data.MessageKind
import com.example.aichat.data.Models
import com.example.aichat.util.TimeFormat

/**
 * 聊天主界面：消息列表 + 输入框。
 *
 * 视觉参考 DeepSeek / ChatGPT 移动端：
 *  - 纯白页面，AI 回复为浅灰背景块，无描边
 *  - 用户消息为浅灰气泡、右对齐
 *  - 每条气泡下方显示时间；跨天时插入日期分隔
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState()

    // 语音输入的麦克风权限：首次点击时申请，拒绝后只提示不阻塞输入。
    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.toggleVoiceInput()
    }
    val onMicClick: () -> Unit = remember {
        {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.toggleVoiceInput()
            } else {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // 页面离开（切到灯控页、进设置）时释放识别服务。
    DisposableEffect(Unit) {
        onDispose { viewModel.releaseVoiceInput() }
    }

    // 新消息或流式内容增长时自动滚动到底部。
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { viewModel.toggleHistory(true) }) {
                        Text(
                            text = state.current.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = Models.find(state.modelId).label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::newConversation) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "新对话",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            InputBar(
                text = state.displayInput,
                enabled = !state.isStreaming && !state.isListening,
                canSend = state.canSend,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                isStreaming = state.isStreaming,
                isListening = state.isListening,
                canStartVoice = state.canStartVoice,
                voiceHint = state.voiceHint,
                onMicClick = onMicClick,
                onDismissVoiceHint = viewModel::dismissVoiceHint,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.apiKeyConfigured) {
                ApiKeyHint(onOpenSettings)
            }

            state.error?.let { error ->
                ErrorBanner(message = error, onDismiss = viewModel::dismissError)
            }

            if (state.reasoningVisible.isNotBlank() && state.isStreaming) {
                ReasoningPanel(text = state.reasoningVisible)
            }

            if (state.messages.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages) { message ->
                        val index = state.messages.indexOf(message)
                        val showDivider = index == 0 ||
                            !TimeFormat.sameDay(
                                state.messages[index - 1].timestamp,
                                message.timestamp,
                            )
                        Column {
                            if (showDivider) {
                                DayDivider(TimeFormat.dayDivider(message.timestamp))
                                Spacer(Modifier.height(2.dp))
                            }
                            MessageBubble(
                                message = message,
                                isStreamingPlaceholder =
                                    state.isStreaming && message.content.isEmpty() &&
                                        message.role == ChatMessage.ROLE_ASSISTANT,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.historyOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.toggleHistory(false) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            HistorySheet(
                history = state.history,
                currentTitle = state.current.title,
                onOpen = viewModel::openConversation,
                onDelete = viewModel::deleteConversation,
            )
        }
    }
}

/** 跨天分隔标签。 */
@Composable
private fun DayDivider(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // 微信的时间分隔是纯灰字居中，不加胶囊底色，视觉上更轻。
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

/** 历史对话面板。 */
@Composable
private fun HistorySheet(
    history: List<Conversation>,
    currentTitle: String,
    onOpen: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史对话", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "当前：$currentTitle",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (history.isEmpty()) {
            Text(
                text = "还没有历史对话。\n点左上角「+」开始新对话后，之前的会话会保存在这里。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(history) { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(conversation) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = conversation.title,
                                fontSize = 15.sp,
                                maxLines = 1,
                            )
                            Text(
                                text = "${conversation.messages.size} 条消息 · " +
                                    TimeFormat.conversationTime(conversation.lastActiveAt),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(conversation) }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isStreamingPlaceholder: Boolean) {
    val isUser = message.role == ChatMessage.ROLE_USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = if (isUser) {
                RoundedCornerShape(6.dp)
            } else {
                // AI 气泡左上角留尖角，模拟微信消息尾巴的指向感。
                RoundedCornerShape(topStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 6.dp)
            },
            // 微信配色：自己绿色、对方白色，靠颜色 + 对齐方向区分，不加姓名标签。
            color = if (isUser) WeChatGreen else Color(0xFFFFFFFF),
            shadowElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (isStreamingPlaceholder) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (message.kind == MessageKind.LOCAL_COMMAND) {
                                "正在执行指令…"
                            } else {
                                "正在思考"
                            },
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isUser) {
                                    Color.White
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        // 本地回执不是模型生成的，明确标出来避免误解。
                        if (message.kind == MessageKind.LOCAL_COMMAND) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "⚡ 本地指令",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isUser) {
                                    Color.White.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = TimeFormat.messageTime(message.timestamp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun InputBar(
    text: String,
    enabled: Boolean,
    canSend: Boolean,
    isStreaming: Boolean,
    isListening: Boolean,
    canStartVoice: Boolean,
    voiceHint: String?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMicClick: () -> Unit,
    onDismissVoiceHint: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (isListening || voiceHint != null) {
                VoiceStatusBar(
                    isListening = isListening,
                    hint = voiceHint,
                    onDismissHint = onDismissVoiceHint,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 输入框用 Surface + BasicTextField 自绘，不用 OutlinedTextField：
                // Material 的 OutlinedTextField 默认内边距让最小高度下不去，做不出微信那种 36dp 的矮框。
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF7F7F7),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                if (isListening) "正在听，请说话…" else "给 AI 发送消息…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChange,
                            enabled = enabled,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (!isStreaming) {
                    // 用 Surface 画按钮：IconButton 默认的圆形水波纹会盖掉圆角方形背景，
                    // 直接 background() 会出现「圆形按下态 + 方形底色」割裂的效果。
                    Surface(
                        onClick = onMicClick,
                        enabled = canStartVoice || isListening,
                        shape = CircleShape,
                        color = if (isListening) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color(0xFFF2F2F2)
                        },
                        contentColor = if (isListening) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = if (isListening) "结束语音输入" else "语音输入",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (isStreaming) {
                    Surface(
                        onClick = onStop,
                        shape = CircleShape,
                        color = WeChatGreen,
                        contentColor = Color.White,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = onSend,
                        enabled = canSend,
                        shape = CircleShape,
                        color = if (canSend) WeChatGreen else Color(0xFFE6E6E6),
                        contentColor = if (canSend) Color.White else Color(0xFFB0B0B0),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 语音输入的状态条：识别中显示「正在听」，识别失败/无结果时显示一次性提示。
 * 识别中没有录音波形（系统识别服务不提供稳定的音量回调跨机型），
 * 因此只用文字提示，避免动画在不同机型上表现不一致。
 */
@Composable
private fun VoiceStatusBar(
    isListening: Boolean,
    hint: String?,
    onDismissHint: () -> Unit,
) {
    Surface(
        color = if (isListening) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isListening) "🎙 正在识别，识别完成会自动发送" else (hint ?: ""),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isListening) {
                TextButton(onClick = onDismissHint) { Text("知道了", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun ApiKeyHint(onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "尚未配置 API Key",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun ReasoningPanel(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "推理中…",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = text.takeLast(600),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "有什么可以帮你？",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "在下方输入问题，AI 会实时逐字回复",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
