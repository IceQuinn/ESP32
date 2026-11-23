package com.example.aichat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DeepSeek 接口客户端。
 *
 * DeepSeek 提供 OpenAI 兼容的 Chat Completions 接口：
 *   POST https://api.deepseek.com/chat/completions
 *   Authorization: Bearer <api_key>
 *   body: {"model": "...", "messages": [...], "stream": true}
 *
 * 流式响应采用 Server-Sent Events：每行形如 "data: {json}"，
 * 最后以 "data: [DONE]" 结束。这里不依赖第三方 HTTP 库，
 * 用 HttpURLConnection 手动读取并按行解析，避免引入额外依赖。
 */
object DeepSeekClient {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 120_000

    /** 发起流式对话。 */
    suspend fun streamChat(
        apiKey: String,
        modelId: String,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onReasoning: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ) = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "API Key 为空" }

        val body = buildRequestBody(modelId, messages, stream = true)
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val errorText = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw ApiException(status, describeHttpError(status, errorText))
            }

            val cancelled = AtomicBoolean(false)
            var receivedAny = false
            var readError: Throwable? = null

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                // readLine() 是阻塞调用：服务端“思考中”暂不发送数据时，
                // 主线程的取消标志无法被及时感知。因此把读取放到独立线程，
                // 本协程用带超时的 queue.take() 轮询，从而可以快速响应“停止”。
                val queue = LinkedBlockingQueue<Any>(64)
                val readerThread = Thread({
                    try {
                        while (!cancelled.get()) {
                            val line = reader.readLine() ?: break
                            queue.put(line)
                        }
                    } catch (e: InterruptedException) {
                        // 主动中断，正常结束。
                    } catch (e: Throwable) {
                        // 连接被 disconnect() 关闭时抛出的 IO 异常，属于预期的中止路径。
                        if (!cancelled.get()) {
                            try {
                                queue.put(e)
                            } catch (_: InterruptedException) {
                            }
                        }
                    } finally {
                        // 使用哨兵对象标识流结束。
                        try {
                            queue.put(STREAM_END)
                        } catch (_: InterruptedException) {
                        }
                    }
                }, "deepseek-sse-reader").apply { isDaemon = true }
                readerThread.start()

                try {
                    while (true) {
                        if (isCancelled()) {
                            cancelled.set(true)
                            break
                        }
                        val item = try {
                            queue.poll(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)
                        } catch (e: InterruptedException) {
                            cancelled.set(true)
                            break
                        } ?: continue

                        if (item === STREAM_END) break
                        if (item is Throwable) {
                            readError = item
                            break
                        }

                        val line = item as String
                        if (!line.startsWith(DATA_PREFIX)) continue

                        val payload = line.removePrefix(DATA_PREFIX).trim()
                        if (payload.isEmpty()) continue
                        if (payload == DONE_MARKER) break

                        val delta = parseDelta(payload) ?: continue
                        receivedAny = true

                        if (delta.reasoning.isNotEmpty()) {
                            onReasoning?.invoke(delta.reasoning)
                        }
                        if (delta.content.isNotEmpty()) {
                            onDelta(delta.content)
                        }
                    }
                } finally {
                    // 无论正常结束还是中途退出，都确保读取线程退出，避免泄漏。
                    cancelled.set(true)
                    readerThread.interrupt()
                }
            }

            if (cancelled.get()) {
                // 用户主动停止，不视为错误。
                return@withContext
            }
            readError?.let { throw it }
            if (!receivedAny) {
                throw ApiException(status, "接口没有返回任何内容，请重试。")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 非流式请求，仅用于“测试连接”按钮做轻量连通性验证。 */
    suspend fun ping(apiKey: String, modelId: String): String = withContext(Dispatchers.IO) {
        val body = buildRequestBody(
            modelId = modelId,
            messages = listOf(ChatMessage(ChatMessage.ROLE_USER, "你好")),
            stream = false,
            maxTokens = 16,
        )
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorText = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw ApiException(status, describeHttpError(status, errorText))
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw ApiException(status, "响应格式异常。")
            }
            // 推理模型测试时可能只返回 reasoning_content，content 为 null，同样视为连接正常。
            val messageObj = choices.optJSONObject(0)?.optJSONObject("message")
            val content = if (messageObj == null) "" else safeString(messageObj, "content")
            val reasoning = if (messageObj == null) "" else safeString(messageObj, "reasoning_content")
            if (content.isBlank() && reasoning.isBlank()) {
                throw ApiException(status, "模型没有返回内容，请检查所选模型是否可用。")
            }
            "连接成功，模型已响应。"
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestBody(
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        maxTokens: Int? = null,
    ): String {
        val array = JSONArray()
        messages.forEach { message ->
            if (message.content.isBlank() && message.role != ChatMessage.ROLE_ASSISTANT) return@forEach
            array.put(
                JSONObject().apply {
                    put("role", message.role)
                    put("content", message.content)
                }
            )
        }
        return JSONObject().apply {
            put("model", modelId)
            put("messages", array)
            put("stream", stream)
            if (maxTokens != null) put("max_tokens", maxTokens)
        }.toString()
    }

    private data class Delta(val content: String, val reasoning: String)

    /**
     * 兼容两种增量字段：
     *   - deepseek-chat 使用 choices[0].delta.content
     *   - deepseek-reasoner 额外提供 choices[0].delta.reasoning_content
     *
     * 注意：思考阶段的 chunk 里 content 字段常常是 JSON null。
     * org.json 的 optString(key, fallback) 在“字段存在但值为 null”时
     * 返回的是字面量字符串 "null"，而不是 fallback，会污染正文。
     * 因此这里必须用 safeString 显式判断 JSONObject.NULL。
     */
    private fun parseDelta(payload: String): Delta? {
        return try {
            val root = JSONObject(payload)
            val delta = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?: return null
            Delta(
                content = safeString(delta, "content"),
                reasoning = safeString(delta, "reasoning_content"),
            )
        } catch (e: JSONException) {
            // 单行解析失败不应中断整个流，跳过该行。
            null
        }
    }

    /**
     * 安全读取字符串字段：值为 null、缺失、或意外得到字面量 "null" 时一律返回空串。
     */
    private fun safeString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.opt(key)
        return when (value) {
            null, JSONObject.NULL -> ""
            is String -> if (value == "null") "" else value
            else -> value.toString()
        }
    }

    /** 把接口返回的错误 JSON 翻译成用户能看懂的中文提示。 */
    private fun describeHttpError(status: Int, rawBody: String): String {
        val apiMessage = try {
            val error = JSONObject(rawBody).optJSONObject("error")
            if (error == null) "" else safeString(error, "message")
        } catch (e: JSONException) {
            ""
        }
        val hint = when (status) {
            401 -> "API Key 无效或已失效，请到设置中检查。"
            402 -> "账户余额不足，请前往 DeepSeek 平台充值。"
            422 -> "请求参数有误，可能是所选模型不可用。"
            429 -> "请求过于频繁，请稍后再试。"
            in 500..599 -> "服务端暂时不可用，请稍后重试。"
            else -> "请求失败（HTTP $status）。"
        }
        return if (apiMessage.isBlank()) hint else "$hint\n$apiMessage"
    }

    class ApiException(val statusCode: Int, override val message: String) : Exception(message)

    private const val DATA_PREFIX = "data:"
    private const val DONE_MARKER = "[DONE]"

    /** 轮询读取队列的间隔，决定“停止”按钮的最长响应延迟。 */
    private const val CANCEL_POLL_MS = 100L

    /** 读取线程写入队列的结束哨兵。 */
    private val STREAM_END = Any()
}
