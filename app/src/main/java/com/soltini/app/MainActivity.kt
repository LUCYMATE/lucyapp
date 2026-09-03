package com.soltini.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.soltini.app.overlay.OverlayService
import com.soltini.app.ui.LogScreen
import com.soltini.app.ui.MainViewModel
import com.soltini.app.ui.VoiceScreen
import com.soltini.app.ui.agent.AgentHubScreen
import com.soltini.app.ui.memory.MemoryVaultScreen
import com.soltini.app.ui.onboarding.UnifiedOnboardingScreen
import com.soltini.app.ui.settings.SettingsContainer
import com.soltini.app.ui.theme.GeminiVoiceTheme
import com.soltini.app.ui.theme.PearlWhiteSurface
import com.soltini.app.ui.theme.PinkyBorder
import com.soltini.app.ui.theme.PinkyWhiteBackground
import com.soltini.app.ui.theme.RosePink
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_SCREEN = "target_screen"
    }

    private lateinit var viewModel: MainViewModel

    private val currentScreenState = mutableStateOf("voice")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val isMiui = Build.MANUFACTURER.lowercase() in listOf("xiaomi", "poco", "redmi")
        val miuiComplete = if (isMiui) getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("miui_onboarding_complete", false) else true
        val needsOnboarding = !hasMic || !miuiComplete

        val targetScreen = intent.getStringExtra(EXTRA_TARGET_SCREEN)
        handleSendIntent(intent)
        
        currentScreenState.value = when {
            needsOnboarding -> "onboarding"
            targetScreen != null -> targetScreen
            else -> "voice"
        }

        setContent {
            GeminiVoiceTheme {
                var currentScreen by currentScreenState

                LaunchedEffect(currentScreen) {
                    if (currentScreen in listOf("voice", "agent", "memory", "settings", "logs")) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            OverlayService.start(this@MainActivity)
                        }
                    }
                }

                if (currentScreen == "onboarding") {
                    UnifiedOnboardingScreen(
                        onComplete = { currentScreen = "voice" },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (currentScreen == "logs") {
                    LogScreen(
                        onBack = { currentScreen = "settings" },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Main 4-Tab Architecture with Floating Capsule Navigation
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PinkyWhiteBackground)
                    ) {
                        // Content with bottom padding so bottom bar does not overlap content
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 76.dp)
                        ) {
                            Crossfade(targetState = currentScreen, label = "tabTransition") { tab ->
                                when (tab) {
                                    "agent" -> AgentHubScreen(
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    "memory" -> MemoryVaultScreen(
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    "settings" -> SettingsContainer(
                                        onBack = { currentScreen = "voice" },
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    else -> VoiceScreen(
                                        viewModel = viewModel,
                                        onOpenLogs = { currentScreen = "logs" },
                                        onOpenSettings = { currentScreen = "settings" },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        // Floating Modern Bottom Navigation Bar
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp, vertical = 10.dp)
                        ) {
                            SoltiniBottomBar(
                                currentTab = currentScreen,
                                onTabSelect = { currentScreen = it }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
        val target = intent.getStringExtra(EXTRA_TARGET_SCREEN)
        if (target != null) {
            currentScreenState.value = target
        }
    }

    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true) {
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                android.util.Log.i("MainActivity", "Received shared audio file: $uri")
                viewModel.sendTextMessage("I just shared an audio file with you. (Note: Audio file processing via share intent is coming soon)")
                currentScreenState.value = "voice"
            }
        }
    }
}

@Composable
private fun SoltiniBottomBar(
    currentTab: String,
    onTabSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoltiniNavItem(
                icon = Icons.Default.Mic,
                label = "Live",
                isSelected = currentTab == "voice",
                onClick = { onTabSelect("voice") }
            )
            SoltiniNavItem(
                icon = Icons.Default.Bolt,
                label = "Agent",
                isSelected = currentTab == "agent",
                onClick = { onTabSelect("agent") }
            )
            SoltiniNavItem(
                icon = Icons.Default.Psychology,
                label = "Memory",
                isSelected = currentTab == "memory",
                onClick = { onTabSelect("memory") }
            )
            SoltiniNavItem(
                icon = Icons.Default.Settings,
                label = "Studio",
                isSelected = currentTab == "settings",
                onClick = { onTabSelect("settings") }
            )
        }
    }
}

@Composable
private fun SoltiniNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) RosePink else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = if (isSelected) RosePink else TextSecondary,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium
            )
        }
    }
}
