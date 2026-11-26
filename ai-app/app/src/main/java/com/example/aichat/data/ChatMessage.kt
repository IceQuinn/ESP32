package com.example.aichat.data

import androidx.compose.runtime.Immutable

/**
 * 单条对话消息。
 *
 * role 取值为接口协议中的角色字符串：system / user / assistant。
 * timestamp 为该消息创建的时刻（毫秒），用于在气泡下方显示时间。
 * 目前只保存纯文本内容，图片等多模态内容留待后续扩展。
 */
@Immutable
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 消息来源。本地指令回执不来自模型，界面上需要单独标识，
     * 同时也避免用户误以为 AI 真的「控制」了硬件。
     */
    val kind: MessageKind = MessageKind.TEXT,
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** 消息类型。新增类型时必须保留 TEXT 作为默认值，旧会话 JSON 才不会受影响。 */
enum class MessageKind {
    /** 普通文本，来自用户或模型。 */
    TEXT,

    /** 由本地指令执行产生的回执，未经过模型。 */
    LOCAL_COMMAND,
}

/**
 * 一个可选的模型条目。
 *
 * id 是发送给接口的 model 字段，label 是界面上显示的中文说明。
 */
data class ModelOption(
    val id: String,
    val label: String,
    val description: String,
)

/**
 * DeepSeek 目前对外开放的两个模型。
 *
 * deepseek-chat 为通用对话模型，响应快、适合日常问答。
 * deepseek-reasoner 为推理模型，会先输出思维链，响应较慢但复杂问题更稳。
 */
object Models {
    val DEEPSEEK = listOf(
        ModelOption(
            id = "deepseek-chat",
            label = "DeepSeek Chat",
            description = "通用对话，响应快",
        ),
        ModelOption(
            id = "deepseek-reasoner",
            label = "DeepSeek Reasoner",
            description = "深度推理，响应较慢",
        ),
    )

    val DEFAULT_MODEL_ID = DEEPSEEK.first().id

    fun find(modelId: String): ModelOption =
        DEEPSEEK.firstOrNull { it.id == modelId } ?: DEEPSEEK.first()
}
