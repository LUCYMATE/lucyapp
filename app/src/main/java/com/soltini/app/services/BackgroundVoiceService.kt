package com.soltini.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.soltini.app.audio.AudioPlayer
import com.soltini.app.audio.AudioRecorder
import com.soltini.app.network.ConnectionState
import com.soltini.app.network.GeminiLiveListener
import com.soltini.app.network.GeminiLiveManager
import com.soltini.app.ui.TranscriptEntry
import com.soltini.app.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BackgroundVoiceService : Service(), GeminiLiveListener {

    companion object {
        private const val TAG = "BackgroundVoiceService"
        private const val CHANNEL_ID = "VoiceSessionChannel"
        private const val NOTIFICATION_ID = 2
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private val audioPlayer = AudioPlayer()
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var geminiLiveManager: GeminiLiveManager

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcripts: StateFlow<List<TranscriptEntry>> = _transcripts.asStateFlow()
    private var currentGeminiTranscript = StringBuilder()

    // Expose flows from the child components
    lateinit var connectionState: StateFlow<ConnectionState>
    lateinit var errorMessage: StateFlow<String?>
    lateinit var micAmplitude: StateFlow<Float>
    lateinit var speakerAmplitude: StateFlow<Float>
    lateinit var isSpeaking: StateFlow<Boolean>

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundVoiceService = this@BackgroundVoiceService
    }

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Soltini::VoiceWakeLock")
        wakeLock?.acquire()

        audioRecorder = AudioRecorder(
            onAudioChunkCaptured = { pcmChunk ->
                geminiLiveManager.sendAudioChunk(pcmChunk)
            }
        )
        geminiLiveManager = GeminiLiveManager(this)

        connectionState = geminiLiveManager.connectionState
        errorMessage = geminiLiveManager.errorMessage
        micAmplitude = audioRecorder.micAmplitude
        speakerAmplitude = audioPlayer.speakerAmplitude
        isSpeaking = audioPlayer.isSpeaking

        createNotificationChannel()
        startForegroundService()

        serviceScope.launch {
            connectionState.collect { state ->
                when (state) {
                    ConnectionState.Live -> {
                        if (_hasMicPermission.value) {
                            audioRecorder.startRecording()
                        }
                        audioPlayer.start()
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.LISTENING)
                    }
                    ConnectionState.Disconnected, ConnectionState.Error -> {
                        audioRecorder.stopRecording()
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.IDLE)
                    }
                    else -> {}
                }
            }
        }

        // Keep OverlayService synced with amplitude
        serviceScope.launch {
            speakerAmplitude.collect { amp ->
                OverlayService.getInstance()?.setMouthAmplitude(amp)
            }
        }
        serviceScope.launch {
            isSpeaking.collect { isSpeaking ->
                OverlayService.getInstance()?.setTalking(isSpeaking)
                if (connectionState.value == ConnectionState.Live) {
                    if (isSpeaking) {
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.SPEAKING)
                    } else {
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.LISTENING)
                    }
                }
            }
        }

        // Auto-connect if restarted by system (e.g. after swipe from recents)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, 
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _hasMicPermission.value = true
            if (connectionState.value == ConnectionState.Disconnected) {
                connect()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun onPermissionResult(granted: Boolean) {
        _hasMicPermission.value = granted
        if (granted && connectionState.value == ConnectionState.Live) {
            audioRecorder.startRecording()
        }
    }

    fun connect() {
        geminiLiveManager.connect()
    }

    fun disconnect() {
        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
    }

    fun toggleMute() {
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        audioRecorder.isMuted = newMute
    }

    fun clearTranscript() {
        _transcripts.value = emptyList()
        currentGeminiTranscript.clear()
    }

    // --- GeminiLiveListener Implementation ---

    override fun onAudioDataReceived(pcmData: ByteArray) {
        audioPlayer.playChunk(pcmData)
    }

    override fun onTextReceived(text: String) {
        currentGeminiTranscript.append(text)
        val fullText = currentGeminiTranscript.toString().trim()

        val list = _transcripts.value.toMutableList()
        val lastIndex = list.indexOfLast { it.sender == TranscriptEntry.Sender.GEMINI }

        if (lastIndex != -1) {
            list[lastIndex] = list[lastIndex].copy(text = fullText)
        } else {
            list.add(TranscriptEntry(sender = TranscriptEntry.Sender.GEMINI, text = fullText))
        }

        _transcripts.value = list
    }

    override fun onInterrupted() {
        Log.d(TAG, "User interrupted Gemini speech")
        audioPlayer.interrupt()
        currentGeminiTranscript.clear()
    }

    override fun onTurnComplete() {
        Log.d(TAG, "Gemini turn completed")
        currentGeminiTranscript.clear()
    }

    override fun onError(message: String) {
        Log.e(TAG, "Gemini Live Error: $message")
    }

    override fun onDestroy() {
        super.onDestroy()
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        
        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Soltini Voice Service")
            .setContentText("Soltini is connected to Gemini Live")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Session Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
