package com.example.aichat.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间显示工具。
 *
 * 规则（参考主流聊天应用）：
 *  - 今天：只显示“HH:mm”
 *  - 昨天：“昨天 HH:mm”
 *  - 今年内：“M月d日 HH:mm”
 *  - 更早：“yyyy年M月d日”
 */
object TimeFormat {

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val mdHm = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
    private val ymd = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

    /** 消息气泡下方的时间。 */
    fun messageTime(timestamp: Long): String {
        val date = Date(timestamp)
        val cal = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()

        return when {
            isSameDay(cal, now) -> hm.format(date)
            isYesterday(cal, now) -> "昨天 ${hm.format(date)}"
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> mdHm.format(date)
            else -> ymd.format(date)
        }
    }

    /** 历史列表的会话时间，较简短。 */
    fun conversationTime(timestamp: Long): String {
        val date = Date(timestamp)
        val cal = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()

        return when {
            isSameDay(cal, now) -> hm.format(date)
            isYesterday(cal, now) -> "昨天"
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat(
                "M月d日",
                Locale.getDefault(),
            ).format(date)

            else -> ymd.format(date)
        }
    }

    /** 会话内的时间分隔标签：只在跨天时显示。 */
    fun dayDivider(timestamp: Long): String {
        val date = Date(timestamp)
        val cal = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()
        return when {
            isSameDay(cal, now) -> "今天"
            isYesterday(cal, now) -> "昨天"
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat(
                "M月d日",
                Locale.getDefault(),
            ).format(date)

            else -> ymd.format(date)
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(target: Calendar, now: Calendar): Boolean {
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return isSameDay(target, yesterday)
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = a }
        val c2 = Calendar.getInstance().apply { timeInMillis = b }
        return isSameDay(c1, c2)
    }
}
