package com.jarvis.assistant

import com.jarvis.assistant.stt.VoskSpeechToText
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoskSpeechToText JSON parsing logic.
 */
class VoskSpeechToTextTest {

    @Test
    fun `parseResultText extracts text from full result JSON`() {
        val stt = createSttForTesting()
        val json = """{"text" : "hello world"}"""
        assertEquals("hello world", stt.parseResultText(json))
    }

    @Test
    fun `parseResultText extracts partial text`() {
        val stt = createSttForTesting()
        val json = """{"partial" : "hello"}"""
        assertEquals("hello", stt.parseResultText(json))
    }

    @Test
    fun `parseResultText returns empty for empty text`() {
        val stt = createSttForTesting()
        val json = """{"text" : ""}"""
        assertEquals("", stt.parseResultText(json))
    }

    @Test
    fun `parseResultText returns empty for invalid JSON`() {
        val stt = createSttForTesting()
        assertEquals("", stt.parseResultText("not json"))
    }

    @Test
    fun `parseResultText handles whitespace in text`() {
        val stt = createSttForTesting()
        val json = """{"text" : " open chrome "}"""
        assertEquals("open chrome", stt.parseResultText(json))
    }

    /**
     * Create a VoskSpeechToText instance for testing.
     * Uses reflection to avoid needing an Android context.
     */
    private fun createSttForTesting(): VoskSpeechToText {
        // Use unsafe allocation to bypass constructor (no Android context needed for parsing)
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafe.isAccessible = true
        val unsafeInstance = unsafe.get(null) as sun.misc.Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafeInstance.allocateInstance(VoskSpeechToText::class.java) as VoskSpeechToText
    }
}
