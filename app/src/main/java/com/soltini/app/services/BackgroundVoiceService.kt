package com.soltini.app.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
import com.soltini.app.core.SoltiniDispatchers
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
import kotlinx.coroutines.withTimeoutOrNull
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

        const val ACTION_PAUSE_APP = "com.soltini.app.ACTION_PAUSE_APP"
        const val ACTION_RESUME_APP = "com.soltini.app.ACTION_RESUME_APP"

        @Volatile
        private var instance: BackgroundVoiceService? = null

        fun getInstance(): BackgroundVoiceService? = instance
        fun isActive(): Boolean = instance != null
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    // SupervisorJob: one failing coroutine does NOT cancel all sibling coroutines
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── User-configurable AI settings ────────────────────────────────────────
    lateinit var appSettings: AppSettings
        private set

    // ── Master App Pause ─────────────────────────────────────────────────────
    /**
     * App Master Pause — user explicitly paused the entire app.
     * When true: zero background mic, zero WebSocket, no floating overlay, no auto-resurrection.
     */
    private val _isAppPaused = MutableStateFlow(false)
    val isAppPaused: StateFlow<Boolean> = _isAppPaused.asStateFlow()

    // ── Persistent vector memory ─────────────────────────────────────────────
    lateinit var memoryManager: MemoryManager
        private set

    /**
     * Counts how many tool calls are currently in-flight.
     * Accessibility high-friction tracking is enabled while this is > 0
     * and disabled when it drops back to 0, preventing stale tracking state.
     */
    private val activeToolCallCount = AtomicInteger(0)

    private val audioPlayer by lazy { AudioPlayer(this) }
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

    @Volatile
    private var isAppInForeground = false

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            _hasMicPermission.value = hasMic
            if (hasMic) {
                promoteToMicrophoneFgs()
                if (::audioRecorder.isInitialized && connectionState.value == ConnectionState.Live && !audioRecorder.isRecordingActive) {
                    audioRecorder.startRecording()
                }
            }
            if (!appSettings.isAppPaused && !_isLitePaused.value && !_isHardPaused.value) {
                if (connectionState.value == ConnectionState.Disconnected || connectionState.value == ConnectionState.Error) {
                    connect()
                }
            }
        }
    }

    fun promoteToMicrophoneFgs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasMicPermission) {
                    val notification = buildNotification("Your AI Assistant is active")
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    Log.d(TAG, "Promoted FGS to MICROPHONE type")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to promote FGS to MICROPHONE type: ${e.message}")
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundVoiceService = this@BackgroundVoiceService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "BackgroundVoiceService created")

        // ── User-configurable settings + memory FIRST ───────────────────────────
        appSettings = AppSettings(this)
        _isAppPaused.value = appSettings.isAppPaused
        val initialMic = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        _hasMicPermission.value = initialMic
        Log.i(TAG, "Initial microphone permission granted=$initialMic, isAppPaused=${appSettings.isAppPaused}")
        memoryManager = MemoryManager(MemoryDatabase(this), appSettings)

        // Start hardware-offloaded silent audio to bypass MIUI's "Clear All" with 0% battery drain
        startZeroBatterySilentAudioTrick()

        // ── Call startForeground immediately ─────────────────────────────────────
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

        wakeWordEngine = com.soltini.app.audio.WakeWordEngine(this).apply {
            initialize()
            onWakeWordDetected = { word ->
                if (!appSettings.isAppPaused) {
                    Log.i(TAG, "Wake word detected: $word — activating assistant")
                    if (_isSleeping.value) {
                        wakeUp()
                    }
                    if (connectionState.value == ConnectionState.Disconnected) {
                        connect()
                    }
                    OverlayService.getInstance()?.setRingState(com.soltini.app.overlay.RingState.LISTENING)
                }
            }
            if (appSettings.isWakeWordEnabled && !appSettings.isAppPaused) {
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
                if (appSettings.isWakeWordEnabled && _isSleeping.value) {
                    wakeWordEngine.processAudioFrame(pcmChunk, pcmChunk.size)
                }
                if (!_isSleeping.value && !_isMuted.value) {
                    geminiLiveManager.sendAudioChunk(pcmChunk)
                }
            },
            onSpeechStateChanged = { isSpeech ->
                if (isSpeech) {
                    lastSpeechTimestamp = System.currentTimeMillis()
                }
            }
        ).apply {
            isDataSaverEnabled = appSettings.isDataSaverEnabled
            vadThreshold = appSettings.vadThreshold
            micGain = appSettings.micGain
            isScreenRecordingModeEnabled = appSettings.isScreenRecordingModeEnabled
            audioSourcePreference = appSettings.audioSource
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
                        try {
                            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            // Switch to MODE_IN_COMMUNICATION with speakerphone enabled so Android
                            // hardware AEC (Acoustic Echo Cancellation) & NS (Noise Suppression) are active
                            am.mode = AudioManager.MODE_IN_COMMUNICATION
                            am.isSpeakerphoneOn = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val speakerDevice = am.availableCommunicationDevices.firstOrNull {
                                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                                }
                                if (speakerDevice != null) {
                                    am.setCommunicationDevice(speakerDevice)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to configure audio mode: ${e.message}")
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
                            am.isSpeakerphoneOn = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                am.clearCommunicationDevice()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to restore audio routing: ${e.message}")
                        }

                        // Keep mic alive if we're sleeping with wake word enabled —
                        // stopRecording() here would kill the wake-word listener.
                        if (!(_isSleeping.value && appSettings.isWakeWordEnabled)) {
                            audioRecorder.stopRecording()
                        }
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
        if (!appSettings.isAppPaused && androidx.core.content.ContextCompat.checkSelfPermission(
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
        when (intent?.action) {
            ACTION_PAUSE_APP -> {
                pauseApp()
                return START_NOT_STICKY
            }
            ACTION_RESUME_APP -> {
                resumeApp()
                return START_STICKY
            }
        }

        val isPaused = if (::appSettings.isInitialized) appSettings.isAppPaused else false
        if (isPaused) {
            Log.i(TAG, "onStartCommand: Soltini is Master PAUSED — ensuring pause state and standing down")
            pauseApp()
            return START_NOT_STICKY
        }

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
        } else {
            val isStickyRestart = intent == null
            val isBootOrResurrect = isStickyRestart ||
                    intent?.getBooleanExtra(BootReceiver.EXTRA_FROM_BOOT_OR_RESURRECT, false) == true ||
                    intent?.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                    intent?.action == "com.soltini.app.RESTART_SERVICE"

            if (isBootOrResurrect) {
                Log.i(TAG, "Service started in background standby via boot/resurrection/sticky (isSticky=$isStickyRestart)")
                // Go to sleep mode, but sleep() will preserve the mic if wake word is enabled
                if (!_isSleeping.value) {
                    sleep()
                } else if (appSettings.isWakeWordEnabled && !audioRecorder.isRecordingActive && _hasMicPermission.value) {
                    // Already sleeping but mic was somehow stopped — restart it for wake word detection
                    audioRecorder.startRecording()
                }

                // Ensure overlay is also up if allowed and not already running
                if (android.provider.Settings.canDrawOverlays(this) &&
                    OverlayService.getInstance() == null &&
                    appSettings.isAutoStartEnabled) {
                    try {
                        OverlayService.start(this)
                        Log.i(TAG, "OverlayService started from BackgroundVoiceService resurrection")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not start OverlayService on resurrect: ${e.message}")
                    }
                }
            } else if (_isSleeping.value) {
                wakeUp()
            }
        }
        
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val isPaused = if (::appSettings.isInitialized) appSettings.isAppPaused else false
        if (isPaused) {
            Log.i(TAG, "onTaskRemoved: App is Master PAUSED — skipping background resurrection")
            return
        }
        Log.i(TAG, "onTaskRemoved: Swiped from recents — scheduling background resurrection")
        scheduleServiceResurrection()
    }

    private fun scheduleServiceResurrection() {
        val isPaused = if (::appSettings.isInitialized) appSettings.isAppPaused else false
        if (isPaused) {
            Log.d(TAG, "Service resurrection skipped because Master Pause is active")
            return
        }
        if (_isHardPaused.value) {
            Log.d(TAG, "Service resurrection skipped because hard pause is active")
            return
        }
        val isAutoStart = if (::appSettings.isInitialized) appSettings.isAutoStartEnabled else true
        if (!isAutoStart) {
            Log.d(TAG, "Service resurrection skipped because AutoStart is disabled in settings")
            return
        }
        try {
            val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
                action = "com.soltini.app.RESTART_SERVICE"
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                1002,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val triggerAt = System.currentTimeMillis() + 1000L

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    val showIntent = PendingIntent.getActivity(
                        applicationContext,
                        0,
                        Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    // setAlarmClock grants Android 12+ (API 31+) background foreground service start exemption
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
                    alarmManager?.setAlarmClock(alarmClockInfo, pendingIntent)
                    Log.i(TAG, "Resurrection alarm scheduled via setAlarmClock (exempt from Android 12+ FGS restrictions)")
                    return
                } catch (e: Throwable) {
                    Log.w(TAG, "setAlarmClock failed: ${e.message}, falling back to setExactAndAllowWhileIdle")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager?.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.i(TAG, "Resurrection alarm scheduled in 1000ms via BootReceiver")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to schedule resurrection alarm: ${e.message}")
        }
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
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_UNKNOWN)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build()
                )
                setVolume(0f, 0f)
                isLooping = true
                setOnErrorListener { _, _, _ -> true }
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
            if (::audioRecorder.isInitialized && connectionState.value == ConnectionState.Live && !audioRecorder.isRecordingActive) {
                audioRecorder.startRecording()
            }
        }
    }

    fun connect() {
        if (appSettings.isAppPaused) {
            Log.i(TAG, "connect() called while appSettings.isAppPaused; clearing pause state.")
            appSettings.isAppPaused = false
            _isAppPaused.value = false
        }
        if (_isLitePaused.value || _isHardPaused.value) {
            Log.i(TAG, "connect() called while service is paused; clearing pause states.")
            _isLitePaused.value = false
            _isHardPaused.value = false
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

    // ── Master App Pause ─────────────────────────────────────────────────────
    /**
     * Master App Pause:
     *   - Persistently sets isAppPaused = true in AppSettings.
     *   - Completely shuts down microphone recording (AudioRecord freed).
     *   - Stops audio playback and cancels active WakeLock.
     *   - Fully disconnects Gemini Live WebSocket.
     *   - Stops WakeWordEngine.
     *   - Stops and dismisses OverlayService (no floating orb or avatar).
     *   - Cancels any scheduled background resurrection alarms.
     *   - Silences AccessibilityService.
     *   - Updates notification to indicate paused status.
     */
    fun pauseApp() {
        Log.i(TAG, "Master App Pause activated — completely pausing Soltini")
        if (::appSettings.isInitialized) {
            appSettings.isAppPaused = true
        }
        _isAppPaused.value = true
        _isSleeping.value = true

        try {
            if (::audioRecorder.isInitialized) audioRecorder.stopRecording()
            audioPlayer.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio on pause: ${e.message}")
        }

        try {
            if (::geminiLiveManager.isInitialized) geminiLiveManager.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting Gemini on pause: ${e.message}")
        }

        try {
            if (::wakeWordEngine.isInitialized) wakeWordEngine.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping wake word on pause: ${e.message}")
        }

        wakeLock?.let { if (it.isHeld) it.release() }

        // Cancel any pending resurrection alarms
        try {
            val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
                action = "com.soltini.app.RESTART_SERVICE"
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                1002,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.cancel(pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling resurrection alarm: ${e.message}")
        }

        // Stop OverlayService completely
        try {
            OverlayService.stop(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping overlay on pause: ${e.message}")
        }

        // Put accessibility into silent dormancy — zero interaction with device,
        // without calling disableSelf() so permission is preserved and never needs re-granting
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isPausedLite = true
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false

        updateNotification("⏸ Soltini is Paused — Tap to open and resume")
    }

    fun resumeApp() {
        Log.i(TAG, "Master App Resumed — restoring normal assistant operations")
        appSettings.isAppPaused = false
        _isAppPaused.value = false
        _isSleeping.value = false
        _isLitePaused.value = false
        _isHardPaused.value = false

        // Instantly reactivate accessibility — no Android Settings re-grant needed
        com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isPausedLite = false

        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)

        if (appSettings.isWakeWordEnabled) {
            try {
                wakeWordEngine.start()
            } catch (e: Exception) {
                Log.w(TAG, "Error restarting wake word on resume: ${e.message}")
            }
        }

        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                OverlayService.start(this)
            } catch (e: Exception) {
                Log.w(TAG, "Error starting overlay on resume: ${e.message}")
            }
        }

        updateNotification("Your AI Assistant is ready")
        connect()
    }

    fun toggleAppPause() {
        if (_isAppPaused.value || appSettings.isAppPaused) {
            resumeApp()
        } else {
            pauseApp()
        }
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

        // Keep mic alive if wake word detection is enabled so WakeWordEngine
        // can still hear the user say "Hey Soltini" / "Hey Maya" in standby.
        // The audioRecorder callback already guards: when _isSleeping==true it only
        // feeds wakeWordEngine.processAudioFrame() and does NOT send to Gemini.
        if (!appSettings.isWakeWordEnabled) {
            audioRecorder.stopRecording()
        }

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
        _isMuted.value = false
        audioRecorder.isMuted = false

        // Reacquire WakeLock so the CPU stays on during voice session
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }

        // Start microphone IMMEDIATELY — do not wait for WebSocket Live state.
        // GeminiLiveManager buffers audio chunks in pendingChunks until setupComplete,
        // so every word the user speaks during the ~1-3s connection handshake is captured.
        if (_hasMicPermission.value && !audioRecorder.isRecordingActive) {
            audioRecorder.startRecording()
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

        if (toolName == "sleep_agent" || (toolName == "delegate_skill" && (args.optString("skill_name").contains("sleep") || args.optString("action").contains("sleep")))) {
            geminiLiveManager.sendToolResponse(
                callId,
                toolName,
                JSONObject().apply {
                    put("status", "success")
                    put("message", "Going to sleep now. Call me anytime by saying Hey Soltini.")
                }
            )
            sleep()
            return
        }

        val actionName = when {
            toolName == "delegate_skill" -> {
                val act = args.optString("action").ifBlank { args.optString("skill_name", "action") }
                val det = args.optString("details")
                if (det.isNotBlank()) "$act ($det)" else act
            }
            toolName == "trigger_automation" -> args.optString("details").ifBlank { args.optString("request", "automation task") }
            else -> toolName
        }

        // Phase 1: Immediately acknowledge tool call with in_progress so Gemini Live gives
        // an instant (<300ms) ultra-low-latency voice acknowledgement ("On it!", "Sure thing!").
        geminiLiveManager.sendToolResponse(
            callId,
            toolName,
            JSONObject().apply {
                put("status", "in_progress")
                put("message", "Action '$actionName' is in progress. Immediately acknowledge briefly to the user.")
            }
        )

        // Phase 2: Execute tool in background and verbally speak the outcome (success or error details)
        serviceScope.launch(SoltiniDispatchers.AgentWorkerDispatcher) {
            val count = activeToolCallCount.incrementAndGet()
            if (count == 1) {
                com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = true
            }

            try {
                val result = if (toolName == "trigger_automation" || (toolName == "delegate_skill" && (args.optString("skill_name").lowercase() == "automation" || args.optString("action").lowercase() == "trigger_automation"))) {
                    val req = args.optString("details").ifBlank { args.optString("request", "automation task") }
                    val summary = backgroundAgentRunner.execute(req)
                    JSONObject().apply {
                        put("status", "success")
                        put("message", summary)
                    }
                } else if (toolName == "delegate_skill") {
                    executeDelegatedSkill(args)
                } else {
                    agentToolExecutor.execute(toolName, args)
                }

                // If Gemini is still speaking its initial acknowledgement, wait briefly for it to finish
                // so the completion speech doesn't collide or cut off the initial words.
                val waitStart = System.currentTimeMillis()
                while (audioPlayer.isSpeaking.value && (System.currentTimeMillis() - waitStart < 2500)) {
                    kotlinx.coroutines.delay(100)
                }
                kotlinx.coroutines.delay(100)

                val status = result.optString("status", if (result.has("error")) "error" else "success")
                val message = result.optString("message", result.optString("error", "Completed"))
                val error = result.optString("error", "")

                if (status == "success" && error.isBlank()) {
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Action '$actionName' succeeded. Outcome: $message. Speak to the user confirming what was done.]"
                    )
                } else {
                    val failReason = if (error.isNotBlank()) error else message
                    geminiLiveManager.sendClientContent(
                        "[SYSTEM NOTIFICATION: Action '$actionName' failed. Error: $failReason. Message: $message. Speak to the user clearly explaining what failed, why it failed, and what is needed to fix it.]"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution exception for $toolName: ${e.message}", e)
                geminiLiveManager.sendClientContent(
                    "[SYSTEM NOTIFICATION: Action '$actionName' failed with exception: ${e.message ?: "Unknown error"}. Inform the user what failed and why.]"
                )
            } finally {
                val remaining = activeToolCallCount.decrementAndGet()
                if (remaining <= 0) {
                    activeToolCallCount.set(0)
                    com.soltini.app.agent.SoltiniAccessibilityService.getInstance()?.isToolCallActive = false
                }
            }
        }
    }

    private suspend fun executeDelegatedSkill(args: JSONObject): JSONObject {
        val skillName = args.optString("skill_name").ifBlank { args.optString("skill", "") }.lowercase().trim()
        val action = args.optString("action", "").lowercase().trim()
        val details = args.optString("details").ifBlank { args.optString("detail", "") }.trim()
        val full = "$skillName $action ${details.lowercase()}".trim()

        Log.i(TAG, "executeDelegatedSkill: skill=$skillName, action=$action, details=$details (full='$full')")

        return when {
            // ── 1. Sleep / Standby ───────────────────────────────────────────
            full.contains("sleep") -> {
                serviceScope.launch(Dispatchers.Main) { sleep() }
                JSONObject().apply {
                    put("status", "success")
                    put("message", "Going to sleep now.")
                }
            }

            // ── 2. Flashlight / Torch ────────────────────────────────────────
            full.contains("flashlight") || full.contains("torch") || full.contains("flash_light") ||
                action in listOf("toggle_flashlight", "flashlight", "torch") ||
                (full.contains("flash") && !full.contains("camera")) -> {
                val turnOff = full.contains("off") || full.contains("disable") || full.contains("stop") || full.contains("false")
                val isToggle = action.contains("toggle") || details.contains("toggle", ignoreCase = true)
                val targetOn = if (isToggle && !full.contains("on") && !full.contains("off")) null else !turnOff
                agentToolExecutor.execute("toggle_flashlight", JSONObject().apply {
                    if (targetOn != null) put("on", targetOn)
                })
            }

            // ── 3. Volume & Sound ────────────────────────────────────────────
            full.contains("volume") || full.contains("sound") || full.contains("loudness") ||
                action in listOf("set_volume", "volume_up", "volume_down", "mute", "unmute") ||
                details.contains("volume", ignoreCase = true) -> {
                val subAction = when {
                    full.contains("mute") && !full.contains("unmute") -> "mute"
                    full.contains("unmute") -> "unmute"
                    action == "volume_up" || full.contains("up") || full.contains("increase") || full.contains("raise") -> "up"
                    action == "volume_down" || full.contains("down") || full.contains("decrease") || full.contains("lower") -> "down"
                    else -> null
                }
                val digits = details.filter { it.isDigit() }.toIntOrNull()
                agentToolExecutor.execute("set_volume", JSONObject().apply {
                    if (digits != null) put("level", digits)
                    if (subAction != null) put("action", subAction)
                })
            }

            // ── 4. Brightness ────────────────────────────────────────────────
            full.contains("brightness") || full.contains("screen light") || action in listOf("set_brightness", "brightness_up", "brightness_down") -> {
                val subAction = when {
                    full.contains("max") || full.contains("full") -> "max"
                    full.contains("min") || full.contains("lowest") -> "min"
                    action == "brightness_up" || full.contains("up") || full.contains("increase") || full.contains("brighten") -> "up"
                    action == "brightness_down" || full.contains("down") || full.contains("decrease") || full.contains("dim") -> "down"
                    else -> null
                }
                val digits = details.filter { it.isDigit() }.toIntOrNull()
                agentToolExecutor.execute("set_brightness", JSONObject().apply {
                    if (digits != null) put("level", digits)
                    if (subAction != null) put("action", subAction)
                })
            }

            // ── 5. Wi-Fi ─────────────────────────────────────────────────────
            full.contains("wifi") || full.contains("wi-fi") || full.contains("wlan") -> {
                val turnOff = full.contains("off") || full.contains("disable") || full.contains("disconnect")
                agentToolExecutor.execute("toggle_wifi", JSONObject().apply {
                    put("on", !turnOff)
                })
            }

            // ── 6. Bluetooth ─────────────────────────────────────────────────
            full.contains("bluetooth") || action in listOf("bluetooth", "toggle_bluetooth", "bt") || full.contains(" bt ") -> {
                val turnOff = full.contains("off") || full.contains("disable") || full.contains("disconnect")
                agentToolExecutor.execute("toggle_bluetooth", JSONObject().apply {
                    put("on", !turnOff)
                })
            }

            // ── 7. Screen Lock / Wake / Unlock ──────────────────────────────
            action in listOf("lock", "lock_device", "lock_screen") || full.contains("lock screen") || full.contains("lock phone") || full.contains("lock device") -> {
                agentToolExecutor.execute("lock_device", JSONObject())
            }
            action in listOf("wake", "wake_device", "wake_screen") || full.contains("wake screen") || full.contains("wake phone") || full.contains("wake device") -> {
                agentToolExecutor.execute("wake_device", JSONObject())
            }
            action in listOf("unlock", "unlock_and_wake") || full.contains("unlock phone") || full.contains("unlock device") -> {
                agentToolExecutor.execute("unlock_and_wake", JSONObject())
            }

            // ── 8. Navigation Buttons ────────────────────────────────────────
            action in listOf("back", "press_back", "go_back") || full.contains("go back") || full.contains("press back") -> {
                agentToolExecutor.execute("press_back", JSONObject())
            }
            action in listOf("home", "press_home", "go_home") || full.contains("go home") || full.contains("press home") -> {
                agentToolExecutor.execute("press_home", JSONObject())
            }
            action in listOf("recents", "press_recents", "recent_apps", "app_switch") || full.contains("recents") || full.contains("switch app") -> {
                agentToolExecutor.execute("press_recents", JSONObject())
            }

            // ── 9. Screen Perception & Vision ────────────────────────────────
            skillName.contains("screen") || skillName.contains("vision") || action.contains("screen") || action.contains("vision") ||
                action.contains("screenshot") || full.contains("see screen") || full.contains("read screen") || full.contains("take screenshot") -> {
                when {
                    action.contains("compact") || full.contains("compact") ->
                        agentToolExecutor.execute("read_compact_screen", JSONObject())
                    action.contains("read") || full.contains("read screen") ->
                        agentToolExecutor.execute("read_screen", JSONObject().apply { put("screenshot", false) })
                    action.contains("screenshot") || full.contains("screenshot") ->
                        agentToolExecutor.execute("take_screenshot", JSONObject())
                    else ->
                        agentToolExecutor.execute("see_screen", JSONObject().apply { put("screenshot", true) })
                }
            }

            // ── 10. UI Actions (Click, Tap, Type, Scroll, Swipe) ─────────────
            action in listOf("click", "tap", "click_element") -> {
                val target = details.ifBlank { action.substringAfter("click").substringAfter("tap").trim() }
                agentToolExecutor.execute("click_element", JSONObject().apply { put("text", target) })
            }
            action == "tap_screen" -> {
                agentToolExecutor.execute("tap_screen", args)
            }
            action in listOf("type", "type_text") -> {
                agentToolExecutor.execute("type_text", JSONObject().apply { put("text", details) })
            }
            action in listOf("clear", "clear_text") -> {
                agentToolExecutor.execute("clear_text", JSONObject())
            }
            action in listOf("scroll_down", "scroll_up") -> {
                agentToolExecutor.execute(action, JSONObject())
            }
            action in listOf("swipe", "swipe_screen") -> {
                agentToolExecutor.execute("swipe_screen", args)
            }

            // ── 11. Phone Calls ──────────────────────────────────────────────
            skillName.contains("phone") || skillName.contains("call") || action in listOf("call", "make_phone_call", "dial", "answer_call", "decline_call") -> {
                when {
                    action in listOf("answer", "answer_call") || full.contains("answer call") ->
                        agentToolExecutor.execute("answer_call", JSONObject())
                    action in listOf("decline", "decline_call", "reject") || full.contains("decline call") || full.contains("reject call") ->
                        agentToolExecutor.execute("decline_call", JSONObject())
                    else -> {
                        val target = details.ifBlank { action.substringAfter("call").trim() }
                        agentToolExecutor.execute("make_phone_call", JSONObject().apply {
                            put("phone_number", target)
                            put("contact_name", target)
                        })
                    }
                }
            }

            // ── 12. Contacts & Call Log ──────────────────────────────────────
            action in listOf("read_contacts", "contacts", "search_contact") || (details.contains("contact") && !full.contains("whatsapp")) -> {
                agentToolExecutor.execute("read_contacts", JSONObject().apply { put("query", details) })
            }
            action in listOf("read_call_log", "call_log", "recent_calls") || full.contains("call log") -> {
                val limit = details.filter { it.isDigit() }.toIntOrNull() ?: 5
                agentToolExecutor.execute("read_call_log", JSONObject().apply { put("limit", limit) })
            }

            // ── 13. Notifications & SMS ──────────────────────────────────────
            action in listOf("read_notifications", "notifications") || full.contains("read notification") -> {
                agentToolExecutor.execute("read_notifications", JSONObject().apply { put("package_name", details) })
            }
            action in listOf("send_sms", "sms", "text", "send_text") -> {
                val parts = details.split(":", limit = 2)
                val target = parts.firstOrNull()?.trim() ?: ""
                val msg = parts.getOrNull(1)?.trim() ?: ""
                agentToolExecutor.execute("send_sms", JSONObject().apply {
                    put("phone_number", target)
                    put("message", msg)
                })
            }

            // ── 14. Alarms, Reminders, Maps & Location ───────────────────────
            action in listOf("set_alarm", "alarm") || full.contains("set alarm") -> {
                val digits = details.filter { it.isDigit() }
                val hour = if (digits.length >= 3) digits.take(2).toIntOrNull() ?: 8 else digits.toIntOrNull() ?: 8
                val minute = if (digits.length >= 4) digits.substring(2, 4).toIntOrNull() ?: 0 else 0
                agentToolExecutor.execute("set_alarm", JSONObject().apply {
                    put("hour", hour)
                    put("minute", minute)
                    put("label", details)
                })
            }
            action in listOf("schedule_reminder", "reminder", "remind") || full.contains("remind me") -> {
                agentToolExecutor.execute("schedule_reminder", JSONObject().apply { put("message", details) })
            }
            action in listOf("get_location", "location", "gps") || full.contains("where am i") || full.contains("my location") -> {
                agentToolExecutor.execute("get_location", JSONObject())
            }
            action in listOf("maps", "open_google_maps", "navigate", "directions") || full.contains("google maps") -> {
                agentToolExecutor.execute("open_google_maps", JSONObject().apply { put("query", details) })
            }
            action in listOf("set_do_not_disturb", "dnd", "silent_mode") || full.contains("do not disturb") -> {
                agentToolExecutor.execute("set_do_not_disturb", JSONObject().apply { put("on", !full.contains("off")) })
            }

            // ── 15. YouTube ──────────────────────────────────────────────────
            skillName.contains("youtube") || action.startsWith("youtube_") -> {
                when (action) {
                    "search", "youtube_search" -> agentToolExecutor.execute("youtube_search", JSONObject().apply { put("query", details) })
                    "open", "open_video", "play_video", "youtube_open_video" -> agentToolExecutor.execute("youtube_open_video", JSONObject().apply { put("video_id", details) })
                    "play", "pause", "play_pause", "youtube_play_pause" -> agentToolExecutor.execute("youtube_play_pause", JSONObject())
                    "seek", "youtube_seek" -> agentToolExecutor.execute("youtube_seek", JSONObject().apply { put("seconds", details.filter { it.isDigit() }.toIntOrNull() ?: 10) })
                    "next", "youtube_next" -> agentToolExecutor.execute("youtube_next", JSONObject())
                    "previous", "prev", "youtube_previous" -> agentToolExecutor.execute("youtube_previous", JSONObject())
                    "like", "youtube_like" -> agentToolExecutor.execute("youtube_like", JSONObject())
                    "dislike", "youtube_dislike" -> agentToolExecutor.execute("youtube_dislike", JSONObject())
                    "subscribe", "youtube_subscribe" -> agentToolExecutor.execute("youtube_subscribe", JSONObject())
                    "skip_ad", "youtube_skip_ad" -> agentToolExecutor.execute("youtube_skip_ad", JSONObject())
                    "fullscreen", "youtube_fullscreen" -> agentToolExecutor.execute("youtube_fullscreen", JSONObject())
                    "captions", "youtube_captions" -> agentToolExecutor.execute("youtube_captions", JSONObject())
                    "mute", "youtube_mute" -> agentToolExecutor.execute("youtube_mute", JSONObject())
                    "read_screen", "read_youtube_screen" -> agentToolExecutor.execute("read_youtube_screen", JSONObject())
                    else -> agentToolExecutor.execute("youtube_search", JSONObject().apply { put("query", details.ifBlank { action }) })
                }
            }

            // ── 16. WhatsApp ─────────────────────────────────────────────────
            skillName.contains("whatsapp") || action.startsWith("whatsapp_") -> {
                when {
                    action.contains("call") -> {
                        val isVideo = action.contains("video") || details.contains("video", ignoreCase = true)
                        agentToolExecutor.execute("whatsapp_call", JSONObject().apply {
                            put("contact_name", details.replace("video", "", ignoreCase = true).trim())
                            put("is_video", isVideo)
                        })
                    }
                    else -> {
                        var contact = details
                        var message = ""
                        if (details.contains(":")) {
                            contact = details.substringBefore(":").trim()
                            message = details.substringAfter(":").trim()
                        }
                        agentToolExecutor.execute("whatsapp_send_message", JSONObject().apply {
                            put("contact_name", contact)
                            put("message", message)
                        })
                    }
                }
            }

            // ── 17. Reels & Shorts ───────────────────────────────────────────
            skillName.contains("reel") || skillName.contains("short") || action in listOf("next_reel", "previous_reel") -> {
                if (action.contains("prev") || full.contains("prev")) {
                    agentToolExecutor.execute("previous_reel", JSONObject())
                } else {
                    agentToolExecutor.execute("next_reel", JSONObject())
                }
            }

            // ── 18. Macros & Termux ──────────────────────────────────────────
            skillName.contains("macro") || action in listOf("run_macro", "get_available_macros") -> {
                if (action == "get_available_macros") {
                    agentToolExecutor.execute("get_available_macros", JSONObject())
                } else {
                    agentToolExecutor.execute("run_macro", JSONObject().apply { put("macro_name", details) })
                }
            }
            skillName.contains("termux") || action in listOf("run_termux_command", "termux", "terminal", "bash") -> {
                agentToolExecutor.execute("run_termux_command", JSONObject().apply { put("command", details) })
            }

            // ── 19. Open App ─────────────────────────────────────────────────
            skillName in listOf("open_app", "app") || action in listOf("open", "launch", "open_app", "launch_app", "start_app") -> {
                val target = details.ifBlank { action.substringAfter("open").substringAfter("launch").trim() }
                agentToolExecutor.execute("open_app", JSONObject().apply { put("app_name", target) })
            }

            // ── 20. Direct Tool Name Fallback ────────────────────────────────
            action in com.soltini.app.agent.AgentToolExecutor.SUPPORTED_TOOLS -> {
                agentToolExecutor.execute(action, args)
            }

            // ── 21. Open App as smart fallback if target is named ───────────
            details.isNotBlank() && (action.isBlank() || action in listOf("run", "start")) -> {
                agentToolExecutor.execute("open_app", JSONObject().apply { put("app_name", details) })
            }

            // ── 22. Autonomous Agent Runner Fallback ─────────────────────────
            else -> {
                try {
                    agentToolExecutor.execute(action, args)
                } catch (e: Exception) {
                    val fallbackReq = if (details.isNotBlank()) "$skillName $action $details" else "$skillName $action"
                    val summary = backgroundAgentRunner.execute(fallbackReq)
                    JSONObject().apply {
                        put("status", "success")
                        put("message", summary)
                    }
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
        audioPlayer.markTurnComplete()
        // Capture the finished transcript before clearing, then extract memories
        val finishedText = currentGeminiTranscript.toString().trim()
        currentGeminiTranscript.clear()
        if (finishedText.isNotBlank()) {
            // Show AI response as floating bubble on the overlay orb (Maya-style)
            OverlayService.getInstance()?.showResponseBubble(finishedText)

            val lastUserPrompt = _transcripts.value.lastOrNull { it.sender == TranscriptEntry.Sender.USER }?.text ?: ""
            val fullTurnExcerpt = if (lastUserPrompt.isNotBlank()) {
                "User: $lastUserPrompt\nSoltini: $finishedText"
            } else {
                finishedText
            }

            serviceScope.launch(SoltiniDispatchers.AgentWorkerDispatcher) {
                try {
                    memoryManager.extractAndSave(fullTurnExcerpt)
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
        instance = null
        super.onDestroy()

        try {
            silentMediaPlayer?.stop()
            silentMediaPlayer?.release()
            silentMediaPlayer = null
        } catch (e: Exception) { /* ignore */ }

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        try {
            if (::audioRecorder.isInitialized) audioRecorder.stopRecording()
        } catch (e: Exception) { /* ignore */ }
        try {
            audioPlayer.stop()
        } catch (e: Exception) { /* ignore */ }
        try {
            if (::geminiLiveManager.isInitialized) geminiLiveManager.disconnect()
        } catch (e: Exception) { /* ignore */ }
        try {
            if (::wakeWordEngine.isInitialized) wakeWordEngine.stop()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LUCY")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)

        val isPaused = if (::appSettings.isInitialized) {
            _isAppPaused.value || appSettings.isAppPaused
        } else {
            _isAppPaused.value
        }
        if (isPaused) {
            val resumeIntent = Intent(this, BackgroundVoiceService::class.java).apply {
                action = ACTION_RESUME_APP
            }
            val resumePendingIntent = PendingIntent.getService(
                this, 101, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "▶ Resume App", resumePendingIntent)
        } else {
            val pauseIntent = Intent(this, BackgroundVoiceService::class.java).apply {
                action = ACTION_PAUSE_APP
            }
            val pausePendingIntent = PendingIntent.getService(
                this, 102, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "⏸ Pause App", pausePendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundService() {
        val notification = buildNotification("Your AI Assistant is ready")

        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ (API 34+): Starting FGS with MICROPHONE type from background
            // causes SecurityException / ForegroundServiceStartNotAllowedException.
            // When starting from background/boot, start as SPECIAL_USE (defined in manifest with subtype ai_voice_assistant_background).
            // When the app comes to foreground or user interacts, promoteToMicrophoneFgs() elevates to MICROPHONE.
            var started = false
            if (isAppInForeground) {
                val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasMicPermission) {
                    try {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                        started = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Foreground start with MICROPHONE failed, will fallback to SPECIAL_USE: ${e.message}")
                    }
                }
            }
            if (!started) {
                try {
                    // FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x40000000 (1073741824)
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        1073741824
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground with SPECIAL_USE failed: ${e.message}")
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (fatal: Exception) {
                        Log.e(TAG, "Fatal startForeground error: ${fatal.message}", fatal)
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            try {
                if (hasMicPermission) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with MICROPHONE failed: ${e.message}")
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
