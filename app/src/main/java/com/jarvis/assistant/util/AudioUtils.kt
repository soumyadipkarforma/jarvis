package com.jarvis.assistant.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Audio utility functions for recording and processing audio data.
 */
object AudioUtils {

    private const val TAG = "AudioUtils"
    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val DEFAULT_SILENCE_THRESHOLD = 500.0

    /**
     * Calculate the minimum buffer size for audio recording.
     */
    fun getMinBufferSize(): Int {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Use at least 2x minimum for stability
        return maxOf(minSize * 2, SAMPLE_RATE * 2)
    }

    /**
     * Create an AudioRecord instance configured for voice recording.
     * Returns null if the audio source is not available.
     */
    fun createAudioRecord(): AudioRecord? {
        return try {
            val bufferSize = getMinBufferSize()
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord.release()
                null
            } else {
                audioRecord
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            null
        }
    }

    /**
     * Convert a short array of audio samples to a byte array (little-endian PCM16).
     */
    fun shortsToBytes(shorts: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Calculate the RMS (Root Mean Square) energy of an audio buffer.
     * Useful for detecting silence/speech.
     */
    fun calculateRMS(buffer: ShortArray, length: Int): Double {
        if (length == 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return Math.sqrt(sum / length)
    }

    /**
     * Determine if an audio buffer is silent based on RMS threshold.
     */
    fun isSilent(buffer: ShortArray, length: Int, threshold: Double = DEFAULT_SILENCE_THRESHOLD): Boolean {
        return calculateRMS(buffer, length) < threshold
    }
}
