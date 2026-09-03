package com.soltini.app.core

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DrivingModeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DrivingModeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val deviceClass = device?.bluetoothClass?.deviceClass
            
            // Check if device is a car audio system (1056 is AUDIO_VIDEO_CAR_AUDIO)
            if (deviceClass == 1056) {
                Log.i(TAG, "Car bluetooth connected. Waking up assistant for driving mode.")
                val voiceIntent = Intent(context, com.soltini.app.services.BackgroundVoiceService::class.java).apply {
                    this.action = "com.soltini.app.ACTION_SPEAK_REMINDER"
                    putExtra("message", "Driving mode activated. I am ready to assist you hands-free while you drive.")
                }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(voiceIntent)
                    } else {
                        context.startService(voiceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start VoiceService for driving mode: ${e.message}")
                }
            }
        }
    }
}
