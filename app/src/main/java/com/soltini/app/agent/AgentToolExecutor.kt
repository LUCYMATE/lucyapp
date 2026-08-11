package com.soltini.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * AgentToolExecutor
 *
 * Central dispatcher for all Gemini tool calls. Receives (toolName, args) from
 * BackgroundVoiceService.onToolCall() and routes to the appropriate Android API.
 *
 * ┌── Device Control ─────────────────────────────────────────────────────────┐
 * │  open_app(app_name)           — launch any installed app by name         │
 * │  lock_device()                — lock screen via Accessibility             │
 * │  wake_device()                — turn screen on via WakeLock              │
 * │  sleep_agent()                — handled by BackgroundVoiceService        │
 * │  press_back()                 — back button                              │
 * │  press_home()                 — home button                              │
 * │  press_recents()              — recent apps                              │
 * └───────────────────────────────────────────────────────────────────────────┘
 * ┌── Screen Automation ───────────────────────────────────────────────────────┐
 * │  read_screen()                — returns all visible UI text              │
 * │  click_element(text)          — click the UI element with matching text  │
 * │  tap_screen(x, y)             — tap at pixel coordinates                 │
 * │  swipe_screen(x1,y1,x2,y2)   — swipe/drag gesture                       │
 * │  type_text(text)              — type into focused input field            │
 * │  clear_text()                 — clear the focused input field            │
 * │  scroll_down()                — scroll the main view down                │
 * │  scroll_up()                  — scroll the main view up                  │
 * └───────────────────────────────────────────────────────────────────────────┘
 * ┌── YouTube Automation ──────────────────────────────────────────────────────┐
 * │  youtube_search, youtube_open_video, youtube_play_pause, youtube_seek,   │
 * │  youtube_next, youtube_previous, youtube_like, youtube_dislike,          │
 * │  youtube_subscribe, youtube_skip_ad, youtube_set_quality,                │
 * │  youtube_fullscreen, youtube_mute, youtube_captions, read_youtube_screen │
 * └───────────────────────────────────────────────────────────────────────────┘
 * ┌── WhatsApp & Reels Automation ─────────────────────────────────────────────┐
 * │  whatsapp_send_message, whatsapp_call, next_reel, previous_reel          │
 * └───────────────────────────────────────────────────────────────────────────┘
 */
class AgentToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AgentToolExecutor"
    }

    private val youtubeAutomator = YouTubeAutomator(context)
    private val whatsappAutomator = WhatsAppAutomator(context)
    private val reelsAutomator = ReelsAutomator(context)

    /**
     * Main dispatch function. Returns a JSONObject result sent back to Gemini
     * as a toolResponse so it can verbally confirm what happened.
     */
    fun execute(toolName: String, args: JSONObject): JSONObject {
        Log.i(TAG, "Executing tool: $toolName | args: $args")
        return when (toolName) {
            // ── Device Control ────────────────────────────────────────────
            "open_app"      -> openApp(args.optString("app_name", ""))
            "lock_device"   -> accessibilityAction { it.lockScreen() } success "Device locked"
            "wake_device"   -> wakeDevice()
            "sleep_agent"   -> result("status", "sleep_requested") // handled in BackgroundVoiceService
            "press_back"    -> accessibilityAction { it.pressBack() } success "Back pressed"
            "press_home"    -> accessibilityAction { it.pressHome() } success "Home pressed"
            "press_recents" -> accessibilityAction { it.pressRecents() } success "Recents opened"

            // ── Screen Automation ─────────────────────────────────────────
            "read_screen"   -> readScreen()
            "click_element" -> clickElement(args.optString("text", ""))
            "tap_screen"    -> tapScreen(
                x = args.optDouble("x", -1.0).toFloat(),
                y = args.optDouble("y", -1.0).toFloat()
            )
            "swipe_screen"  -> swipeScreen(
                x1 = args.optDouble("x1", 0.0).toFloat(),
                y1 = args.optDouble("y1", 0.0).toFloat(),
                x2 = args.optDouble("x2", 0.0).toFloat(),
                y2 = args.optDouble("y2", 0.0).toFloat(),
                durationMs = args.optLong("duration_ms", 300)
            )
            "type_text"     -> typeText(args.optString("text", ""))
            "clear_text"    -> accessibilityAction { it.clearText() } success "Text cleared"
            "scroll_down"   -> accessibilityAction { it.scrollDown() } success "Scrolled down"
            "scroll_up"     -> accessibilityAction { it.scrollUp() } success "Scrolled up"

            // ── YouTube Automation ────────────────────────────────────────
            "youtube_search"      -> youtubeAutomator.search(args.optString("query", ""))
            "youtube_open_video"  -> youtubeAutomator.openVideo(args.optString("video_id", ""))
            "youtube_play_pause"  -> youtubeAutomator.playPause()
            "youtube_seek"        -> youtubeAutomator.seek(args.optInt("seconds", 10))
            "youtube_next"        -> youtubeAutomator.nextVideo()
            "youtube_previous"    -> youtubeAutomator.previousVideo()
            "youtube_like"        -> youtubeAutomator.likeVideo()
            "youtube_dislike"     -> youtubeAutomator.dislikeVideo()
            "youtube_subscribe"   -> youtubeAutomator.subscribe()
            "youtube_skip_ad"     -> youtubeAutomator.skipAd()
            "youtube_set_quality" -> youtubeAutomator.setQuality(args.optString("quality", "1080p"))
            "youtube_fullscreen"  -> youtubeAutomator.toggleFullscreen()
            "youtube_captions"    -> youtubeAutomator.toggleCaptions()
            "youtube_mute"        -> youtubeAutomator.toggleMute()
            "read_youtube_screen" -> youtubeAutomator.readYouTubeScreen()

            // ── WhatsApp Automation ───────────────────────────────────────
            "whatsapp_send_message" -> whatsappAutomator.sendMessage(args.optString("contact_name", ""), args.optString("message", ""))
            "whatsapp_call"         -> whatsappAutomator.makeCall(args.optString("contact_name", ""), args.optBoolean("is_video", false))

            // ── Reels Automation ──────────────────────────────────────────
            "next_reel"             -> reelsAutomator.nextReel()
            "previous_reel"         -> reelsAutomator.previousReel()

            else -> error("Unknown tool: $toolName")
        }
    }

    // ─── Device Control ───────────────────────────────────────────────────────

    private fun openApp(appName: String): JSONObject {
        if (appName.isBlank()) return error("app_name is required")

        val pm: PackageManager = context.packageManager
        val query = appName.lowercase().trim()
        val match = pm.getInstalledApplications(PackageManager.GET_META_DATA).firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            label.contains(query) || query.contains(label)
        } ?: return error("No app found matching: $appName")

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            ?: return error("App found (${match.packageName}) but cannot be launched")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return result("status", "opened", "app", pm.getApplicationLabel(match).toString())
    }

    private fun wakeDevice(): JSONObject {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Soltini::WakeDevice"
            )
            wl.acquire(3000L)
            wl.release()
            result("status", "screen_on")
        } catch (e: Exception) {
            error(e.message ?: "Failed to wake device")
        }
    }

    // ─── Screen Automation ────────────────────────────────────────────────────

    private fun readScreen(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        val text = svc.readScreen()
        return JSONObject().apply {
            put("status", "ok")
            put("screen_content", text)
        }
    }

    private fun clickElement(text: String): JSONObject {
        if (text.isBlank()) return error("text is required")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.clickByText(text)) {
            result("status", "clicked", "element", text)
        } else {
            error("No clickable element found matching: $text")
        }
    }

    private fun tapScreen(x: Float, y: Float): JSONObject {
        if (x < 0 || y < 0) return error("x and y coordinates are required")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.tapAt(x, y)) {
            result("status", "tapped", "x", x.toString(), "y", y.toString())
        } else {
            error("Tap gesture failed — requires Android 7+ and gesture permission")
        }
    }

    private fun swipeScreen(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.swipe(x1, y1, x2, y2, durationMs)) {
            result("status", "swiped")
        } else {
            error("Swipe gesture failed")
        }
    }

    private fun typeText(text: String): JSONObject {
        if (text.isBlank()) return error("text is required")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        return if (svc.typeText(text)) {
            result("status", "typed", "text", text)
        } else {
            error("No editable text field found on screen. Please tap a text field first.")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Runs a lambda against the active AccessibilityService instance.
     * Returns an error JSONObject if the service is not running.
     */
    private fun accessibilityAction(action: (SoltiniAccessibilityService) -> Boolean): AccessibilityResult {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return AccessibilityResult.NoService
        val ok = action(svc)
        return if (ok) AccessibilityResult.Success else AccessibilityResult.Failed
    }

    private sealed class AccessibilityResult {
        object NoService : AccessibilityResult()
        object Success : AccessibilityResult()
        object Failed : AccessibilityResult()
    }

    private infix fun AccessibilityResult.success(successMsg: String): JSONObject = when (this) {
        is AccessibilityResult.NoService -> error("Accessibility Service not active. Enable it in Settings → Accessibility → Soltini")
        is AccessibilityResult.Success   -> result("status", successMsg)
        is AccessibilityResult.Failed    -> error("Action failed")
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply { put("error", msg) }
}
