package com.lucy.mate.ui

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import com.lucy.mate.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import com.lucy.mate.auth.UserManager
import com.lucy.mate.ui.auth.AccountDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lucy.mate.overlay.OverlayService
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucy.mate.network.ConnectionState
import com.lucy.mate.settings.AppSettings
import com.lucy.mate.ui.components.AudioVisualizerOrb
import com.lucy.mate.ui.components.Live2DView
import com.lucy.mate.ui.theme.AmberConnecting
import com.lucy.mate.ui.theme.EmeraldLive
import com.lucy.mate.ui.theme.PearlWhiteSurface
import com.lucy.mate.ui.theme.PinkyBorder
import com.lucy.mate.ui.theme.PinkyWhiteBackground
import com.lucy.mate.ui.theme.RedError
import com.lucy.mate.ui.theme.RosePink
import com.lucy.mate.ui.theme.RubyShield
import com.lucy.mate.ui.theme.SoftBlush
import com.lucy.mate.ui.theme.SoftViolet
import com.lucy.mate.ui.theme.TextPrimary
import com.lucy.mate.ui.theme.TextSecondary

/**
 * Visualizer modes supported on VoiceScreen.
 */
enum class VisualizerMode(val label: String, val emoji: String) {
    ORB("Sound Orb", "🌸"),
    AVATAR("Live2D Avatar", "🎭")
}

/**
 * Unified 5-item visualizer choices for single bar in exact user-requested order.
 */
enum class VisualizerChoice(
    val label: String,
    val emoji: String,
    val mode: VisualizerMode,
    val characterName: String
) {
    LUCY_3D("LUCY 3D", "👑", VisualizerMode.AVATAR, "LUCY"),
    ORB("Sound Orb", "🌸", VisualizerMode.ORB, ""),
    MAYA("Maya", "🌸", VisualizerMode.AVATAR, "Maya"),
    FRIDAY("Friday", "⚡", VisualizerMode.AVATAR, "Friday"),
    VENOM("Venom", "🖤", VisualizerMode.AVATAR, "Venom");

    companion object {
        fun from(mode: VisualizerMode, char: String): VisualizerChoice {
            return if (mode == VisualizerMode.ORB) {
                ORB
            } else {
                when {
                    char.contains("Lucy", ignoreCase = true) || char.contains("Soltini", ignoreCase = true) -> LUCY_3D
                    char.contains("Maya", ignoreCase = true) -> MAYA
                    char.contains("Friday", ignoreCase = true) -> FRIDAY
                    char.contains("Venom", ignoreCase = true) -> VENOM
                    else -> LUCY_3D
                }
            }
        }
    }
}

