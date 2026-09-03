package com.soltini.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UnlockActivity
 *
 * Transparent translucent activity that wakes up the device, turns the screen on,
 * and requests dismissal of the Android keyguard for hands-free voice automation.
 */
class UnlockActivity : Activity() {

    companion object {
        private const val TAG = "UnlockActivity"
        private var unlockDeferred: CompletableDeferred<Boolean>? = null

        suspend fun requestUnlockAndWake(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = powerManager.isInteractive

            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isLocked = keyguardManager?.isKeyguardLocked ?: false

            if (isScreenOn && !isLocked) {
                Log.d(TAG, "Screen is already interactive and unlocked.")
                return true
            }

            unlockDeferred = CompletableDeferred()
            val intent = Intent(context, UnlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)

            val result = withTimeoutOrNull(5000L) {
                unlockDeferred?.await()
            } ?: false

            return result
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "UnlockActivity onCreate: waking and unlocking device")

        // 1. Screen on & Show when locked flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // 2. WakeLock pulse
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Soltini:UnlockWakeLock"
            )
            wakeLock.acquire(3000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }

        // 3. Request Keyguard dismissal
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null && keyguardManager.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        Log.i(TAG, "Keyguard dismissal succeeded")
                        unlockDeferred?.complete(true)
                        finish()
                    }

                    override fun onDismissCancelled() {
                        super.onDismissCancelled()
                        Log.w(TAG, "Keyguard dismissal cancelled")
                        unlockDeferred?.complete(false)
                        finish()
                    }

                    override fun onDismissError() {
                        super.onDismissError()
                        Log.e(TAG, "Keyguard dismissal error")
                        unlockDeferred?.complete(false)
                        finish()
                    }
                })
            } else {
                unlockDeferred?.complete(true)
                finish()
            }
        } else {
            unlockDeferred?.complete(true)
            finish()
        }
    }

    override fun onDestroy() {
        if (unlockDeferred?.isCompleted == false) {
            unlockDeferred?.complete(true)
        }
        super.onDestroy()
    }
}
