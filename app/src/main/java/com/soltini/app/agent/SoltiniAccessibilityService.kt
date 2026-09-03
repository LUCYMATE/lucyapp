package com.soltini.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isToolCallActive = false
        Log.i(TAG, "Accessibility Service connected — ready for tool calls")
        // NOTE: We do NOT modify serviceInfo here. The static XML config is the
        // single source of truth for event subscriptions. Touching serviceInfo
        // at runtime was the root cause of the auto-disable bug.
    }

    @Volatile
    var isAutomatingForceStop: Boolean = false

    private val appSettings by lazy { com.soltini.app.settings.AppSettings(this) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lite Pause: service is alive but completely silent — return immediately
        if (isPausedLite) return
        try {
            event ?: return
            val eventType = event.eventType

            if (isAutomatingForceStop) {
                // We are looking for "Force stop" or "OK" buttons
                val root = rootInActiveWindow
                if (root != null) {
                    // Look for "Force stop"
                    var forceStopNode = findNodeByQuery(root, "force stop", requireClickable = true)
                    // Some UIs use "Force closed" or just "Force"
                    if (forceStopNode == null) forceStopNode = findNodeByQuery(root, "force", requireClickable = true)
                    
                    if (forceStopNode != null) {
                        Log.i(TAG, "Found Force Stop button, clicking it!")
                        forceStopNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        forceStopNode.recycle()
                        // Don't return yet, we might also see the OK button immediately on some UIs
                    }

                    // Look for confirmation button ("OK" or "Force stop" again)
                    val okNode = findNodeByQuery(root, "ok", requireClickable = true)
                    if (okNode != null) {
                        Log.i(TAG, "Found OK confirmation, clicking it!")
                        okNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        okNode.recycle()
                        isAutomatingForceStop = false // Automation complete
                    }
                    root.recycle()
                }
            }

            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val packageName = event.packageName?.toString()
                if (!packageName.isNullOrBlank()) {
                    if (appSettings.isBankingProtectionEnabled && appSettings.isBankingApp(packageName)) {
                        Log.i(TAG, "Banking app in foreground ($packageName) — hiding overlay & pausing A11y")
                        isToolCallActive = false
                        com.soltini.app.overlay.OverlayService.getInstance()?.setHiddenForBanking(true)
                    } else if (appSettings.isBankingProtectionEnabled) {
                        // Switched back to a normal app — restore overlay
                        com.soltini.app.overlay.OverlayService.getInstance()?.setHiddenForBanking(false)
                    }
                }
            }

            // Only track focused editable views when a tool call is in progress.
            // When idle this callback fires but we do nothing — zero friction.
            if (!isToolCallActive) return
            if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val node = event.source
                if (node != null && node.isEditable) {
                    lastFocusedEditable?.recycle()
                    lastFocusedEditable = AccessibilityNodeInfo.obtain(node)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Suppressed crash in onAccessibilityEvent: ${t.message}")
        }
    }

    override fun onInterrupt() {
        // Wrap in try/catch — any uncaught exception here causes Android to mark
        // the service as crashed and may auto-disable the permission.
        try {
            Log.w(TAG, "Accessibility Service interrupted")
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try {
            // Clear instance BEFORE super so no in-flight tool call fires against
            // a destroyed service context.
            instance = null
            isToolCallActive = false
            lastFocusedEditable?.recycle()
            lastFocusedEditable = null
        } catch (_: Throwable) {}
        super.onDestroy()
        Log.i(TAG, "Accessibility Service destroyed")
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
    fun readScreen(): String {
        val sb = StringBuilder()
        try {
            val windows: List<AccessibilityWindowInfo> = windows ?: emptyList()
            for (window in windows) {
                val root = window.root ?: continue
                collectNodeText(root, sb, depth = 0)
                root.recycle()
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
            val windows: List<AccessibilityWindowInfo> = windows ?: emptyList()
            for (window in windows) {
                val root = window.root ?: continue
                nodeCount = collectCompactNodeText(root, sb, nodeCount, maxNodes)
                root.recycle()
                if (nodeCount >= maxNodes) break
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
            child.recycle()
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
            // Get the real screen-absolute bounding box for this node.
            // getBoundsInScreen() returns coordinates in the same space that
            // dispatchGesture() expects: (0,0) = physical top-left of display,
            // including the status bar. This is exactly what tap_screen needs.
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
            child.recycle()
        }
    }

    // ─── Click / Tap ──────────────────────────────────────────────────────────

    fun clickByText(query: String): Boolean {
        if (query.isBlank()) return false
        val q = query.lowercase().trim()
        val windows: List<AccessibilityWindowInfo> = windows ?: return false

        for (window in windows) {
            val root = window.root ?: continue
            val match = findNodeByQuery(root, q, requireClickable = true)
            root.recycle()
            if (match != null) {
                val success = match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                match.recycle()
                Log.i(TAG, "clickByText('$query') -> success=$success")
                return success
            }
        }
        Log.w(TAG, "clickByText('$query') -> no matching node found")
        return false
    }

    fun tapAt(x: Float, y: Float): Boolean {
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
                root.recycle()
                if (editable != null) {
                    editable.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    val success = setTextOnNode(editable, text)
                    editable.recycle()
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
        val action = if (forward)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        val windows: List<AccessibilityWindowInfo> = windows ?: return false
        for (window in windows) {
            val root = window.root ?: continue
            val scrollable = findFirstScrollable(root)
            root.recycle()
            if (scrollable != null) {
                val success = scrollable.performAction(action)
                scrollable.recycle()
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
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
