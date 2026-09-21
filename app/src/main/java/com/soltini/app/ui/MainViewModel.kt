package com.soltini.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soltini.app.network.ConnectionState
import com.soltini.app.services.BackgroundVoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TranscriptEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Sender {
        USER, GEMINI
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val appSettings = com.soltini.app.settings.AppSettings(application)
    val skillRegistry = com.soltini.app.agent.skills.SkillRegistry.getInstance(application)
    private var voiceService: BackgroundVoiceService? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _speakerAmplitude = MutableStateFlow(0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcripts: StateFlow<List<TranscriptEntry>> = _transcripts.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isLitePaused = MutableStateFlow(false)
    val isLitePaused: StateFlow<Boolean> = _isLitePaused.asStateFlow()

    private val _isHardPaused = MutableStateFlow(false)
    val isHardPaused: StateFlow<Boolean> = _isHardPaused.asStateFlow()

    private val _isAppPaused = MutableStateFlow(appSettings.isAppPaused)
    val isAppPaused: StateFlow<Boolean> = _isAppPaused.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BackgroundVoiceService.LocalBinder
            voiceService = binder.getService()
            voiceService?.setAppInForeground(true)
            observeService()
            // Safety net: if START_STICKY restarted the service but the WebSocket is not
            // yet alive (e.g. still Disconnected after the auto-connect in onCreate),
            // trigger connect() here so the user always gets a live session when opening the app.
            viewModelScope.launch {
                val svc = voiceService ?: return@launch
                if (!appSettings.isAppPaused && !svc.isSleeping.value &&
                    svc.connectionState.value == ConnectionState.Disconnected) {
                    svc.connect()
                }
            }
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            voiceService = null
        }
    }

    init {
        val intent = Intent(application, BackgroundVoiceService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                application.startForegroundService(intent)
            } else {
                application.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Could not startForegroundService immediately, relying on bindService: ${e.message}")
        }
        try {
            application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to bind service", e)
        }
    }

    private fun observeService() {
        voiceService?.let { service ->
            viewModelScope.launch { service.connectionState.collect { _connectionState.value = it } }
            viewModelScope.launch { service.errorMessage.collect { _errorMessage.value = it } }
            viewModelScope.launch { service.micAmplitude.collect { _micAmplitude.value = it } }
            viewModelScope.launch { service.speakerAmplitude.collect { _speakerAmplitude.value = it } }
            viewModelScope.launch { service.isSpeaking.collect { _isSpeaking.value = it } }
            viewModelScope.launch { service.isMuted.collect { _isMuted.value = it } }
            viewModelScope.launch { service.hasMicPermission.collect { _hasMicPermission.value = it } }
            viewModelScope.launch { service.transcripts.collect { _transcripts.value = it } }
            viewModelScope.launch { service.isPaused.collect {
                _isPaused.value = it
                _isLitePaused.value = it
            }}
            viewModelScope.launch { service.isHardPaused.collect { _isHardPaused.value = it } }
            viewModelScope.launch { service.isAppPaused.collect { _isAppPaused.value = it } }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        voiceService?.onPermissionResult(granted)
    }

    fun connect() {
        voiceService?.connect()
    }

    fun disconnect() {
        voiceService?.disconnect()
    }

    fun toggleMute() {
        voiceService?.toggleMute()
    }

    fun clearTranscript() {
        voiceService?.clearTranscript()
    }

    /** Disconnects and reconnects to apply new API key / persona / voice settings. */
    fun reconnect() {
        voiceService?.reconnect()
    }

    /**
     * Injects a text prompt into the live Gemini session as if the user spoke it.
     * Called by quick-macro buttons on the VoiceScreen.
     */
    fun sendTextMessage(text: String) {
        voiceService?.sendTextMessage(text)
    }

    /** Master App Pause: disables all background mic, overlay, autostart, and resurrection. */
    fun pauseApp() {
        appSettings.isAppPaused = true
        _isAppPaused.value = true
        voiceService?.pauseApp()
    }

    /** Master App Resume: re-enables normal background assistant, overlay, and mic. */
    fun resumeApp() {
        appSettings.isAppPaused = false
        _isAppPaused.value = false
        _isPaused.value = false
        _isLitePaused.value = false
        _isHardPaused.value = false
        val intent = Intent(getApplication(), BackgroundVoiceService::class.java).apply {
            action = "com.soltini.app.ACTION_RESUME_APP"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Error starting service with ACTION_RESUME_APP: ${e.message}")
        }
        voiceService?.resumeApp()
        connect()
    }

    /** Toggle Master App Pause. */
    fun toggleAppPause() {
        if (_isAppPaused.value) resumeApp() else pauseApp()
    }

    /** Toggles Pause Mode: pauses everything including accessibility, or resumes. */
    fun togglePauseMode() {
        voiceService?.togglePauseMode()
    }

    /** Lite Pause: silences accessibility without disabling. Instant resume. */
    fun toggleLitePauseMode() {
        voiceService?.toggleLitePauseMode()
    }

    /** Hard Pause: calls disableSelf() for strict banks. Auto-opens settings on resume. */
    fun toggleHardPauseMode() {
        voiceService?.toggleHardPauseMode()
    }

    /** Gives Settings UI access to user-configurable AI settings. */
    // getAppSettings() property getter is automatically synthesized from val appSettings

    fun setAppInForeground(inForeground: Boolean) {
        voiceService?.setAppInForeground(inForeground)
    }

    /** Gives Settings UI access to the memory store (for viewer + clear button). */
    fun getMemoryManager() = voiceService?.memoryManager

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unbindService(connection)
        } catch (e: Exception) {
            // Ignore if already unbound
        }
    }
}
