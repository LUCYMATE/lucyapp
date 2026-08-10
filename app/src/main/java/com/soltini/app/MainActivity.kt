package com.soltini.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.soltini.app.ui.VoiceScreen
import com.soltini.app.ui.theme.GeminiVoiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            GeminiVoiceTheme {
                // Determine initial screen based on required permissions
                val hasOverlay = Settings.canDrawOverlays(this)
                val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                
                var currentScreen by remember { 
                    mutableStateOf(if (hasOverlay && hasMic) "voice" else "permissions") 
                }

                // If voice screen is the active screen and overlay is granted, start the service
                LaunchedEffect(currentScreen) {
                    if (currentScreen == "voice" || currentScreen == "logs") {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            OverlayService.start(this@MainActivity)
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        "permissions" -> {
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
                        else -> {
                            VoiceScreen(
                                viewModel = viewModel,
                                onOpenLogs = { currentScreen = "logs" },
                                modifier = Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}
