package com.soltini.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * PhoneStateReceiver
 *
 * Listens for incoming phone calls and call state transitions to mute voice assistant audio,
 * prevent microphone contention, and announce caller information.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneStateReceiver"

        @Volatile
        var lastCallState: String? = null
            private set

        @Volatile
        var incomingNumber: String? = null
            private set

        @Volatile
        var isCallActive: Boolean = false
            private set

        var onCallStateChangedListener: ((state: String, number: String?) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        if (state == lastCallState) return
        lastCallState = state
        incomingNumber = number

        Log.i(TAG, "Phone state changed: $state (number: $number)")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isCallActive = true
                Log.d(TAG, "Incoming call ringing from: $number")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isCallActive = true
                Log.d(TAG, "Call in progress / offhook")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                isCallActive = false
                Log.d(TAG, "Call ended / phone idle")
            }
        }

        onCallStateChangedListener?.invoke(state, number)
    }
}
