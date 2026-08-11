package com.soltini.app.ui

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soltini.app.network.ConnectionState
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.AccentIndigo
import com.soltini.app.ui.theme.AccentPink
import com.soltini.app.ui.theme.AccentPurple
import com.soltini.app.ui.theme.AccentTeal
import com.soltini.app.ui.theme.AmberConnecting
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.RedError
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceScreen(
    viewModel: MainViewModel,
    onOpenLogs: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val micAmplitude by viewModel.micAmplitude.collectAsStateWithLifecycle()
    val speakerAmplitude by viewModel.speakerAmplitude.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()
    val hasMicPermission by viewModel.hasMicPermission.collectAsStateWithLifecycle()

    LaunchedEffect(hasMicPermission, connectionState) {
        if (hasMicPermission && (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error)) {
            viewModel.connect()
        }
    }

    val isLive = connectionState == ConnectionState.Live
    val activeAmplitude = if (isSpeaking) speakerAmplitude else micAmplitude

    // Quick agent status checks (re-evaluated on compose)
    val hasAssistant = remember(connectionState) { checkAssistantRoleHome(context) }
    val hasAccessibility = remember(connectionState) { checkAccessibilityHome(context) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = DarkBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ── Ambient background glow ───────────────────────────────────────
            AmbientBackground(isLive = isLive, isSpeaking = isSpeaking)

            // ── Main scrollable content ───────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top bar
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    TopBar(
                        connectionState = connectionState,
                        onOpenLogs = onOpenLogs,
                        onOpenSettings = onOpenSettings,
                        onReconnect = { viewModel.connect() }
                    )
                }

                // Hero waveform card (replaces orb — orb is on the overlay)
                item {
                    HeroWaveCard(
                        connectionState = connectionState,
                        isSpeaking = isSpeaking,
                        isMuted = isMuted,
                        amplitude = activeAmplitude,
                        onToggleMute = { viewModel.toggleMute() }
                    )
                }

                // Quick voice tips / command chips
                item {
                    CommandChipsRow()
                }

                // Agent capabilities status cards
                item {
                    AgentStatusRow(
                        hasAssistant = hasAssistant,
                        hasAccessibility = hasAccessibility,
                        onOpenSettings = onOpenSettings
                    )
                }

                // Error card
                if (errorMessage != null) {
                    item {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically { -it },
                            exit = fadeOut()
                        ) {
                            ErrorCard(errorMessage!!) { viewModel.connect() }
                        }
                    }
                }

                // Transcript
                if (transcripts.isNotEmpty()) {
                    item {
                        TranscriptHeader(onClear = { viewModel.clearTranscript() })
                    }
                    items(transcripts, key = { it.id }) { entry ->
                        TranscriptBubble(entry)
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

// ── Ambient Background Glow ────────────────────────────────────────────────────
@Composable
private fun AmbientBackground(isLive: Boolean, isSpeaking: Boolean) {
    val inf = rememberInfiniteTransition(label = "bg")
    val shift by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Reverse),
        label = "bgShift"
    )
    val glowColor = when {
        !isLive -> AccentBlue.copy(alpha = 0.03f)
        isSpeaking -> AccentPink.copy(alpha = 0.06f)
        else -> AccentTeal.copy(alpha = 0.05f)
    }
    val animGlow by animateColorAsState(glowColor, tween(800), label = "glow")

    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width * (0.3f + shift * 0.4f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(animGlow, Color.Transparent),
                center = Offset(cx, size.height * 0.2f),
                radius = size.width * 0.9f
            ),
            radius = size.width * 0.9f,
            center = Offset(cx, size.height * 0.2f)
        )
    }
}

