package com.example.aichat.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话持久化存储。
 *
 * 实现方式：把全部会话序列化成 JSON 写入应用私有目录下的文件。
 * 选择文件而非数据库，是为了避免引入 Room 等额外依赖，
 * 且会话数量在个人使用规模下（几十个）完全够用。
 *
 * 写入采用“先写临时文件再改名”的方式，避免中途崩溃导致文件损坏。
 */
class ConversationStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /** 读取全部会话；文件不存在或解析失败时返回空列表，不影响应用启动。 */
    fun loadConversations(): List<Conversation> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(obj.toConversation())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取会话历史失败，忽略本次数据", e)
            emptyList()
        }
    }

    /** 覆盖写入全部会话。 */
    fun saveConversations(conversations: List<Conversation>) {
        try {
            val array = JSONArray()
            conversations.forEach { array.put(it.toJson()) }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(array.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // 改名在某些文件系统上可能失败，退化为直接写入。
                file.writeText(array.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存会话历史失败", e)
        }
    }

    private fun Conversation.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    put("timestamp", m.timestamp)
                }
            )
        }
        put("messages", arr)
    }

    private fun JSONObject.toConversation(): Conversation {
        val now = System.currentTimeMillis()
        val arr = optJSONArray("messages")
        val messages = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    add(
                        ChatMessage(
                            role = m.optString("role", ChatMessage.ROLE_USER),
                            content = m.optString("content", ""),
                            timestamp = m.optLong("timestamp", now),
                        )
                    )
                }
            }
        }
        return Conversation(
            id = optString("id", java.util.UUID.randomUUID().toString()),
            title = optString("title", "新对话"),
            messages = messages,
            createdAt = optLong("createdAt", now),
            updatedAt = optLong("updatedAt", now),
        )
    }

    private companion object {
        const val TAG = "ConversationStore"
        const val FILE_NAME = "conversations.json"
    }
}
