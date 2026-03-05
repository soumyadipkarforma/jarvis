package com.jarvis.assistant

import com.jarvis.assistant.stt.VoskSpeechToText
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoskSpeechToText JSON parsing logic.
 */
class VoskSpeechToTextTest {

    @Test
    fun `parseVoskResult extracts text from full result JSON`() {
        val json = """{"text" : "hello world"}"""
        assertEquals("hello world", VoskSpeechToText.parseVoskResult(json))
    }

    @Test
    fun `parseVoskResult extracts partial text`() {
        val json = """{"partial" : "hello"}"""
        assertEquals("hello", VoskSpeechToText.parseVoskResult(json))
    }

    @Test
    fun `parseVoskResult returns empty for empty text`() {
        val json = """{"text" : ""}"""
        assertEquals("", VoskSpeechToText.parseVoskResult(json))
    }

    @Test
    fun `parseVoskResult returns empty for invalid JSON`() {
        assertEquals("", VoskSpeechToText.parseVoskResult("not json"))
    }

    @Test
    fun `parseVoskResult handles whitespace in text`() {
        val json = """{"text" : " open chrome "}"""
        assertEquals("open chrome", VoskSpeechToText.parseVoskResult(json))
    }
}
