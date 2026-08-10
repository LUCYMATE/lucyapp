package com.soltini.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soltini.app.network.ConnectionState
import com.soltini.app.ui.components.AudioVisualizerOrb
import com.soltini.app.ui.theme.AmberConnecting
import com.soltini.app.ui.theme.BluePrimary
import com.soltini.app.ui.theme.BlueSecondary
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.LightBackground
import com.soltini.app.ui.theme.LightBorder
import com.soltini.app.ui.theme.LightSurface
import com.soltini.app.ui.theme.LightSurfaceVariant
import com.soltini.app.ui.theme.RedError
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

@Composable
fun VoiceScreen(
    viewModel: MainViewModel,
    onOpenLogs: () -> Unit = {},
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(granted)
    }

    val isLive = connectionState == ConnectionState.Live

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = LightBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Professional Header Bar
            HeaderSection(
                connectionState = connectionState,
                onReconnect = { viewModel.connect() },
                onOpenLogs = onOpenLogs
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Microphone Permission Banner
            if (!hasMicPermission) {
                PermissionCard(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Error Banner
            errorMessage?.let { error ->
                ErrorCard(error = error, onRetry = { viewModel.connect() })
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Central Audio Visualizer
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AudioVisualizerOrb(
                        micAmplitude = micAmplitude,
                        speakerAmplitude = speakerAmplitude,
                        isLive = isLive,
                        isSpeaking = isSpeaking,
                        isMuted = isMuted
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    val statusText = when {
                        !isLive -> "\"Tap start to begin conversation\""
                        isMuted -> "Microphone muted"
                        isSpeaking -> "Gemini is speaking..."
                        micAmplitude > 0.15f -> "Listening to you..."
                        else -> "Listening... Speak naturally"
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSpeaking) BluePrimary else TextPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("status_label")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "gemini-3.1-flash-live-preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("model_tag")
                    )
                }
            }

            // Live Transcript Section
            if (transcripts.isNotEmpty()) {
                TranscriptSection(
                    transcripts = transcripts,
                    onClear = { viewModel.clearTranscript() },
                    modifier = Modifier
                        .height(150.dp)
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Bottom Footer & Control Bar
            FooterControlSection(
                isLive = isLive,
                isMuted = isMuted,
                connectionState = connectionState,
                onToggleCall = {
                    if (isLive || connectionState == ConnectionState.Connecting) {
                        viewModel.disconnect()
                    } else {
                        viewModel.connect()
                    }
                },
                onToggleMute = { viewModel.toggleMute() }
            )
        }
    }
}

@Composable
private fun HeaderSection(
    connectionState: ConnectionState,
    onReconnect: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, LightBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BluePrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Gemini Live",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Two-Way Voice",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(connectionState = connectionState)

                Spacer(modifier = Modifier.width(8.dp))

                // Logs Button
                IconButton(
                    onClick = onOpenLogs,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("open_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "View Logs",
                        tint = TextSecondary
                    )
                }

                if (connectionState == ConnectionState.Disconnected ||
                    connectionState == ConnectionState.Error
                ) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onReconnect,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("reconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reconnect",
                            tint = BluePrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(connectionState: ConnectionState) {
    val (statusColor, statusText) = when (connectionState) {
        ConnectionState.Live -> GreenLive to "Live Connection"
        ConnectionState.Connecting -> AmberConnecting to "Connecting"
        ConnectionState.Reconnecting -> AmberConnecting to "Reconnecting"
        ConnectionState.Disconnected -> Color.Gray to "Disconnected"
        ConnectionState.Error -> RedError to "Error"
    }

    val animatedColor by animateColorAsState(targetValue = statusColor, label = "statusColor")

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(animatedColor.copy(alpha = 0.12f))
            .border(1.dp, animatedColor.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("connection_status_badge")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(animatedColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = animatedColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, LightBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = AmberConnecting,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Microphone Access Required",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Grant permission to stream audio with Gemini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                modifier = Modifier.testTag("grant_permission_button")
            ) {
                Text("Grant", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = RedError.copy(alpha = 0.1f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, RedError.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = RedError,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry",
                    tint = RedError
                )
            }
        }
    }
}

@Composable
private fun TranscriptSection(
    transcripts: List<TranscriptEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(transcripts.size, transcripts.lastOrNull()?.text?.length) {
        if (transcripts.isNotEmpty()) {
            listState.animateScrollToItem(transcripts.size - 1)
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, LightBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "LIVE TRANSCRIPT",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Transcript",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transcripts, key = { it.id }) { entry ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LightSurfaceVariant)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterControlSection(
    isLive: Boolean,
    isMuted: Boolean,
    connectionState: ConnectionState,
    onToggleCall: () -> Unit,
    onToggleMute: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, LightBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Audio specs metadata badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "STREAM SPECIFICATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isLive) "Full Duplex PCM" else "Standby",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpecPill(text = "16kHz IN")
                    SpecPill(text = "24kHz OUT")
                }
            }

            // Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute Mic Button
                FloatingActionButton(
                    onClick = onToggleMute,
                    containerColor = if (isMuted) RedError else LightSurface,
                    contentColor = if (isMuted) Color.White else TextPrimary,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .size(60.dp)
                        .border(1.dp, LightBorder, RoundedCornerShape(20.dp))
                        .testTag("mute_button")
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Unmute Mic" else "Mute Mic"
                    )
                }

                // Main Call / End Session Action Button
                FloatingActionButton(
                    onClick = onToggleCall,
                    containerColor = if (isLive || connectionState == ConnectionState.Connecting) RedError else BluePrimary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .size(72.dp)
                        .testTag("connect_button")
                ) {
                    Icon(
                        imageVector = if (isLive || connectionState == ConnectionState.Connecting) Icons.Default.CallEnd else Icons.Default.Call,
                        contentDescription = if (isLive) "End Conversation" else "Start Live Conversation",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LightSurface)
            .border(1.dp, LightBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }
}
