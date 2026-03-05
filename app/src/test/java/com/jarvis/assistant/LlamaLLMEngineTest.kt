package com.jarvis.assistant

import com.jarvis.assistant.llm.LlamaLLMEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for LlamaLLMEngine prompt building logic.
 */
class LlamaLLMEngineTest {

    @Test
    fun `buildPrompt creates correct format without history`() {
        val prompt = LlamaLLMEngine.buildPrompt("What is the weather?")

        assertTrue(prompt.contains("### System:"))
        assertTrue(prompt.contains("### Human: What is the weather?"))
        assertTrue(prompt.contains("### Assistant:"))
        assertTrue(prompt.contains("Jarvis"))
    }

    @Test
    fun `buildPrompt includes conversation history`() {
        val history = listOf(
            "Hello" to "Hello! How can I help?",
            "Tell me a joke" to "Why did the chicken cross the road?"
        )
        val prompt = LlamaLLMEngine.buildPrompt("Another one", history)

        assertTrue(prompt.contains("### Human: Hello"))
        assertTrue(prompt.contains("### Assistant: Hello! How can I help?"))
        assertTrue(prompt.contains("### Human: Tell me a joke"))
        assertTrue(prompt.contains("### Human: Another one"))
    }

    @Test
    fun `buildPrompt limits conversation history to last 3 turns`() {
        val history = listOf(
            "Q1" to "A1",
            "Q2" to "A2",
            "Q3" to "A3",
            "Q4" to "A4",
            "Q5" to "A5"
        )
        val prompt = LlamaLLMEngine.buildPrompt("Q6", history)

        // Should only contain last 3 history items
        assertFalse(prompt.contains("### Human: Q1"))
        assertFalse(prompt.contains("### Human: Q2"))
        assertTrue(prompt.contains("### Human: Q3"))
        assertTrue(prompt.contains("### Human: Q4"))
        assertTrue(prompt.contains("### Human: Q5"))
        assertTrue(prompt.contains("### Human: Q6"))
    }

    @Test
    fun `buildPrompt ends with Assistant marker`() {
        val prompt = LlamaLLMEngine.buildPrompt("test")

        assertTrue(prompt.trimEnd().endsWith("### Assistant:"))
    }
}
