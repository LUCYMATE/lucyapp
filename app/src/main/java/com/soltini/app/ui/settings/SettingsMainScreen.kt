package com.soltini.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.ui.theme.*

@Composable
fun SettingsMainScreen(
    onNavigate: (SettingsRoute) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // Header
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            Text(
                text = "Studio Settings",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Configure AI engine, audio, shield & devices",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        // Section 1: AI & Audio Engine
        Text(
            text = "AI Core & Voice Engine",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SettingsMenuCard(
            title = "AI Persona & Voices",
            subtitle = "Gemini API key, 16 voice models, prompt",
            icon = Icons.Default.Person,
            iconTint = RosePink,
            onClick = { onNavigate(SettingsRoute.API) }
        )

        SettingsMenuCard(
            title = "Audio & Detection Engine",
            subtitle = "VAD sensitivity, Mic gain booster, Anti-Echo",
            icon = Icons.Default.VolumeUp,
            iconTint = SoftViolet,
            onClick = { onNavigate(SettingsRoute.AUDIO) }
        )

        Spacer(Modifier.height(14.dp))

        // Section 2: Security & Shield
        Text(
            text = "Privacy & Safety",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SettingsMenuCard(
            title = "Banking & Privacy Shield",
            subtitle = "Auto-silence mic on 26 banking apps",
            icon = Icons.Default.Shield,
            iconTint = RubyShield,
            onClick = { onNavigate(SettingsRoute.SHIELD) }
        )

        SettingsMenuCard(
            title = "Permissions & Diagnostics",
            subtitle = "Microphone, Accessibility, Assistant Role",
            icon = Icons.Default.Security,
            iconTint = EmeraldLive,
            onClick = { onNavigate(SettingsRoute.PERMISSIONS) }
        )

        Spacer(Modifier.height(14.dp))

        // Section 3: Device & System
        Text(
            text = "System & Communications",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SettingsMenuCard(
            title = "Calls & Automations",
            subtitle = "Speakerphone, WhatsApp calls, notifications",
            icon = Icons.Default.Call,
            iconTint = Color(0xFF3B82F6),
            onClick = { onNavigate(SettingsRoute.CALLS) }
        )

        SettingsMenuCard(
            title = "Connectors & Integrations",
            subtitle = "Google Calendar, Gmail, Spotify",
            icon = Icons.Default.Extension,
            iconTint = SoftViolet,
            onClick = { onNavigate(SettingsRoute.CONNECTORS) }
        )

        SettingsMenuCard(
            title = "Diagnostic Console Logs",
            subtitle = "Live streaming WebSocket event trace",
            icon = Icons.Default.BugReport,
            iconTint = AmberConnecting,
            onClick = { onNavigate(SettingsRoute.LOGS) }
        )

        SettingsMenuCard(
            title = "About Soltini",
            subtitle = "v2.0 • Architecture & Developer",
            icon = Icons.Default.Info,
            iconTint = TextSecondary,
            onClick = { onNavigate(SettingsRoute.ABOUT) }
        )

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun SettingsMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = RosePink,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
