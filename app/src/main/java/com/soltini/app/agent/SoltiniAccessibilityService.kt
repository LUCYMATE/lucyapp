package com.soltini.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.soltini.app.core.SoltiniDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SoltiniAccessibilityService
 *
 * Full automation service that gives Soltini the ability to:
 *
 *  ┌── Global Actions ──────────────────────────────────────────────────────┐
 *  │  lockScreen()     — GLOBAL_ACTION_LOCK_SCREEN (Android 9+)            │
 *  │  pressBack()      — GLOBAL_ACTION_BACK                                │
 *  │  pressHome()      — GLOBAL_ACTION_HOME                                │
 *  │  pressRecents()   — GLOBAL_ACTION_RECENTS                             │
 *  └────────────────────────────────────────────────────────────────────────┘
 *  ┌── Screen Reading ───────────────────────────────────────────────────────┐
 *  │  readScreen()     — walks the a11y tree, returns all visible text      │
 *  │  findNodes(text)  — finds nodes whose text/desc matches a query        │
 *  └────────────────────────────────────────────────────────────────────────┘
 *  ┌── Click / Tap ──────────────────────────────────────────────────────────┐
 *  │  clickByText(text)     — perform ACTION_CLICK on matching node         │
 *  │  tapAt(x, y)           — dispatch a tap gesture at pixel coords        │
 *  └────────────────────────────────────────────────────────────────────────┘
 *  ┌── Typing ───────────────────────────────────────────────────────────────┐
 *  │  typeText(text)        — ACTION_SET_TEXT on focused / editable node    │
 *  │  clearText()           — clears the focused input field                │
 *  └────────────────────────────────────────────────────────────────────────┘
 *  ┌── Scroll ───────────────────────────────────────────────────────────────┐
 *  │  scrollDown() / scrollUp() — find first scrollable view and scroll     │
 *  └────────────────────────────────────────────────────────────────────────┘
 *
 * Requires the user to enable this service in:
 *   Settings → Accessibility → Soltini
 *
 * On Android 13+ sideloaded APKs:
 *   App Info → ⋮ → "Allow restricted settings" must be enabled first.
 *
 * ── Stability Design ──────────────────────────────────────────────────────
 * We deliberately NEVER modify serviceInfo at runtime. Previous code called
 * serviceInfo = info to toggle event types, which caused Android to treat the
 * service as unstable and auto-disable it when the app crashed.
 *
 * Instead, the XML config subscribes to a fixed minimal set of events and we
 * use an internal @Volatile flag (isToolCallActive) to gate whether we act on
 * those events. Zero risk of Android disabling the permission.
 */
class SoltiniAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SoltiniA11y"

        @SuppressLint("StaticFieldLeak")
        private var instance: SoltiniAccessibilityService? = null

        fun getInstance(): SoltiniAccessibilityService? = instance
        fun isActive(): Boolean = instance != null
    }

    /**
     * Set to true only while a Gemini tool call is actively executing.
     * Controls whether onAccessibilityEvent tracks editable nodes.
     *
     * @Volatile ensures cross-thread visibility without a full lock.
     * We NEVER touch serviceInfo — that caused the auto-disable bug.
     */
    @Volatile
    var isToolCallActive: Boolean = false
        set(value) {
            field = value
            Log.i(TAG, "isToolCallActive = $value")
        }

    /**
     * Lite Pause Mode flag.
     *
     * When true the accessibility service stays ENABLED in the Android system list
     * (so no user interaction is needed to resume), but:
     *   • onAccessibilityEvent() returns immediately without doing anything
     *   • All content/window queries return null / empty
     *
     * This makes Soltini functionally invisible to banking apps that check what
     * the accessibility service is actually reading. The service appears "on" but
     * is completely silent — meeting the spirit of most banking app checks which
     * look for services actively monitoring content (canRetrieveWindowContent
     * reads, active window captures, etc.).
     *
     * For stricter banking apps that check the raw enabled-services list,
     * use Hard Pause (disableSelf()) via enterHardPauseMode().
     */
    @Volatile
    var isPausedLite: Boolean = false
        set(value) {
            field = value
            Log.i(TAG, "Lite Pause = $value")
        }

    // Last focused editable node — updated only when a tool call is active
    private var lastFocusedEditable: AccessibilityNodeInfo? = null

    private val serviceScope = CoroutineScope(SoltiniDispatchers.AgentWorkerDispatcher + SupervisorJob())

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                node.recycle()
            } catch (_: Throwable) {}
        }
    }

    // ── Smart Manual Activity Detection ───────────────────────────────────────
    @Volatile
    private var lastUserManualInteractionTime: Long = 0L

    fun isUserInteractingManually(): Boolean {
        if (!appSettings.isSmartManualDetectionEnabled) return false
        val elapsed = System.currentTimeMillis() - lastUserManualInteractionTime
        val cooldownMs = appSettings.manualInteractionCooldownSeconds * 1000L
        return elapsed < cooldownMs
    }

    fun notifyManualInteraction() {
        lastUserManualInteractionTime = System.currentTimeMillis()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isToolCallActive = false
        Log.i(TAG, "Accessibility Service connected — ready for tool calls")
        ensureForegroundServicesRunning()
    }

    fun ensureForegroundServicesRunning() {
        serviceScope.launch {
            try {
                if (appSettings.isAppPaused) {
                    Log.d(TAG, "Watchdog: Soltini is Master PAUSED — skipping foreground services restart")
                    return@launch
                }
                if (!appSettings.isAutoStartEnabled) return@launch

                if (com.soltini.app.services.BackgroundVoiceService.getInstance() == null) {
                    val voiceIntent = android.content.Intent(this@SoltiniAccessibilityService, com.soltini.app.services.BackgroundVoiceService::class.java).apply {
                        putExtra(com.soltini.app.BootReceiver.EXTRA_FROM_BOOT_OR_RESURRECT, true)
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(voiceIntent)
                        } else {
                            startService(voiceIntent)
                        }
                        Log.d(TAG, "Watchdog: BackgroundVoiceService started safely off main thread")
                    } catch (e: Exception) {
                        Log.w(TAG, "Watchdog: Could not start BackgroundVoiceService: ${e.message}")
                    }
                }

                if (android.provider.Settings.canDrawOverlays(this@SoltiniAccessibilityService) &&
                    com.soltini.app.overlay.OverlayService.getInstance() == null) {
                    try {
                        com.soltini.app.overlay.OverlayService.start(this@SoltiniAccessibilityService)
                        Log.d(TAG, "Watchdog: OverlayService started safely off main thread")
                    } catch (e: Exception) {
                        Log.w(TAG, "Watchdog: Could not start OverlayService: ${e.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Watchdog error: ${t.message}")
            }
        }
    }

    @Volatile
    var isAutomatingForceStop: Boolean = false

    private val appSettings by lazy { com.soltini.app.settings.AppSettings(this) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // While app is paused: completely dormant with zero device relation,
        // without disabling the service in Android OS (no re-grant needed in Settings)
        if (appSettings.isAppPaused || isPausedLite) return
        try {
            event ?: return
            val eventType = event.eventType

            // ── Smart Manual Activity Detection ──────────────────────────────
            // When user taps, scrolls, or types, detect immediately and return with ZERO delay
            if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            ) {
                lastUserManualInteractionTime = System.currentTimeMillis()
                if (isToolCallActive) {
                    Log.i(TAG, "Manual user interaction detected (event=$eventType) — auto-yielding tool control to user")
                    isToolCallActive = false
                }
                return // ZERO processing overhead — guarantee 0 touch lag for user
            }

            if (isAutomatingForceStop) {
                serviceScope.launch {
                    try {
                        handleForceStopAutomation()
                    } catch (t: Throwable) {
                        Log.w(TAG, "handleForceStopAutomation caught error: ${t.message}")
                    }
                }
            }

            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val packageName = event.packageName?.toString()
                if (!packageName.isNullOrBlank()) {
                    serviceScope.launch {
                        try {
                            val overlay = com.soltini.app.overlay.OverlayService.getInstance()
                            if (appSettings.isBankingProtectionEnabled && appSettings.isBankingApp(packageName)) {
                                Log.i(TAG, "Banking app in foreground ($packageName) — hiding overlay & pausing A11y")
                                isToolCallActive = false
                                overlay?.setHiddenForBanking(true)
                            } else if (appSettings.isBankingProtectionEnabled && overlay?.isHiddenForBanking() == true) {
                                // Switched back to normal app — restore overlay ONLY if it was hidden for banking
                                overlay.setHiddenForBanking(false)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Banking state transition caught error: ${t.message}")
                        }
                    }
                }
            }

            // Only track focused editable views when a tool call is in progress.
            // When idle this callback fires but we do nothing — zero friction.
            if (!isToolCallActive) return
            if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val node = event.source
                if (node != null && node.isEditable) {
                    safeRecycle(lastFocusedEditable)
                    lastFocusedEditable = AccessibilityNodeInfo.obtain(node)
                    safeRecycle(node)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Suppressed crash in onAccessibilityEvent: ${t.message}")
        }
    }

    private fun handleForceStopAutomation() {
        val root = rootInActiveWindow ?: return
        try {
            // Try standard Android Settings view IDs first (language independent)
            val forceStopViewIds = listOf(
                "com.android.settings:id/button1_negative",
                "com.android.settings:id/button1",
                "com.android.settings:id/right_button",
                "com.android.settings:id/force_stop_button"
            )
            var forceStopNode: AccessibilityNodeInfo? = null
            for (id in forceStopViewIds) {
                try {
                    val matches = root.findAccessibilityNodeInfosByViewId(id)
                    forceStopNode = matches?.firstOrNull { it.isEnabled && it.isClickable }
                    if (forceStopNode != null) break
                } catch (_: Exception) {}
            }

            // Fallback to multilingual text queries
            if (forceStopNode == null) {
                val queries = listOf("force stop", "force", "forcer l'arrêt", "forzar detención", "beenden erzwingen", "फ़ोर्स स्टॉप")
                for (q in queries) {
                    val candidate = findNodeByQuery(root, q, requireClickable = true)
                    if (candidate != null && candidate.isEnabled) {
                        forceStopNode = candidate
                        break
                    }
                }
            }

            if (forceStopNode != null) {
                Log.i(TAG, "Found Force Stop button, clicking it!")
                forceStopNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(forceStopNode)
            }

            // Look for confirmation dialog OK button (android:id/button1 is standard AlertDialog positive button)
            var okNode: AccessibilityNodeInfo? = null
            try {
                val matches = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                okNode = matches?.firstOrNull { it.isEnabled && it.isClickable }
            } catch (_: Exception) {}

            if (okNode == null) {
                val okQueries = listOf("ok", "force stop", "stop", "confirm", "d'accord", "aceptar", "bestätigen", "ठीक")
                for (q in okQueries) {
                    val candidate = findNodeByQuery(root, q, requireClickable = true)
                    if (candidate != null && candidate.isEnabled) {
                        okNode = candidate
                        break
                    }
                }
            }

            if (okNode != null) {
                Log.i(TAG, "Found OK confirmation, clicking it!")
                okNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(okNode)
                isAutomatingForceStop = false // Automation complete
            }
        } finally {
            safeRecycle(root)
        }
    }

    override fun onInterrupt() {
        // Wrap in try/catch — any uncaught exception here causes Android to mark
        // the service as crashed and may auto-disable the permission.
        try {
            Log.w(TAG, "Accessibility Service interrupted")
            isToolCallActive = false
            isAutomatingForceStop = false
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try {
            // Clear instance BEFORE super so no in-flight tool call fires against
            // a destroyed service context.
            instance = null
            isToolCallActive = false
            isAutomatingForceStop = false
            safeRecycle(lastFocusedEditable)
            lastFocusedEditable = null
            serviceScope.cancel()
        } catch (_: Throwable) {}
        super.onDestroy()
        Log.i(TAG, "Accessibility Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: SoltiniAccessibilityService thread-safe state cleanup on task removal")
        try {
            isToolCallActive = false
            isAutomatingForceStop = false
            safeRecycle(lastFocusedEditable)
            lastFocusedEditable = null
        } catch (t: Throwable) {
            Log.w(TAG, "Error in onTaskRemoved cleanup: ${t.message}")
        }
    }

    // ─── Global Actions ───────────────────────────────────────────────────────

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            Log.w(TAG, "Lock screen not supported below Android 9")
            false
        }
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ─── Screen Reading ───────────────────────────────────────────────────────

    /**
     * Walks the full accessibility tree of all visible windows and collects
     * all visible text into a structured string.
     *
     * Clickable elements include their on-screen center coordinates in the format
     * @(cx,cy) so Gemini can pass those exact values to tap_screen().
     * These are SCREEN-ABSOLUTE pixel coordinates (same space as dispatchGesture).
     *
     * @return A plain-text representation of the screen content suitable for
     *         sending to Gemini as a tool response.
     */
    // ─── Screen Screenshot (Android 11+) ─────────────────────────────────────

    /**
     * Captures a screenshot directly via AccessibilityService on Android 11+ (API 30+)
     * without needing MediaProjection or user permission dialogs.
     */
    fun takeScreenshotDirectly(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val softwareBitmap = try {
                                    bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    try { hardwareBuffer.close() } catch (_: Exception) {}
                                }
                                if (bitmap != null && softwareBitmap != null && softwareBitmap != bitmap) {
                                    try { bitmap.recycle() } catch (_: Exception) {}
                                }
                                callback(softwareBitmap ?: bitmap)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode hardware screenshot: ${e.message}")
                                callback(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed with errorCode=$errorCode")
                            callback(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception: ${e.message}")
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    // ─── Screen Reading ───────────────────────────────────────────────────────

    /**
     * Walks the full accessibility tree of all visible windows and collects
     * all visible text into a structured string.
     *
     * Clickable elements include their on-screen center coordinates in the format
     * @(cx,cy) so Gemini can pass those exact values to tap_screen().
     *
     * @return A plain-text representation of the screen content suitable for
     *         sending to Gemini as a tool response.
     */
    fun readScreen(): String {
        val sb = StringBuilder()
        try {
            val windowList: List<AccessibilityWindowInfo> = windows ?: emptyList()
            if (windowList.isNotEmpty()) {
                for (window in windowList) {
                    val root = window.root ?: continue
                    collectNodeText(root, sb, depth = 0)
                    safeRecycle(root)
                }
            }

            // Fallback: If windows was empty or yielded no content, read active root window directly
            if (sb.isEmpty()) {
                val activeRoot = rootInActiveWindow
                if (activeRoot != null) {
                    collectNodeText(activeRoot, sb, depth = 0)
                    safeRecycle(activeRoot)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readScreen failed: ${e.message}")
            return "Error reading screen: ${e.message}"
        }
        val result = sb.toString().trim()
        return result.ifEmpty { "Screen appears empty or content is not accessible." }
    }

    /**
     * Maya-style ultra-compact node hierarchy serialization (text|desc|x,y;)
     * Capped at maxNodes (default 400) to save token overhead.
     */
    fun dumpCompactScreenState(maxNodes: Int = 400): String {
        val sb = StringBuilder()
        var nodeCount = 0
        try {
            val windowList: List<AccessibilityWindowInfo> = windows ?: emptyList()
            if (windowList.isNotEmpty()) {
                for (window in windowList) {
                    val root = window.root ?: continue
                    nodeCount = collectCompactNodeText(root, sb, nodeCount, maxNodes)
                    safeRecycle(root)
                    if (nodeCount >= maxNodes) break
                }
            }

            if (nodeCount == 0) {
                val activeRoot = rootInActiveWindow
                if (activeRoot != null) {
                    collectCompactNodeText(activeRoot, sb, 0, maxNodes)
                    safeRecycle(activeRoot)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "dumpCompactScreenState failed: ${e.message}")
            return ""
        }
        return sb.toString()
    }

    private fun collectCompactNodeText(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        currentCount: Int,
        maxNodes: Int
    ): Int {
        var count = currentCount
        if (count >= maxNodes) return count

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if (text.isNotEmpty() || desc.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            sb.append(text)
                .append('|')
                .append(desc)
                .append('|')
                .append(rect.centerX())
                .append(',')
                .append(rect.centerY())
                .append(';')
            count++
        }

        for (i in 0 until node.childCount) {
            if (count >= maxNodes) break
            val child = node.getChild(i) ?: continue
            count = collectCompactNodeText(child, sb, count, maxNodes)
            safeRecycle(child)
        }
        return count
    }

    /**
     * Wait for specified text to appear or disappear on screen.
     */
    suspend fun waitForScreenText(text: String, mode: String = "appear", timeoutSeconds: Int = 60): Boolean {
        val q = text.lowercase().trim()
        val isAppear = mode.equals("appear", ignoreCase = true)
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val screen = readScreen().lowercase()
            val contains = screen.contains(q)
            if (isAppear && contains) return true
            if (!isAppear && !contains) return true
            kotlinx.coroutines.delay(500)
        }
        return false
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth.coerceAtMost(8))

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = node.hintText?.toString()?.trim()

        val displayed = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> "[$desc]"
            !hint.isNullOrEmpty() -> "(hint: $hint)"
            else -> null
        }

        if (displayed != null) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val cx = bounds.centerX()
            val cy = bounds.centerY()

            val interactMarker = when {
                node.isClickable && node.isEditable -> " ✏ tap@($cx,$cy)"
                node.isClickable                    -> " ◆ tap@($cx,$cy)"
                node.isEditable                    -> " ✏ tap@($cx,$cy)"
                else                               -> ""
            }
            sb.appendLine("$indent$displayed$interactMarker")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeText(child, sb, depth + 1)
            safeRecycle(child)
        }
    }

    // ─── Click / Tap ──────────────────────────────────────────────────────────

    fun clickByText(query: String): Boolean {
        if (query.isBlank()) return false
        val q = query.lowercase().trim()

        // 1. Try search across all windows
        val windowList: List<AccessibilityWindowInfo> = windows ?: emptyList()
        for (window in windowList) {
            val root = window.root ?: continue
            val match = findNodeByQuery(root, q, requireClickable = false)
            safeRecycle(root)
            if (match != null) {
                val clicked = performClickOrTap(match)
                safeRecycle(match)
                if (clicked) {
                    Log.i(TAG, "clickByText('$query') -> succeeded in window")
                    return true
                }
            }
        }

        // 2. Fallback: Search in active root window
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            val match = findNodeByQuery(activeRoot, q, requireClickable = false)
            safeRecycle(activeRoot)
            if (match != null) {
                val clicked = performClickOrTap(match)
                safeRecycle(match)
                if (clicked) {
                    Log.i(TAG, "clickByText('$query') -> succeeded in rootInActiveWindow")
                    return true
                }
            }
        }

        Log.w(TAG, "clickByText('$query') -> no matching element found on screen")
        return false
    }

    private fun performClickOrTap(node: AccessibilityNodeInfo): Boolean {
        if (isUserInteractingManually()) {
            Log.w(TAG, "performClickOrTap suppressed: User is actively interacting manually with device")
            return false
        }
        // A. Direct accessibility click on node
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // B. Check clickable ancestors (e.g. Button or CardView wrapping a TextView)
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                safeRecycle(parent)
                return true
            }
            val nextParent = parent.parent
            safeRecycle(parent)
            parent = nextParent
        }

        // C. Physical coordinate tap at node center (guaranteed for Jetpack Compose, Flutter, React Native, WebViews)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            Log.i(TAG, "performClickOrTap falling back to physical tap at ($cx, $cy) for bounds: $bounds")
            return tapAt(cx, cy)
        }
        return false
    }

    fun tapAt(x: Float, y: Float): Boolean {
        if (isUserInteractingManually()) {
            Log.w(TAG, "tapAt($x, $y) suppressed: User is actively interacting manually with device")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "tapAt requires Android 7+")
            return false
        }
        return try {
            // dispatchGesture uses raw screen-absolute coordinates.
            // These must match what getBoundsInScreen() returns — and they do,
            // because both use the same (0,0)=physical-top-left coordinate space.
            Log.i(TAG, "tapAt dispatching gesture at raw screen coords ($x, $y)")
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "tapAt($x,$y) failed: ${e.message}")
            false
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        if (isUserInteractingManually()) {
            Log.w(TAG, "swipe suppressed: User is actively interacting manually with device")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "swipe failed: ${e.message}")
            false
        }
    }

    // ─── Typing ───────────────────────────────────────────────────────────────

    fun typeText(text: String): Boolean {
        if (isUserInteractingManually()) {
            Log.w(TAG, "typeText suppressed: User is actively interacting manually with device")
            return false
        }
        try {
            val cached = lastFocusedEditable
            if (cached != null) {
                try {
                    if (cached.isEditable) {
                        val success = setTextOnNode(cached, text)
                        if (success) return true
                    }
                } catch (_: Exception) {
                    // cached node was recycled by the system — clear it and fall through
                    lastFocusedEditable = null
                }
            }

            val windows: List<AccessibilityWindowInfo> = windows ?: return false
            for (window in windows) {
                val root = window.root ?: continue
                val editable = findFirstEditable(root)
                safeRecycle(root)
                if (editable != null) {
                    editable.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    val success = setTextOnNode(editable, text)
                    safeRecycle(editable)
                    if (success) return true
                }
            }
            Log.w(TAG, "typeText: no editable field found on screen")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "typeText failed: ${e.message}")
            return false
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "setTextOnNode('$text') -> $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "setTextOnNode failed: ${e.message}")
            false
        }
    }


    fun clearText(): Boolean = typeText("")

    // ─── Scroll ───────────────────────────────────────────────────────────────

    fun scrollDown(): Boolean = scroll(forward = true)

    fun scrollUp(): Boolean = scroll(forward = false)

    private fun scroll(forward: Boolean): Boolean {
        if (isUserInteractingManually()) {
            Log.w(TAG, "scroll suppressed: User is actively interacting manually with device")
            return false
        }
        val action = if (forward)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        val windows: List<AccessibilityWindowInfo> = windows ?: return false
        for (window in windows) {
            val root = window.root ?: continue
            val scrollable = findFirstScrollable(root)
            safeRecycle(root)
            if (scrollable != null) {
                val success = scrollable.performAction(action)
                safeRecycle(scrollable)
                Log.i(TAG, "scroll(forward=$forward) -> $success")
                return success
            }
        }
        Log.w(TAG, "scroll: no scrollable view found")
        return false
    }

    // ─── Node Search Helpers ──────────────────────────────────────────────────

    private fun levenshteinRatio(s1: String, s2: String): Double {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = kotlin.math.min(
                    dp[i - 1][j] + 1,
                    kotlin.math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        val distance = dp[s1.length][s2.length]
        val maxLength = kotlin.math.max(s1.length, s2.length)
        return 1.0 - (distance.toDouble() / maxLength)
    }

    private fun findNodeByQuery(
        node: AccessibilityNodeInfo,
        query: String,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        
        val contains = text.contains(query) || desc.contains(query)
        val fuzzyText = if (text.isNotEmpty()) levenshteinRatio(text, query) else 0.0
        val fuzzyDesc = if (desc.isNotEmpty()) levenshteinRatio(desc, query) else 0.0
        
        val matches = contains || fuzzyText > 0.85 || fuzzyDesc > 0.85
        val clickOk = !requireClickable || node.isClickable

        if (matches && clickOk) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByQuery(child, query, requireClickable)
            safeRecycle(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            safeRecycle(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollable(child)
            safeRecycle(child)
            if (found != null) return found
        }
        return null
    }
}
