package com.jarvis.assistant.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local LLM inference engine using llama.cpp via JNI.
 *
 * Runs the Phi-2 model (GGUF format) entirely on-device for offline
 * text generation. The llama.cpp library is loaded as a native shared
 * library (.so) bundled with the app.
 *
 * Usage:
 *   1. Call [initialize] with the path to the GGUF model file
 *   2. Call [generateResponse] to get a text completion
 *   3. Call [release] when done
 *
 * Model Setup:
 *   Place the Phi-2 GGUF model file (e.g., phi-2.Q4_K_M.gguf) in the
 *   app's files directory. The model can be downloaded and placed there
 *   on first launch or via a settings screen.
 */
class LlamaLLMEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlamaLLM"
        private const val DEFAULT_MODEL_NAME = "phi-2.Q4_K_M.gguf"
        private const val SYSTEM_PROMPT = """You are Jarvis, a highly intelligent and helpful AI assistant. 
You speak in a refined, professional manner similar to a British butler. 
Keep responses concise and helpful. You are running on an Android device offline."""

        init {
            try {
                System.loadLibrary("llama-android")
                Log.i(TAG, "llama-android native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "llama-android native library not available", e)
            }
        }
    }

    private var modelPointer: Long = 0L
    private var contextPointer: Long = 0L
    private var isLoaded = false
    private var maxTokens = 256

    /**
     * Initialize the LLM engine by loading the model.
     *
     * @param modelPath Full path to the GGUF model file, or just the filename
     *                  to look in the app's files directory
     * @param maxTokens Maximum number of tokens to generate per response
     * @return true if the model was loaded successfully
     */
    suspend fun initialize(
        modelPath: String = DEFAULT_MODEL_NAME,
        maxTokens: Int = 256
    ): Boolean = withContext(Dispatchers.IO) {
        this@LlamaLLMEngine.maxTokens = maxTokens

        val modelFile = resolveModelPath(modelPath)
        if (modelFile == null || !modelFile.exists()) {
            Log.e(TAG, "Model file not found: $modelPath")
            return@withContext false
        }

        try {
            modelPointer = nativeLoadModel(modelFile.absolutePath)
            if (modelPointer == 0L) {
                Log.e(TAG, "Failed to load model (null pointer)")
                return@withContext false
            }

            contextPointer = nativeCreateContext(modelPointer, 2048)
            if (contextPointer == 0L) {
                Log.e(TAG, "Failed to create context")
                nativeFreeModel(modelPointer)
                modelPointer = 0L
                return@withContext false
            }

            isLoaded = true
            Log.i(TAG, "Model loaded: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM", e)
            false
        }
    }

    /**
     * Generate a text response for the given user input.
     *
     * @param userInput The user's query text
     * @param conversationHistory Optional list of previous messages for context
     * @return The generated response text, or an error message
     */
    suspend fun generateResponse(
        userInput: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            return@withContext "I apologize, but my language model is not loaded yet."
        }

        val prompt = buildPrompt(userInput, conversationHistory)

        try {
            val response = nativeGenerate(contextPointer, prompt, maxTokens)
            val cleanedResponse = cleanResponse(response)
            Log.d(TAG, "Generated response: ${cleanedResponse.take(100)}...")
            cleanedResponse
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            "I encountered an error while processing your request."
        }
    }

    /**
     * Build the full prompt with system instructions and conversation context.
     */
    internal fun buildPrompt(
        userInput: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("### System:")
        sb.appendLine(SYSTEM_PROMPT)
        sb.appendLine()

        // Add conversation history (last few turns)
        val recentHistory = conversationHistory.takeLast(3)
        for ((user, assistant) in recentHistory) {
            sb.appendLine("### Human: $user")
            sb.appendLine("### Assistant: $assistant")
            sb.appendLine()
        }

        sb.appendLine("### Human: $userInput")
        sb.appendLine("### Assistant:")
        return sb.toString()
    }

    /**
     * Clean up the raw LLM output.
     */
    private fun cleanResponse(raw: String): String {
        var response = raw.trim()

        // Remove any trailing prompt tokens
        val stopTokens = listOf("### Human:", "### Assistant:", "### System:", "<|endoftext|>")
        for (token in stopTokens) {
            val idx = response.indexOf(token)
            if (idx > 0) {
                response = response.substring(0, idx).trim()
            }
        }

        return response.ifEmpty {
            "I'm not sure how to respond to that."
        }
    }

    /**
     * Resolve the model file path.
     */
    private fun resolveModelPath(modelPath: String): File? {
        // If it's already an absolute path
        val direct = File(modelPath)
        if (direct.exists()) return direct

        // Check in app's files directory
        val inFilesDir = File(context.filesDir, modelPath)
        if (inFilesDir.exists()) return inFilesDir

        // Check in external files directory
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val inExternal = File(externalDir, modelPath)
            if (inExternal.exists()) return inExternal
        }

        // Check in a 'models' subdirectory
        val inModelsDir = File(context.filesDir, "models/$modelPath")
        if (inModelsDir.exists()) return inModelsDir

        return null
    }

    /**
     * Release all resources.
     */
    fun release() {
        try {
            if (contextPointer != 0L) {
                nativeFreeContext(contextPointer)
                contextPointer = 0L
            }
            if (modelPointer != 0L) {
                nativeFreeModel(modelPointer)
                modelPointer = 0L
            }
            isLoaded = false
            Log.i(TAG, "LLM resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LLM", e)
        }
    }

    /**
     * Check if the model is loaded and ready.
     */
    fun isReady(): Boolean = isLoaded

    /**
     * Get model information.
     */
    fun getModelInfo(): String {
        return if (isLoaded) {
            "Phi-2 (GGUF) - Loaded"
        } else {
            "No model loaded"
        }
    }

    // Native JNI methods - implemented in llama-android native library
    private external fun nativeLoadModel(modelPath: String): Long
    private external fun nativeCreateContext(modelPtr: Long, contextSize: Int): Long
    private external fun nativeGenerate(contextPtr: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFreeContext(contextPtr: Long)
    private external fun nativeFreeModel(modelPtr: Long)
}
