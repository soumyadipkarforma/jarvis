package com.jarvis.assistant.util

/**
 * Represents the current state of the Jarvis voice assistant.
 */
enum class JarvisState {
    /** Idle, waiting for wake word or manual activation */
    IDLE,
    /** Wake word detected or manual mic tap, recording audio */
    LISTENING,
    /** Processing speech-to-text and/or LLM inference */
    PROCESSING,
    /** Speaking the response via TTS */
    SPEAKING,
    /** An error occurred */
    ERROR
}

/**
 * Represents a single message in the conversation.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of a voice command processing pipeline.
 */
sealed class VoiceResult {
    data class Success(val userText: String, val response: String) : VoiceResult()
    data class CommandExecuted(val userText: String, val feedback: String) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
}

/**
 * Configuration for the Jarvis assistant engines.
 */
data class JarvisConfig(
    val porcupineAccessKey: String = "",
    val voskModelPath: String = "model-small-en-us",
    val llamaModelPath: String = "phi-2.Q4_K_M.gguf",
    val coquiModelPath: String = "tts-model",
    val maxLLMTokens: Int = 256,
    val sampleRate: Int = 16000,
    val silenceTimeoutMs: Long = 2000L
)
