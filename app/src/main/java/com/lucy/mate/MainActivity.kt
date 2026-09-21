package com.lucy.mate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.collectAsState
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
import com.lucy.mate.auth.UserManager
import com.lucy.mate.overlay.OverlayService
import com.lucy.mate.ui.LogScreen
import com.lucy.mate.ui.MainViewModel
import com.lucy.mate.ui.VoiceScreen
import com.lucy.mate.ui.agent.AgentHubScreen
import com.lucy.mate.ui.auth.AuthGateScreen
import com.lucy.mate.ui.auth.TierDowngradeDialog
import com.lucy.mate.ui.memory.MemoryVaultScreen
import com.lucy.mate.ui.onboarding.UnifiedOnboardingScreen
import com.lucy.mate.ui.settings.SettingsContainer
import com.lucy.mate.ui.theme.GeminiVoiceTheme
import com.lucy.mate.ui.theme.PearlWhiteSurface
import com.lucy.mate.ui.theme.PinkyBorder
import com.lucy.mate.ui.theme.PinkyWhiteBackground
import com.lucy.mate.ui.theme.RosePink
import com.lucy.mate.ui.theme.TextPrimary
import com.lucy.mate.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_SCREEN = "target_screen"
    }

    private lateinit var viewModel: MainViewModel

    private val currentScreenState = mutableStateOf("voice")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val userManager = UserManager.getInstance(this)
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val isMiui = Build.MANUFACTURER.lowercase() in listOf("xiaomi", "poco", "redmi")
        val miuiComplete = if (isMiui) getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("miui_onboarding_complete", false) else true
        val needsOnboarding = !hasMic || !miuiComplete

        val hasAuth = userManager.hasValidSession()

        val targetScreen = intent.getStringExtra(EXTRA_TARGET_SCREEN)
        handleSendIntent(intent)
        handleAuthIntent(intent)
        
        currentScreenState.value = when {
            !hasAuth -> "auth"
            needsOnboarding -> "onboarding"
            targetScreen != null -> targetScreen
            else -> "voice"
        }

        setContent {
            GeminiVoiceTheme {
                var currentScreen by currentScreenState
                val userSession by userManager.currentUser.collectAsState()
                val downgradeNotice by userManager.tierDowngradeNotice.collectAsState()

                // Security Lockdown: Immediately enforce AuthGate if no valid session exists
                LaunchedEffect(userSession) {
                    if (userSession == null || userSession?.isRevoked == true || !userManager.hasValidSession()) {
                        if (currentScreen != "auth") {
                            currentScreen = "auth"
                        }
                    }
                }

                LaunchedEffect(currentScreen) {
                    if (currentScreen in listOf("voice", "agent", "memory", "settings", "logs")) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            OverlayService.start(this@MainActivity)
                        }
                    }
                }

                // Downgrade Notice Dialog (Pro -> Free transition)
                downgradeNotice?.let { notice ->
                    TierDowngradeDialog(
                        notice = notice,
                        onDismiss = {
                            userManager.dismissDowngradeNotice()
                        }
                    )
                }

                if (currentScreen == "auth") {
                    AuthGateScreen(
                        onAuthorized = {
                            val hasMicGranted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            val isMiuiDevice = Build.MANUFACTURER.lowercase() in listOf("xiaomi", "poco", "redmi")
                            val miuiDone = if (isMiuiDevice) getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("miui_onboarding_complete", false) else true
                            currentScreen = if (!hasMicGranted || !miuiDone) "onboarding" else "voice"
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (currentScreen == "onboarding") {
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
                    BackHandler(enabled = currentScreen == "profile") {
                        currentScreen = "voice"
                    }

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
                                .padding(bottom = if (currentScreen == "profile") 0.dp else 76.dp)
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
                                        onSignOut = { currentScreen = "auth" },
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    "profile" -> com.lucy.mate.ui.auth.ProfileScreen(
                                        onBack = { currentScreen = "voice" },
                                        onSignOut = { currentScreen = "auth" },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    else -> VoiceScreen(
                                        viewModel = viewModel,
                                        onOpenLogs = { currentScreen = "logs" },
                                        onOpenSettings = { currentScreen = "settings" },
                                        onOpenProfile = { currentScreen = "profile" },
                                        onSignOut = { currentScreen = "auth" },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        // Floating Modern Bottom Navigation Bar (Hidden when on full profile screen)
                        if (currentScreen != "profile") {
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
        handleAuthIntent(intent)
        val target = intent.getStringExtra(EXTRA_TARGET_SCREEN)
        if (target != null) {
            currentScreenState.value = target
        }
    }

    override fun onResume() {
        super.onResume()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(hasMic)
        viewModel.setAppInForeground(true)
    }

    override fun onStop() {
        super.onStop()
        viewModel.setAppInForeground(false)
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

    private fun handleAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "lucymate") {
            intent.data = null
            android.util.Log.i("MainActivity", "Captured auth deep link: $uri")
            val userManager = com.lucy.mate.auth.UserManager.getInstance(this)
            val success = userManager.handleAuthDeepLink(uri)
            if (success) {
                val name = userManager.currentUser.value?.name ?: "User"
                android.widget.Toast.makeText(this, "✨ Signed in as $name", android.widget.Toast.LENGTH_SHORT).show()
                if (currentScreenState.value == "auth") {
                    val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val isMiui = Build.MANUFACTURER.lowercase() in listOf("xiaomi", "poco", "redmi")
                    val miuiComplete = if (isMiui) getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("miui_onboarding_complete", false) else true
                    currentScreenState.value = if (!hasMic || !miuiComplete) "onboarding" else "voice"
                }
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
