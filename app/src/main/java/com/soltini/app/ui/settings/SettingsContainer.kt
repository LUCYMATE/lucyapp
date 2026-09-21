package com.soltini.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.ui.LogScreen
import com.soltini.app.ui.MainViewModel
import com.soltini.app.ui.theme.PinkyWhiteBackground
import com.soltini.app.ui.theme.RosePink
import com.soltini.app.ui.theme.TextPrimary

enum class SettingsRoute { MAIN, PERMISSIONS, API, MEMORY, AUDIO, CALLS, ABOUT, CONNECTORS, SHIELD, LOGS, OVERLAY, PROFILE }

@Composable
fun SettingsContainer(
    onBack: () -> Unit,
    onSignOut: () -> Unit = {},
    viewModel: MainViewModel? = null,
    modifier: Modifier = Modifier
) {
    var currentRoute by remember { mutableStateOf(SettingsRoute.MAIN) }

    BackHandler(enabled = currentRoute != SettingsRoute.MAIN) {
        currentRoute = SettingsRoute.MAIN
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = PinkyWhiteBackground
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clickable {
                            if (currentRoute == SettingsRoute.MAIN) onBack() else currentRoute = SettingsRoute.MAIN
                        }
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, tint = RosePink)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (currentRoute == SettingsRoute.MAIN) "Done" else "Settings",
                            color = RosePink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Sub-Settings Route Dispatcher
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentRoute) {
                    SettingsRoute.MAIN -> SettingsMainScreen(
                        onNavigate = { currentRoute = it },
                        onSignOut = onSignOut
                    )
                    SettingsRoute.API -> SettingsAiConfigScreen(
                        appSettings = viewModel?.appSettings,
                        onSaveAndReconnect = { viewModel?.reconnect() }
                    )
                    SettingsRoute.AUDIO -> SettingsAudioScreen(viewModel = viewModel)
                    SettingsRoute.SHIELD -> SettingsBankingShieldScreen(appSettings = viewModel?.appSettings)
                    SettingsRoute.PERMISSIONS -> SettingsPermissionsScreen()
                    SettingsRoute.CALLS -> SettingsCallsScreen(viewModel = viewModel)
                    SettingsRoute.LOGS -> LogScreen(onBack = { currentRoute = SettingsRoute.MAIN })
                    SettingsRoute.ABOUT -> SettingsAboutScreen()
                    SettingsRoute.MEMORY -> SettingsMemoryScreen(memoryManager = viewModel?.getMemoryManager())
                    SettingsRoute.CONNECTORS -> SettingsConnectorsScreen(viewModel = viewModel)
                    SettingsRoute.OVERLAY -> SettingsFloatingOverlayScreen(appSettings = viewModel?.appSettings)
                    SettingsRoute.PROFILE -> com.soltini.app.ui.auth.ProfileScreen(
                        onBack = { currentRoute = SettingsRoute.MAIN },
                        onSignOut = onSignOut
                    )
                }
            }
        }
    }
}
