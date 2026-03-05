package com.jarvis.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.ui.ChatAdapter
import com.jarvis.assistant.ui.MainViewModel
import com.jarvis.assistant.util.ChatMessage
import com.jarvis.assistant.util.JarvisState

/**
 * Main activity for the Jarvis Assistant app.
 *
 * Displays the conversation UI and controls for the voice assistant.
 * Binds to the [JarvisForegroundService] for wake word detection and
 * voice recording.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var chatAdapter: ChatAdapter

    // Service binding
    private var jarvisService: JarvisForegroundService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val jarvisBinder = binder as JarvisForegroundService.JarvisBinder
            jarvisService = jarvisBinder.getService()
            jarvisService?.setCallback(serviceCallback)
            serviceBound = true
            viewModel.setServiceRunning(true)
            Log.i(TAG, "Bound to Jarvis service")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            jarvisService = null
            serviceBound = false
            viewModel.setServiceRunning(false)
            Log.i(TAG, "Unbound from Jarvis service")
        }
    }

    private val serviceCallback = object : JarvisForegroundService.ServiceCallback {
        override fun onWakeWordDetected() {
            runOnUiThread {
                viewModel.setState(JarvisState.LISTENING)
                viewModel.setStatusText("Listening...")
                viewModel.addMessage(ChatMessage("🎤 Wake word detected", isUser = false))
            }
        }

        override fun onSpeechRecognized(text: String) {
            runOnUiThread {
                viewModel.processUserInput(text)
            }
        }

        override fun onStateChanged(state: JarvisState) {
            runOnUiThread {
                viewModel.setState(state)
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                viewModel.setState(JarvisState.ERROR)
                viewModel.setStatusText(message)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            startJarvisService()
        } else {
            Toast.makeText(this, getString(R.string.error_mic_permission), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupUI()
        observeViewModel()
        viewModel.initializeEngines()
    }

    private fun setupUI() {
        // Setup chat RecyclerView
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        // Welcome message
        viewModel.addMessage(
            ChatMessage(getString(R.string.welcome_message), isUser = false)
        )

        // Mic button
        binding.micButton.setOnClickListener {
            if (serviceBound && jarvisService != null) {
                jarvisService?.startManualListening()
                viewModel.setState(JarvisState.LISTENING)
                viewModel.setStatusText("Listening...")
            } else {
                Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
            }
        }

        // Service toggle button
        binding.serviceToggleButton.setOnClickListener {
            if (serviceBound) {
                stopJarvisService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // Clear button
        binding.clearButton.setOnClickListener {
            viewModel.clearMessages()
        }
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { messages ->
            chatAdapter.submitList(messages.toList())
            if (messages.isNotEmpty()) {
                binding.chatRecyclerView.smoothScrollToPosition(messages.size - 1)
            }
        }

        viewModel.state.observe(this) { state ->
            updateUIForState(state)
        }

        viewModel.statusText.observe(this) { text ->
            binding.statusText.text = text
        }

        viewModel.showDownloadPrompt.observe(this) { show ->
            if (show) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Download Required")
                    .setMessage("Jarvis needs to download AI models (~1.8GB) to function offline. This may take some time.")
                    .setPositiveButton("Download") { _, _ -> viewModel.startDownload() }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }

        viewModel.downloadProgress.observe(this) { progress ->
            // Update a progress bar if available in layout
            if (progress > 0) {
                viewModel.setStatusText("Downloading... $progress%")
            }
        }

        viewModel.serviceRunning.observe(this) { running ->
            binding.serviceToggleButton.text = if (running) {
                getString(R.string.service_stop)
            } else {
                getString(R.string.service_start)
            }
        }
    }

    private fun updateUIForState(state: JarvisState) {
        val (color, text) = when (state) {
            JarvisState.IDLE -> Pair(R.color.status_idle, getString(R.string.idle))
            JarvisState.LISTENING -> Pair(R.color.status_listening, getString(R.string.listening))
            JarvisState.PROCESSING -> Pair(R.color.status_processing, getString(R.string.processing))
            JarvisState.SPEAKING -> Pair(R.color.status_speaking, getString(R.string.speaking))
            JarvisState.ERROR -> Pair(R.color.status_error, "Error")
        }

        binding.statusIndicator.setTextColor(ContextCompat.getColor(this, color))
        binding.statusIndicator.text = text
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startJarvisService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startJarvisService() {
        val intent = Intent(this, JarvisForegroundService::class.java).apply {
            action = JarvisForegroundService.ACTION_START
        }

        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopJarvisService() {
        if (serviceBound) {
            jarvisService?.setCallback(null)
            unbindService(serviceConnection)
            serviceBound = false
        }

        val intent = Intent(this, JarvisForegroundService::class.java).apply {
            action = JarvisForegroundService.ACTION_STOP
        }
        startService(intent)
        viewModel.setServiceRunning(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            jarvisService?.setCallback(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