@Composable
fun VoiceScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings(context) }
    val initialMode = remember {
        if (appSettings.visualizerMode.equals("AVATAR", ignoreCase = true)) VisualizerMode.AVATAR else VisualizerMode.ORB
    }
    val initialChar = remember {
        val saved = appSettings.characterPersona
        if (saved.isNotBlank()) saved.replace(" (VRM)", "").trim() else "LUCY"
    }
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val micAmplitude by viewModel.micAmplitude.collectAsStateWithLifecycle()
    val speakerAmplitude by viewModel.speakerAmplitude.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()
    val hasMicPermission by viewModel.hasMicPermission.collectAsStateWithLifecycle()
    val isAppPaused by viewModel.isAppPaused.collectAsStateWithLifecycle()
    val isLitePaused by viewModel.isLitePaused.collectAsStateWithLifecycle()
    val isHardPaused by viewModel.isHardPaused.collectAsStateWithLifecycle()
    val isPaused = isAppPaused || isLitePaused || isHardPaused

    var visualizerMode by remember { mutableStateOf(initialMode) }
    var selectedCharacter by remember { mutableStateOf(initialChar) }
    var showChatSheet by remember { mutableStateOf(false) }
    var showQuickPanel by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayBannerDismissed by remember { mutableStateOf(false) }

    var floatingWindowSize by remember { mutableFloatStateOf(appSettings.getFloatingSizeDp().toFloat()) }

    LaunchedEffect(visualizerMode, selectedCharacter) {
        OverlayService.getInstance()?.updateVisualizer(visualizerMode.name, selectedCharacter)
    }

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
                // 1. Top Header Bar (Quick Panel button and Account Profile button)
                VoiceTopBar(
                    connectionState = connectionState,
                    isPaused = isPaused,
                    isBankingPaused = isLitePaused || isHardPaused,
                    onOpenQuickPanel = { showQuickPanel = !showQuickPanel },
                    onOpenAccount = onOpenProfile,
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

                Spacer(Modifier.height(8.dp))

                // Single Unified Model / Visualizer Selection Bar
                val currentChoice = remember(visualizerMode, selectedCharacter) {
                    VisualizerChoice.from(visualizerMode, selectedCharacter)
                }
                VisualizerUnifiedBar(
                    currentChoice = currentChoice,
                    onSelectChoice = { choice ->
                        visualizerMode = choice.mode
                        appSettings.visualizerMode = choice.mode.name
                        if (choice.mode == VisualizerMode.AVATAR) {
                            selectedCharacter = choice.characterName
                            appSettings.characterPersona = if (choice == VisualizerChoice.LUCY_3D) AppSettings.CHARACTER_LUCY else choice.characterName
                        }
                    }
                )

                Spacer(Modifier.height(6.dp))

                // Central Visualizer Stage (fills the screen elegantly)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = visualizerMode,
                        label = "visualizer_stage_crossfade",
                        modifier = Modifier.fillMaxSize()
                    ) { mode ->
                        when (mode) {
                            VisualizerMode.ORB -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AudioVisualizerOrb(
                                        micAmplitude = micAmplitude,
                                        speakerAmplitude = speakerAmplitude,
                                        isLive = isLive,
                                        isSpeaking = isSpeaking,
                                        isMuted = isMuted,
                                        modifier = Modifier.size(240.dp)
                                    )
                                }
                            }
                            VisualizerMode.AVATAR -> {
                                Live2DView(
                                    characterName = selectedCharacter,
                                    amplitude = if (isSpeaking) speakerAmplitude else 0f,
                                    isSpeaking = isSpeaking,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 6. Floating Tactile Dock
                VoiceFloatingDock(
                    connectionState = connectionState,
                    isSpeaking = isSpeaking,
                    isMuted = isMuted,
                    isPaused = isPaused,
                    onMicClick = {
                        if (isPaused) {
                            viewModel.resumeApp()
                        } else if (connectionState != ConnectionState.Live) {
                            viewModel.connect()
                        } else {
                            viewModel.toggleMute()
                        }
                    },
                    onTogglePause = { viewModel.toggleAppPause() },
                    onToggleChat = { showChatSheet = !showChatSheet }
                )
            }

            // 7. Conversation History Bottom Drawer with text input bar + audio response
            AnimatedVisibility(
                visible = showChatSheet,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ConversationHistorySheet(
                    transcripts = transcripts,
                    onSendMessage = { text -> viewModel.sendTextMessage(text) },
                    onClose = { showChatSheet = false },
                    onClear = { viewModel.clearTranscript() }
                )
            }

            // 8. Quick Panel Bottom Drawer
            AnimatedVisibility(
                visible = showQuickPanel,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                QuickPanelSheet(
                    appSettings = appSettings,
                    visualizerMode = visualizerMode,
                    onVisualizerModeChange = { mode ->
                        visualizerMode = mode
                        appSettings.visualizerMode = mode.name
                        OverlayService.getInstance()?.updateVisualizer(mode.name, selectedCharacter)
                    },
                    selectedCharacter = selectedCharacter,
                    onCharacterChange = { char ->
                        selectedCharacter = char
                        appSettings.characterPersona = char
                        OverlayService.getInstance()?.updateVisualizer(visualizerMode.name, char)
                    },
                    floatingWindowSize = floatingWindowSize,
                    onFloatingWindowSizeChange = { newSize ->
                        floatingWindowSize = newSize
                        val sizeInt = newSize.toInt()
                        appSettings.floatingOrbSizeDp = sizeInt
                        appSettings.floatingAvatarSizeDp = sizeInt
                        OverlayService.getInstance()?.updateWindowSize(sizeInt)
                    },
                    onOpenChatSheet = {
                        showQuickPanel = false
                        showChatSheet = true
                    },
                    onOpenSettings = onOpenSettings,
                    onReconnect = { viewModel.connect() },
                    onClose = { showQuickPanel = false }
                )
            }

            // 9. Soltini Account & PRO Status Dialog
            if (showAccountDialog) {
                AccountDialog(
                    onDismiss = { showAccountDialog = false },
                    onSignOut = {
                        showAccountDialog = false
                        onSignOut()
                    }
                )
            }
        }
    }
}

