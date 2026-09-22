package com.lucy.mate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.lucy.mate.overlay.OverlayService
import com.lucy.mate.services.BackgroundVoiceService

/**
 * BootReceiver
 *
 * Listens for BOOT_COMPLETED (and OEM equivalents like QUICKBOOT_POWERON on HTC/OnePlus)
 * and starts the Soltini foreground services automatically after device reboot.
 *
 * START_STICKY only handles app-kill-and-restart within a session. This receiver
 * handles the cold-boot case where the OS has never started our process yet.
 *
 * NOTE: Some OEM launchers (Xiaomi MIUI, Huawei EMUI, Samsung One UI) require the user
 * to additionally enable "Autostart" in their device's Battery / App settings.
 * We cannot override that restriction from code.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SoltiniBootReceiver"
        const val EXTRA_FROM_BOOT_OR_RESURRECT = "from_boot_or_resurrect"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Accept standard boot, direct boot, screen unlock + OEM quick-boot variants
        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_USER_PRESENT ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||          // HTC
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||          // HTC (legacy)
                action == "android.intent.action.MY_PACKAGE_REPLACED" ||        // APK update restart
                action == "android.intent.action.REBOOT" ||
                action == "com.lucy.mate.RESTART_SERVICE"                     // Custom task removed restart

        if (!isBootAction) return

        val appSettings = com.lucy.mate.settings.AppSettings.getInstance(context)
        if (appSettings.isAppPaused) {
            Log.i(TAG, "Event ($action) received but Soltini is completely PAUSED by user. Skipping all background starts.")
            return
        }
        if (!appSettings.isAutoStartEnabled) {
            Log.i(TAG, "Event ($action) received but Background Auto-Start is disabled by user in settings. Skipping.")
            return
        }

        Log.i(TAG, "Boot/update/resurrection event received (action=$action) — starting Soltini services")

        // ── Start BackgroundVoiceService ─────────────────────────────────────
        try {
            val voiceIntent = Intent(context, BackgroundVoiceService::class.java).apply {
                this.action = action
                putExtra(EXTRA_FROM_BOOT_OR_RESURRECT, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(voiceIntent)
            } else {
                context.startService(voiceIntent)
            }
            Log.i(TAG, "BackgroundVoiceService start triggered after $action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BackgroundVoiceService on $action: ${e.message}", e)
            com.lucy.mate.agent.SoltiniAccessibilityService.getInstance()?.ensureForegroundServicesRunning()
        }

        // ── Start OverlayService (only if overlay permission is still granted) ─
        try {
            if (Settings.canDrawOverlays(context)) {
                val overlayIntent = Intent(context, OverlayService::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_FROM_BOOT_OR_RESURRECT, true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent)
                } else {
                    context.startService(overlayIntent)
                }
                Log.i(TAG, "OverlayService start triggered after $action")
            } else {
                Log.w(TAG, "Overlay permission not granted — OverlayService skipped at $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService on $action: ${e.message}", e)
            com.lucy.mate.agent.SoltiniAccessibilityService.getInstance()?.ensureForegroundServicesRunning()
        }
    }
}
