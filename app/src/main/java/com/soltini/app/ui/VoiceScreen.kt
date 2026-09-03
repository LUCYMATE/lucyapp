package com.soltini.app.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.soltini.app.overlay.OverlayService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soltini.app.network.ConnectionState
import com.soltini.app.ui.components.AudioVisualizerOrb
import com.soltini.app.ui.components.Live2DView
import com.soltini.app.ui.theme.AmberConnecting
import com.soltini.app.ui.theme.EmeraldLive
import com.soltini.app.ui.theme.PearlWhiteSurface
import com.soltini.app.ui.theme.PinkyBorder
import com.soltini.app.ui.theme.PinkyWhiteBackground
import com.soltini.app.ui.theme.RedError
import com.soltini.app.ui.theme.RosePink
import com.soltini.app.ui.theme.RubyShield
import com.soltini.app.ui.theme.SoftBlush
import com.soltini.app.ui.theme.SoftViolet
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

/**
 * Visualizer modes supported on VoiceScreen.
 */
enum class VisualizerMode(val label: String, val emoji: String) {
    ORB("Sound Orb", "🌸"),
    AVATAR("Live2D Avatar", "🎭")
}

@Composable
fun VoiceScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val micAmplitude by viewModel.micAmplitude.collectAsStateWithLifecycle()
    val speakerAmplitude by viewModel.speakerAmplitude.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()
    val hasMicPermission by viewModel.hasMicPermission.collectAsStateWithLifecycle()
    val isLitePaused by viewModel.isLitePaused.collectAsStateWithLifecycle()
    val isHardPaused by viewModel.isHardPaused.collectAsStateWithLifecycle()
    val isPaused = isLitePaused || isHardPaused

    var visualizerMode by remember { mutableStateOf(VisualizerMode.ORB) }
    var selectedCharacter by remember { mutableStateOf("Soltini") }
    var showChatSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayBannerDismissed by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = Settings.canDrawOverlays(context)
                if (granted && !hasOverlayPermission) {
                    hasOverlayPermission = true
                    OverlayService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasMicPermission, connectionState, isPaused) {
        if (hasMicPermission && !isPaused && (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error)) {
            viewModel.connect()
        }
    }

    val isLive = connectionState == ConnectionState.Live && !isPaused
    val latestTranscript = transcripts.lastOrNull()?.text ?: "Listening... Tap the mic or speak freely"

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = PinkyWhiteBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Main Content Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Top Header Bar
                VoiceTopBar(
                    connectionState = connectionState,
                    isPaused = isPaused,
                    onToggleChat = { showChatSheet = !showChatSheet },
                    onReconnect = { viewModel.connect() }
                )

                // Floating Overlay Permission Luxury Banner
                if (!hasOverlayPermission && !overlayBannerDismissed) {
                    Spacer(Modifier.height(10.dp))
                    FloatingOverlayPromptBanner(
                        onEnable = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            )
                        },
                        onDismiss = { overlayBannerDismissed = true }
                    )
                }

                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    ErrorCard(errorMessage = errorMessage!!) { viewModel.connect() }
                }

                Spacer(Modifier.height(10.dp))

                // 2. Mode Selector Pill ("🌸 Sound Orb" vs "🎭 Live2D Avatar")
                VisualizerModeSelector(
                    currentMode = visualizerMode,
                    onSelectMode = { visualizerMode = it }
                )

                // 3. Character Selection Bar when in Avatar mode
                AnimatedVisibility(
                    visible = visualizerMode == VisualizerMode.AVATAR,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        CharacterSelectionBar(
                            selectedCharacter = selectedCharacter,
                            onSelectCharacter = { selectedCharacter = it }
                        )
                    }
                }

                // 4. Central Stage (Orb or Avatar) + Subtitles Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Crossfade(
                            targetState = visualizerMode,
                            label = "visualizer_stage_crossfade"
                        ) { mode ->
                            when (mode) {
                                VisualizerMode.ORB -> {
                                    AudioVisualizerOrb(
                                        micAmplitude = micAmplitude,
                                        speakerAmplitude = speakerAmplitude,
                                        isLive = isLive,
                                        isSpeaking = isSpeaking,
                                        isMuted = isMuted,
                                        modifier = Modifier.size(230.dp)
                                    )
                                }
                                VisualizerMode.AVATAR -> {
                                    Live2DView(
                                        characterName = selectedCharacter,
                                        amplitude = if (isSpeaking) speakerAmplitude else micAmplitude,
                                        isSpeaking = isSpeaking,
                                        modifier = Modifier.size(230.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Real-time Subtitles card in Pearl White with PinkyBorder
                        SubtitlesCard(
                            latestTranscript = latestTranscript,
                            isSpeaking = isSpeaking
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 5. Quick Action Chips
                VoiceQuickActionsRow(
                    onTrigger = { _, prompt ->
                        viewModel.sendTextMessage(prompt)
                    }
                )

                Spacer(Modifier.height(10.dp))

                // 6. Floating Tactile Dock
                VoiceFloatingDock(
                    connectionState = connectionState,
                    isSpeaking = isSpeaking,
                    isMuted = isMuted,
                    isPaused = isPaused,
                    onToggleMute = { viewModel.toggleMute() },
                    onTogglePause = { viewModel.toggleLitePauseMode() },
                    onToggleChat = { showChatSheet = !showChatSheet }
                )
            }

            // 7. Conversation History Bottom Drawer with copy/clear
            AnimatedVisibility(
                visible = showChatSheet,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ConversationHistorySheet(
                    transcripts = transcripts,
                    onClose = { showChatSheet = false },
                    onClear = { viewModel.clearTranscript() }
                )
            }
        }
    }
}

@Composable
private fun VisualizerModeSelector(
    currentMode: VisualizerMode,
    onSelectMode: (VisualizerMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(PearlWhiteSurface)
            .border(1.dp, PinkyBorder, CircleShape)
            .padding(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VisualizerMode.values().forEach { mode ->
                val isSelected = currentMode == mode
                val bg = if (isSelected) RosePink else Color.Transparent
                val textColor = if (isSelected) Color.White else TextSecondary
                val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(bg)
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${mode.emoji} ${mode.label}",
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = fontWeight
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterSelectionBar(
    selectedCharacter: String,
    onSelectCharacter: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val characters = listOf(
        "Soltini" to "👑",
        "Maya" to "🌸",
        "Friday" to "⚡",
        "Venom" to "🖤"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            characters.forEach { (name, emoji) ->
                val isSelected = selectedCharacter.equals(name, ignoreCase = true)
                val bg = if (isSelected) RosePink else PearlWhiteSurface
                val textColor = if (isSelected) Color.White else TextPrimary
                val borderColor = if (isSelected) RosePink else PinkyBorder

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(bg)
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .clickable { onSelectCharacter(name) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(emoji, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = name,
                            color = textColor,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitlesCard(
    latestTranscript: String,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(0.92f),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isSpeaking) RosePink else SoftViolet)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isSpeaking) "Soltini Speaking" else "Live Subtitles",
                    color = if (isSpeaking) RosePink else SoftViolet,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = latestTranscript,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VoiceTopBar(
    connectionState: ConnectionState,
    isPaused: Boolean,
    onToggleChat: () -> Unit,
    onReconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(listOf(RosePink, SoftViolet))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("S", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "SOLTINI",
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp
                )
                VoiceStatusBadge(connectionState = connectionState, isPaused = isPaused)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error) {
                IconButton(onClick = onReconnect, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, "Reconnect", tint = RosePink, modifier = Modifier.size(20.dp))
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(PearlWhiteSurface)
                    .border(1.dp, PinkyBorder, RoundedCornerShape(16.dp))
                    .clickable { onToggleChat() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Chat, "History", tint = RosePink, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("History", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun VoiceStatusBadge(connectionState: ConnectionState, isPaused: Boolean) {
    val (color, label) = when {
        isPaused -> RubyShield to "Paused (Banking)"
        connectionState == ConnectionState.Live -> EmeraldLive to "Live Audio"
        connectionState == ConnectionState.Connecting -> AmberConnecting to "Connecting"
        connectionState == ConnectionState.Reconnecting -> AmberConnecting to "Reconnecting"
        connectionState == ConnectionState.Error -> RedError to "Connection Error"
        else -> TextSecondary to "Offline"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VoiceFloatingDock(
    connectionState: ConnectionState,
    isSpeaking: Boolean,
    isMuted: Boolean,
    isPaused: Boolean,
    onToggleMute: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Banking Shield Quick Pause
            IconButton(
                onClick = onTogglePause,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isPaused) RubyShield.copy(alpha = 0.15f) else PinkyWhiteBackground)
                    .border(1.dp, if (isPaused) RubyShield.copy(alpha = 0.35f) else PinkyBorder, CircleShape)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Shield,
                    contentDescription = "Banking Shield",
                    tint = if (isPaused) RubyShield else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Big Central Rose Pink Mic Button
            FloatingActionButton(
                onClick = onToggleMute,
                containerColor = if (isMuted) Color(0xFFF1F5F9) else RosePink,
                contentColor = if (isMuted) TextSecondary else Color.White,
                shape = CircleShape,
                modifier = Modifier.size(58.dp)
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    modifier = Modifier.size(26.dp)
                )
            }

            // Conversation History Button
            IconButton(
                onClick = onToggleChat,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(PinkyWhiteBackground)
                    .border(1.dp, PinkyBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = "Conversation History",
                    tint = RosePink,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceQuickActionsRow(
    onTrigger: (macroName: String, prompt: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val macros = listOf(
        "📸 Read Screen" to "Take a screenshot and describe everything on my screen",
        "💬 WhatsApp"    to "Open WhatsApp and help me compose a message",
        "▶️ YouTube"     to "Open YouTube and play relaxing music",
        "🔒 Lock Device" to "Lock the device screen right now"
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(macros) { (title, prompt) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(PearlWhiteSurface)
                    .border(1.dp, PinkyBorder, RoundedCornerShape(16.dp))
                    .clickable { onTrigger(title, prompt) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistorySheet(
    transcripts: List<TranscriptEntry>,
    onClose: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(440.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        border = BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Conversation History", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (transcripts.isNotEmpty()) {
                        Text(
                            "Copy All",
                            color = SoftViolet,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    val fullConversation = transcripts.joinToString("\n\n") {
                                        val speaker = if (it.sender == TranscriptEntry.Sender.USER) "You" else "Soltini"
                                        "$speaker: ${it.text}"
                                    }
                                    clipboardManager.setText(AnnotatedString(fullConversation))
                                    Toast.makeText(context, "Full conversation copied", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        "Clear",
                        color = RosePink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onClear() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    Text(
                        "Close",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (transcripts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conversation yet. Speak to Soltini!", color = TextSecondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transcripts, key = { it.id }) { entry ->
                        val isUser = entry.sender == TranscriptEntry.Sender.USER
                        val bg = if (isUser) RosePink.copy(alpha = 0.12f) else PinkyWhiteBackground
                        val align = if (isUser) Alignment.End else Alignment.Start

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = align
                        ) {
                            Text(
                                text = if (isUser) "You" else "Soltini",
                                color = if (isUser) RosePink else SoftViolet,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 20.dp,
                                            topEnd = 20.dp,
                                            bottomStart = if (isUser) 20.dp else 6.dp,
                                            bottomEnd = if (isUser) 6.dp else 20.dp
                                        )
                                    )
                                    .background(bg)
                                    .border(1.dp, PinkyBorder, RoundedCornerShape(
                                        topStart = 20.dp,
                                        topEnd = 20.dp,
                                        bottomStart = if (isUser) 20.dp else 6.dp,
                                        bottomEnd = if (isUser) 6.dp else 20.dp
                                    ))
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            clipboardManager.setText(AnnotatedString(entry.text))
                                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = entry.text,
                                    color = TextPrimary,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingOverlayPromptBanner(
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        border = BorderStroke(1.5.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(RosePink.copy(alpha = 0.12f))
                        .border(1.dp, RosePink.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = RosePink,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Enable Floating Voice Orb",
                        color = TextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Access Soltini anywhere over other apps",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onEnable,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RosePink,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(34.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
                ) {
                    Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorCard(errorMessage: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RedError.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, RedError.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, null, tint = RedError, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(errorMessage, color = RedError, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                "Retry",
                color = RedError,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onRetry() }.padding(4.dp)
            )
        }
    }
}
