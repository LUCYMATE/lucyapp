package com.soltini.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.soltini.app.overlay.OverlayService
import com.soltini.app.ui.LogScreen
import com.soltini.app.ui.MainViewModel
import com.soltini.app.ui.PermissionsScreen
import com.soltini.app.ui.SettingsScreen
import com.soltini.app.ui.VoiceScreen
import com.soltini.app.ui.theme.GeminiVoiceTheme

/**
 * MainActivity
 *
 * Routing logic:
 *   1. "permissions" — First launch: only asks for Microphone (required to start).
 *   2. "voice"       — Main screen with floating orb.
 *   3. "settings"    — Full permissions dashboard (overlay, accessibility, assistant role…).
 *   4. "logs"        — Debug log viewer.
 *
 * First launch detection: we only block on Microphone. The Overlay permission
 * is checked but the app still launches so the user can be guided from the Settings page.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            GeminiVoiceTheme {
                val hasMic = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                // Only block on Microphone for the very first launch.
                // Everything else is managed from the Settings page.
                var currentScreen by remember {
                    mutableStateOf(if (hasMic) "voice" else "permissions")
                }

                // Start the overlay service as soon as we are past the mic gate
                LaunchedEffect(currentScreen) {
                    if (currentScreen == "voice" || currentScreen == "settings" || currentScreen == "logs") {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            OverlayService.start(this@MainActivity)
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    when (currentScreen) {
                        "permissions" -> {
                            // First-launch screen: only asks for Microphone
                            PermissionsScreen(
                                onAllPermissionsGranted = { currentScreen = "voice" },
                                modifier = Modifier
                            )
                        }
                        "logs" -> {
                            LogScreen(
                                onBack = { currentScreen = "voice" },
                                modifier = Modifier
                            )
                        }
                        "settings" -> {
                            // Full permissions & capabilities dashboard
                            SettingsScreen(
                                onBack = { currentScreen = "voice" },
                                viewModel = viewModel,
                                modifier = Modifier
                            )
                        }
                        else -> {
                            VoiceScreen(
                                viewModel = viewModel,
                                onOpenLogs = { currentScreen = "logs" },
                                onOpenSettings = { currentScreen = "settings" },
                                modifier = Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}
