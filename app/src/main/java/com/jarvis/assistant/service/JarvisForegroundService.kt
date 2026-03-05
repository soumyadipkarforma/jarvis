package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioRecord
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.stt.VoskSpeechToText
import com.jarvis.assistant.util.AudioUtils
import com.jarvis.assistant.util.JarvisState
import com.jarvis.assistant.wakeword.WakeWordEngine
import kotlinx.coroutines.*

/**
 * Foreground service that continuously listens for the "Jarvis" wake word.
 *
 * When the wake word is detected, the service:
 * 1. Notifies the bound activity (if any)
 * 2. Starts recording audio for speech-to-text
 * 3. Sends the transcribed text back to the activity for LLM processing
 *
 * The service uses a partial wake lock to ensure the microphone stays active
 * while the screen is off.
 */
class JarvisForegroundService : Service() {

    companion object {
        private const val TAG = "JarvisService"
        const val ACTION_START = "com.jarvis.assistant.action.START"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP"
        private const val MAX_SILENCE_FRAMES = 30 // ~2 seconds of silence
        private const val MAX_RECORDING_DURATION_MS = 15_000L // 15 seconds
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
    }

    // Binder for activity communication
    private val binder = JarvisBinder()
    private var callback: ServiceCallback? = null

    // Engines
    private var wakeWordEngine: WakeWordEngine? = null
    private var sttEngine: VoskSpeechToText? = null
    private var audioRecord: AudioRecord? = null

    // Coroutine scope for background work
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State
    private var isListeningForWakeWord = false
    private var isRecordingSpeech = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentState = JarvisState.IDLE

    /**
     * Callback interface for communicating with the bound activity.
     */
    interface ServiceCallback {
        fun onWakeWordDetected()
        fun onSpeechRecognized(text: String)
        fun onStateChanged(state: JarvisState)
        fun onError(message: String)
    }