@Composable
private fun VisualizerUnifiedBar(
    currentChoice: VisualizerChoice,
    onSelectChoice: (VisualizerChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(VisualizerChoice.values()) { choice ->
                val isSelected = currentChoice == choice
                val bg = if (isSelected) RosePink else PearlWhiteSurface
                val textColor = if (isSelected) Color.White else TextPrimary
                val borderColor = if (isSelected) RosePink else PinkyBorder

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(bg)
                        .border(1.dp, borderColor, CircleShape)
                        .clickable { onSelectChoice(choice) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = choice.emoji,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = choice.label,
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
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
                    text = if (isSpeaking) "LUCY Speaking" else "Live Subtitles",
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
    isBankingPaused: Boolean = false,
    onOpenQuickPanel: () -> Unit,
    onOpenAccount: () -> Unit,
    onReconnect: () -> Unit
) {
    val context = LocalContext.current
    val userManager = remember { UserManager.getInstance(context) }
    val session by userManager.currentUser.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // User Profile Avatar with LUCY Mascot Logo Badge + Greeting "Hi, {name}" + "LUCY" badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onOpenAccount() }
                .padding(vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier.size(46.dp)
            ) {
                val currentSession = session
                if (!currentSession?.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = currentSession?.avatarUrl,
                        contentDescription = "User Profile",
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, RosePink, CircleShape)
                            .align(Alignment.CenterStart)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (currentSession != null && !currentSession.email.endsWith("@soltini.local")) SoftBlush else PearlWhiteSurface)
                            .border(1.5.dp, RosePink, CircleShape)
                            .align(Alignment.CenterStart),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "User Profile",
                            tint = if (currentSession != null && !currentSession.email.endsWith("@soltini.local")) RosePink else TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Soltini Mascot Icon Badge at bottom-end of profile
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground_adaptive),
                    contentDescription = "LUCY Mascot",
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(PearlWhiteSurface)
                        .border(1.dp, PinkyBorder, CircleShape)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column {
                val firstName = remember(session) {
                    val rawName = session?.name?.trim()
                    if (!rawName.isNullOrBlank()) {
                        rawName.split("\\s+".toRegex()).firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "User"
                    } else {
                        session?.email?.substringBefore("@")?.replaceFirstChar { it.uppercase() } ?: "LUCY"
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Hi, $firstName",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                VoiceStatusBadge(
                    connectionState = connectionState,
                    isPaused = isPaused,
                    isBankingPaused = isBankingPaused
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    .clickable { onOpenQuickPanel() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, "Quick Panel", tint = RosePink, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Quick Panel", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun VoiceStatusBadge(
    connectionState: ConnectionState,
    isPaused: Boolean,
    isBankingPaused: Boolean = false
) {
    val (color, label) = when {
        isBankingPaused -> RubyShield to "Paused (Banking)"
        isPaused -> RubyShield to "Paused"
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
    onMicClick: () -> Unit,
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
            // Master Pause / Resume Quick Toggle
            IconButton(
                onClick = onTogglePause,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isPaused) RubyShield.copy(alpha = 0.15f) else PinkyWhiteBackground)
                    .border(1.dp, if (isPaused) RubyShield.copy(alpha = 0.35f) else PinkyBorder, CircleShape)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isPaused) "Resume App" else "Pause App",
                    tint = if (isPaused) RubyShield else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Big Central Rose Pink Mic Button
            FloatingActionButton(
                onClick = onMicClick,
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
    onSendMessage: (String) -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            listState.animateScrollToItem(transcripts.size - 1)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(520.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        border = BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Chat, null, tint = RosePink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Chat & History", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (transcripts.isNotEmpty()) {
                        Text(
                            "Copy All",
                            color = SoftViolet,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    val fullConversation = transcripts.joinToString("\n\n") {
                                        val speaker = if (it.sender == TranscriptEntry.Sender.USER) "You" else "LUCY"
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
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Transcript list
            if (transcripts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No messages yet. Speak or type below to talk with LUCY!",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isUser) "You" else "LUCY (AI)",
                                    color = if (isUser) RosePink else SoftViolet,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                                if (!isUser) {
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            Toast.makeText(context, "Flagged response for safety review. Thank you!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = "Report AI Response",
                                            tint = TextSecondary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }

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

            Spacer(Modifier.height(8.dp))

            // Bottom Text Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            "Type to LUCY (e.g. noisy room)...",
                            color = TextSecondary,
                            fontSize = 12.5.sp
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = PinkyWhiteBackground,
                        unfocusedContainerColor = PinkyWhiteBackground,
                        focusedBorderColor = RosePink,
                        unfocusedBorderColor = PinkyBorder,
                        cursorColor = RosePink
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val trimmed = inputText.trim()
                            if (trimmed.isNotBlank()) {
                                onSendMessage(trimmed)
                                inputText = ""
                                focusManager.clearFocus()
                            }
                        }
                    )
                )

                IconButton(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotBlank()) {
                            onSendMessage(trimmed)
                            inputText = ""
                            focusManager.clearFocus()
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(RosePink)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPanelSheet(
    appSettings: AppSettings,
    visualizerMode: VisualizerMode,
    onVisualizerModeChange: (VisualizerMode) -> Unit,
    selectedCharacter: String,
    onCharacterChange: (String) -> Unit,
    floatingWindowSize: Float,
    onFloatingWindowSizeChange: (Float) -> Unit,
    onOpenChatSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var apiKeyText by remember { mutableStateOf(appSettings.geminiApiKey) }
    var isApiKeyVisible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        border = BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(RosePink.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = RosePink, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Quick Control Panel",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            // 1. Single Unified Floating Window Size Slider Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PinkyWhiteBackground),
                border = BorderStroke(1.dp, PinkyBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AspectRatio, null, tint = RosePink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Floating Window Size", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("${floatingWindowSize.toInt()} dp", color = RosePink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        "Unified size control for floating Sound Orb & Virtual Avatar",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Slider(
                        value = floatingWindowSize.coerceIn(40f, 250f),
                        onValueChange = onFloatingWindowSizeChange,
                        valueRange = 40f..250f,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = RosePink,
                            activeTrackColor = RosePink,
                            inactiveTrackColor = PinkyBorder
                        )
                    )
                }
            }

            // 2. Fast Inline Gemini API Key Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PinkyWhiteBackground),
                border = BorderStroke(1.dp, PinkyBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = RosePink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gemini API Key", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = { apiKeyText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste Google AI Studio API Key...", color = TextSecondary, fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle key visibility",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = PearlWhiteSurface,
                            unfocusedContainerColor = PearlWhiteSurface,
                            focusedBorderColor = RosePink,
                            unfocusedBorderColor = PinkyBorder,
                            cursorColor = RosePink
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val trimmed = apiKeyText.trim()
                            if (trimmed.isNotBlank()) {
                                appSettings.geminiApiKey = trimmed
                                Toast.makeText(context, "API Key saved! Connecting...", Toast.LENGTH_SHORT).show()
                                onReconnect()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RosePink),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save & Connect", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 3. Quick Visualizer & Character Picker
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PinkyWhiteBackground),
                border = BorderStroke(1.dp, PinkyBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Display Mode & Character", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VisualizerMode.values().forEach { mode ->
                            val isSelected = visualizerMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) RosePink else PearlWhiteSurface)
                                    .border(1.dp, if (isSelected) RosePink else PinkyBorder, RoundedCornerShape(14.dp))
                                    .clickable { onVisualizerModeChange(mode) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${mode.emoji} ${mode.label}",
                                    color = if (isSelected) Color.White else TextPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    if (visualizerMode == VisualizerMode.AVATAR) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("LUCY" to "👑", "Maya" to "🌸", "Friday" to "⚡", "Venom" to "🖤").forEach { (charName, emoji) ->
                                val isSelected = selectedCharacter.equals(charName, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) RosePink.copy(alpha = 0.15f) else PearlWhiteSurface)
                                        .border(1.dp, if (isSelected) RosePink else PinkyBorder, RoundedCornerShape(12.dp))
                                        .clickable { onCharacterChange(charName) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "$emoji $charName",
                                        color = if (isSelected) RosePink else TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Action Shortcuts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenChatSheet,
                    modifier = Modifier.weight(1f).height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PearlWhiteSurface),
                    border = BorderStroke(1.dp, PinkyBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Chat, null, tint = RosePink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Chat History", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        onClose()
                        onOpenSettings()
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PearlWhiteSurface),
                    border = BorderStroke(1.dp, PinkyBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Tune, null, tint = SoftViolet, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("All Settings", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                        text = "Access LUCY anywhere over other apps",
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
