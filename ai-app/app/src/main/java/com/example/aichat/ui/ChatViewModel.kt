package com.example.aichat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.ble.BleRepository
import com.example.aichat.ble.LedAction
import com.example.aichat.ble.LedCommandParser
import com.example.aichat.data.ChatMessage
import com.example.aichat.data.Conversation
import com.example.aichat.data.ConversationStore
import com.example.aichat.data.DeepSeekClient
import com.example.aichat.data.MessageKind
import com.example.aichat.data.Models
import com.example.aichat.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 聊天页面的状态与业务逻辑。
 *
 * 会话模型：
 *  - current 为正在显示的会话，可以继续提问；
 *  - history 为其他已归档的会话，可从历史面板重新打开。
 *
 * 持久化：所有会话（当前 + 历史）在变化后写入本地文件，
 * 启动时自动恢复，并打开最近活跃的那个会话。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val conversationStore = ConversationStore(application)

    /** 与灯控页共用同一个 BLE 连接。 */
    private val ble = BleRepository.get(application)

    data class UiState(
        val current: Conversation = Conversation(),
        val history: List<Conversation> = emptyList(),
        val input: String = "",
        val isStreaming: Boolean = false,
        val error: String? = null,
        val reasoningVisible: String = "",
        val apiKeyConfigured: Boolean = false,
        val modelId: String = Models.DEFAULT_MODEL_ID,
        val historyOpen: Boolean = false,
        val restored: Boolean = false,
    ) {
        val messages: List<ChatMessage>
            get() = current.messages

        val canSend: Boolean
            get() = input.isNotBlank() && !isStreaming && apiKeyConfigured
    }

    private val _state = MutableStateFlow(
        UiState(
            apiKeyConfigured = settings.isConfigured,
            modelId = settings.modelId,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        restore()
    }

    /** 启动时恢复上次的会话与历史。 */
    private fun restore() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { conversationStore.loadConversations() }
            if (loaded.isEmpty()) {
                _state.update {
                    it.copy(current = Conversation(), history = emptyList(), restored = true)
                }
                return@launch
            }
            // 最近活跃的会话作为当前会话打开，其余按时间倒序排进历史。
            val sorted = loaded.sortedByDescending { it.lastActiveAt }
            _state.update {
                it.copy(
                    current = sorted.first(),
                    history = sorted.drop(1),
                    restored = true,
                )
            }
        }
    }

    /** 把当前状态写入本地文件。调用时机为消息或会话发生变化之后。 */
    private fun persist() {
        val snapshot = _state.value
        val all = listOf(snapshot.current) + snapshot.history
        // 空会话且没有任何历史时不必落盘。
        if (all.all { it.isEmpty }) return
        viewModelScope.launch(Dispatchers.IO) {
            conversationStore.saveConversations(all.filterNot { it.isEmpty })
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun toggleHistory(open: Boolean = true) {
        _state.update { it.copy(historyOpen = open) }
    }

    /** 设置页保存后刷新，使聊天页立刻反映最新的 Key 与模型。 */
    fun refreshSettings() {
        _state.update {
            it.copy(
                apiKeyConfigured = settings.isConfigured,
                modelId = settings.modelId,
            )
        }
    }

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        val userText = current.input.trim()

        // 第一层：本地规则。命中就直接执行，完全不经过网络。
        val localAction = LedCommandParser.parse(userText)
        if (localAction != null) {
            runLedCommand(userText, localAction)
            return
        }

        // 第二层：提到了灯/ESP32 但说法不明确，交给 AI 判断是否要控灯。
        if (LedCommandParser.isDeviceRelated(userText) && settings.isConfigured) {
            runIntentCheck(userText)
            return
        }

        startChatReply(userText)
    }

    /** 执行本地控灯指令，并在会话里留下一条本地回执。 */
    private fun runLedCommand(userText: String, action: LedAction) {
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(ChatMessage.ROLE_USER, userText, timestamp = now)

        _state.update { st ->
            val conv = st.current
            st.copy(
                current = conv.copy(
                    // 先放一条空的助手消息占位，执行完成后填入回执，
                    // 用户能立刻看到「已经收到指令」。
                    messages = conv.messages + userMessage + ChatMessage(
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = "",
                        timestamp = now,
                        kind = MessageKind.LOCAL_COMMAND,
                    ),
                    updatedAt = now,
                    title = if (conv.title == "新对话") {
                        firstUserTitle(conv.messages + userMessage)
                    } else {
                        conv.title
                    },
                ),
                input = "",
                isStreaming = true,
                error = null,
                reasoningVisible = "",
            )
        }

        viewModelScope.launch {
            val ok = ble.setLed(action == LedAction.ON)
            val reply = if (ok) {
                if (action == LedAction.ON) {
                    "已为你打开 ESP32 的灯。"
                } else {
                    "已为你关闭 ESP32 的灯。"
                }
            } else {
                "没能控制 ESP32 的灯：${ble.failureReason()}"
            }
            completeLocalReply(reply)
            _state.update { it.copy(isStreaming = false) }
            persist()
        }
    }

    /**
     * 用一次轻量的 Function Calling 判断意图。
     *
     * 判断结果不是控灯指令时，照常把这条消息发给 AI，
     * 所以「ESP32 的灯怎么接」这类问题仍然由模型回答。
     */
    private fun runIntentCheck(userText: String) {
        _state.update { it.copy(isStreaming = true, error = null) }
        viewModelScope.launch {
            val action = withContext(Dispatchers.IO) {
                runCatching {
                    DeepSeekClient.detectLedAction(
                        apiKey = settings.apiKey,
                        modelId = settings.modelId,
                        text = userText,
                    )
                }.getOrNull()
            }
            _state.update { it.copy(isStreaming = false) }

            val parsed = when (action) {
                "on" -> LedAction.ON
                "off" -> LedAction.OFF
                else -> null
            }
            if (parsed != null) {
                runLedCommand(userText, parsed)
            } else {
                startChatReply(userText)
            }
        }
    }

    /** 用回执内容替换占位的空助手消息。 */
    private fun completeLocalReply(text: String) {
        _state.update { st ->
            val messages = st.current.messages.toMutableList()
            val index = messages.indexOfLast { it.role == ChatMessage.ROLE_ASSISTANT }
            if (index >= 0 && messages[index].content.isEmpty()) {
                messages[index] = messages[index].copy(content = text)
            } else {
                messages.add(
                    ChatMessage(
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = text,
                        kind = MessageKind.LOCAL_COMMAND,
                    ),
                )
            }
            st.copy(current = st.current.copy(messages = messages))
        }
    }

    /** 普通聊天：发送给 DeepSeek 并逐字流式显示。 */
    private fun startChatReply(userText: String) {
        val current = _state.value
        val now = System.currentTimeMillis()

        val history = current.messages +
            ChatMessage(ChatMessage.ROLE_USER, userText, timestamp = now)
        val withPlaceholder = current.current.copy(
            // 先插入用户消息和一条空的助手消息，流式内容会逐步填充后者。
            messages = history + ChatMessage(ChatMessage.ROLE_ASSISTANT, "", timestamp = now),
            updatedAt = now,
            title = if (current.current.title == "新对话") {
                firstUserTitle(history)
            } else {
                current.current.title
            },
        )

        _state.update {
            it.copy(
                current = withPlaceholder,
                input = "",
                isStreaming = true,
                error = null,
                reasoningVisible = "",
            )
        }

        val apiKey = settings.apiKey
        val modelId = settings.modelId
        val conversationId = current.current.id

        streamJob = viewModelScope.launch {
            try {
                DeepSeekClient.streamChat(
                    apiKey = apiKey,
                    modelId = modelId,
                    messages = history,
                    onDelta = { chunk -> appendToAssistant(conversationId, chunk) },
                    onReasoning = { chunk ->
                        _state.update { it.copy(reasoningVisible = it.reasoningVisible + chunk) }
                    },
                    // 用户点“停止”会取消本协程，这里直接读取协程自身的存活状态。
                    isCancelled = { !coroutineContext.isActive },
                )
            } catch (e: Exception) {
                // 用户主动停止时协程被取消，不应弹出错误。
                if (e is kotlinx.coroutines.CancellationException) {
                    return@launch
                }
                _state.update { st ->
                    val conv = st.current
                    // 助手消息若一个字都没收到，就移除这条占位消息。
                    val msgs = if (conv.messages.lastOrNull()?.content.isNullOrEmpty()) {
                        conv.messages.dropLast(1)
                    } else {
                        conv.messages
                    }
                    st.copy(current = conv.copy(messages = msgs), error = friendlyMessage(e))
                }
            } finally {
                _state.update { it.copy(isStreaming = false, reasoningVisible = "") }
                persist()
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _state.update { it.copy(isStreaming = false) }
        persist()
    }

    /**
     * 开始新对话：把当前会话归档进历史列表，然后切换到空会话。
     *
     * 若当前会话为空（尚未发过消息），则不入历史，直接复用，避免堆积空会话。
     */
    fun newConversation() {
        stop()
        _state.update { st ->
            val current = st.current
            if (current.isEmpty) {
                st.copy(historyOpen = false, error = null)
            } else {
                st.copy(
                    current = Conversation(),
                    history = listOf(current) + st.history,
                    error = null,
                    reasoningVisible = "",
                    historyOpen = false,
                )
            }
        }
        persist()
    }

    /** 从历史列表打开某个会话。当前会话若已有内容，先归档，避免丢失。 */
    fun openConversation(conversation: Conversation) {
        stop()
        _state.update { st ->
            if (conversation.id == st.current.id) {
                st.copy(historyOpen = false)
            } else {
                val archived = if (st.current.isEmpty) {
                    st.history
                } else {
                    listOf(st.current) + st.history.filterNot { it.id == st.current.id }
                }
                st.copy(
                    current = conversation,
                    history = archived.filterNot { it.id == conversation.id },
                    historyOpen = false,
                    reasoningVisible = "",
                    error = null,
                )
            }
        }
        persist()
    }

    fun deleteConversation(conversation: Conversation) {
        _state.update { st ->
            if (conversation.id == st.current.id) {
                st.copy(
                    current = Conversation(),
                    history = st.history.filterNot { it.id == conversation.id },
                )
            } else {
                st.copy(history = st.history.filterNot { it.id == conversation.id })
            }
        }
        persist()
    }

    /** 清空当前会话内容，但保留会话本身。 */
    fun clearConversation() {
        stop()
        _state.update {
            it.copy(
                current = it.current.copy(messages = emptyList(), title = "新对话"),
                isStreaming = false,
                error = null,
                reasoningVisible = "",
            )
        }
        persist()
    }

    /** 仅当会话仍是当前会话时追加内容，防止切换会话后流式结果写到错误的会话里。 */
    private fun appendToAssistant(conversationId: String, chunk: String) {
        if (chunk.isEmpty()) return
        _state.update { st ->
            if (st.current.id != conversationId) return@update st
            val msgs = st.current.messages.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && last.role == ChatMessage.ROLE_ASSISTANT) {
                msgs[msgs.lastIndex] = last.copy(content = last.content + chunk)
            }
            st.copy(current = st.current.copy(messages = msgs))
        }
    }

    private fun firstUserTitle(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == ChatMessage.ROLE_USER } ?: return "新对话"
        val normalized = first.content.replace('\n', ' ').trim()
        return if (normalized.length > 18) normalized.take(18) + "…" else normalized
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is DeepSeekClient.ApiException -> e.message
        is UnknownHostException -> "无法连接网络，请检查网络后重试。"
        is SocketTimeoutException -> "请求超时，请稍后重试。"
        is IOException -> "网络异常：${e.message ?: "连接中断"}"
        is IllegalArgumentException -> e.message ?: "请求参数错误。"
        else -> "发生未知错误：${e.message ?: e.javaClass.simpleName}"
    }
}