    inner class JarvisBinder : Binder() {
        fun getService(): JarvisForegroundService = this@JarvisForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setCallback(callback: ServiceCallback?) {
        this.callback = callback
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(JarvisApplication.NOTIFICATION_ID, createNotification())
                startWakeWordDetection()
            }
        }
        return START_STICKY
    }

    /**
     * Initialize and start wake word detection loop.
     */
    fun startWakeWordDetection() {
        if (isListeningForWakeWord) return

        serviceScope.launch {
            try {
                // Initialize wake word engine
                wakeWordEngine = WakeWordEngine(this@JarvisForegroundService)
                val accessKey = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
                    .getString("porcupine_key", "") ?: ""

                if (accessKey.isEmpty()) {
                    Log.w(TAG, "Porcupine access key not set")
                    callback?.onError("Wake word engine needs a Picovoice access key")
                    return@launch
                }

                val initialized = wakeWordEngine?.initialize(accessKey) ?: false
                if (!initialized) {
                    callback?.onError("Failed to initialize wake word engine")
                    return@launch
                }

                // Initialize audio recording
                audioRecord = AudioUtils.createAudioRecord()
                if (audioRecord == null) {
                    callback?.onError("Failed to access microphone")
                    return@launch
                }

                // Acquire wake lock
                acquireWakeLock()

                // Start listening
                isListeningForWakeWord = true
                updateState(JarvisState.IDLE)
                audioRecord?.startRecording()

                val frameLength = wakeWordEngine?.frameLength ?: 512
                val buffer = ShortArray(frameLength)

                Log.i(TAG, "Wake word detection started (frame=$frameLength)")

                while (isListeningForWakeWord && isActive) {
                    val read = audioRecord?.read(buffer, 0, frameLength) ?: -1
                    if (read > 0) {
                        val detected = wakeWordEngine?.processFrame(buffer) ?: false
                        if (detected) {
                            Log.i(TAG, "Wake word detected!")
                            handleWakeWordDetected()
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error in wake word detection loop", e)
                callback?.onError("Wake word detection error: ${e.message}")
            } finally {
                isListeningForWakeWord = false
            }
        }
    }

    /**
     * Handle wake word detection — switch to speech recording mode.
     */
    private suspend fun handleWakeWordDetected() {
        callback?.onWakeWordDetected()
        updateState(JarvisState.LISTENING)

        // Start speech recognition
        startSpeechRecording()
    }

    /**
     * Record speech and transcribe it using Vosk STT.
     */
    private suspend fun startSpeechRecording() {
        if (isRecordingSpeech) return
        isRecordingSpeech = true

        try {
            // Initialize STT if needed
            if (sttEngine == null) {
                sttEngine = VoskSpeechToText(this@JarvisForegroundService)
                val sttReady = CompletableDeferred<Boolean>()
                sttEngine?.initialize { success ->
                    sttReady.complete(success)
                }
                if (!sttReady.await()) {
                    callback?.onError("Speech recognition not available")
                    isRecordingSpeech = false
                    resumeWakeWordListening()
                    return
                }
            }

            if (sttEngine?.startListening() != true) {
                callback?.onError("Failed to start speech recognition")
                isRecordingSpeech = false
                resumeWakeWordListening()
                return
            }

            // Record audio with silence detection
            val bufferSize = AudioUtils.getMinBufferSize()
            val buffer = ShortArray(bufferSize / 2)
            var silenceCounter = 0
            var hasHeardSpeech = false
            val startTime = System.currentTimeMillis()

            while (isRecordingSpeech) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read <= 0) continue

                val bytes = AudioUtils.shortsToBytes(buffer, read)
                val isFinal = sttEngine?.acceptWaveForm(bytes, bytes.size) ?: false

                if (isFinal) {
                    val result = sttEngine?.getResult() ?: ""
                    if (result.isNotBlank()) {
                        finishSpeechRecording(result)
                        return
                    }
                }

                // Silence detection
                val silent = AudioUtils.isSilent(buffer, read)
                if (silent) {
                    silenceCounter++
                    if (hasHeardSpeech && silenceCounter >= MAX_SILENCE_FRAMES) {
                        // End of speech detected
                        val finalResult = sttEngine?.getFinalResult() ?: ""
                        finishSpeechRecording(finalResult)
                        return
                    }
                } else {
                    hasHeardSpeech = true
                    silenceCounter = 0
                }

                // Timeout
                if (System.currentTimeMillis() - startTime > MAX_RECORDING_DURATION_MS) {
                    val finalResult = sttEngine?.getFinalResult() ?: ""
                    finishSpeechRecording(finalResult)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during speech recording", e)
            callback?.onError("Speech recording error: ${e.message}")
        } finally {
            isRecordingSpeech = false
            sttEngine?.stopListening()
            resumeWakeWordListening()
        }
    }

    /**
     * Finish speech recording and send the result to the callback.
     */
    private fun finishSpeechRecording(text: String) {
        isRecordingSpeech = false
        sttEngine?.stopListening()

        if (text.isNotBlank()) {
            Log.i(TAG, "Speech recognized: $text")
            callback?.onSpeechRecognized(text)
        } else {
            Log.w(TAG, "No speech recognized")
            callback?.onError("I didn't hear anything. Please try again.")
        }

        updateState(JarvisState.IDLE)
    }

    /**
     * Resume listening for the wake word after speech processing.
     */
    private fun resumeWakeWordListening() {
        updateState(JarvisState.IDLE)
        // The main loop will continue automatically
    }

    /**
     * Manually trigger a voice session (from mic button tap).
     */
    fun startManualListening() {
        serviceScope.launch {
            updateState(JarvisState.LISTENING)
            startSpeechRecording()
        }
    }

    /**
     * Stop all listening and release resources.
     */
    private fun stopAllListening() {
        isListeningForWakeWord = false
        isRecordingSpeech = false

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        wakeWordEngine?.release()
        wakeWordEngine = null

        sttEngine?.release()
        sttEngine = null

        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllListening()
        Log.i(TAG, "Service destroyed")
    }

    /**
     * Create the foreground notification.
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JarvisApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.service_stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateState(state: JarvisState) {
        currentState = state
        callback?.onStateChanged(state)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JarvisAssistant::WakeWordLock"
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
