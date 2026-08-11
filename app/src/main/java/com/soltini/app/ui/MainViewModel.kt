package com.soltini.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BackgroundVoiceService.LocalBinder
            voiceService = binder.getService()
            observeService()
            // Safety net: if START_STICKY restarted the service but the WebSocket is not
            // yet alive (e.g. still Disconnected after the auto-connect in onCreate),
            // trigger connect() here so the user always gets a live session when opening the app.
            viewModelScope.launch {
                val svc = voiceService ?: return@launch
                if (!svc.isSleeping.value &&
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
        application.startService(intent) // Keep service running in background
        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
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

    /** Gives Settings UI access to user-configurable AI settings. */
    fun getAppSettings() = voiceService?.appSettings

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
