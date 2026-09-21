package com.lucy.mate.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SoltiniNotificationListener
 *
 * Intercepts, indexes, and enables autonomous voice-based quick replies for incoming
 * notifications (WhatsApp, Telegram, SMS, Instagram, Caller alerts).
 */
class SoltiniNotificationListener : NotificationListenerService() {

    data class ParsedNotification(
        val key: String,
        val id: Int,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val isCall: Boolean,
        val replyAction: PendingIntent? = null,
        val remoteInput: RemoteInput? = null
    )

    companion object {
        private const val TAG = "SoltiniNotification"

        @Volatile
        private var instance: SoltiniNotificationListener? = null

        fun getInstance(): SoltiniNotificationListener? = instance

        fun isConnected(): Boolean = instance != null

        // Cache of latest notifications
        private val recentNotifications = CopyOnWriteArrayList<ParsedNotification>()
        private val activeKeyMap = ConcurrentHashMap<String, ParsedNotification>()

        // Target packages of high interest
        val MONITORED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.instagram.android",
            "org.thoughtcrime.securesms"
        )

        fun getRecentNotifications(limit: Int = 10): List<ParsedNotification> {
            return recentNotifications.takeLast(limit).reversed()
        }

        fun getNotificationsForPackage(pkg: String): List<ParsedNotification> {
            return recentNotifications.filter { it.packageName.contains(pkg, ignoreCase = true) }
        }

        fun getRecentNotificationsSummary(): String {
            val list = getRecentNotifications(5)
            if (list.isEmpty()) return ""
            return list.joinToString("; ") { "${it.appName} (${it.title}): ${it.text}" }
        }

        /**
         * Autonomous quick-reply via Notification RemoteInput without opening the app
         */
        fun replyToNotification(context: Context, key: String, message: String): Boolean {
            val item = activeKeyMap[key] ?: recentNotifications.find { it.key == key }
            if (item?.replyAction == null || item.remoteInput == null) {
                Log.w(TAG, "No quick reply action available for notification: $key")
                return false
            }

            try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(item.remoteInput.resultKey, message)
                RemoteInput.addResultsToIntent(arrayOf(item.remoteInput), intent, bundle)

                item.replyAction.send(context, 0, intent)
                Log.i(TAG, "Replied successfully to notification $key with: $message")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply to notification: ${e.message}", e)
                return false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "SoltiniNotificationListener connected")
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.i(TAG, "SoltiniNotificationListener disconnected, requesting rebind")
        try {
            requestRebind(ComponentName(this, SoltiniNotificationListener::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting rebind: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { processNotification(it, isNew = true) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let {
            activeKeyMap.remove(it.key)
        }
    }

    fun refreshActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn, isNew = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing notifications: ${e.message}")
        }
    }

    private fun processNotification(sbn: StatusBarNotification, isNew: Boolean) {
        try {
            val pkg = sbn.packageName ?: return
            val n = sbn.notification ?: return
            val extras = n.extras ?: Bundle()

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ""

            if (title.isBlank() && text.isBlank()) return

            val isCall = n.category == Notification.CATEGORY_CALL ||
                    title.contains("incoming call", ignoreCase = true) ||
                    text.contains("incoming call", ignoreCase = true)

            // Look for RemoteInput inline reply action
            var replyPendingIntent: PendingIntent? = null
            var directRemoteInput: RemoteInput? = null

            n.actions?.let { actions ->
                for (action in actions) {
                    val remoteInputs = action.remoteInputs
                    if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                        replyPendingIntent = action.actionIntent
                        directRemoteInput = remoteInputs[0]
                        break
                    }
                }
            }

            val appName = try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg
            }

            val parsed = ParsedNotification(
                key = sbn.key,
                id = sbn.id,
                packageName = pkg,
                appName = appName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                isCall = isCall,
                replyAction = replyPendingIntent,
                remoteInput = directRemoteInput
            )

            activeKeyMap[sbn.key] = parsed

            // Keep in bounded history list
            if (recentNotifications.size > 50) {
                recentNotifications.removeAt(0)
            }
            // Update or add
            val existingIndex = recentNotifications.indexOfFirst { it.key == sbn.key }
            if (existingIndex >= 0) {
                recentNotifications[existingIndex] = parsed
            } else {
                recentNotifications.add(parsed)
            }

            if (isNew) {
                Log.d(TAG, "New notification from $appName ($title): $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}")
        }
    }
}
