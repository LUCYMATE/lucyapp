package com.soltini.app.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.soltini.app.MainActivity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    private lateinit var backgroundAgentRunner: com.soltini.app.agent.BackgroundAgentRunner
    private lateinit var wakeWordEngine: com.soltini.app.audio.WakeWordEngine

    private val _hasMicPermission = MutableStateFlow(false)
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSleeping = MutableStateFlow(false)
    val isSleeping: StateFlow<Boolean> = _isSleeping.asStateFlow()

    /**
     * Lite Pause — mic/WS off, accessibility service silenced but still listed.
     * Works for most banking apps. No re-enable needed.
     */
    private val _isLitePaused = MutableStateFlow(false)
    val isLitePaused: StateFlow<Boolean> = _isLitePaused.asStateFlow()

    /**
     * Hard Pause — mic/WS off + disableSelf() on accessibility service.
     * For strict banking apps (HDFC, ICICI, etc.). Requires one manual re-enable.
     */
    private val _isHardPaused = MutableStateFlow(false)
    val isHardPaused: StateFlow<Boolean> = _isHardPaused.asStateFlow()

    /** True if either pause mode is active (convenience for UI) */
    val isPaused: StateFlow<Boolean> get() = _isLitePaused  // kept for compat — see combined below

    private val _transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcripts: StateFlow<List<TranscriptEntry>> = _transcripts.asStateFlow()
    private var currentGeminiTranscript = StringBuilder()
    private var lastSpeechTimestamp = System.currentTimeMillis()

    // Expose flows from the child components
    lateinit var connectionState: StateFlow<ConnectionState>
    lateinit var errorMessage: StateFlow<String?>
    lateinit var micAmplitude: StateFlow<Float>
    lateinit var speakerAmplitude: StateFlow<Float>
    lateinit var isSpeaking: StateFlow<Boolean>

    // Zero-battery MIUI bypass
    private var silentMediaPlayer: MediaPlayer? = null

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundVoiceService = this@BackgroundVoiceService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BackgroundVoiceService created")

        // Start hardware-offloaded silent audio to bypass MIUI's "Clear All" with 0% battery drain
        startZeroBatterySilentAudioTrick()

        // ── FIRST: Call startForeground immediately ─────────────────────────────
        // Android 8+ will kill any service that does not call startForeground within
        // 5 seconds of creation. We do this BEFORE any other work so we never hit
        // that window even if GeminiLiveManager or AudioRecorder take time to init.
        createNotificationChannel()
        startForegroundService()

        // ── WakeLock with auto-renewal ──────────────────────────────────────────
        // A timeout prevents OEM battery managers (Xiaomi, Samsung, OnePlus) from
        // treating an indefinite WakeLock as a reason to kill the process.
        // We also launch a coroutine that renews the lock every 9 minutes so it
        // never expires during an ongoing voice session.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Soltini::VoiceWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L) // 10-minute initial timeout

        // WakeLock auto-renewal coroutine — re-acquires every 9 minutes
        serviceScope.launch {
            while (isActive) {
                delay(9 * 60 * 1000L) // renew 1 minute before expiry
                if (!_isSleeping.value) {
                    try {
                        if (wakeLock?.isHeld == false) {
                            wakeLock?.acquire(10 * 60 * 1000L)
                            Log.d(TAG, "WakeLock renewed")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WakeLock renewal failed: ${e.message}")
                    }
                }
            }
        }

        // ── User-configurable settings + memory ──────────────────────────────
        appSettings = AppSettings(this)
        memoryManager = MemoryManager(MemoryDatabase(this), appSettings)

        wakeWordEngine = com.soltini.app.audio.WakeWordEngine(this).apply {
            initialize()
            onWakeWordDetected = { word ->
                Log.i(TAG, "Wake word detected: $word — activating assistant")
                if (_isSleeping.value) {
                    wakeUp()
                }
                if (connectionState.value == ConnectionState.Disconnected) {
                    connect()
                }
                OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.LISTENING)
            }
            if (appSettings.isWakeWordEnabled) {
                start()
            }
        }

        com.soltini.app.core.PhoneStateReceiver.onCallStateChangedListener = { state, number ->
            when (state) {
                android.telephony.TelephonyManager.EXTRA_STATE_RINGING,
                android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.i(TAG, "Phone call in progress ($state, number: $number) — muting Soltini audio")
                    audioPlayer.stop()
                    setMute(true)
                }
                android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.i(TAG, "Phone call finished — unmuting Soltini audio")
                    setMute(false)
                    if (connectionState.value == ConnectionState.Live) {
                        audioPlayer.start()
                    }
                }
            }
        }

        audioRecorder = AudioRecorder(
            onAudioChunkCaptured = { pcmChunk ->
                if (appSettings.isWakeWordEnabled) {
                    wakeWordEngine.processAudioFrame(pcmChunk, pcmChunk.size)
                }
                if (!_isSleeping.value && !_isMuted.value) {
                    geminiLiveManager.sendAudioChunk(pcmChunk)
                }
            },
            onSpeechStateChanged = {
                lastSpeechTimestamp = System.currentTimeMillis()
            }
        ).apply {
            isDataSaverEnabled = appSettings.isDataSaverEnabled
            vadThreshold = appSettings.vadThreshold
            micGain = appSettings.micGain
            isScreenRecordingModeEnabled = appSettings.isScreenRecordingModeEnabled
        }


        // Pass 'this' as both GeminiLiveListener and ToolCallListener
        geminiLiveManager = GeminiLiveManager(
            listener = this,
            toolCallListener = this,
            appSettings = appSettings,
            memoryManager = memoryManager
        )

        agentToolExecutor = AgentToolExecutor(this)
        backgroundAgentRunner = com.soltini.app.agent.BackgroundAgentRunner(this, agentToolExecutor, appSettings)

        connectionState = geminiLiveManager.connectionState
        errorMessage = geminiLiveManager.errorMessage
        micAmplitude = audioRecorder.micAmplitude
        speakerAmplitude = audioPlayer.speakerAmplitude
        isSpeaking = audioPlayer.isSpeaking

        serviceScope.launch {
            connectionState.collect { state ->
                when (state) {
                    ConnectionState.Live -> {
                        if (appSettings.isScreenRecordingModeEnabled) {
                            try {
                                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                am.mode = AudioManager.MODE_IN_COMMUNICATION
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to set MODE_IN_COMMUNICATION: ${e.message}")
                            }
                        }

                        lastSpeechTimestamp = System.currentTimeMillis()
                        if (_hasMicPermission.value) {
                            audioRecorder.startRecording()
                        }
                        audioPlayer.start()
                        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.LISTENING)
                    }
                    ConnectionState.Disconnected, ConnectionState.Error -> {
                        try {
                            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            am.mode = AudioManager.MODE_NORMAL
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to restore MODE_NORMAL: ${e.message}")
                        }

                        audioRecorder.stopRecording()
                        val ringState = if (_isSleeping.value) com.soltini.app.overlay.RingState.SLEEPING else com.soltini.app.overlay.RingState.IDLE
                        OverlayService.getInstance()?.setRingState(ringState)
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
                audioRecorder.isModelSpeaking = isSpeaking
                if (isSpeaking) {
                    lastSpeechTimestamp = System.currentTimeMillis()
                }
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
        if (intent?.action == "com.soltini.app.ACTION_SPEAK_REMINDER") {
            val msg = intent.getStringExtra("message") ?: ""
            if (msg.isNotEmpty()) {
                if (_isSleeping.value) wakeUp()
                if (connectionState.value == ConnectionState.Disconnected) connect()
                
                serviceScope.launch {
                    // Wait up to 5 seconds for connection
                    var retries = 25
                    while (connectionState.value != ConnectionState.Live && retries > 0) {
                        kotlinx.coroutines.delay(200)
                        retries--
                    }
                    if (connectionState.value == ConnectionState.Live) {
                        val notificationSummary = com.soltini.app.automation.SoltiniNotificationListener.getRecentNotificationsSummary()
                        val fullMsg = if (notificationSummary.isNotEmpty()) {
                            "REMINDER TRIGGERED: $msg\nAlso, you have recent notifications: $notificationSummary. Please speak aloud to summarize the reminder and notifications to the user."
                        } else {
                            "REMINDER TRIGGERED: $msg. Please speak aloud to notify the user."
                        }
                        geminiLiveManager.sendClientContent(fullMsg)
                    }
                }
            }
        } else if (_isSleeping.value) {
            wakeUp()
        }
        
        // START_NOT_STICKY: We explicitly do NOT want the OS to natively resurrect this service.
        // Instead, we manually handle the "continuous agent" resurrection via AlarmManager in
        // onTaskRemoved, which allows us to respect Pause Mode (i.e. we don't schedule the alarm
        // if the user paused the agent before swiping it away).
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (_isLitePaused.value || _isHardPaused.value) {
            Log.i(TAG, "Task removed while PAUSED — shutting down completely and not auto-restarting")
            
            // Fully shut down everything since the user swiped away while paused
            audioRecorder.stopRecording()
            audioPlayer.stop()
            geminiLiveManager.disconnect()
            wakeLock?.let { if (it.isHeld) it.release() }
            
            com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
            OverlayService.stop(this)
            stopSelf()
        } else {
            Log.i(TAG, "Task removed (swiped from recents) — scheduling AlarmManager to restart service")
            val restartIntent = Intent(applicationContext, com.soltini.app.BootReceiver::class.java).apply {
                action = "com.soltini.app.RESTART_SERVICE"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            // Fire 1 second from now to allow the OS to finish killing the process first
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        }
        
        super.onTaskRemoved(rootIntent)
    }

    private fun startZeroBatterySilentAudioTrick() {
        try {
            // Generate a tiny 1-second silent WAV file
            val silentFile = File(cacheDir, "zero_battery_silence.wav")
            if (!silentFile.exists()) {
                val sampleRate = 8000
                val channels = 1
                val bitsPerSample = 16
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val totalDataLen = sampleRate * 2 // 1 second of audio
                val totalAudioLen = totalDataLen
                val totalDataLenWithHeader = totalDataLen + 36

                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(totalDataLenWithHeader)
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16) // Subchunk1Size
                header.putShort(1.toShort()) // AudioFormat (PCM)
                header.putShort(channels.toShort())
                header.putInt(sampleRate)
                header.putInt(byteRate)
                header.putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
                header.putShort(bitsPerSample.toShort())
                header.put("data".toByteArray())
                header.putInt(totalAudioLen)

                FileOutputStream(silentFile).use { fos ->
                    fos.write(header.array())
                    fos.write(ByteArray(totalAudioLen)) // zeroes
                }
            }

            silentMediaPlayer = MediaPlayer().apply {
                setDataSource(silentFile.absolutePath)
                setVolume(0f, 0f)
                isLooping = true
                prepare()
                start()
            }
            Log.i(TAG, "Zero-battery silent audio trick activated.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start silent media player: ${e.message}")
        }
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
        if (isPaused.value) {
            Log.i(TAG, "connect() ignored because service is paused.")
            return
        }
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
        audioRecorder.isDataSaverEnabled = appSettings.isDataSaverEnabled
        audioRecorder.vadThreshold = appSettings.vadThreshold
        audioRecorder.micGain = appSettings.micGain
        audioRecorder.isScreenRecordingModeEnabled = appSettings.isScreenRecordingModeEnabled
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
        setMute(newMute)
    }

    fun setMute(muted: Boolean) {
        _isMuted.value = muted
        audioRecorder.isMuted = muted
    }

    fun clearTranscript() {
        _transcripts.value = emptyList()
        currentGeminiTranscript.clear()
    }

    /**
     * Injects a text message into the live Gemini session as if the user spoke it.
     * Used by quick macro buttons to send preset prompts without voice input.
     */
    fun sendTextMessage(text: String) {
        if (connectionState.value != ConnectionState.Live) {
            // Auto-connect first, then send after a brief delay
            connect()
            serviceScope.launch {
                kotlinx.coroutines.delay(2500)
                if (connectionState.value == ConnectionState.Live) {
                    geminiLiveManager.sendClientContent(text)
                    addTranscript(TranscriptEntry(sender = TranscriptEntry.Sender.USER, text = text))
                }
            }
        } else {
            geminiLiveManager.sendClientContent(text)
            addTranscript(TranscriptEntry(sender = TranscriptEntry.Sender.USER, text = text))
        }
    }

    private fun addTranscript(entry: TranscriptEntry) {
        _transcripts.value = (_transcripts.value + entry).takeLast(100)
    }

    // ── Lite Pause (default) ─────────────────────────────────────────────────
    /**
     * Lite Pause Mode:
     *   • Stops mic + WebSocket (0 data while paused)
     *   • Sets isPausedLite = true on the accessibility service, making it
     *     completely silent: onAccessibilityEvent returns immediately,
     *     no window reads — functionally invisible to most banking apps
     *     (GPay, PhonePe, Paytm, most bank apps check for active monitoring,
     *     not just whether the service is listed).
     *   • Does NOT call disableSelf() — so NO re-enable is needed on resume.
     *   • Resume is instant: mic + WebSocket reconnect automatically.
     */
    fun enterLitePauseMode() {
        if (_isLitePaused.value || _isHardPaused.value) return
        Log.i(TAG, "Entering Lite Pause — silencing accessibility without disabling")
        _isLitePaused.value = true
        _isSleeping.value = true

        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }

        // Silence the service without disabling it
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isPausedLite = true
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false

        // Completely hide the orb overlay while paused
        OverlayService.getInstance()?.setHiddenForBanking(true)
        updateNotification("⏸ Lite Pause — tap to resume")
    }

    fun exitLitePauseMode() {
        if (!_isLitePaused.value) return
        Log.i(TAG, "Exiting Lite Pause")
        _isLitePaused.value = false
        _isSleeping.value = false

        // Un-silence the accessibility service
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isPausedLite = false

        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
        // Show the orb overlay again
        OverlayService.getInstance()?.setHiddenForBanking(false)
        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.IDLE)
        updateNotification("Your AI Assistant is ready")
        connect()
    }

    fun toggleLitePauseMode() {
        if (_isLitePaused.value) exitLitePauseMode() else enterLitePauseMode()
    }

    // ── Hard Pause (strict banks) ─────────────────────────────────────────────
    /**
     * Hard Pause Mode:
     *   • Stops mic + WebSocket
     *   • Calls disableSelf() — removes Soltini from Android's enabled-services
     *     list entirely. Even the most strict banking apps (HDFC, ICICI) will
     *     not find it.
     *   • On resume, automatically opens the accessibility settings screen so the
     *     user only needs ONE tap to re-enable (instead of hunting through menus).
     */
    fun enterHardPauseMode() {
        if (_isHardPaused.value || _isLitePaused.value) return
        Log.i(TAG, "Entering Hard Pause (Force Stop) — opening App Info to self-terminate")
        _isHardPaused.value = true
        _isSleeping.value = true

        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }

        // Tell the accessibility service to look for and click the Force Stop button
        val a11yService = com.soltini.app.agent.SoltiniAccessibilityService.getInstance()
        if (a11yService != null) {
            a11yService.isAutomatingForceStop = true

            // Launch the App Info settings screen for this app
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } else {
            // Fallback if accessibility is dead
            stopSelf()
        }

        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.SLEEPING)
        updateNotification("🔴 Force Stopped — open app to resume")
    }

    fun exitHardPauseMode() {
        if (!_isHardPaused.value) return
        Log.i(TAG, "Exiting Hard Pause (Force Stop mode)")
        _isHardPaused.value = false
        _isSleeping.value = false

        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
        OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.IDLE)
        updateNotification("Your AI Assistant is ready")

        // Accessibility is automatically rebound when the app is manually launched after Force Stop
        connect()
    }

    fun toggleHardPauseMode() {
        if (_isHardPaused.value) exitHardPauseMode() else enterHardPauseMode()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open accessibility settings: ${e.message}")
        }
    }

    // Legacy compat — togglePauseMode now triggers Lite Pause by default
    fun enterPauseMode() = enterLitePauseMode()
    fun exitPauseMode() = if (_isLitePaused.value) exitLitePauseMode() else exitHardPauseMode()
    fun togglePauseMode() = toggleLitePauseMode()

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
        Log.i(TAG, "Tool call received: $toolName (callId=$callId) | args: $args")

        if (toolName == "sleep_agent") {
            sleep()
            return
        }

        if (toolName == "trigger_automation") {
            val request = args.optString("request", "")
            Log.i(TAG, "Triggering background automation agent for request: \"$request\"")

            // 1. Immediately acknowledge to Live Voice model so it says "I'm on it!" without delay
            geminiLiveManager.sendToolResponse(
                callId,
                toolName,
                JSONObject().apply {
                    put("status", "in_progress")
                    put("message", "Automation task triggered for: \"$request\". Tell the user you are working on it now (e.g. \"I'm on it!\" or \"Working on that now!\"). Do NOT say it is already finished yet.")
                }
            )

            // 2. Execute background agent loop asynchronously
            serviceScope.launch(Dispatchers.IO) {
                val count = activeToolCallCount.incrementAndGet()
                if (count == 1) {
                    com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = true
                }
                try {
                    val summary = backgroundAgentRunner.execute(request)
                    Log.i(TAG, "Background automation finished with summary: $summary")
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Background automation completed for request: \"$request\". Outcome: $summary. Confirm execution to the user now in a brief, friendly way!]"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Background automation failed: ${e.message}", e)
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Background automation failed for request: \"$request\" with error: ${e.message}. Briefly inform the user.]"
                    )
                } finally {
                    val remaining = activeToolCallCount.decrementAndGet()
                    if (remaining <= 0) {
                        activeToolCallCount.set(0)
                        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
                    }
                }
            }
            return
        }

        // Direct device tools (open_app, lock_device, wake_device, press_back, press_home, press_recents)
        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Executing direct tool: $toolName (callId=$callId)")

            val count = activeToolCallCount.incrementAndGet()
            if (count == 1) {
                com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = true
            }

            try {
                val result = agentToolExecutor.execute(toolName, args)
                geminiLiveManager.sendToolResponse(callId, toolName, result)
            } finally {
                val remaining = activeToolCallCount.decrementAndGet()
                if (remaining <= 0) {
                    activeToolCallCount.set(0)
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
            // Show AI response as floating bubble on the overlay orb (Maya-style)
            OverlayService.getInstance()?.showResponseBubble(finishedText)

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

        try {
            silentMediaPlayer?.stop()
            silentMediaPlayer?.release()
            silentMediaPlayer = null
        } catch (e: Exception) { /* ignore */ }

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        audioRecorder.stopRecording()
        audioPlayer.stop()
        geminiLiveManager.disconnect()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Soltini")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundService() {
        val notification = buildNotification("Your AI Assistant is ready")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (hasMicPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= 34) {
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