// ── Top Bar ────────────────────────────────────────────────────────────────────
@Composable
private fun TopBar(
    connectionState: ConnectionState,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onReconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Brand
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(AccentBlue, AccentPurple))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SmartToy, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("Soltini", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("AI Assistant", color = TextSecondary, fontSize = 11.sp)
            }
        }

        // Actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(connectionState)
            Spacer(modifier = Modifier.width(4.dp))
            if (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error) {
                IconButton(onClick = onReconnect, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onOpenLogs, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.BugReport, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Settings, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StatusPill(connectionState: ConnectionState) {
    val (color, label) = when (connectionState) {
        ConnectionState.Live -> GreenLive to "Live"
        ConnectionState.Connecting -> AmberConnecting to "Connecting"
        ConnectionState.Reconnecting -> AmberConnecting to "Reconnecting"
        ConnectionState.Disconnected -> Color.Gray to "Offline"
        ConnectionState.Error -> RedError to "Error"
    }
    val animColor by animateColorAsState(color, tween(400), label = "pill")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(animColor.copy(alpha = 0.12f))
            .border(1.dp, animColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier.size(7.dp).clip(CircleShape).background(animColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, color = animColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Hero Waveform Card ─────────────────────────────────────────────────────────
@Composable
private fun HeroWaveCard(
    connectionState: ConnectionState,
    isSpeaking: Boolean,
    isMuted: Boolean,
    amplitude: Float,
    onToggleMute: () -> Unit
) {
    val isLive = connectionState == ConnectionState.Live
    val animAmp by animateFloatAsState(amplitude, tween(80), label = "amp")

    val waveColor = when {
        !isLive -> AccentBlue.copy(alpha = 0.4f)
        isMuted -> Color.Gray.copy(alpha = 0.5f)
        isSpeaking -> AccentPink
        else -> AccentTeal
    }
    val animWaveColor by animateColorAsState(waveColor, tween(500), label = "wave")

    val statusText = when {
        !isLive -> "Disconnected — tap ↻ to reconnect"
        isMuted -> "Microphone muted"
        isSpeaking -> "Soltini is speaking..."
        else -> "Listening..."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                animWaveColor.copy(alpha = 0.06f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Waveform canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 40.dp)
            ) {
                val midY = size.height / 2f
                val barCount = 48
                val barSpacing = size.width / barCount
                val maxBarH = size.height * 0.45f

                val time = System.currentTimeMillis() / 1000f

                for (i in 0 until barCount) {
                    val normalizedPos = i.toFloat() / barCount
                    // Smooth sine wave with amplitude modulation
                    val baseWave = sin((normalizedPos * Math.PI * 6 + time * 2.5f).toFloat()) * 0.3f + 0.3f
                    val ampBoost = animAmp * sin((normalizedPos * Math.PI * 4).toFloat()).coerceAtLeast(0f)
                    val barH = ((baseWave + ampBoost) * maxBarH).coerceIn(4.dp.toPx(), maxBarH)

                    val x = i * barSpacing + barSpacing / 2
                    val alpha = if (!isLive) 0.3f else (0.5f + normalizedPos * 0.5f).coerceIn(0.3f, 1f)

                    drawLine(
                        color = animWaveColor.copy(alpha = alpha),
                        start = Offset(x, midY - barH / 2),
                        end = Offset(x, midY + barH / 2),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Bottom info row
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        statusText,
                        color = if (isSpeaking) animWaveColor else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSpeaking) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(
                        "Orb active on overlay →",
                        color = TextSecondary.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                }

                // Mute FAB
                FloatingActionButton(
                    onClick = onToggleMute,
                    containerColor = if (isMuted) RedError.copy(0.2f) else DarkSurfaceVariant,
                    contentColor = if (isMuted) RedError else TextPrimary,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── Command Chips Row ──────────────────────────────────────────────────────────
private val voiceCommands = listOf(
    "Open WhatsApp",
    "Lock the screen",
    "Open Camera",
    "Go to sleep",
    "Wake the screen",
    "Open YouTube",
    "Open Settings",
    "Open Maps"
)

@Composable
private fun CommandChipsRow() {
    Column {
        Text(
            "Try saying...",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(voiceCommands) { cmd ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "\"$cmd\"",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ── Agent Status Row ───────────────────────────────────────────────────────────
@Composable
private fun AgentStatusRow(
    hasAssistant: Boolean,
    hasAccessibility: Boolean,
    onOpenSettings: () -> Unit
) {
    Column {
        Text(
            "Agent Capabilities",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AgentCapCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Assistant,
                title = "Default Assistant",
                subtitle = if (hasAssistant) "Active" else "Tap to enable",
                isActive = hasAssistant,
                activeColor = AccentBlue,
                onClick = onOpenSettings
            )
            AgentCapCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Lock,
                title = "Lock Screen",
                subtitle = if (hasAccessibility) "Active" else "Tap to enable",
                isActive = hasAccessibility,
                activeColor = AccentPurple,
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun AgentCapCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        if (isActive) activeColor.copy(0.5f) else DarkBorder, tween(500), label = "border"
    )

    Card(
        modifier = modifier
            .height(100.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) activeColor.copy(0.08f) else DarkSurface
        ),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isActive) activeColor.copy(0.18f) else DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = if (isActive) activeColor else TextSecondary, modifier = Modifier.size(18.dp))
                }
                if (isActive) {
                    Icon(Icons.Default.CheckCircle, null, tint = activeColor, modifier = Modifier.size(16.dp))
                }
            }
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(subtitle, color = if (isActive) activeColor else TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

// ── Transcript ─────────────────────────────────────────────────────────────────
@Composable
private fun TranscriptHeader(onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Conversation", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Clear",
            color = AccentBlue,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onClear() }
        )
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val isUser = entry.sender == TranscriptEntry.Sender.USER
    val bubbleShape = RoundedCornerShape(
        topStart = if (isUser) 18.dp else 4.dp,
        topEnd = if (isUser) 4.dp else 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(AccentBlue, AccentPurple))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SmartToy, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        // Always use Brush so both branches return the same type
        val bubbleBrush: Brush = if (isUser) {
            Brush.linearGradient(listOf(DarkSurfaceVariant, DarkSurfaceVariant))
        } else {
            Brush.linearGradient(listOf(AccentBlue.copy(0.15f), AccentPurple.copy(0.10f)))
        }
        val bubbleBorder = if (isUser) DarkBorder else AccentBlue.copy(0.2f)

        Box(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .clip(bubbleShape)
                .background(bubbleBrush)
                .border(1.dp, bubbleBorder, bubbleShape)
                .padding(12.dp)
        ) {
            Text(entry.text, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

// ── Error Card ─────────────────────────────────────────────────────────────────
@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RedError.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RedError.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiOff, null, tint = RedError, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(error, color = RedError, modifier = Modifier.weight(1f), fontSize = 12.sp)
            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, "Retry", tint = RedError, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Permission check helpers for home ─────────────────────────────────────────
private fun checkAssistantRoleHome(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
        rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } else false
}

private fun checkAccessibilityHome(context: Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains(context.packageName, ignoreCase = true)
}
