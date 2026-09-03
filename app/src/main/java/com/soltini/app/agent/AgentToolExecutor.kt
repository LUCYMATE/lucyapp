package com.soltini.app.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import com.soltini.app.automation.SoltiniNotificationListener
import com.soltini.app.core.ReminderReceiver
import com.soltini.app.services.ScreenCaptureManager
import com.soltini.app.termux.TermuxResultReceiver
import com.soltini.app.ui.UnlockActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import android.hardware.camera2.CameraManager
import android.media.AudioManager
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
            // ── Device Control & Screen Wake ──────────────────────────────
            "open_app"            -> openApp(args.optString("app_name", ""))
            "lock_device"         -> accessibilityAction { it.lockScreen() } success "Device locked"
            "wake_device"         -> wakeDevice()
            "unlock_and_wake"     -> unlockAndWake()
            "sleep_agent"         -> result("status", "sleep_requested")
            "press_back"          -> accessibilityAction { it.pressBack() } success "Back pressed"
            "press_home"          -> accessibilityAction { it.pressHome() } success "Home pressed"
            "press_recents"       -> accessibilityAction { it.pressRecents() } success "Recents opened"

            // ── Screen Automation & Perception ────────────────────────────
            "read_screen"         -> readScreen()
            "read_compact_screen" -> readCompactScreen(args.optInt("max_nodes", 400))
            "take_screenshot_vision" -> takeScreenshotVision()
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
            "make_phone_call"      -> makePhoneCall(args.optString("phone_number", ""))
            "send_sms"             -> sendSms(args.optString("phone_number", ""), args.optString("message", ""))

            // ── Declarative Macros & Automation ───────────────────────────
            "run_macro"            -> runMacro(args.optString("macro_name", ""), args.optJSONObject("params"))
            "get_available_macros" -> getAvailableMacros()

            // ── System & Device Tools ─────────────────────────────────────────
            "toggle_flashlight"    -> toggleFlashlight(args.optBoolean("on", true))
            "set_brightness"       -> setBrightness(args.optInt("level", 50))
            "set_volume"           -> setVolume(args.optInt("level", 50))
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

    private fun unlockAndWake(): JSONObject {
        val success = runBlocking {
            UnlockActivity.requestUnlockAndWake(context)
        }
        return if (success) {
            result("status", "unlocked_and_awake")
        } else {
            error("Could not unlock screen or keyguard")
        }
    }

    // ─── Screen Automation & Vision ───────────────────────────────────────────

    private fun readScreen(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        val text = svc.readScreen()
        return JSONObject().apply {
            put("status", "ok")
            put("screen_content", text)
        }
    }

    private fun readCompactScreen(maxNodes: Int): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")
        val compactState = svc.dumpCompactScreenState(maxNodes)
        return JSONObject().apply {
            put("status", "ok")
            put("compact_screen", compactState)
        }
    }

    private fun takeScreenshotVision(): JSONObject {
        val base64Jpeg = runBlocking {
            ScreenCaptureManager.captureScreenshotBase64(context, quality = 75)
        }
        return if (base64Jpeg != null) {
            JSONObject().apply {
                put("status", "ok")
                put("image_base64", base64Jpeg)
                put("format", "image/jpeg")
            }
        } else {
            error("Failed to capture screen image. Ensure MediaProjection permission is granted.")
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
    private fun makePhoneCall(number: String): JSONObject {
        if (number.isBlank()) return error("phone_number is required")
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            result("status", "calling", "number", number)
        } catch (e: Exception) {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
                result("status", "dialer_opened", "number", number)
            } catch (ex: Exception) {
                error("Failed to make phone call: ${ex.message}")
            }
        }
    }

    private fun sendSms(number: String, message: String): JSONObject {
        if (number.isBlank() || message.isBlank()) return error("phone_number and message are required")
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(number, null, message, null, null)
            result("status", "sms_sent", "number", number, "message", message)
        } catch (e: Exception) {
            error("Failed to send SMS: ${e.message}")
        }
    }

    // ─── Declarative Macros ───────────────────────────────────────────────────

    private fun runMacro(macroName: String, paramsObj: JSONObject?): JSONObject {
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
        val resultMessage = runBlocking {
            MacroRunner.executeMacro(context, macroName, paramMap, a11y, this@AgentToolExecutor)
        }

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

    private fun runTermuxCommand(executable: String, argsArray: JSONArray?): JSONObject {
        if (executable.isBlank()) return error("command is required")
        val argsList = mutableListOf<String>()
        if (argsArray != null) {
            for (i in 0 until argsArray.length()) {
                argsList.add(argsArray.getString(i))
            }
        }

        val res = runBlocking {
            TermuxResultReceiver.runTermuxCommand(
                context,
                executable,
                argsList.toTypedArray()
            )
        }

        return JSONObject().apply {
            put("status", if (res.exitCode == 0) "ok" else "failed")
            put("exit_code", res.exitCode)
            put("stdout", res.stdout)
            put("stderr", res.stderr)
            res.error?.let { put("error_message", it) }
        }
    }

    // ── System & Device Tools (Injected) ───────────────────────────────────

    private fun toggleFlashlight(on: Boolean): JSONObject {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
            result("status", "ok", "flashlight", if (on) "on" else "off")
        } catch (e: Exception) {
            error("Failed to toggle flashlight: ${e.message}")
        }
    }

    private fun setBrightness(level: Int): JSONObject {
        return try {
            if (android.provider.Settings.System.canWrite(context)) {
                val clamped = level.coerceIn(0, 255)
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    clamped
                )
                result("status", "ok", "brightness", clamped)
            } else {
                error("Missing WRITE_SETTINGS permission to adjust brightness.")
            }
        } catch (e: Exception) {
            error("Failed to set brightness: ${e.message}")
        }
    }

    private fun setVolume(level: Int): JSONObject {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val clamped = level.coerceIn(0, maxVolume)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, clamped, android.media.AudioManager.FLAG_SHOW_UI)
            result("status", "ok", "volume", clamped)
        } catch (e: Exception) {
            error("Failed to set volume: ${e.message}")
        }
    }

    private fun toggleWifi(on: Boolean): JSONObject {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = on
                result("status", "ok", "wifi", if (on) "on" else "off")
            } else {
                val intent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                result("status", "pending_user_action", "message", "Opened WiFi settings panel")
            }
        } catch (e: Exception) {
            error("Failed to toggle WiFi: ${e.message}")
        }
    }

    private fun toggleBluetooth(on: Boolean): JSONObject {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = bluetoothManager.adapter
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (on) {
                    @Suppress("DEPRECATION")
                    adapter?.enable()
                } else {
                    @Suppress("DEPRECATION")
                    adapter?.disable()
                }
                result("status", "ok", "bluetooth", if (on) "on" else "off")
            } else {
                error("Missing BLUETOOTH_CONNECT permission")
            }
        } catch (e: Exception) {
            error("Failed to toggle Bluetooth: ${e.message}")
        }
    }

    private fun getLocation(): JSONObject {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val provider = locationManager.getBestProvider(android.location.Criteria(), true)
                if (provider != null) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null) {
                        result("status", "ok", "latitude", location.latitude, "longitude", location.longitude)
                    } else {
                        error("Location not available right now.")
                    }
                } else {
                    error("No location provider available.")
                }
            } else {
                error("Missing ACCESS_FINE_LOCATION permission.")
            }
        } catch (e: Exception) {
            error("Failed to get location: ${e.message}")
        }
    }

    private fun answerCall(): JSONObject {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                result("status", "ok")
            } else {
                error("Missing ANSWER_PHONE_CALLS permission.")
            }
        } catch (e: Exception) {
            error("Failed to answer call: ${e.message}")
        }
    }

    private fun declineCall(): JSONObject {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                    result("status", "ok")
                } else {
                    error("Decline call requires Android 9+")
                }
            } else {
                error("Missing ANSWER_PHONE_CALLS permission.")
            }
        } catch (e: Exception) {
            error("Failed to decline call: ${e.message}")
        }
    }

    private fun readCallLog(limit: Int): JSONObject {
        return try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val cursor = context.contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    null, null, null, android.provider.CallLog.Calls.DATE + " DESC"
                )
                val calls = org.json.JSONArray()
                cursor?.use {
                    val numberIndex = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                    val typeIndex = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                    val dateIndex = it.getColumnIndex(android.provider.CallLog.Calls.DATE)
                    val nameIndex = it.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        val number = it.getString(numberIndex)
                        val type = it.getInt(typeIndex)
                        val date = it.getLong(dateIndex)
                        val name = it.getString(nameIndex)
                        val typeStr = when (type) {
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
                            else -> "Other"
                        }
                        calls.put(JSONObject().apply {
                            put("number", number)
                            put("name", name ?: "Unknown")
                            put("type", typeStr)
                            put("date", date)
                        })
                        count++
                    }
                }
                result("status", "ok", "calls", calls)
            } else {
                error("Missing READ_CALL_LOG permission.")
            }
        } catch (e: Exception) {
            error("Failed to read call log: ${e.message}")
        }
    }

    private fun readContacts(query: String): JSONObject {
        return try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val cursor = context.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$query%"),
                    null
                )
                val contacts = org.json.JSONArray()
                cursor?.use {
                    val nameIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext() && contacts.length() < 10) {
                        contacts.put(JSONObject().apply {
                            put("name", it.getString(nameIndex))
                            put("number", it.getString(numberIndex))
                        })
                    }
                }
                result("status", "ok", "contacts", contacts)
            } else {
                error("Missing READ_CONTACTS permission.")
            }
        } catch (e: Exception) {
            error("Failed to read contacts: ${e.message}")
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): JSONObject {
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            result("status", "ok", "message", "Alarm set for $hour:$minute")
        } catch (e: Exception) {
            error("Failed to set alarm: ${e.message}")
        }
    }

    private fun setDoNotDisturb(on: Boolean): JSONObject {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) {
                if (on) {
                    notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                result("status", "ok", "dnd", if (on) "on" else "off")
            } else {
                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                result("status", "pending_user_action", "message", "Opened DND access settings")
            }
        } catch (e: Exception) {
            error("Failed to set DND: ${e.message}")
        }
    }

    private fun openGoogleMaps(query: String): JSONObject {
        return try {
            val gmmIntentUri = android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(query)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            mapIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(mapIntent)
            result("status", "ok")
        } catch (e: Exception) {
            error("Failed to open maps: ${e.message}")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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



