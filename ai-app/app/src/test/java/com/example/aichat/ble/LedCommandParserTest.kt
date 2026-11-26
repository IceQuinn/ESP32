package com.example.aichat.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只覆盖本地规则解析，不依赖 Android 运行时，用 JVM 单元测试即可验证。
 */
class LedCommandParserTest {

    @Test
    fun `显式开灯指令`() {
        assertEquals(LedAction.ON, LedCommandParser.parse("打开esp32的灯"))
        assertEquals(LedAction.ON, LedCommandParser.parse("开灯"))
        assertEquals(LedAction.ON, LedCommandParser.parse("把灯打开"))
        assertEquals(LedAction.ON, LedCommandParser.parse("帮我点亮 LED"))
    }

    @Test
    fun `显式关灯指令`() {
        assertEquals(LedAction.OFF, LedCommandParser.parse("关闭esp32的灯"))
        assertEquals(LedAction.OFF, LedCommandParser.parse("关灯"))
        assertEquals(LedAction.OFF, LedCommandParser.parse("把灯关掉"))
        assertEquals(LedAction.OFF, LedCommandParser.parse("熄灭 LED"))
    }

    @Test
    fun `疑问句不执行`() {
        assertNull(LedCommandParser.parse("esp32的灯怎么打开？"))
        assertNull(LedCommandParser.parse("灯亮着吗"))
        assertNull(LedCommandParser.parse("如何关闭这个灯"))
        assertNull(LedCommandParser.parse("打开灯可以吗"))
    }

    @Test
    fun `否定句不执行`() {
        assertNull(LedCommandParser.parse("不要打开灯"))
        assertNull(LedCommandParser.parse("别关灯"))
        assertNull(LedCommandParser.parse("暂时不用开灯"))
    }

    @Test
    fun `无关消息不执行`() {
        assertNull(LedCommandParser.parse("今天天气不错"))
        assertNull(LedCommandParser.parse("帮我写一段 Kotlin 代码"))
        assertFalse(LedCommandParser.isDeviceRelated("你好"))
    }

    @Test
    fun `提到设备但说法模糊交给 AI`() {
        assertTrue(LedCommandParser.isDeviceRelated("esp32 的灯现在什么状态"))
        assertNull(LedCommandParser.parse("esp32 的灯现在什么状态"))
        assertNull(LedCommandParser.parse("把那个灯弄亮一点"))
    }
}
