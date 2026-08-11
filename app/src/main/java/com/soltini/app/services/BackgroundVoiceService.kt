package com.soltini.app.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.soltini.app.BootReceiver
import com.soltini.app.agent.AgentToolExecutor
import com.soltini.app.audio.AudioPlayer
import com.soltini.app.audio.AudioRecorder
import com.soltini.app.memory.MemoryDatabase
import com.soltini.app.memory.MemoryManager
import com.soltini.app.network.ConnectionState
import com.soltini.app.network.GeminiLiveListener
import com.soltini.app.network.GeminiLiveManager
import com.soltini.app.network.ToolCallListener
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.TranscriptEntry
import com.soltini.app.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * BackgroundVoiceService
 *
 * Core foreground service that:
 *   1. Maintains the persistent Gemini Live WebSocket connection.
 *   2. Routes microphone audio to the WebSocket.
 *   3. Plays received audio from Gemini.
 *   4. Implements ToolCallListener to execute device actions when Gemini requests them.
 *   5. Manages sleep / wake state (disconnects Gemini on sleep, stays alive for re-trigger).
 */
class BackgroundVoiceService : Service(), GeminiLiveListener, ToolCallListener {

    companion object {
        private const val TAG = "BackgroundVoiceService"
        private const val CHANNEL_ID = "VoiceSessionChannel"
        private const val NOTIFICATION_ID = 2
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    // SupervisorJob: one failing coroutine does NOT cancel all sibling coroutines
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── User-configurable AI settings ────────────────────────────────────────
    lateinit var appSettings: AppSettings
        private set

    // ── Persistent vector memory ─────────────────────────────────────────────
    lateinit var memoryManager: MemoryManager
        private set

    /**
     * Counts how many tool calls are currently in-flight.
     * Accessibility high-friction tracking is enabled while this is > 0
     * and disabled when it drops back to 0, preventing stale tracking state.
     */
    private val activeToolCallCount = AtomicInteger(0)

    private val audioPlayer = AudioPlayer()
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var geminiLiveManager: GeminiLiveManager
    private lateinit var agentToolExecutor: AgentToolExecutor

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSleeping = MutableStateFlow(false)
    val isSleeping: StateFlow<Boolean> = _isSleeping.asStateFlow()

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

        // ── FIRST: Call startForeground immediately ─────────────────────────────
        // Android 8+ will kill any service that does not call startForeground within
        // 5 seconds of creation. We do this BEFORE any other work so we never hit
        // that window even if GeminiLiveManager or AudioRecorder take time to init.
        createNotificationChannel()
        startForegroundService()

        // ── WakeLock with timeout ───────────────────────────────────────────────
        // A timeout prevents OEM battery managers (Xiaomi, Samsung, OnePlus) from
        // treating an indefinite WakeLock as a reason to kill the process.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Soltini::VoiceWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L) // 10-minute timeout

        // ── User-configurable settings + memory ──────────────────────────────
        appSettings = AppSettings(this)
        memoryManager = MemoryManager(MemoryDatabase(this), appSettings)

        audioRecorder = AudioRecorder(
            onAudioChunkCaptured = { pcmChunk ->
                if (!_isSleeping.value) {
                    geminiLiveManager.sendAudioChunk(pcmChunk)
                }
            }
        )

        // Pass 'this' as both GeminiLiveListener and ToolCallListener
        geminiLiveManager = GeminiLiveManager(
            listener = this,
            toolCallListener = this,
            appSettings = appSettings,
            memoryManager = memoryManager
        )

        agentToolExecutor = AgentToolExecutor(this)

        connectionState = geminiLiveManager.connectionState
        errorMessage = geminiLiveManager.errorMessage
        micAmplitude = audioRecorder.micAmplitude
        speakerAmplitude = audioPlayer.speakerAmplitude
        isSpeaking = audioPlayer.isSpeaking

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
                        val state = if (_isSleeping.value) com.soltini.app.overlay.RingState.SLEEPING else com.soltini.app.overlay.RingState.IDLE
                        OverlayService.getInstance()?.setRingState(state)
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
        // Only handle explicit sleep wakeup here.
        if (_isSleeping.value) {
            wakeUp()
        }
        // START_STICKY: Android will restart this service automatically after it is
        // killed (e.g. swipe from Recents), which keeps accessibility actions alive.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed (swiped from recents) — scheduling AlarmManager to restart service")
        val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
            action = "com.soltini.app.RESTART_SERVICE"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Fire 1 second from now to allow the OS to finish killing the process first
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun onPermissionResult(granted: Boolean) {
        _hasMicPermission.value = granted
        if (granted) {
            // Re-call startForegroundService now that we have permission, so the OS adds the microphone FGS type
            startForegroundService()
            if (connectionState.value == ConnectionState.Live) {
                audioRecorder.startRecording()
            }
        }
    }

    fun connect() {
        _isSleeping.value = false
        geminiLiveManager.connect()
    }

    /**
     * Disconnects the current session and immediately opens a fresh one.
     * Used by the "Save & Reconnect" button in AI Configuration settings
     * so new API key / persona / voice take effect without restarting.
     */
    fun reconnect() {
        Log.i(TAG, "Reconnecting to apply new AI settings")
        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
        // Small gap to let WebSocket close cleanly before re-opening
        serviceScope.launch {
            kotlinx.coroutines.delay(500)
            connect()
        }
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

    /**
     * Puts Soltini into 100% sleep:
     *   - Stops microphone recording (AudioRecord released)
     *   - Stops audio playback (AudioTrack released)
     *   - Fully disconnects Gemini WebSocket + shuts down OkHttp client
     *   - Releases the WakeLock so the CPU can actually sleep
     *   - Disables accessibility tool-call flag (no event processing overhead)
     *
     * The foreground service stays alive (notification visible) so Android keeps
     * the process ready for the next wakeup tap on the orb.
     */
    fun sleep() {
        Log.i(TAG, "Soltini going to sleep — full power-down")
        _isSleeping.value = true

        // Stop all audio hardware
        audioRecorder.stopRecording()
        audioPlayer.stop()

        // Cut the WebSocket and shut down the OkHttp thread pool
        geminiLiveManager.disconnect()

        // Release WakeLock — CPU can now truly sleep between orb taps
        wakeLock?.let { if (it.isHeld) it.release() }

        // Disable accessibility event processing — no overhead while idle
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false

        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.SLEEPING)
    }

    /**
     * Wakes Soltini from sleep:
     *   - Reacquires a fresh WakeLock (10-min timeout)
     *   - Reconnects to Gemini Live WebSocket
     */
    fun wakeUp() {
        Log.i(TAG, "Soltini waking up from sleep")
        _isSleeping.value = false

        // Reacquire WakeLock so the CPU stays on during voice session
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }

        connect()
    }

