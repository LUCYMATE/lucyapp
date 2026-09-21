package com.lucy.mate.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lucy.mate.MainActivity

/**
 * ReminderReceiver
 *
 * Handles exact alarms and reminders scheduled via AlarmManager.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderReceiver"
        private const val CHANNEL_ID = "soltini_reminders"
        private const val CHANNEL_NAME = "LUCY Reminders"

        fun scheduleReminder(
            context: Context,
            triggerAtMillis: Long,
            message: String,
            reminderId: Int = (System.currentTimeMillis() % 100000).toInt()
        ): Boolean {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = "com.lucy.mate.ACTION_REMINDER"
                putExtra("extra_reminder_id", reminderId)
                putExtra("extra_reminder_message", message)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, reminderId, intent, flags)

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
                Log.i(TAG, "Scheduled reminder #$reminderId at $triggerAtMillis: '$message'")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule reminder: ${e.message}", e)
                false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra("extra_reminder_id", 0)
        val message = intent.getStringExtra("extra_reminder_message") ?: "Time for your scheduled task!"

        Log.i(TAG, "Reminder #$reminderId triggered: $message")

        showNotification(context, reminderId, message)

        // Trigger agent to speak the reminder
        val voiceIntent = Intent(context, com.lucy.mate.services.BackgroundVoiceService::class.java).apply {
            action = "com.lucy.mate.ACTION_SPEAK_REMINDER"
            putExtra("message", message)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(voiceIntent)
            } else {
                context.startService(voiceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BackgroundVoiceService: ${e.message}")
        }
    }

    private fun showNotification(context: Context, id: Int, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders and tasks from Soltini"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LUCY Reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        nm.notify(id, notification)
    }
}
