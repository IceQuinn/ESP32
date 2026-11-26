package com.example.aichat.ble

/** app 需要执行的灯控动作。 */
enum class LedAction { ON, OFF }

/**
 * 本地关键词解析器。
 *
 * 设计取舍：
 *  - 只处理明确、无歧义的说法（打开/关闭 + 灯），命中后立即执行，零延迟零 token；
 *  - 疑问句、否定句一律不执行，交给 AI 兜底，避免「灯怎么打开？」误触发；
 *  - 拿不准的（含「灯」但不是清晰指令）交给 isDeviceRelated 判断，
 *    再由 AI Function Calling 二次确认。
 */
object LedCommandParser {

    /** 出现这些词才认为这条消息与设备有关。 */
    private val deviceKeywords = listOf("灯", "led", "esp32", "light", "灯泡")

    /** 开灯说法，按长度优先匹配。 */
    private val onPhrases = listOf(
        "打开", "开启", "点亮", "亮起", "开灯", "开一下",
        "把灯开", "给灯开", "灯打开", "灯开启", "灯亮",
    )

    /** 关灯说法；先于开灯判断，避免「关闭」被「开」误伤。 */
    private val offPhrases = listOf(
        "关闭", "关掉", "关灯", "熄灭", "关一下",
        "把灯关", "给灯关", "灯关掉", "灯关闭", "灭掉", "灭灯",
    )

    /** 否定词：出现时不做任何动作，避免「不要开灯」反而开灯。 */
    private val negations = listOf("不要", "不用", "别", "无需", "不必", "不想")

    /** 疑问词：出现时视为提问，交给 AI 回答而不是执行。 */
    private val questionWords = listOf(
        "怎么", "如何", "为什么", "为啥", "什么", "能不能", "可不可以",
        "可以吗", "教程", "代码", "吗", "？", "?",
    )

    /** 这条消息是否在谈论灯/ESP32。 */
    fun isDeviceRelated(text: String): Boolean {
        val normalized = text.lowercase()
        return deviceKeywords.any { normalized.contains(it) }
    }

    /** 解析明确的开关灯指令；无法确定时返回 null。 */
    fun parse(text: String): LedAction? {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return null
        if (!isDeviceRelated(normalized)) return null
        if (questionWords.any { normalized.contains(it) }) return null
        if (negations.any { normalized.contains(it) }) return null

        if (offPhrases.any { normalized.contains(it) }) return LedAction.OFF
        if (onPhrases.any { normalized.contains(it) }) return LedAction.ON
        return null
    }
}
