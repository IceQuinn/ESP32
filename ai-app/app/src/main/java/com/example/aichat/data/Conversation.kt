package com.example.aichat.data

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * 一次完整对话会话。
 *
 * updatedAt 用于历史列表排序（最近活跃的排最前）与时间显示。
 */
@Immutable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean
        get() = messages.isEmpty()

    /** 最近一条消息的时间，没有消息时退回创建时间。 */
    val lastActiveAt: Long
        get() = messages.lastOrNull()?.timestamp ?: updatedAt

    /**
     * 用首条用户消息生成标题，超长时截断，避免标题栏被撑爆。
     */
    fun deriveTitle(): String {
        val firstUser = messages.firstOrNull { it.role == ChatMessage.ROLE_USER }
            ?: return "新对话"
        val normalized = firstUser.content.replace('\n', ' ').trim()
        return if (normalized.length > 18) normalized.take(18) + "…" else normalized
    }
}
