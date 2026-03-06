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
     * Initialize all AI engines. Checks for missing models.
     */
    fun initializeEngines() {
        viewModelScope.launch {
            _statusText.value = "Checking for AI brain..."

            // Check if models are in internal storage
            val llamaExists = downloadManager.isModelDownloaded(config.llamaModelPath)
            val voskExists = downloadManager.isModelDownloaded(config.voskModelPath)
            
            if (!llamaExists || !voskExists) {
                _statusText.postValue("First run: Jarvis needs to initialize (2GB storage required).")
                _showDownloadPrompt.postValue(true)
                return@launch
            }

            actuallyInitialize()
        }
    }

    /**
     * Initialize the assistant by extracting bundled models from assets.
     */
    fun startDownload() {
        _showDownloadPrompt.value = false
        _statusText.value = "Initializing AI Components..."
        
        viewModelScope.launch {
            try {
                // 1. Extract SmolLM model from assets
                _statusText.postValue("Initializing Brain (may take a moment)...")
                val llamaFile = downloadManager.copyAssetToFile(
                    config.llamaModelPath,
                    config.llamaModelPath
                ) { progress -> _downloadProgress.postValue(progress) }

                if (llamaFile == null) throw Exception("Failed to extract LLM")

                // 2. Extract Vosk model directory from assets
                _statusText.postValue("Initializing Speech Engine...")
                val voskSuccess = downloadManager.copyAssetDir(
                    config.voskModelPath,
                    config.voskModelPath
                )
                if (!voskSuccess) throw Exception("Failed to extract STT")

                // 3. Extract Piper TTS model from assets
                _statusText.postValue("Initializing Voice...")
                downloadManager.copyAssetToFile("tts-model.onnx", "tts-model.onnx") {}
                downloadManager.copyAssetToFile("tts-model.onnx.json", "tts-model.onnx.json") {}

                _statusText.postValue("Setup complete! Jarvis is coming online...")
                actuallyInitialize()
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                _statusText.postValue("Error: Failed to initialize. Storage full?")
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
            Log.w(TAG, "LLM not available")
        }

        // Initialize TTS
        val ttsReady = ttsEngine.initialize(config.coquiModelPath)
        if (ttsReady) {
            Log.i(TAG, "TTS initialized")
        } else {
            Log.w(TAG, "TTS not available")
        }

        _statusText.postValue("Jarvis is ready. Say \"Jarvis\" or tap the mic.")
        _state.postValue(JarvisState.IDLE)
    }

    /**
     * Process transcribed text through the command processor and LLM.
     */
    fun processUserInput(userText: String) {
        if (userText.isBlank()) {
            _state.value = JarvisState.IDLE
            _statusText.value = "I didn't catch that. Please try again."
            return
        }

        addMessage(ChatMessage(userText, isUser = true))
        _state.value = JarvisState.PROCESSING
        _statusText.value = "Processing..."

        viewModelScope.launch {
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
                    if (commandResult.response.startsWith("FLASHLIGHT_")) {
                        val action = if (commandResult.response == "FLASHLIGHT_on") "on" else "off"
                        respondWithText("Turning flashlight $action.")
                    } else {
                        respondWithText(commandResult.response)
                    }
                }

                is CommandProcessor.CommandResult.NotACommand -> {
                    if (llmEngine.isReady()) {
                        val response = llmEngine.generateResponse(userText, conversationHistory)
                        conversationHistory.add(userText to response)
                        if (conversationHistory.size > MAX_CONVERSATION_HISTORY) {
                            conversationHistory.removeAt(0)
                        }
                        respondWithText(response)
                    } else {
                        respondWithText("Offline mode: I can open apps or check the battery. LLM is not loaded.")
                    }
                }
            }
        }
    }

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

    fun addMessage(message: ChatMessage) {
        val current = _messages.value.orEmpty().toMutableList()
        current.add(message)
        _messages.postValue(current)
    }

    fun clearMessages() {
        _messages.value = emptyList()
        conversationHistory.clear()
    }

    fun setState(newState: JarvisState) {
        _state.postValue(newState)
    }

    fun setStatusText(text: String) {
        _statusText.postValue(text)
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.postValue(running)
    }

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