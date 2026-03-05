package com.jarvis.assistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline Text-to-Speech engine using Coqui TTS.
 *
 * Produces natural-sounding speech with a deep, Jarvis-like male voice.
 * Runs entirely on-device using Coqui TTS native library.
 *
 * Usage:
 *   1. Call [initialize] with the path to the TTS model
 *   2. Call [speak] to synthesize and play speech
 *   3. Call [release] when done
 *
 * Model Setup:
 *   Place the Coqui TTS model files in the app's files directory.
 *   Required files typically include:
 *   - model.tflite (or model checkpoint)
 *   - config.json
 *   - vocoder model (optional, for higher quality)
 */
class CoquiTextToSpeech(private val context: Context) {

    companion object {
        private const val TAG = "CoquiTTS"
        private const val SAMPLE_RATE = 22050
        private const val DEFAULT_SPEED = 0.9f  // Slightly slower for gravitas

        init {
            try {
                System.loadLibrary("coqui-tts-android")
                Log.i(TAG, "coqui-tts-android native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "coqui-tts-android native library not available", e)
            }
        }
    }

    private var ttsPointer: Long = 0L
    private var audioTrack: AudioTrack? = null
    private var isInitialized = false
    private var isSpeaking = false
    private var speed = DEFAULT_SPEED

    /**
     * Callback interface for TTS events.
     */
    interface TTSCallback {
        fun onSpeechStart()
        fun onSpeechEnd()
        fun onError(error: String)
    }

    private var callback: TTSCallback? = null

    /**
     * Set the TTS event callback.
     */
    fun setCallback(callback: TTSCallback) {
        this.callback = callback
    }

    /**
     * Initialize the Coqui TTS engine.
     *
     * @param modelPath Path to the TTS model directory
     * @return true if initialization succeeded
     */
    suspend fun initialize(modelPath: String = "tts-model"): Boolean = withContext(Dispatchers.IO) {
        val modelDir = resolveModelPath(modelPath)
        if (modelDir == null) {
            Log.e(TAG, "TTS model directory not found: $modelPath")
            return@withContext false
        }

        try {
            ttsPointer = nativeInit(modelDir.absolutePath)
            if (ttsPointer == 0L) {
                Log.e(TAG, "Failed to initialize TTS engine")
                return@withContext false
            }

            // Configure for deep male voice
            nativeSetSpeakerParams(ttsPointer, 0.85f, 0.9f) // pitch=0.85, speed=0.9

            initAudioTrack()
            isInitialized = true
            Log.i(TAG, "Coqui TTS initialized with model from: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TTS", e)
            false
        }
    }

    /**
     * Synthesize text to speech and play it.
     *
     * @param text The text to speak
     */
    suspend fun speak(text: String): Unit = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "TTS not initialized")
            callback?.onError("TTS not initialized")
            return@withContext
        }

        if (text.isBlank()) return@withContext

        try {
            isSpeaking = true
            callback?.onSpeechStart()

            // Synthesize audio
            val audioData = nativeSynthesize(ttsPointer, text)
            if (audioData == null || audioData.isEmpty()) {
                Log.e(TAG, "TTS synthesis returned empty audio")
                callback?.onError("Synthesis failed")
                return@withContext
            }

            // Play the audio
            playAudio(audioData)

            isSpeaking = false
            callback?.onSpeechEnd()
            Log.d(TAG, "Finished speaking: ${text.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error during speech synthesis", e)
            isSpeaking = false
            callback?.onError("Speech synthesis error: ${e.message}")
        }
    }

    /**
     * Stop any ongoing speech playback.
     */
    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.flush()
            isSpeaking = false
            Log.d(TAG, "Speech stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech", e)
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        try {
            stop()
            audioTrack?.release()
            audioTrack = null

            if (ttsPointer != 0L) {
                nativeRelease(ttsPointer)
                ttsPointer = 0L
            }

            isInitialized = false
            Log.i(TAG, "TTS resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TTS", e)
        }
    }

    /**
     * Check if the engine is initialized and ready.
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Check if the engine is currently speaking.
     */
    fun isSpeaking(): Boolean = isSpeaking

    /**
     * Set the speech speed multiplier.
     *
     * @param speed Speech rate (0.5 = half speed, 1.0 = normal, 2.0 = double speed)
     */
    fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.5f, 2.0f)
    }

    /**
     * Initialize AudioTrack for playback.
     */
    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Play synthesized audio data through the AudioTrack.
     */
    private fun playAudio(audioData: FloatArray) {
        audioTrack?.let { track ->
            track.play()
            track.write(audioData, 0, audioData.size, AudioTrack.WRITE_BLOCKING)
            track.stop()
        }
    }

    /**
     * Resolve the model directory path.
     */
    private fun resolveModelPath(modelPath: String): File? {
        val direct = File(modelPath)
        if (direct.isDirectory) return direct

        val inFilesDir = File(context.filesDir, modelPath)
        if (inFilesDir.isDirectory) return inFilesDir

        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val inExternal = File(externalDir, modelPath)
            if (inExternal.isDirectory) return inExternal
        }

        val inModelsDir = File(context.filesDir, "models/$modelPath")
        if (inModelsDir.isDirectory) return inModelsDir

        return null
    }

    // Native JNI methods - implemented in coqui-tts-android native library
    private external fun nativeInit(modelPath: String): Long
    private external fun nativeSynthesize(ttsPtr: Long, text: String): FloatArray?
    private external fun nativeSetSpeakerParams(ttsPtr: Long, pitch: Float, speed: Float)
    private external fun nativeRelease(ttsPtr: Long)
}
