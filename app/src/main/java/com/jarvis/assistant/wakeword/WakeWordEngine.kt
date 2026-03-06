package com.jarvis.assistant.wakeword

import android.content.Context
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException

/**
 * Wake word detection engine using Picovoice Porcupine.
 *
 * Listens for the "Jarvis" wake word to activate the voice assistant.
 * Porcupine runs entirely on-device with minimal CPU usage.
 *
 * Usage:
 *   1. Call [initialize] with a valid Picovoice access key
 *   2. Feed audio frames via [processFrame]
 *   3. Call [release] when done
 */
class WakeWordEngine(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordEngine"
    }

    private var porcupine: Porcupine? = null
    val frameLength: Int get() = porcupine?.frameLength ?: 512
    val sampleRate: Int get() = porcupine?.sampleRate ?: 16000

    /**
     * Initialize the Porcupine wake word engine.
     *
     * @param accessKey Picovoice access key (obtain from console.picovoice.ai)
     * @return true if initialization succeeded
     */
    fun initialize(accessKey: String): Boolean {
        return try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf(getKeywordPath()))
                .setSensitivities(floatArrayOf(0.7f))
                .build(context)

            Log.i(TAG, "Porcupine initialized: frameLength=${porcupine?.frameLength}, sampleRate=${porcupine?.sampleRate}")
            true
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine", e)
            // Fallback: try built-in keyword
            tryBuiltInKeyword(accessKey)
        }
    }

    /**
     * Try initializing with built-in "jarvis" keyword as fallback.
     */
    private fun tryBuiltInKeyword(accessKey: String): Boolean {
        return try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivities(floatArrayOf(0.7f))
                .build(context)

            Log.i(TAG, "Porcupine initialized with built-in keyword")
            true
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine with built-in keyword", e)
            false
        }
    }

    /**
     * Process an audio frame and check for wake word detection.
     *
     * @param audioFrame PCM audio samples (16-bit, mono, 16kHz)
     * @return true if the wake word was detected
     */
    fun processFrame(audioFrame: ShortArray): Boolean {
        return try {
            val keywordIndex = porcupine?.process(audioFrame) ?: -1
            if (keywordIndex >= 0) {
                Log.i(TAG, "Wake word 'Jarvis' detected!")
                true
            } else {
                false
            }
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error processing audio frame", e)
            false
        }
    }

    /**
     * Release all resources held by Porcupine.
     */
    fun release() {
        try {
            porcupine?.delete()
            porcupine = null
            Log.i(TAG, "Porcupine released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Porcupine", e)
        }
    }

    /**
     * Check if the engine is currently initialized and ready.
     */
    fun isReady(): Boolean = porcupine != null

    /**
     * Get the path to the custom keyword file, if one exists in assets.
     */
    private fun getKeywordPath(): String {
        val keywordFile = java.io.File(context.filesDir, "jarvis_android.ppn")
        if (!keywordFile.exists()) {
            try {
                context.assets.open("jarvis_android.ppn").use { input ->
                    keywordFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Custom keyword file not found in assets, will use built-in", e)
                return ""
            }
        }
        return keywordFile.absolutePath
    }
}
