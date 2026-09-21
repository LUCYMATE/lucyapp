package com.soltini.app.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import com.soltini.app.automation.SoltiniNotificationListener
import com.soltini.app.core.ReminderReceiver
import com.soltini.app.termux.TermuxResultReceiver
import com.soltini.app.ui.UnlockActivity
import com.soltini.app.util.AppLogger
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.location.LocationManager
import android.telecom.TelecomManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.AlarmClock
import android.app.NotificationManager
import android.location.Location

/**
 * AgentToolExecutor
 *
 * Central dispatcher for all Gemini & Autonomous Agent tool calls.
 * Implements all Maya device control, screen perception, notifications, macros,
 * vision, and system tools alongside Soltini's media/agent automators.
 */
class AgentToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AgentToolExecutor"

        val SUPPORTED_TOOLS = setOf(
            "open_app", "lock_device", "wake_device", "unlock_and_wake", "sleep_agent",
            "press_back", "press_home", "press_recents", "see_screen", "read_screen",
            "read_compact_screen", "take_screenshot", "take_screenshot_vision",
            "click_element", "tap_screen", "swipe_screen", "type_text", "clear_text",
            "scroll_down", "scroll_up", "read_notifications", "reply_to_notification",
            "make_phone_call", "send_sms", "run_macro", "get_available_macros",
            "toggle_flashlight", "set_brightness", "set_volume", "toggle_wifi",
            "toggle_bluetooth", "get_location", "answer_call", "decline_call",
            "read_call_log", "read_contacts", "set_alarm", "set_do_not_disturb",
            "open_google_maps", "schedule_reminder", "run_termux_command",
            "youtube_search", "youtube_open_video", "youtube_play_pause", "youtube_seek",
            "youtube_next", "youtube_previous", "youtube_like", "youtube_dislike",
            "youtube_subscribe", "youtube_skip_ad", "youtube_set_quality",
            "youtube_fullscreen", "youtube_captions", "youtube_mute", "read_youtube_screen",
            "whatsapp_send_message", "whatsapp_call", "next_reel", "previous_reel"
        )
    }

    @Volatile
    private var isTorchActive: Boolean = false

    init {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cm?.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    isTorchActive = enabled
                }
            }, null)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not register torch callback: ${e.message}")
        }
    }

    private val youtubeAutomator = YouTubeAutomator(context)
    private val whatsappAutomator = WhatsAppAutomator(context)
    private val reelsAutomator = ReelsAutomator(context)

    /**
     * Main dispatch function. Returns a JSONObject result sent back to Gemini
     * as a toolResponse so it can verbally confirm what happened.
     */
    suspend fun execute(toolName: String, args: JSONObject): JSONObject {
        Log.i(TAG, "Executing tool: $toolName | args: $args")
        return when (toolName) {
            // ── Device Control & Screen Wake ──────────────────────────────
            "open_app"            -> openApp(args.optString("app_name", ""))
            "lock_device"         -> accessibilityAction { it.lockScreen() } success "Device locked"
            "wake_device"         -> wakeDevice()
            "unlock_and_wake"     -> unlockAndWake()
            "sleep_agent"         -> result("status", "sleep_requested")
            "press_back"          -> accessibilityAction { it.pressBack() } success "Back pressed"
            "press_home"          -> accessibilityAction { it.pressHome() } success "Home pressed"
            "press_recents"       -> accessibilityAction { it.pressRecents() } success "Recents opened"

            // ── Screen Automation & Perception (Dual-Track Vision & Wireframe) ────
            "see_screen"          -> seeScreen(includeScreenshot = args.optBoolean("screenshot", true))
            "read_screen"         -> seeScreen(includeScreenshot = args.optBoolean("screenshot", false))
            "read_compact_screen" -> readCompactScreen(args.optInt("max_nodes", 400))
            "take_screenshot"     -> seeScreen(includeScreenshot = true)
            "take_screenshot_vision" -> seeScreen(includeScreenshot = true)
            "click_element"       -> clickElement(args.optString("text", ""))
            "tap_screen"          -> tapScreen(
                x = args.optDouble("x", -1.0).toFloat(),
                y = args.optDouble("y", -1.0).toFloat()
            )
            "swipe_screen"        -> swipeScreen(
                x1 = args.optDouble("x1", 0.0).toFloat(),
                y1 = args.optDouble("y1", 0.0).toFloat(),
                x2 = args.optDouble("x2", 0.0).toFloat(),
                y2 = args.optDouble("y2", 0.0).toFloat(),
                durationMs = args.optLong("duration_ms", 300)
            )
            "type_text"           -> typeText(args.optString("text", ""))
            "clear_text"          -> accessibilityAction { it.clearText() } success "Text cleared"
            "scroll_down"         -> accessibilityAction { it.scrollDown() } success "Scrolled down"
            "scroll_up"           -> accessibilityAction { it.scrollUp() } success "Scrolled up"

            // ── Notifications & Communications ───────────────────────────
            "read_notifications"   -> readNotifications(args.optString("package_name", ""))
            "reply_to_notification" -> replyToNotification(
                key = args.optString("notification_key", ""),
                message = args.optString("message", "")
            )
            "make_phone_call"      -> makePhoneCall(
                args.optString("phone_number").ifBlank { args.optString("contact_name", "") }
            )
            "send_sms"             -> sendSms(args.optString("phone_number", ""), args.optString("message", ""))

            // ── Declarative Macros & Automation ───────────────────────────
            "run_macro"            -> runMacro(args.optString("macro_name", ""), args.optJSONObject("params"))
            "get_available_macros" -> getAvailableMacros()

            // ── System & Device Tools ─────────────────────────────────────────
            "toggle_flashlight"    -> toggleFlashlight(if (args.has("on")) args.optBoolean("on") else null)
            "set_brightness"       -> setBrightness(if (args.has("level")) args.optInt("level") else null, args.optString("action").ifBlank { null })
            "set_volume"           -> setVolume(if (args.has("level")) args.optInt("level") else null, args.optString("action").ifBlank { null })
            "toggle_wifi"          -> toggleWifi(args.optBoolean("on", true))
            "toggle_bluetooth"     -> toggleBluetooth(args.optBoolean("on", true))
            "get_location"         -> getLocation()
            "answer_call"          -> answerCall()
            "decline_call"         -> declineCall()
            "read_call_log"        -> readCallLog(args.optInt("limit", 5))
            "read_contacts"        -> readContacts(args.optString("query", ""))
            "set_alarm"            -> setAlarm(args.optInt("hour", 8), args.optInt("minute", 0), args.optString("label", ""))
            "set_do_not_disturb"   -> setDoNotDisturb(args.optBoolean("on", true))
            "open_google_maps"     -> openGoogleMaps(args.optString("query", ""))

            // ── Reminders & Termux CLI ────────────────────────────────────
            "schedule_reminder"    -> scheduleReminder(
                message = args.optString("message", ""),
                delayMinutes = args.optInt("delay_minutes", 10)
            )
            "run_termux_command"   -> runTermuxCommand(
                executable = args.optString("command", ""),
                argsArray = args.optJSONArray("args")
            )

            // ── YouTube Automation ────────────────────────────────────────
            "youtube_search"       -> youtubeAutomator.search(args.optString("query", ""))
            "youtube_open_video"   -> youtubeAutomator.openVideo(args.optString("video_id", ""))
            "youtube_play_pause"   -> youtubeAutomator.playPause()
            "youtube_seek"         -> youtubeAutomator.seek(args.optInt("seconds", 10))
            "youtube_next"         -> youtubeAutomator.nextVideo()
            "youtube_previous"     -> youtubeAutomator.previousVideo()
            "youtube_like"         -> youtubeAutomator.likeVideo()
            "youtube_dislike"      -> youtubeAutomator.dislikeVideo()
            "youtube_subscribe"    -> youtubeAutomator.subscribe()
            "youtube_skip_ad"      -> youtubeAutomator.skipAd()
            "youtube_set_quality"  -> youtubeAutomator.setQuality(args.optString("quality", "1080p"))
            "youtube_fullscreen"   -> youtubeAutomator.toggleFullscreen()
            "youtube_captions"     -> youtubeAutomator.toggleCaptions()
            "youtube_mute"         -> youtubeAutomator.toggleMute()
            "read_youtube_screen"  -> youtubeAutomator.readYouTubeScreen()

            // ── WhatsApp & Reels Automation ───────────────────────────────
            "whatsapp_send_message" -> whatsappAutomator.sendMessage(
                args.optString("contact_name", ""),
                args.optString("message", "")
            )
            "whatsapp_call"        -> whatsappAutomator.makeCall(
                args.optString("contact_name", ""),
                args.optBoolean("is_video", false)
            )
            "next_reel"            -> reelsAutomator.nextReel()
            "previous_reel"        -> reelsAutomator.previousReel()

            else -> error("Unknown tool: $toolName")
        }
    }

    // ─── Device Control ───────────────────────────────────────────────────────

    fun openApp(appName: String): JSONObject {
        if (appName.isBlank()) return error("App name is required to open an app.")

        val pm: PackageManager = context.packageManager
        val query = appName.lowercase().trim()

        // 1. Direct system intent aliases for standard Android utilities
        when (query) {
            "camera" -> {
                try {
                    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return result("status", "success", "message", "Opened Camera.", "app", "Camera")
                } catch (e: Exception) {
                    // Fallthrough to package search
                }
            }
            "settings" -> {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return result("status", "success", "message", "Opened Settings.", "app", "Settings")
                } catch (e: Exception) {
                    // Fallthrough
                }
            }
            "clock", "alarm" -> {
                try {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return result("status", "success", "message", "Opened Clock.", "app", "Clock")
                } catch (e: Exception) {
                    // Fallthrough
                }
            }
            "dialer", "phone" -> {
                try {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return result("status", "success", "message", "Opened Phone Dialer.", "app", "Phone")
                } catch (e: Exception) {
                    // Fallthrough
                }
            }
        }

        // 2. Query actual launchable apps from the app drawer
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        // Try exact match first
        val exactMatch = resolveInfos.firstOrNull {
            it.loadLabel(pm).toString().trim().equals(query, ignoreCase = true)
        }
        // Then prefix or word match
        val partialMatch = exactMatch ?: resolveInfos.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(query) || query.contains(label) || it.activityInfo.packageName.lowercase().contains(query)
        }

        if (partialMatch != null) {
            val pkg = partialMatch.activityInfo.packageName
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val appLabel = partialMatch.loadLabel(pm).toString()
                return result("status", "success", "message", "Opened $appLabel.", "app", appLabel, "package", pkg)
            }
        }

        // 3. Fallback to installed applications (including system apps with launch intents)
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = installedApps.firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            (label.contains(query) || query.contains(label) || it.packageName.lowercase().contains(query)) &&
                pm.getLaunchIntentForPackage(it.packageName) != null
        }

        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val label = pm.getApplicationLabel(match).toString()
                return result("status", "success", "message", "Opened $label.", "app", label, "package", match.packageName)
            }
        }

        return error("I couldn't find an app named '$appName' on your device. Please check if it is installed.")
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
            result("status", "success", "message", "Screen woke up.")
        } catch (e: Exception) {
            error("Failed to wake device: ${e.message ?: "Unknown error"}")
        }
    }

    private suspend fun unlockAndWake(): JSONObject {
        val success = UnlockActivity.requestUnlockAndWake(context)
        return if (success) {
            result("status", "success", "message", "Device unlocked and screen woke up.")
        } else {
            error("Could not unlock screen or keyguard. Please unlock the device manually.")
        }
    }

    // ─── Screen Automation & Vision (Dual-Track Perception Engine) ───────────

    private suspend fun seeScreen(includeScreenshot: Boolean = true): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
        val wireframe = svc?.readScreen()

        var screenshotBase64: String? = null
        if (includeScreenshot) {
            // Direct AccessibilityService screenshot (Android 11+, zero popups, instant)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && svc != null) {
                try {
                    val capturedBitmap = withTimeoutOrNull(2500L) {
                        suspendCancellableCoroutine<Bitmap?> { cont ->
                            svc.takeScreenshotDirectly { bmp ->
                                if (cont.isActive) cont.resume(bmp)
                            }
                        }
                    }
                    if (capturedBitmap != null) {
                        val scaledBitmap = scaleBitmapDown(capturedBitmap, maxDimension = 1024)
                        screenshotBase64 = bitmapToBase64Jpeg(scaledBitmap)
                        if (scaledBitmap != capturedBitmap) {
                            capturedBitmap.recycle()
                        }
                        scaledBitmap.recycle()
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Direct a11y screenshot failed: ${e.message}")
                }
            }
        }

        if (wireframe == null && screenshotBase64 == null) {
            return error("Cannot inspect screen: Soltini Accessibility Service is not active. Please enable Soltini in Settings > Accessibility.")
        }

        return JSONObject().apply {
            put("status", "success")
            put("message", "Screen inspected successfully.")
            put("screen_wireframe", wireframe ?: "Visual screenshot captured")
            put("screen_content", wireframe ?: "Visual screenshot captured")
            if (screenshotBase64 != null) {
                put("image_base64", screenshotBase64)
                put("format", "image/jpeg")
            }
        }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / Math.max(width, height).toFloat()
        val targetWidth = (width * scale).toInt()
        val targetHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 75): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun readCompactScreen(maxNodes: Int): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Cannot read screen: Soltini Accessibility Service is not active. Please enable Soltini in Settings > Accessibility.")
        val compactState = svc.dumpCompactScreenState(maxNodes)
        return JSONObject().apply {
            put("status", "success")
            put("message", "Screen hierarchy read successfully.")
            put("compact_screen", compactState)
        }
    }

    private fun clickElement(text: String): JSONObject {
        if (text.isBlank()) return error("Element text or label is required to click.")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Cannot click '$text' because Soltini Accessibility Service is disabled. Please enable it in Settings > Accessibility.")
        if (svc.isUserInteractingManually()) {
            return result("status", "user_active_backoff", "message", "User is currently interacting manually with the screen. Action paused to prevent disruption.")
        }
        return if (svc.clickByText(text)) {
            result("status", "success", "message", "Clicked on '$text'.", "element", text)
        } else {
            error("No clickable element matching '$text' was found on the screen.")
        }
    }

    private fun tapScreen(x: Float, y: Float): JSONObject {
        if (x < 0 || y < 0) return error("Screen coordinates (x, y) are required to tap.")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Cannot tap screen because Soltini Accessibility Service is disabled. Please enable it in Settings > Accessibility.")
        if (svc.isUserInteractingManually()) {
            return result("status", "user_active_backoff", "message", "User is currently interacting manually with the screen. Action paused to prevent disruption.")
        }
        return if (svc.tapAt(x, y)) {
            result("status", "success", "message", "Tapped at coordinates ($x, $y).", "x", x.toString(), "y", y.toString())
        } else {
            error("Tap gesture failed. Please verify Accessibility Service permissions.")
        }
    }

    private fun swipeScreen(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Cannot swipe screen because Soltini Accessibility Service is disabled. Please enable it in Settings > Accessibility.")
        if (svc.isUserInteractingManually()) {
            return result("status", "user_active_backoff", "message", "User is currently interacting manually with the screen. Action paused to prevent disruption.")
        }
        return if (svc.swipe(x1, y1, x2, y2, durationMs)) {
            result("status", "success", "message", "Swiped screen successfully.")
        } else {
            error("Swipe gesture failed. Please verify Accessibility Service permissions.")
        }
    }

    private fun typeText(text: String): JSONObject {
        if (text.isBlank()) return error("Text to type is required.")
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Cannot type text because Soltini Accessibility Service is disabled. Please enable it in Settings > Accessibility.")
        if (svc.isUserInteractingManually()) {
            return result("status", "user_active_backoff", "message", "User is currently interacting manually with the screen. Action paused to prevent disruption.")
        }
        return if (svc.typeText(text)) {
            result("status", "success", "message", "Typed '$text' into the focused field.", "text", text)
        } else {
            error("No editable text field is currently focused. Please tap an input field first.")
        }
    }

    // ─── Notifications & Communications ───────────────────────────────────────

    private fun readNotifications(packageName: String): JSONObject {
        val list = if (packageName.isNotBlank()) {
            SoltiniNotificationListener.getNotificationsForPackage(packageName)
        } else {
            SoltiniNotificationListener.getRecentNotifications(15)
        }

        val array = JSONArray()
        for (item in list) {
            array.put(JSONObject().apply {
                put("key", item.key)
                put("app", item.appName)
                put("package", item.packageName)
                put("sender", item.title)
                put("message", item.text)
                put("is_call", item.isCall)
                put("can_reply", item.replyAction != null)
            })
        }

        return JSONObject().apply {
            put("status", "ok")
            put("count", list.size)
            put("notifications", array)
        }
    }

    private fun replyToNotification(key: String, message: String): JSONObject {
        if (key.isBlank() || message.isBlank()) return error("notification_key and message are required")
        val ok = SoltiniNotificationListener.replyToNotification(context, key, message)
        return if (ok) {
            result("status", "replied", "message", message)
        } else {
            error("Failed to reply to notification: key not found or no inline reply capability")
        }
    }

    @SuppressLint("MissingPermission")
    private fun makePhoneCall(target: String): JSONObject {
        if (target.isBlank()) return error("phone_number or contact_name is required")
        var numberToCall = target.trim()

        // If target contains letters (e.g. "Mom", "Dad", "John"), resolve contact name via phonebook
        if (target.any { it.isLetter() }) {
            val contactsResult = readContacts(target)
            val contactsArr = contactsResult.optJSONArray("contacts")
            if (contactsArr != null && contactsArr.length() > 0) {
                val firstMatch = contactsArr.getJSONObject(0)
                numberToCall = firstMatch.optString("number", target)
                AppLogger.i(TAG, "Resolved contact '$target' to phone number: $numberToCall")
            } else {
                AppLogger.w(TAG, "No contact match found for '$target', attempting to dial query directly")
            }
        }

        val cleanNumber = numberToCall.replace(Regex("[^0-9+*#]"), "")
        if (cleanNumber.isBlank() && numberToCall.all { !it.isDigit() }) {
            return error("Could not find a valid phone number for '$target'.")
        }

        return try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(numberToCall)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                result("status", "success", "message", "Calling $target now.", "number", numberToCall)
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(numberToCall)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
                result("status", "success", "message", "Opened dialer for $target because direct call permission is not granted.", "number", numberToCall)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to make phone call", e)
            error("Failed to place call to '$target': ${e.message ?: "Unknown telephony error"}")
        }
    }

    private fun sendSms(number: String, message: String): JSONObject {
        if (number.isBlank()) return error("Recipient phone number or contact is required to send an SMS.")
        if (message.isBlank()) return error("Message text cannot be empty.")

        var targetNumber = number.trim()
        if (targetNumber.any { it.isLetter() }) {
            val contactsResult = readContacts(targetNumber)
            val contactsArr = contactsResult.optJSONArray("contacts")
            if (contactsArr != null && contactsArr.length() > 0) {
                targetNumber = contactsArr.getJSONObject(0).optString("number", targetNumber)
            }
        }

        return try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(targetNumber, null, message, null, null)
                result("status", "success", "message", "SMS sent successfully to $number.", "number", targetNumber)
            } else {
                val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(targetNumber)}")).apply {
                    putExtra("sms_body", message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(smsIntent)
                result("status", "success", "message", "Opened messaging app to send your SMS because SMS permission is not granted.", "number", targetNumber)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to send SMS", e)
            error("Failed to send SMS: ${e.message ?: "Unknown SMS error"}")
        }
    }

    // ─── Declarative Macros ───────────────────────────────────────────────────

    private suspend fun runMacro(macroName: String, paramsObj: JSONObject?): JSONObject {
        if (macroName.isBlank()) return error("macro_name is required")
        val paramMap = mutableMapOf<String, String>()
        if (paramsObj != null) {
            val keys = paramsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                paramMap[k] = paramsObj.optString(k, "")
            }
        }

        val a11y = SoltiniAccessibilityService.getInstance()
        val resultMessage = MacroRunner.executeMacro(context, macroName, paramMap, a11y, this@AgentToolExecutor)

        return JSONObject().apply {
            put("status", if (resultMessage.startsWith("Error")) "error" else "ok")
            put("message", resultMessage)
        }
    }

    private fun getAvailableMacros(): JSONObject {
        if (MacroRunner.getAvailableMacros().isEmpty()) {
            MacroRunner.loadMacros(context)
        }
        val macros = MacroRunner.getAvailableMacros()
        return JSONObject().apply {
            put("status", "ok")
            put("macros", JSONArray(macros))
        }
    }

    // ─── Reminders & Termux ───────────────────────────────────────────────────

    private fun scheduleReminder(message: String, delayMinutes: Int): JSONObject {
        if (message.isBlank()) return error("message is required")
        val triggerAt = System.currentTimeMillis() + (delayMinutes * 60 * 1000L)
        val success = ReminderReceiver.scheduleReminder(context, triggerAt, message)
        return if (success) {
            result("status", "scheduled", "message", message, "in_minutes", delayMinutes.toString())
        } else {
            error("Failed to schedule reminder with AlarmManager")
        }
    }

    private suspend fun runTermuxCommand(executable: String, argsArray: JSONArray?): JSONObject {
        if (executable.isBlank()) return error("command is required")
        val argsList = mutableListOf<String>()
        if (argsArray != null) {
            for (i in 0 until argsArray.length()) {
                argsList.add(argsArray.getString(i))
            }
        }

        val res = TermuxResultReceiver.runTermuxCommand(
            context,
            executable,
            argsList.toTypedArray()
        )

        return JSONObject().apply {
            put("status", if (res.exitCode == 0) "ok" else "failed")
            put("exit_code", res.exitCode)
            put("stdout", res.stdout)
            put("stderr", res.stderr)
            res.error?.let { put("error_message", it) }
        }
    }

    // ── System & Device Tools (Injected) ───────────────────────────────────

    private fun toggleFlashlight(on: Boolean? = null): JSONObject {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return error("Camera service is not available on this device.")

            val ids = cameraManager.cameraIdList
            if (ids.isEmpty()) {
                return error("No camera hardware found on this device.")
            }

            // Find a camera with flash, prioritizing back-facing camera
            var torchCameraId: String? = null
            for (id in ids) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (flashAvailable && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchCameraId = id
                        break
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error checking camera $id: ${e.message}")
                }
            }

            // If no back camera with flash found, check any camera with flash
            if (torchCameraId == null) {
                for (id in ids) {
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                            torchCameraId = id
                            break
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }

            if (torchCameraId == null) {
                return error("No flashlight or torch hardware unit was found on this device.")
            }

            val targetState = on ?: !isTorchActive
            cameraManager.setTorchMode(torchCameraId, targetState)
            isTorchActive = targetState

            val stateText = if (targetState) "turned on" else "turned off"
            result("status", "success", "message", "The flashlight has been $stateText.", "flashlight", if (targetState) "on" else "off")
        } catch (e: android.hardware.camera2.CameraAccessException) {
            AppLogger.e(TAG, "CameraAccessException in toggleFlashlight", e)
            error("Cannot control flashlight: Camera is currently in use by another app or camera access is restricted.")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "SecurityException in toggleFlashlight", e)
            error("Camera permission is required to control the flashlight. Please grant Camera permission in Settings.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception in toggleFlashlight", e)
            error("Failed to toggle flashlight: ${e.message ?: "Unknown camera error"}")
        }
    }

    private fun setBrightness(level: Int? = null, action: String? = null): JSONObject {
        return try {
            if (!Settings.System.canWrite(context)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // ignore
                }
                return error("Cannot adjust brightness because 'Modify system settings' permission is not granted. I opened the permission screen for you to allow it.")
            }

            val current = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) {
                128
            }

            val targetValue = when {
                action == "max" -> 255
                action == "min" -> 5
                action == "up" -> (current + 35).coerceAtMost(255)
                action == "down" -> (current - 35).coerceAtLeast(5)
                level != null -> {
                    if (level in 0..100) {
                        ((level / 100.0) * 255).toInt().coerceIn(5, 255)
                    } else {
                        level.coerceIn(5, 255)
                    }
                }
                else -> current
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                targetValue
            )
            val percent = (targetValue * 100) / 255
            result("status", "success", "message", "Screen brightness set to $percent%.", "brightness", targetValue, "percent", percent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set brightness", e)
            error("Failed to set brightness: ${e.message ?: "Unknown error"}")
        }
    }

    private fun setVolume(level: Int? = null, action: String? = null): JSONObject {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return error("Audio service is not available on this device.")

            val stream = AudioManager.STREAM_MUSIC
            val maxVolume = audioManager.getStreamMaxVolume(stream)
            val currentVolume = audioManager.getStreamVolume(stream)

            val newVolume = when {
                action == "mute" -> 0
                action == "unmute" -> (maxVolume * 0.5f).toInt().coerceAtLeast(1)
                action == "up" -> (currentVolume + 2).coerceAtMost(maxVolume)
                action == "down" -> (currentVolume - 2).coerceAtLeast(0)
                level != null -> {
                    if (level in 0..100 && level > maxVolume) {
                        ((level / 100.0) * maxVolume).toInt().coerceIn(0, maxVolume)
                    } else {
                        level.coerceIn(0, maxVolume)
                    }
                }
                else -> currentVolume
            }

            audioManager.setStreamVolume(stream, newVolume, AudioManager.FLAG_SHOW_UI)
            val percent = if (maxVolume > 0) (newVolume * 100) / maxVolume else 0
            result(
                "status", "success",
                "message", if (newVolume == 0) "Media volume is muted." else "Media volume set to $percent%.",
                "volume", newVolume,
                "percent", percent
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set volume", e)
            error("Failed to set volume: ${e.message ?: "Unknown audio error"}")
        }
    }

    private fun toggleWifi(on: Boolean): JSONObject {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return error("Wi-Fi service is not available on this device.")

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = on
                result("status", "success", "message", if (on) "Wi-Fi turned on." else "Wi-Fi turned off.", "wifi", if (on) "on" else "off")
            } else {
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                result("status", "success", "message", "Opened the Wi-Fi panel for you to toggle it.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to toggle WiFi", e)
            error("Failed to control Wi-Fi: ${e.message ?: "Unknown error"}")
        }
    }

    private fun toggleBluetooth(on: Boolean): JSONObject {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                ?: return error("Bluetooth is not supported on this device.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return error("Bluetooth permission (Nearby Devices) is not granted. Please grant Nearby Devices permission in Settings.")
                }
            }

            @Suppress("DEPRECATION")
            val success = if (on) adapter.enable() else adapter.disable()
            if (success) {
                result("status", "success", "message", if (on) "Bluetooth turned on." else "Bluetooth turned off.", "bluetooth", if (on) "on" else "off")
            } else {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                result("status", "pending_user_action", "message", "Could not toggle Bluetooth directly. Opened Bluetooth settings.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to toggle Bluetooth", e)
            error("Failed to control Bluetooth: ${e.message ?: "Unknown error"}")
        }
    }

    private fun getLocation(): JSONObject {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return error("Location service is not available on this device.")

            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                return error("Location permission is not granted. Please grant Location permission in Settings.")
            }

            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            var bestLocation: Location? = null
            for (p in providers) {
                try {
                    val loc = locationManager.getLastKnownLocation(p)
                    if (loc != null && (bestLocation == null || loc.accuracy < bestLocation.accuracy)) {
                        bestLocation = loc
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            if (bestLocation != null) {
                result(
                    "status", "success",
                    "latitude", bestLocation.latitude,
                    "longitude", bestLocation.longitude,
                    "accuracy_meters", bestLocation.accuracy,
                    "message", "Current location: ${bestLocation.latitude}, ${bestLocation.longitude} (accuracy: ${bestLocation.accuracy.toInt()}m)."
                )
            } else {
                error("Could not determine current location. Please verify that Location/GPS is turned on in Quick Settings.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get location", e)
            error("Failed to get location: ${e.message ?: "Unknown location error"}")
        }
    }

    private fun answerCall(): JSONObject {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return error("Telecom service is not available on this device.")

            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                result("status", "success", "message", "Answered phone call.")
            } else {
                error("Missing 'Answer Phone Calls' permission in Settings.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to answer call", e)
            error("Failed to answer call: ${e.message ?: "Unknown error"}")
        }
    }

    private fun declineCall(): JSONObject {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return error("Telecom service is not available on this device.")

            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                    result("status", "success", "message", "Declined phone call.")
                } else {
                    error("Declining calls requires Android 9 (Pie) or higher.")
                }
            } else {
                error("Missing 'Answer Phone Calls' permission in Settings.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to decline call", e)
            error("Failed to decline call: ${e.message ?: "Unknown error"}")
        }
    }

    private fun readCallLog(limit: Int): JSONObject {
        return try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    null, null, null, CallLog.Calls.DATE + " DESC"
                )
                val calls = JSONArray()
                cursor?.use {
                    val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                    val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
                    val nameIndex = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    while (it.moveToNext() && calls.length() < limit) {
                        val number = it.getString(numberIndex)
                        val type = it.getInt(typeIndex)
                        val date = it.getLong(dateIndex)
                        val name = it.getString(nameIndex)
                        val typeStr = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            else -> "Other"
                        }
                        calls.put(JSONObject().apply {
                            put("number", number)
                            put("name", name ?: "Unknown")
                            put("type", typeStr)
                            put("date", date)
                        })
                    }
                }
                result("status", "success", "message", "Retrieved ${calls.length()} recent call log entries.", "calls", calls)
            } else {
                error("Missing READ_CALL_LOG permission. Please grant Call Logs permission in Settings.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read call log", e)
            error("Failed to read call log: ${e.message ?: "Unknown error"}")
        }
    }

    private fun readContacts(query: String): JSONObject {
        return try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$query%"),
                    null
                )
                val contacts = JSONArray()
                cursor?.use {
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext() && contacts.length() < 10) {
                        contacts.put(JSONObject().apply {
                            put("name", it.getString(nameIndex))
                            put("number", it.getString(numberIndex))
                        })
                    }
                }
                result("status", "success", "message", "Found ${contacts.length()} contacts matching '$query'.", "contacts", contacts)
            } else {
                error("Missing READ_CONTACTS permission. Please grant Contacts permission in Settings.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read contacts", e)
            error("Failed to read contacts: ${e.message ?: "Unknown error"}")
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): JSONObject {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            result("status", "success", "message", "Alarm set for ${String.format("%02d:%02d", hour, minute)}${if (label.isNotBlank()) " ($label)" else ""}.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set alarm", e)
            error("Failed to set alarm: ${e.message ?: "Unknown error"}")
        }
    }

    private fun setDoNotDisturb(on: Boolean): JSONObject {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return error("Notification service is not available on this device.")

            if (notificationManager.isNotificationPolicyAccessGranted) {
                if (on) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                result("status", "success", "message", if (on) "Do Not Disturb turned on." else "Do Not Disturb turned off.", "dnd", if (on) "on" else "off")
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                result("status", "pending_user_action", "message", "Opened Do Not Disturb access settings for you to grant permission.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set DND", e)
            error("Failed to set Do Not Disturb: ${e.message ?: "Unknown error"}")
        }
    }

    private fun openGoogleMaps(query: String): JSONObject {
        return try {
            val gmmIntentUri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(mapIntent)
            result("status", "success", "message", "Opened Google Maps for '$query'.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to open maps", e)
            error("Failed to open Google Maps: ${e.message ?: "Unknown error"}")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun accessibilityAction(action: (SoltiniAccessibilityService) -> Boolean): AccessibilityResult {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return AccessibilityResult.NoService
        if (svc.isUserInteractingManually()) {
            return AccessibilityResult.UserInteracting
        }
        val ok = action(svc)
        return if (ok) AccessibilityResult.Success else AccessibilityResult.Failed
    }

    private sealed class AccessibilityResult {
        object NoService : AccessibilityResult()
        object UserInteracting : AccessibilityResult()
        object Success : AccessibilityResult()
        object Failed : AccessibilityResult()
    }

    private infix fun AccessibilityResult.success(successMsg: String): JSONObject = when (this) {
        is AccessibilityResult.NoService -> error("LUCY Accessibility Service is disabled. Please enable LUCY in Settings > Accessibility to allow screen control.")
        is AccessibilityResult.UserInteracting -> JSONObject().apply {
            put("status", "user_active_backoff")
            put("message", "User is currently touching the screen. Action paused to avoid conflict.")
        }
        is AccessibilityResult.Success   -> JSONObject().apply {
            put("status", "success")
            put("message", successMsg)
        }
        is AccessibilityResult.Failed    -> error("Accessibility action failed. Please make sure the device is active.")
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        put("status", "success")
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
        if (!has("message") && has("status")) {
            put("message", optString("status"))
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply {
        put("status", "error")
        put("error", msg)
        put("message", msg)
    }
}



