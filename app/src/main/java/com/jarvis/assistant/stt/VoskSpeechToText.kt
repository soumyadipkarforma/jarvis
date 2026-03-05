package com.jarvis.assistant.stt

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Offline Speech-to-Text engine using Vosk.
 *
 * Vosk provides accurate offline speech recognition using lightweight models.
 * The engine operates entirely on-device without any network connectivity.
 *
 * Usage:
 *   1. Call [initialize] to load the Vosk model from assets
 *   2. Call [startListening] to begin a recognition session
 *   3. Feed audio data via [acceptWaveForm]
 *   4. Call [getResult] to get the final transcription
 *   5. Call [stopListening] when done with the session
 *   6. Call [release] to free resources
 */
class VoskSpeechToText(private val context: Context) {

    companion object {
        private const val TAG = "VoskSTT"
        private const val SAMPLE_RATE = 16000.0f
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var isInitialized = false

    /**
     * Callback interface for speech recognition events.
     */
    interface SpeechCallback {
        fun onPartialResult(text: String)
        fun onResult(text: String)
        fun onError(error: String)
    }

    /**
     * Initialize the Vosk speech recognition model.
     *
     * @param modelPath Path to the model directory in assets (e.g., "model-small-en-us")
     * @param callback Optional callback for initialization completion
     */
    fun initialize(modelPath: String = "model-small-en-us", callback: ((Boolean) -> Unit)? = null) {
        try {
            StorageService.unpack(context, modelPath, "model",
                { loadedModel ->
                    model = loadedModel
                    isInitialized = true
                    Log.i(TAG, "Vosk model loaded successfully from: $modelPath")
                    callback?.invoke(true)
                },
                { exception ->
                    Log.e(TAG, "Failed to load Vosk model", exception)
                    isInitialized = false
                    callback?.invoke(false)
                }
            )
        } catch (e: IOException) {
            Log.e(TAG, "Error unpacking Vosk model", e)
            callback?.invoke(false)
        }
    }

    /**
     * Start a new recognition session.
     * Must be called before feeding audio data.
     */
    fun startListening(): Boolean {
        if (!isInitialized || model == null) {
            Log.e(TAG, "Vosk model not initialized")
            return false
        }

        return try {
            recognizer?.close()
            recognizer = Recognizer(model, SAMPLE_RATE)
            Log.d(TAG, "Recognition session started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recognizer", e)
            false
        }
    }

    /**
     * Feed audio data to the recognizer.
     *
     * @param data PCM audio bytes (16-bit, mono, 16kHz)
     * @param length Number of bytes to process
     * @return true if the recognizer detected end of speech
     */
    fun acceptWaveForm(data: ByteArray, length: Int): Boolean {
        return try {
            recognizer?.acceptWaveForm(data, length) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio data", e)
            false
        }
    }

    /**
     * Get the partial (in-progress) recognition result.
     */
    fun getPartialResult(): String {
        return try {
            val json = recognizer?.partialResult ?: return ""
            parseResultText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting partial result", e)
            ""
        }
    }

    /**
     * Get the final recognition result.
     * Call this after [acceptWaveForm] returns true or when you want to finalize.
     */
    fun getResult(): String {
        return try {
            val json = recognizer?.result ?: return ""
            parseResultText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting result", e)
            ""
        }
    }

    /**
     * Get the final result and close the recognition session.
     */
    fun getFinalResult(): String {
        return try {
            val json = recognizer?.finalResult ?: return ""
            parseResultText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting final result", e)
            ""
        }
    }

    /**
     * Stop the current recognition session.
     */
    fun stopListening() {
        recognizer?.close()
        recognizer = null
        Log.d(TAG, "Recognition session stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        isInitialized = false
        Log.i(TAG, "Vosk resources released")
    }

    /**
     * Check if the engine is initialized and ready.
     */
    fun isReady(): Boolean = isInitialized && model != null

    /**
     * Parse the recognized text from Vosk JSON response.
     * Vosk returns JSON like: {"text": "hello world"} or {"partial": "hello"}
     */
    internal fun parseResultText(json: String): String {
        // Simple JSON parsing to avoid adding a JSON dependency just for this
        val textMatch = Regex(""""(?:text|partial)"\s*:\s*"([^"]*)"""").find(json)
        return textMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
    }
}
