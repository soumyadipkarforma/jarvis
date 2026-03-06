package com.jarvis.assistant.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.commands.CommandProcessor
import com.jarvis.assistant.llm.LlamaLLMEngine
import com.jarvis.assistant.stt.VoskSpeechToText
import com.jarvis.assistant.tts.CoquiTextToSpeech
import com.jarvis.assistant.util.ChatMessage
import com.jarvis.assistant.util.JarvisConfig
import com.jarvis.assistant.util.JarvisState
import com.jarvis.assistant.util.VoiceResult
import com.jarvis.assistant.wakeword.WakeWordEngine
import kotlinx.coroutines.launch

/**
 * ViewModel for the main Jarvis Assistant screen.
 *
 * Manages:
 * - Conversation history (chat messages)
 * - Assistant state (idle, listening, processing, speaking)
 * - Coordination between STT, LLM, TTS, and Command engines
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_CONVERSATION_HISTORY = 10
    }

    // Engines
    val wakeWordEngine = WakeWordEngine(application)
    val sttEngine = VoskSpeechToText(application)
    val llmEngine = LlamaLLMEngine(application)
    val ttsEngine = CoquiTextToSpeech(application)
    val commandProcessor = CommandProcessor(application)
    private val downloadManager = com.jarvis.assistant.util.ModelDownloadManager(application)

    // State
    private val _state = MutableLiveData(JarvisState.IDLE)
    val state: LiveData<JarvisState> = _state

    // Download Progress
    private val _downloadProgress = MutableLiveData<Int>(0)
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _showDownloadPrompt = MutableLiveData<Boolean>(false)
    val showDownloadPrompt: LiveData<Boolean> = _showDownloadPrompt

    // Chat messages
    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    // Status text for the bottom bar
    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    // Service running state
    private val _serviceRunning = MutableLiveData(false)
    val serviceRunning: LiveData<Boolean> = _serviceRunning

    // Conversation history for LLM context
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    // Configuration
    var config = JarvisConfig()
        private set

    /**
     * Initialize all AI engines. Checks for missing models and native libraries.
     */
    fun initializeEngines() {
        viewModelScope.launch {
            _statusText.value = "Checking for AI brain..."

            // Only prompt for the large Llama model (1.7GB)
            // Vosk is now bundled in assets and will be extracted automatically
            val missingLlama = !downloadManager.isModelDownloaded(config.llamaModelPath)
            
            if (missingLlama) {
                _statusText.postValue("Jarvis needs to download his brain (1.7GB).")
                _showDownloadPrompt.postValue(true)
                return@launch
            }

            actuallyInitialize()
        }
    }

    /**
     * Start the download of required models.
     */
    fun startDownload() {
        _showDownloadPrompt.value = false
        _statusText.value = "Downloading Brain Model..."
        
        viewModelScope.launch {
            try {
                // Download Llama Model (Primary requirement)
                _statusText.postValue("Downloading Brain Model (1.7GB)...")
                val llamaFile = downloadManager.downloadFile(
                    com.jarvis.assistant.util.ModelDownloadManager.PHI2_MODEL_URL,
                    config.llamaModelPath
                ) { progress -> _downloadProgress.postValue(progress) }

                if (llamaFile == null) {
                    throw Exception("Failed to download Llama model")
                }

                _statusText.postValue("Download complete! Initializing...")
                actuallyInitialize()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _statusText.postValue("Error: Download failed. Please check connection.")
            }
        }
    }

    private suspend fun actuallyInitialize() {
        _statusText.postValue("Initializing engines...")

        // Initialize STT
        sttEngine.initialize(config.voskModelPath) { success ->
            if (success) {
                Log.i(TAG, "STT initialized")
            } else {
                Log.e(TAG, "STT initialization failed")
                _statusText.postValue("Warning: Speech recognition not available")
            }
        }

        // Initialize LLM
        val llmReady = llmEngine.initialize(config.llamaModelPath, config.maxLLMTokens)
        if (llmReady) {
            Log.i(TAG, "LLM initialized")
        } else {
            Log.w(TAG, "LLM not available - will use command processor only")
        }

        // Initialize TTS
        val ttsReady = ttsEngine.initialize(config.coquiModelPath)
        if (ttsReady) {
            Log.i(TAG, "TTS initialized")
        } else {
            Log.w(TAG, "TTS not available - will use text-only responses")
        }

        _statusText.postValue("Jarvis is ready. Say \"Jarvis\" or tap the mic.")
        _state.postValue(JarvisState.IDLE)
    }

    /**
     * Process transcribed text through the command processor and LLM.
     *
     * @param userText The text recognized from speech
     */
    fun processUserInput(userText: String) {
        if (userText.isBlank()) {
            _state.value = JarvisState.IDLE
            _statusText.value = "I didn't catch that. Please try again."
            return
        }

        // Add user message to chat
        addMessage(ChatMessage(userText, isUser = true))
        _state.value = JarvisState.PROCESSING
        _statusText.value = "Processing..."

        viewModelScope.launch {
            // First, try the offline command processor
            val commandResult = commandProcessor.processCommand(userText)

            when (commandResult) {
                is CommandProcessor.CommandResult.Execute -> {
                    try {
                        getApplication<Application>().startActivity(commandResult.intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to execute command", e)
                    }
                    respondWithText(commandResult.feedback)
                }

                is CommandProcessor.CommandResult.DirectResponse -> {
                    // Handle special flashlight commands
                    if (commandResult.response.startsWith("FLASHLIGHT_")) {
                        val action = if (commandResult.response == "FLASHLIGHT_on") "on" else "off"
                        respondWithText("Turning flashlight $action.")
                    } else {
                        respondWithText(commandResult.response)
                    }
                }

                is CommandProcessor.CommandResult.NotACommand -> {
                    // Not a command — use the LLM
                    if (llmEngine.isReady()) {
                        val response = llmEngine.generateResponse(userText, conversationHistory)
                        conversationHistory.add(userText to response)
                        // Keep history manageable
                        if (conversationHistory.size > MAX_CONVERSATION_HISTORY) {
                            conversationHistory.removeAt(0)
                        }
                        respondWithText(response)
                    } else {
                        respondWithText("I can handle commands like 'open <app>', 'set alarm', or 'what time is it'. For conversations, the language model needs to be loaded.")
                    }
                }
            }
        }
    }

    /**
     * Add a Jarvis response to the chat and speak it via TTS.
     */
    private suspend fun respondWithText(text: String) {
        addMessage(ChatMessage(text, isUser = false))

        if (ttsEngine.isReady()) {
            _state.postValue(JarvisState.SPEAKING)
            _statusText.postValue("Speaking...")
            ttsEngine.speak(text)
        }

        _state.postValue(JarvisState.IDLE)
        _statusText.postValue("Say \"Jarvis\" or tap the mic.")
    }

    /**
     * Add a message to the chat history.
     */
    fun addMessage(message: ChatMessage) {
        val current = _messages.value.orEmpty().toMutableList()
        current.add(message)
        _messages.postValue(current)
    }

    /**
     * Clear all chat messages.
     */
    fun clearMessages() {
        _messages.value = emptyList()
        conversationHistory.clear()
    }

    /**
     * Update the assistant state.
     */
    fun setState(newState: JarvisState) {
        _state.postValue(newState)
    }

    /**
     * Update the status text.
     */
    fun setStatusText(text: String) {
        _statusText.postValue(text)
    }

    /**
     * Mark the foreground service as running or stopped.
     */
    fun setServiceRunning(running: Boolean) {
        _serviceRunning.postValue(running)
    }

    /**
     * Update configuration.
     */
    fun updateConfig(newConfig: JarvisConfig) {
        config = newConfig
    }

    override fun onCleared() {
        super.onCleared()
        sttEngine.release()
        llmEngine.release()
        ttsEngine.release()
        wakeWordEngine.release()
    }
}
