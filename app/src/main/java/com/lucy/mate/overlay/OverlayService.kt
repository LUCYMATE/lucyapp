package com.lucy.mate.overlay

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.lucy.mate.BootReceiver
import com.lucy.mate.MainActivity
import com.lucy.mate.R
import com.lucy.mate.bridge.AriaNativeBridge
import com.lucy.mate.engine.SoltiniGLRenderer
import com.lucy.mate.engine.SoltiniGLTextureView
import com.lucy.mate.engine.SoltiniLive2DEngine
import com.lucy.mate.settings.AppSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DraggableFrameLayout — intercepts touch events BEFORE they reach the WebView child.
 * This is the key fix: the WebView normally consumes all touch events, preventing the
 * parent FrameLayout's OnTouchListener from ever firing. By overriding
 * onInterceptTouchEvent we can steal drag gestures while still forwarding taps to the WebView.
 */
class DraggableFrameLayout(context: Context) : FrameLayout(context) {

    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private var initialDownX = 0f
    private var initialDownY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var lastTapTime = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        if (!isDragging) {
            onLongPress?.invoke()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialDownX = ev.rawX
                initialDownY = ev.rawY
                lastX = ev.rawX
                lastY = ev.rawY
                isDragging = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = abs(ev.rawX - initialDownX)
                val totalDy = abs(ev.rawY - initialDownY)
                if (totalDx > slop || totalDy > slop) {
                    isDragging = true
                    handler.removeCallbacks(longPressRunnable)
                    return true // Intercept touch events — steal gesture from child
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
            }
        }
        return isDragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialDownX = ev.rawX
                initialDownY = ev.rawY
                lastX = ev.rawX
                lastY = ev.rawY
                isDragging = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = abs(ev.rawX - initialDownX)
                val totalDy = abs(ev.rawY - initialDownY)
                if (totalDx > slop || totalDy > slop) {
                    if (!isDragging) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    val dx = ev.rawX - lastX
                    val dy = ev.rawY - lastY
                    lastX = ev.rawX
                    lastY = ev.rawY
                    onDrag?.invoke(dx, dy)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val totalDx = abs(ev.rawX - initialDownX)
                val totalDy = abs(ev.rawY - initialDownY)
                val isClick = !isDragging && (totalDx <= slop && totalDy <= slop)

                if (isClick) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < doubleTapTimeout) {
                        lastTapTime = 0L
                        android.util.Log.d("DraggableFrameLayout", "Touch gesture: DOUBLE TAP")
                        onDoubleTap?.invoke()
                    } else {
                        lastTapTime = now
                        android.util.Log.d("DraggableFrameLayout", "Touch gesture: SINGLE TAP")
                        onTap?.invoke()
                    }
                } else {
                    android.util.Log.d("DraggableFrameLayout", "Touch gesture: DRAG END")
                    onDragEnd?.invoke()
                }
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (isDragging) {
                    onDragEnd?.invoke()
                }
                isDragging = false
            }
        }
        return true
    }
}

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: DraggableFrameLayout
    private var webView: WebView? = null
    private var glView: SoltiniGLTextureView? = null
    @Volatile private var lastVrmAmpPostTime = 0L
    private lateinit var errorTextView: TextView
    private lateinit var ringIndicatorView: RingIndicatorView
    private var currentVisualizerMode: String = "ORB"
    private var currentCharacterName: String = "LUCY"
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridge = AriaNativeBridge(this)
    private val overlayLive2DEngine = SoltiniLive2DEngine(engineTag = "Overlay")
    private var voiceService: com.lucy.mate.services.BackgroundVoiceService? = null
    private var isBound = false

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(className: android.content.ComponentName, service: IBinder) {
            val binder = service as com.lucy.mate.services.BackgroundVoiceService.LocalBinder
            voiceService = binder.getService()
            isBound = true
            // OverlayService is often the first client to bind after a START_STICKY restart.
            // If BackgroundVoiceService is running but its WebSocket is still Disconnected,
            // trigger connect() here so voice is live before the user even opens the app.
            val svc = voiceService
            if (svc != null && !svc.isSleeping.value &&
                svc.connectionState.value == com.lucy.mate.network.ConnectionState.Disconnected) {
                svc.connect()
            }
        }
        override fun onServiceDisconnected(arg0: android.content.ComponentName) {
            isBound = false
            voiceService = null
        }
    }

    companion object {
        const val CHANNEL_ID = "AriaOverlayChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.lucy.mate.STOP_OVERLAY"
        const val ACTION_TOGGLE_BANKING_PAUSE = "com.lucy.mate.TOGGLE_BANKING_PAUSE"

        @SuppressLint("StaticFieldLeak")
        private var instance: OverlayService? = null

        fun getInstance(): OverlayService? = instance

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }
    }

    @Volatile
    private var isHiddenForBanking = false

    fun isHiddenForBanking(): Boolean = isHiddenForBanking

    fun setHiddenForBanking(hidden: Boolean) {
        if (isHiddenForBanking == hidden) return
        isHiddenForBanking = hidden
        android.util.Log.i("OverlayService", "setHiddenForBanking: $hidden")
        mainHandler.post {
            if (::overlayView.isInitialized && ::windowManager.isInitialized) {
                try {
                    overlayView.visibility = if (hidden) View.GONE else View.VISIBLE
                    if (hidden) {
                        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    windowManager.updateViewLayout(overlayView, layoutParams)
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Failed to update layout params for banking hide: ${e.message}")
                }
            }
        }
        updateNotification()
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Throwable) {
            android.util.Log.w("OverlayService", "Failed to update notification: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x40000000 (1073741824)
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    1073741824
                )
            } catch (e: Exception) {
                android.util.Log.w("OverlayService", "startForeground with SPECIAL_USE failed: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, createNotification())
                } catch (fatal: Exception) {
                    android.util.Log.e("OverlayService", "Fatal startForeground error: ${fatal.message}", fatal)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        Intent(this, com.lucy.mate.services.BackgroundVoiceService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        if (AppSettings(this).isAppPaused) {
            android.util.Log.i("OverlayService", "App is paused — skipping overlay setup and stopping")
            stopSelf()
            return
        }

        // Safety check: only add overlay if permission is granted
        if (Settings.canDrawOverlays(this)) {
            setupOverlay()
        } else {
            android.util.Log.e("OverlayService", "SYSTEM_ALERT_WINDOW permission not granted — overlay skipped")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppSettings(this).isAppPaused) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_BANKING_PAUSE -> {
                setHiddenForBanking(!isHiddenForBanking)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.i("OverlayService", "onTaskRemoved: task cleared from recents")
        val appSettings = AppSettings(this)
        if (appSettings.isAppPaused) {
            android.util.Log.i("OverlayService", "App is paused, skipping overlay resurrection")
            return
        }
        if (!appSettings.isAutoStartEnabled) {
            android.util.Log.i("OverlayService", "Auto-start disabled, skipping overlay resurrection")
            return
        }

        // Only schedule if voice service isn't alive or hasn't scheduled it
        if (com.lucy.mate.services.BackgroundVoiceService.getInstance() == null) {
            try {
                if (Settings.canDrawOverlays(this)) {
                    val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
                        action = "com.lucy.mate.RESTART_SERVICE"
                        setPackage(packageName)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        applicationContext,
                        1003,
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
                            // setAlarmClock grants Android 12+ FGS start exemption
                            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
                            alarmManager?.setAlarmClock(alarmClockInfo, pendingIntent)
                            android.util.Log.i("OverlayService", "Overlay resurrection scheduled via setAlarmClock")
                            return
                        } catch (e: Throwable) {
                            android.util.Log.w("OverlayService", "setAlarmClock failed: ${e.message}")
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
                }
            } catch (e: Throwable) {
                android.util.Log.w("OverlayService", "Failed to schedule overlay resurrection: ${e.message}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        webView?.let { return it }
        val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
            }
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addJavascriptInterface(bridge, "AriaNative")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url ?: return null

                    // 1. Direct handling of .vrm with full binary response and CORS headers
                    if (url.path?.endsWith(".vrm", ignoreCase = true) == true) {
                        try {
                            val assetPath = url.path!!.trimStart('/').removePrefix("assets/")
                            val isStream = assets.open(assetPath)
                            val length = try {
                                assets.openFd(assetPath).length.toString()
                            } catch (e: Exception) {
                                null
                            }
                            val headers = mutableMapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Access-Control-Allow-Methods" to "GET, OPTIONS, HEAD",
                                "Access-Control-Allow-Headers" to "*",
                                "Content-Type" to "model/gltf-binary"
                            )
                            length?.let { headers["Content-Length"] = it }
                            return android.webkit.WebResourceResponse("model/gltf-binary", null, 200, "OK", headers, isStream)
                        } catch (e: Exception) {
                            android.util.Log.e("OverlayService", "Failed direct soltini.vrm load", e)
                        }
                    }

                    // 2. Delegate all other assets through WebViewAssetLoader with CORS headers
                    val response = assetLoader.shouldInterceptRequest(url)
                    if (response != null) {
                        val headers = HashMap(response.responseHeaders ?: emptyMap())
                        headers["Access-Control-Allow-Origin"] = "*"
                        headers["Access-Control-Allow-Methods"] = "GET, OPTIONS, HEAD"
                        headers["Access-Control-Allow-Headers"] = "*"
                        response.responseHeaders = headers
                        if (url.path?.endsWith(".js", ignoreCase = true) == true) {
                            response.mimeType = "application/javascript"
                        }
                        return response
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("OverlayService", "WebView page finished: $url")
                    view?.evaluateJavascript("window.dispatchEvent(new Event('resize')); if (window.Aria) { window.Aria.setListening(true); }", null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    android.util.Log.e("OverlayService", "WebView error: ${error?.description} for ${request?.url}")
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html")
        }
        webView = wv
        overlayView.addView(wv, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return wv
    }

    private fun getOrCreateGLView(): SoltiniGLTextureView {
        glView?.let { return it }
        val gl = SoltiniGLTextureView(this).apply {
            setRenderer(SoltiniGLRenderer(this@OverlayService, overlayLive2DEngine))
        }
        glView = gl
        val index = if (webView != null) 1 else 0
        overlayView.addView(gl, index, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return gl
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Use our custom DraggableFrameLayout instead of plain FrameLayout
        overlayView = DraggableFrameLayout(this)

        // ── Ring indicator (primary lightweight visualizer) ──────────────────
        ringIndicatorView = RingIndicatorView(this)

        // ── Floating Response Bubble & Error Banner ──────────────────────────
        errorTextView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#1E1926"))
            val radius = 18 * resources.displayMetrics.density
            val strokePx = (1.5f * resources.displayMetrics.density).toInt()
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = radius
                setStroke(strokePx, Color.parseColor("#FCE7F3"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 8 * resources.displayMetrics.density
            }
            textSize = 11.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val ringMargin = (8 * resources.displayMetrics.density).toInt()
        val ringParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(ringMargin, ringMargin, ringMargin, ringMargin) }
        overlayView.addView(ringIndicatorView, ringParams)

        val errorParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView.addView(errorTextView, errorParams)

        // ── Window layout params ──────────────────────────────────────────────
        val settings = AppSettings(this)
        val initialSizeDp = settings.getFloatingSizeDp(settings.visualizerMode)
        val widthPx  = (initialSizeDp * resources.displayMetrics.density).toInt()
        val heightPx = (initialSizeDp * resources.displayMetrics.density).toInt()

        layoutParams = WindowManager.LayoutParams(
            widthPx, heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE allows touch pass-through to background apps
            // FLAG_LAYOUT_NO_LIMITS lets it go to screen edges
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            gravity = Gravity.TOP or Gravity.START
            val settings = AppSettings(this@OverlayService)
            if (settings.overlayPositionX != -9999 && settings.overlayPositionY != -9999) {
                x = settings.overlayPositionX
                y = settings.overlayPositionY
            } else {
                x = screenWidth - widthPx - (12 * resources.displayMetrics.density).toInt()
                y = (screenHeight * 0.35f).toInt()
            }
        }

        // ── Drag callbacks ────────────────────────────────────────────────────
        overlayView.onDrag = { dx, dy ->
            layoutParams.x = (layoutParams.x + dx).toInt()
            layoutParams.y = (layoutParams.y + dy).toInt()
            try {
                windowManager.updateViewLayout(overlayView, layoutParams)
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "updateViewLayout failed: ${e.message}")
            }
        }

        overlayView.onDragEnd = {
            // Stay EXACTLY wherever the user drops it (middle, corner, edge — zero snap/bounce)
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val curW = layoutParams.width
            val curH = layoutParams.height

            val minX = -(curW * 0.40f).toInt()
            val maxX = (screenW - (curW * 0.45f)).toInt()
            val minY = 0
            val maxY = (screenH - (curH * 0.50f)).toInt()

            layoutParams.x = layoutParams.x.coerceIn(minX, maxX)
            layoutParams.y = layoutParams.y.coerceIn(minY, maxY)

            try {
                windowManager.updateViewLayout(overlayView, layoutParams)
            } catch (_: Exception) {}

            AppSettings(this@OverlayService).apply {
                overlayPositionX = layoutParams.x
                overlayPositionY = layoutParams.y
            }
        }

        overlayView.onTap = {
            onAvatarTapped()
        }

        overlayView.onDoubleTap = {
            bringMainActivityToForeground()
        }

        overlayView.onLongPress = {
            showLongPressMenu()
        }

        // ── Add to window ─────────────────────────────────────────────────────
        try {
            windowManager.addView(overlayView, layoutParams)
            val settings = AppSettings(this)
            updateVisualizer(settings.visualizerMode, settings.characterPersona)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "addView failed: ${e.message}")
            stopSelf()
        }
    }

    /**
     * Dynamically resize floating window layout params and reposition within screen bounds.
     */
    fun updateWindowSize(sizeDp: Int) {
        mainHandler.post {
            if (!::overlayView.isInitialized || !::windowManager.isInitialized || !::layoutParams.isInitialized) return@post
            val density = resources.displayMetrics.density
            val newSizePx = (sizeDp * density).toInt().coerceAtLeast((40 * density).toInt())

            layoutParams.width = newSizePx
            layoutParams.height = newSizePx

            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val minX = -(newSizePx * 0.40f).toInt()
            val maxX = (screenW - (newSizePx * 0.45f)).toInt()
            val minY = 0
            val maxY = (screenH - (newSizePx * 0.50f)).toInt()
            layoutParams.x = layoutParams.x.coerceIn(minX, maxX)
            layoutParams.y = layoutParams.y.coerceIn(minY, maxY)

            try {
                windowManager.updateViewLayout(overlayView, layoutParams)
                webView?.let { wv ->
                    if (wv.visibility == View.VISIBLE) {
                        wv.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "updateViewLayout failed: ${e.message}")
            }
        }
    }

    /**
     * Dynamically switches the floating overlay between:
     * 1. ORB: RingIndicatorView only (webView & glView hidden)
     * 2. 3D VRM: webView only (ringIndicatorView & glView hidden)
     * 3. 2D Live2D: glView only (ringIndicatorView & webView hidden)
     * Never overlaps visualizers — only ONE displays at a time!
     */
    fun updateVisualizer(mode: String, characterName: String) {
        currentVisualizerMode = mode
        currentCharacterName = characterName
        val isVrm = characterName.contains("Soltini", ignoreCase = true) ||
                characterName == AppSettings.CHARACTER_SOLTINI

        android.util.Log.i("OverlayService", "updateVisualizer: mode=$mode, character=$characterName, isVrm=$isVrm")

        // Dynamically adjust window size to the configured preference for this visualizer mode
        val preferredSize = AppSettings(this).getFloatingSizeDp(mode)
        updateWindowSize(preferredSize)

        mainHandler.post {
            if (!::ringIndicatorView.isInitialized) return@post

            if (mode.equals("ORB", ignoreCase = true)) {
                ringIndicatorView.visibility = View.VISIBLE
                webView?.visibility = View.GONE
                glView?.apply {
                    visibility = View.GONE
                    setPaused(true)
                }
            } else if (isVrm) {
                // 3D VRM (Soltini): clean avatar, hide the orb ring!
                ringIndicatorView.visibility = View.GONE
                glView?.apply {
                    visibility = View.GONE
                    setPaused(true)
                }
                val wv = getOrCreateWebView()
                wv.visibility = View.VISIBLE
            } else {
                // 2D Live2D (Maya, Friday, Venom): clean avatar, hide the orb ring!
                ringIndicatorView.visibility = View.GONE
                webView?.visibility = View.GONE
                val gl = getOrCreateGLView()
                gl.visibility = View.VISIBLE
                gl.setPaused(false)
                overlayLive2DEngine.requestCharacter(characterName, this@OverlayService)
            }
        }
    }

    fun onModelReady() {
        mainHandler.post {
            android.util.Log.d("OverlayService", "onModelReady: 3D VRM ready")
            val isVrm = currentCharacterName.contains("Soltini", ignoreCase = true) ||
                    currentCharacterName == AppSettings.CHARACTER_SOLTINI
            if (currentVisualizerMode.equals("AVATAR", ignoreCase = true) && isVrm) {
                ringIndicatorView.visibility = View.GONE
                webView?.visibility = View.VISIBLE
                glView?.apply {
                    visibility = View.GONE
                    setPaused(true)
                }
            }
        }
    }

    fun showError(errorText: String) {
        mainHandler.post {
            errorTextView.visibility = View.VISIBLE
            errorTextView.setTextColor(Color.parseColor("#E11D48"))
            val radius = 16 * resources.displayMetrics.density
            val strokePx = (1.5f * resources.displayMetrics.density).toInt()
            errorTextView.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFF1F2"))
                cornerRadius = radius
                setStroke(strokePx, Color.parseColor("#FECDD3"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                errorTextView.elevation = 6 * resources.displayMetrics.density
            }
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            errorTextView.setPadding(padH, padV, padH, padV)
            errorTextView.textSize = 11f
            errorTextView.typeface = android.graphics.Typeface.DEFAULT_BOLD
            errorTextView.text = errorText
            // Auto hide after 10 seconds unless it's a persistent crash
            mainHandler.postDelayed({
                if (errorTextView.text == errorText) {
                    errorTextView.visibility = View.GONE
                }
            }, 10000)
        }
    }

    fun onAvatarTapped() {
        val svc = voiceService
        if (svc != null) {
            when {
                svc.isSleeping.value -> {
                    svc.wakeUp()
                    showResponseBubble("☀️ Soltini awake & listening", 2000)
                    return
                }
                svc.connectionState.value == com.lucy.mate.network.ConnectionState.Disconnected ||
                svc.connectionState.value == com.lucy.mate.network.ConnectionState.Error -> {
                    svc.connect()
                    showResponseBubble("🔄 Reconnecting...", 2000)
                    return
                }
                svc.isMuted.value -> {
                    svc.setMute(false)
                    showResponseBubble("🎙️ Unmuted (listening)", 2000)
                    return
                }
                else -> {
                    // Single tap = confirm listening. Muting is in long-press menu.
                    showResponseBubble("👂 I'm listening...", 1500)
                    return
                }
            }
        }
    }

    /** Opens MainActivity or brings it to the foreground without creating a new instance. */
    fun bringMainActivityToForeground(targetScreen: String = "voice") {
        val intent = Intent(this, com.lucy.mate.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(com.lucy.mate.MainActivity.EXTRA_TARGET_SCREEN, targetScreen)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Failed to launch MainActivity: ${e.message}")
        }
    }

    /**
     * Shows an AI response text bubble above the orb for [durationMs] milliseconds.
     * Styled in the ultra-smooth luxury Pinky-White aesthetic.
     */
    fun showResponseBubble(text: String, durationMs: Long = 3500) {
        mainHandler.post {
            // Truncate very long responses for the bubble
            val display = if (text.length > 100) text.take(97) + "…" else text
            errorTextView.setTextColor(Color.parseColor("#1E1926"))
            val radius = 18 * resources.displayMetrics.density
            val strokePx = (1.5f * resources.displayMetrics.density).toInt()
            errorTextView.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = radius
                setStroke(strokePx, Color.parseColor("#FCE7F3"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                errorTextView.elevation = 8 * resources.displayMetrics.density
            }
            errorTextView.textSize = 12f
            errorTextView.typeface = android.graphics.Typeface.DEFAULT_BOLD
            errorTextView.text = display
            errorTextView.visibility = View.VISIBLE
            mainHandler.removeCallbacksAndMessages("bubble_hide")
            mainHandler.postDelayed({
                if (errorTextView.text == display) {
                    errorTextView.visibility = View.GONE
                }
            }, durationMs)
        }
    }

    private fun snapToEdge(marginDp: Int = 16) {
        val screenWidth = resources.displayMetrics.widthPixels
        val margin = (marginDp * resources.displayMetrics.density).toInt()
        val centerX = layoutParams.x + (layoutParams.width / 2)
        layoutParams.x = if (centerX < screenWidth / 2) margin else screenWidth - layoutParams.width - margin
        try {
            windowManager.updateViewLayout(overlayView, layoutParams)
        } catch (e: Exception) { /* ignore */ }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Aria Overlay Service", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_BANKING_PAUSE }
        val togglePendingIntent = PendingIntent.getService(
            this, 2, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleTitle = if (isHiddenForBanking) "Resume LUCY" else "Pause (Banking)"
        val contentText = if (isHiddenForBanking) "Paused for Banking app safety" else "LUCY is running"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LUCY")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, toggleTitle, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun setRingState(state: RingState) {
        mainHandler.post { ringIndicatorView.state = state }
    }

    fun setTalking(isTalking: Boolean) {
        mainHandler.post {
            webView?.let { wv ->
                if (wv.visibility == View.VISIBLE) {
                    wv.evaluateJavascript(
                        "window.Aria && window.Aria.setTalking && window.Aria.setTalking($isTalking)", null
                    )
                }
            }
            glView?.let { gl ->
                if (gl.visibility == View.VISIBLE) {
                    gl.setActiveFrameRate(isTalking)
                }
            }
        }
    }

    fun setMouthAmplitude(value: Float) {
        if (currentVisualizerMode.equals("ORB", ignoreCase = true)) {
            // Direct update on RingIndicatorView (thread-safe, postInvalidateOnAnimation internally)
            if (::ringIndicatorView.isInitialized) {
                ringIndicatorView.amplitude = value
            }
            return
        }

        val isVrm = currentCharacterName.contains("Soltini", ignoreCase = true) ||
                currentCharacterName == AppSettings.CHARACTER_SOLTINI

        if (isVrm) {
            val now = SystemClock.uptimeMillis()
            if (now - lastVrmAmpPostTime >= 33L) {
                lastVrmAmpPostTime = now
                mainHandler.post {
                    webView?.let { wv ->
                        if (wv.visibility == View.VISIBLE) {
                            wv.evaluateJavascript(
                                "window.Aria && window.Aria.setMouthAmplitude && window.Aria.setMouthAmplitude($value)", null
                            )
                        }
                    }
                }
            }
        } else {
            // Live2D lip sync is thread-safe on SoltiniLive2DEngine (sets volatile float on SoltiniLive2DModel)
            val lipAmp = (value * 2.5f).coerceIn(0f, 1f)
            overlayLive2DEngine.setLipSync(lipAmp)
        }
    }

    fun setEmotion(tag: String) {
        mainHandler.post {
            webView?.let { wv ->
                if (wv.visibility == View.VISIBLE) {
                    wv.evaluateJavascript(
                        "window.Aria && window.Aria.setEmotion && window.Aria.setEmotion('$tag')", null
                    )
                }
            }
        }
    }

    fun setListening(isListening: Boolean) {
        mainHandler.post {
            webView?.let { wv ->
                if (wv.visibility == View.VISIBLE) {
                    wv.evaluateJavascript(
                        "window.Aria && window.Aria.setListening && window.Aria.setListening($isListening)", null
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (e: Exception) { /* ignore */ }
        }
        glView?.let {
            try { it.destroy() } catch (e: Exception) { /* ignore */ }
        }
        glView = null
        try { overlayLive2DEngine.release() } catch (e: Exception) { /* ignore */ }
        webView?.let {
            try { it.destroy() } catch (e: Exception) { /* ignore */ }
        }
        webView = null
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private var popupMenuContainer: FrameLayout? = null

    private fun showLongPressMenu() {
        if (popupMenuContainer != null) return // Already showing

        popupMenuContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#4D1E1926")) // Soft luxury dim background
            setOnClickListener { dismissLongPressMenu() } // Tap outside to dismiss
        }

        val menuLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val radius = 24 * resources.displayMetrics.density
            val strokeWidth = (1.5f * resources.displayMetrics.density).toInt()
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE) // PearlWhiteSurface
                cornerRadius = radius
                setStroke(strokeWidth, Color.parseColor("#FCE7F3")) // PinkyBorder
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 16 * resources.displayMetrics.density
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Header Title
        val titleView = android.widget.TextView(this).apply {
            text = "✨ Soltini Assistant"
            setTextColor(Color.parseColor("#F43F8E")) // RosePink
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        menuLayout.addView(titleView)

        // Subtle divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#FCE7F3"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        menuLayout.addView(divider)

        val createButton = { text: String, isPrimary: Boolean, isDestructive: Boolean, onClick: () -> Unit ->
            android.widget.TextView(this).apply {
                this.text = text
                setTextColor(
                    when {
                        isDestructive -> Color.parseColor("#E11D48") // RosePinkDeep / Ruby
                        isPrimary -> Color.parseColor("#F43F8E")     // RosePink
                        else -> Color.parseColor("#1E1926")          // TextPrimary
                    }
                )
                textSize = 14.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER

                val btnRadius = 16 * resources.displayMetrics.density
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(
                        when {
                            isPrimary -> Color.parseColor("#FFF5F8") // Soft Rose background
                            isDestructive -> Color.parseColor("#FFF1F2")
                            else -> Color.parseColor("#FDF2F8")     // Blush surface elevated
                        }
                    )
                    cornerRadius = btnRadius
                    val btnStrokePx = (1 * resources.displayMetrics.density).toInt()
                    setStroke(
                        btnStrokePx,
                        when {
                            isPrimary -> Color.parseColor("#FCE7F3")
                            isDestructive -> Color.parseColor("#FECDD3")
                            else -> Color.parseColor("#FCE7F3")
                        }
                    )
                }

                val padH = (20 * resources.displayMetrics.density).toInt()
                val padV = (12 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)

                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }

                setOnClickListener {
                    onClick()
                    dismissLongPressMenu()
                }
            }
        }

        menuLayout.setOnClickListener { /* Consume clicks inside menu card */ }

        menuLayout.addView(createButton("🌸 Open Soltini App", true, false) { bringMainActivityToForeground("voice") })

        menuLayout.addView(createButton("⚙️ Studio Settings", false, false) { bringMainActivityToForeground("settings") })

        val isMuted = voiceService?.isMuted?.value == true
        menuLayout.addView(createButton(if (isMuted) "🎙️ Unmute Mic" else "🤫 Mute Mic", false, false) {
            voiceService?.setMute(!isMuted)
        })

        // Floating window size slider container
        val sliderContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padH = (14 * resources.displayMetrics.density).toInt()
            val padV = (10 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            val containerRadius = 14 * resources.displayMetrics.density
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFF5F8")) // Soft blush
                cornerRadius = containerRadius
                val strokePx = (1 * resources.displayMetrics.density).toInt()
                setStroke(strokePx, Color.parseColor("#FCE7F3"))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        val sliderHeaderRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val sliderTitle = android.widget.TextView(this).apply {
            text = "Assistant Size"
            setTextColor(Color.parseColor("#1E1926"))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val currentSizeDp = AppSettings(this).getFloatingSizeDp(currentVisualizerMode)
        val sliderValueLabel = android.widget.TextView(this).apply {
            text = "${currentSizeDp}dp"
            setTextColor(Color.parseColor("#F43F8E"))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        sliderHeaderRow.addView(sliderTitle)
        sliderHeaderRow.addView(sliderValueLabel)
        sliderContainer.addView(sliderHeaderRow)

        val sizeSeekBar = android.widget.SeekBar(this).apply {
            max = 240 // range 60 to 300 dp
            progress = (currentSizeDp - 60).coerceIn(0, 240)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F43F8E"))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F43F8E"))
            }
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val newSize = progress + 60
                    sliderValueLabel.text = "${newSize}dp"
                    updateWindowSize(newSize)
                    val appSettings = AppSettings(this@OverlayService)
                    if (currentVisualizerMode.equals("ORB", ignoreCase = true)) {
                        appSettings.floatingOrbSizeDp = newSize
                    } else {
                        appSettings.floatingAvatarSizeDp = newSize
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        sliderContainer.addView(sizeSeekBar)
        menuLayout.addView(sliderContainer)

        menuLayout.addView(createButton("⏸ Pause Soltini", false, true) {
            val voice = voiceService ?: com.lucy.mate.services.BackgroundVoiceService.getInstance()
            if (voice != null) {
                voice.pauseApp()
            } else {
                try {
                    val pauseIntent = Intent(this@OverlayService, com.lucy.mate.services.BackgroundVoiceService::class.java).apply {
                        action = com.lucy.mate.services.BackgroundVoiceService.ACTION_PAUSE_APP
                    }
                    startService(pauseIntent)
                } catch (_: Exception) {}
                stopSelf()
            }
        })

        val menuParams = FrameLayout.LayoutParams(
            (260 * resources.displayMetrics.density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        
        popupMenuContainer?.addView(menuLayout, menuParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(popupMenuContainer, params)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Failed to show popup menu: ${e.message}")
        }
    }

    private fun dismissLongPressMenu() {
        popupMenuContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            popupMenuContainer = null
        }
    }
}

