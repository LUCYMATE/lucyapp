package com.soltini.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

/**
 * WhatsAppAutomator
 *
 * Automates WhatsApp using AccessibilityService UI automation.
 *
 * Tools:
 *  - whatsapp_send_message
 *  - whatsapp_call
 *  - whatsapp_video_call
 */
class WhatsAppAutomator(private val context: Context) {

    companion object {
        private const val TAG = "WhatsAppAutomator"
        private const val WHATSAPP_PKG = "com.whatsapp"
    }

    /**
     * Sends a message to a contact by automating the UI.
     */
    fun sendMessage(contactName: String, message: String): JSONObject {
        if (contactName.isBlank() || message.isBlank()) {
            return error("contact_name and message are required")
        }

        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        if (!launchWhatsApp()) return error("WhatsApp not installed")
        Thread.sleep(1500) // Wait for app to open

        // 1. Click Search icon
        if (!svc.clickByText("Search")) {
            return error("Could not find WhatsApp search button")
        }
        Thread.sleep(500)

        // 2. Type contact name
        if (!svc.typeText(contactName)) {
            return error("Could not type in search field")
        }
        Thread.sleep(1500) // Wait for search results

        // 3. Click contact (we try to click the exact name, or something close)
        if (!svc.clickByText(contactName)) {
            return error("Contact '$contactName' not found in search results")
        }
        Thread.sleep(1000) // Wait for chat to open

        // 4. Type message
        if (!svc.typeText(message)) {
            return error("Could not type message in chat")
        }
        Thread.sleep(500)

        // 5. Click Send
        if (!svc.clickByText("Send")) {
            // Sometimes it's just an icon without text, we might need a coordinate fallback,
            // but "Send" usually works as content description on the button.
            return error("Could not find Send button")
        }

        return result("status", "message_sent", "contact", contactName)
    }

    /**
     * Starts a voice or video call.
     */
    fun makeCall(contactName: String, isVideo: Boolean): JSONObject {
        if (contactName.isBlank()) return error("contact_name is required")

        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        if (!launchWhatsApp()) return error("WhatsApp not installed")
        Thread.sleep(1500)

        if (!svc.clickByText("Search")) return error("Could not find WhatsApp search button")
        Thread.sleep(500)

        if (!svc.typeText(contactName)) return error("Could not type in search field")
        Thread.sleep(1500)

        if (!svc.clickByText(contactName)) return error("Contact '$contactName' not found")
        Thread.sleep(1000)

        val callButton = if (isVideo) "Video call" else "Voice call"
        if (!svc.clickByText(callButton)) {
            // Also try just "Call"
            if (!svc.clickByText("Call")) {
                return error("Could not find $callButton button in chat")
            }
        }

        return result("status", "call_started", "contact", contactName, "video", isVideo.toString())
    }

    private fun launchWhatsApp(): Boolean {
        val pm: PackageManager = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(WHATSAPP_PKG) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
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
