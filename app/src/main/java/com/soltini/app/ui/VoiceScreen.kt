package com.soltini.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
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
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.AmberConnecting
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.GreenLive
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

    LaunchedEffect(hasMicPermission, connectionState) {
        if (hasMicPermission && (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error)) {
            viewModel.connect()
        }
    }

    val isLive = connectionState == ConnectionState.Live

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = DarkBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            
            // 1. Central Immersive Orb
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AudioVisualizerOrb(
                    micAmplitude = micAmplitude,
                    speakerAmplitude = speakerAmplitude,
                    isLive = isLive,
                    isSpeaking = isSpeaking,
                    isMuted = isMuted,
                    modifier = Modifier.size(300.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                val statusText = when {
                    !isLive -> "Ready to connect"
                    isMuted -> "Microphone muted"
                    isSpeaking -> "Gemini is speaking..."
                    micAmplitude > 0.15f -> "Listening..."
                    else -> "Listening..."
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSpeaking) AccentBlue else TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }

            // 2. Top Header (Minimalist)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(connectionState = connectionState)
                
                Row {
                    if (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error) {
                        IconButton(onClick = { viewModel.connect() }) {
                            Icon(Icons.Default.Refresh, "Reconnect", tint = AccentBlue)
                        }
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.BugReport, "Logs", tint = TextSecondary)
                    }
                }
            }

            // 3. Floating Notifications (Errors & Permissions)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp, start = 16.dp, end = 16.dp)
            ) {
                // Only errors are shown floating now (permissions handled by PermissionsScreen)
                
                AnimatedVisibility(visible = errorMessage != null, enter = fadeIn(), exit = fadeOut()) {
                    errorMessage?.let { ErrorCard(it) { viewModel.connect() } }
                }
            }

            // 4. Floating Transcript & Controls at Bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                AnimatedVisibility(
                    visible = transcripts.isNotEmpty(),
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    FloatingTranscript(transcripts = transcripts)
                }

                Spacer(modifier = Modifier.height(16.dp))

                SleekDock(
                    isMuted = isMuted,
                    onToggleMute = { viewModel.toggleMute() }
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(connectionState: ConnectionState) {
    val (statusColor, statusText) = when (connectionState) {
        ConnectionState.Live -> GreenLive to "Live"
        ConnectionState.Connecting -> AmberConnecting to "Connecting"
        ConnectionState.Reconnecting -> AmberConnecting to "Reconnecting"
        ConnectionState.Disconnected -> Color.Gray to "Disconnected"
        ConnectionState.Error -> RedError to "Error"
    }
    
    val animatedColor by animateColorAsState(statusColor, label = "statusColor")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animatedColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = statusText, color = animatedColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FloatingTranscript(transcripts: List<TranscriptEntry>) {
    val listState = rememberLazyListState()

    LaunchedEffect(transcripts.size, transcripts.lastOrNull()?.text?.length) {
        if (transcripts.isNotEmpty()) listState.animateScrollToItem(transcripts.size - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(DarkSurface.copy(alpha = 0.8f)) // Glassmorphism translucent
            .border(1.dp, DarkBorder, RoundedCornerShape(24.dp))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(transcripts, key = { it.id }) { entry ->
                val isUser = entry.sender == TranscriptEntry.Sender.USER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isUser) DarkSurfaceVariant else AccentBlue.copy(alpha = 0.15f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = entry.text,
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SleekDock(
    isMuted: Boolean,
    onToggleMute: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute Button
        FloatingActionButton(
            onClick = onToggleMute,
            containerColor = if (isMuted) RedError else DarkSurfaceVariant,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, "Mute")
        }
    }
}



@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RedError.copy(alpha = 0.1f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, RedError.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = RedError, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry", tint = RedError) }
        }
    }
}
