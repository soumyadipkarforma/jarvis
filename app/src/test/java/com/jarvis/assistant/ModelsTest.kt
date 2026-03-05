package com.jarvis.assistant

import com.jarvis.assistant.util.ChatMessage
import com.jarvis.assistant.util.JarvisConfig
import com.jarvis.assistant.util.JarvisState
import com.jarvis.assistant.util.VoiceResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for data models.
 */
class ModelsTest {

    @Test
    fun `JarvisState has all expected values`() {
        val states = JarvisState.values()
        assertEquals(5, states.size)
        assertTrue(states.contains(JarvisState.IDLE))
        assertTrue(states.contains(JarvisState.LISTENING))
        assertTrue(states.contains(JarvisState.PROCESSING))
        assertTrue(states.contains(JarvisState.SPEAKING))
        assertTrue(states.contains(JarvisState.ERROR))
    }

    @Test
    fun `ChatMessage stores correct data`() {
        val message = ChatMessage("Hello", isUser = true, timestamp = 12345L)
        assertEquals("Hello", message.text)
        assertTrue(message.isUser)
        assertEquals(12345L, message.timestamp)
    }

    @Test
    fun `ChatMessage defaults timestamp to current time`() {
        val before = System.currentTimeMillis()
        val message = ChatMessage("Test", isUser = false)
        val after = System.currentTimeMillis()

        assertTrue(message.timestamp in before..after)
    }

    @Test
    fun `VoiceResult Success contains correct data`() {
        val result = VoiceResult.Success("hello", "Hi there!")
        assertEquals("hello", result.userText)
        assertEquals("Hi there!", result.response)
    }

    @Test
    fun `VoiceResult CommandExecuted contains correct data`() {
        val result = VoiceResult.CommandExecuted("open chrome", "Opening Chrome")
        assertEquals("open chrome", result.userText)
        assertEquals("Opening Chrome", result.feedback)
    }

    @Test
    fun `VoiceResult Error contains message`() {
        val result = VoiceResult.Error("Something went wrong")
        assertEquals("Something went wrong", result.message)
    }

    @Test
    fun `JarvisConfig has sensible defaults`() {
        val config = JarvisConfig()
        assertEquals("model-small-en-us", config.voskModelPath)
        assertEquals("phi-2.Q4_K_M.gguf", config.llamaModelPath)
        assertEquals(256, config.maxLLMTokens)
        assertEquals(16000, config.sampleRate)
        assertEquals(2000L, config.silenceTimeoutMs)
    }

    @Test
    fun `JarvisConfig can be customized`() {
        val config = JarvisConfig(
            porcupineAccessKey = "test-key",
            maxLLMTokens = 512,
            sampleRate = 44100
        )
        assertEquals("test-key", config.porcupineAccessKey)
        assertEquals(512, config.maxLLMTokens)
        assertEquals(44100, config.sampleRate)
    }
}