    /**
     * Toggles between sleep and awake states.
     * Called when the user taps the floating orb while the agent is sleeping.
     */
    fun toggleSleep() {
        if (_isSleeping.value) wakeUp() else sleep()
    }

    // ─── ToolCallListener Implementation ─────────────────────────────────────

    /**
     * Called on the main thread when Gemini sends a toolCall message.
     * We execute the action and send the result back to Gemini.
     *
     * Uses an atomic counter instead of a plain boolean flag so that concurrent
     * tool calls can each independently increment/decrement without clobbering each
     * other. High-friction accessibility tracking is on only while count > 0.
     */
    override fun onToolCall(callId: String, toolName: String, args: JSONObject) {
        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Executing tool: $toolName (callId=$callId)")

            if (toolName == "sleep_agent") {
                sleep()
                return@launch
            }

            // Increment counter and activate accessibility event tracking
            val count = activeToolCallCount.incrementAndGet()
            Log.d(TAG, "Tool call started — activeToolCallCount=$count")
            if (count == 1) {
                // Activate using the safe @Volatile flag — never touches serviceInfo
                com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = true
            }

            try {
                val result = agentToolExecutor.execute(toolName, args)
                geminiLiveManager.sendToolResponse(callId, result)
            } finally {
                val remaining = activeToolCallCount.decrementAndGet()
                Log.d(TAG, "Tool call finished — activeToolCallCount=$remaining")
                if (remaining <= 0) {
                    activeToolCallCount.set(0)
                    // Deactivate using the safe @Volatile flag
                    com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
                }
            }
        }
    }

    // ─── GeminiLiveListener Implementation ───────────────────────────────────

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
        // Capture the finished transcript before clearing, then extract memories
        val finishedText = currentGeminiTranscript.toString().trim()
        currentGeminiTranscript.clear()
        if (finishedText.isNotBlank()) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    memoryManager.extractAndSave(finishedText)
                } catch (e: Exception) {
                    Log.w(TAG, "Memory extraction error (non-fatal): ${e.message}")
                }
            }
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "Gemini Live Error: $message")
        // Reset tool call counter and deactivate accessibility on connection error
        activeToolCallCount.set(0)
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
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
            .setContentTitle("Soltini")
            .setContentText("Your AI Assistant is ready")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (hasMicPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= 34) {
                // API 34+ requires a type. Since we don't have mic yet, fallback to specialUse (1 << 30)
                startForeground(NOTIFICATION_ID, notification, 1073741824)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
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
