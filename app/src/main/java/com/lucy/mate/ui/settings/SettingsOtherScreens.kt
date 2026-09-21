package com.lucy.mate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucy.mate.memory.MemoryManager
import com.lucy.mate.overlay.OverlayService
import com.lucy.mate.settings.AppSettings
import com.lucy.mate.ui.MainViewModel
import com.lucy.mate.ui.theme.*

@Composable
fun SettingsBankingShieldScreen(appSettings: AppSettings?) {
    val scrollState = rememberScrollState()
    var isEnabled by remember { mutableStateOf(appSettings?.isBankingProtectionEnabled ?: true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PremiumCard(title = "Shield Configuration") {
            SettingsSwitchRow(
                icon = Icons.Default.Shield,
                title = "Banking Protection Mode",
                subtitle = "Automatically pauses voice capture and silences accessibility hooks when banking and payment apps open.",
                checked = isEnabled,
                onCheckedChange = {
                    isEnabled = it
                    appSettings?.isBankingProtectionEnabled = it
                }
            )
        }

        PremiumCard(title = "Protected Banking Apps (26 Default)") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Google Pay (GPay)" to "com.google.android.apps.nbu.paisa.user",
                    "PhonePe" to "com.phonepe.app",
                    "Paytm" to "net.one97.paytm",
                    "SBI YONO" to "com.sbi.lotusintouch",
                    "YONO Lite SBI" to "com.sbi.yono",
                    "HDFC MobileBanking" to "com.snapwork.ibank",
                    "ICICI iMobile" to "com.csam.icici.bank.imobile",
                    "Axis Mobile" to "com.axis.mobile",
                    "Kotak Mobile Banking" to "com.kotak.mobilebanking",
                    "CRED" to "com.dreamplug.androidapp",
                    "Fi Money" to "com.fi.money",
                    "Jupiter Money" to "com.jupiter.money",
                    "Razorpay" to "com.razorpay",
                    "PayPal" to "com.paypal.android.p2pmobile",
                    "Revolut" to "com.revolut.revolut",
                    "N26 Mobile Bank" to "de.number26.android",
                    "Chase Mobile" to "com.chase.sig.android",
                    "Citi Mobile" to "com.citi.citimobile",
                    "Bank of America" to "com.bankofamerica.mft.mobile",
                    "Barclays Mobile" to "com.barclays.android.barclaysmobilebanking",
                    "Kotak K-Secure" to "com.msf.ksecure",
                    "Deutsche Bank" to "com.db.pwa.ind",
                    "BOB World (Bank of Baroda)" to "com.bankofbaroda.mconnect",
                    "IndusInd Mobile" to "com.fifteen",
                    "Canara ai1" to "com.canarabank.ai1",
                    "Union Bank Mobile" to "com.unionbank.online.android",
                    "IDFC FIRST Bank" to "com.idfcfirstbank.banking"
                ).forEach { (appName, pkg) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(appName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(pkg, color = TextSecondary, fontSize = 10.sp)
                        }
                        Text("Active", color = EmeraldLive, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsMemoryScreen(memoryManager: MemoryManager?) {
    val scrollState = rememberScrollState()
    var keepHistory by remember { mutableStateOf(true) }
    var summarizeOld by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PremiumCard(title = "Conversation Memory") {
            SettingsSwitchRow(
                icon = Icons.Default.History,
                title = "Keep Chat History",
                subtitle = "Saves past conversations so LUCY remembers context over days and weeks.",
                checked = keepHistory,
                onCheckedChange = { keepHistory = it }
            )
            
            SettingsSwitchRow(
                icon = Icons.Default.Compress,
                title = "Auto-Summarize",
                subtitle = "Compresses older conversations to save space while retaining core facts.",
                checked = summarizeOld,
                onCheckedChange = { summarizeOld = it }
            )
        }
    }
}

@Composable
fun SettingsCallsScreen(viewModel: MainViewModel?) {
    val scrollState = rememberScrollState()
    var autoSpeaker by remember { mutableStateOf(true) }
    var autoAnswerCalls by remember { mutableStateOf(false) }
    var announceCaller by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PremiumCard(title = "Call Handling") {
            SettingsSwitchRow(
                icon = Icons.Default.VolumeUp,
                title = "Auto Speakerphone",
                subtitle = "Enables speakerphone automatically when taking hands-free calls through LUCY.",
                checked = autoSpeaker,
                onCheckedChange = { autoSpeaker = it }
            )

            SettingsSwitchRow(
                icon = Icons.Default.RecordVoiceOver,
                title = "Announce Caller Name",
                subtitle = "LUCY announces incoming caller names out loud via TTS.",
                checked = announceCaller,
                onCheckedChange = { announceCaller = it }
            )

            SettingsSwitchRow(
                icon = Icons.Default.Call,
                title = "Auto-Answer WhatsApp Calls",
                subtitle = "Automatically accepts authorized incoming WhatsApp calls when hands-free.",
                checked = autoAnswerCalls,
                onCheckedChange = { autoAnswerCalls = it }
            )
        }

        PremiumCard(title = "Automation & Background Persistence") {
            var isSmartTouchEnabled by remember { mutableStateOf(viewModel?.appSettings?.isSmartManualDetectionEnabled ?: true) }
            var isAutoStart by remember { mutableStateOf(viewModel?.appSettings?.isAutoStartEnabled ?: true) }

            SettingsSwitchRow(
                icon = Icons.Default.Accessibility,
                title = "Smart Manual Detection",
                subtitle = "Automatically halts background gestures and accessibility clicks whenever you touch, scroll, or type on the screen.",
                checked = isSmartTouchEnabled,
                onCheckedChange = {
                    isSmartTouchEnabled = it
                    viewModel?.appSettings?.isSmartManualDetectionEnabled = it
                }
            )

            SettingsSwitchRow(
                icon = Icons.Default.PowerSettingsNew,
                title = "Background Auto-Start",
                subtitle = "Automatically starts voice & overlay services on device boot/unlock and revives them if swiped away from recent tasks.",
                checked = isAutoStart,
                onCheckedChange = {
                    isAutoStart = it
                    viewModel?.appSettings?.isAutoStartEnabled = it
                }
            )
        }
    }
}

@Composable
fun SettingsConnectorsScreen(viewModel: MainViewModel?) {
    val scrollState = rememberScrollState()
    var syncCalendar by remember { mutableStateOf(false) }
    var syncGmail by remember { mutableStateOf(false) }
    var syncSpotify by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PremiumCard(title = "Google Workspace") {
            SettingsSwitchRow(
                icon = Icons.Default.CalendarToday,
                title = "Google Calendar",
                subtitle = "Allow LUCY to query schedule, add events, and set reminders.",
                checked = syncCalendar,
                onCheckedChange = { syncCalendar = it }
            )
            
            SettingsSwitchRow(
                icon = Icons.Default.Mail,
                title = "Gmail Integration",
                subtitle = "Summarize important emails, draft replies, and find flight tickets or receipts.",
                checked = syncGmail,
                onCheckedChange = { syncGmail = it }
            )
        }
        
        PremiumCard(title = "Third-Party Services") {
            SettingsSwitchRow(
                icon = Icons.Default.LibraryMusic,
                title = "Spotify Controller",
                subtitle = "Control playback, search songs, and manage playlists.",
                checked = syncSpotify,
                onCheckedChange = { syncSpotify = it }
            )
        }
    }
}

@Composable
fun SettingsAboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(RosePink.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "App Logo",
                tint = RosePink,
                modifier = Modifier.size(42.dp)
            )
        }
        
        Spacer(Modifier.height(16.dp))
        Text("LUCY AI Voice Agent", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(4.dp))
        Text("Version 2.0 (Pinky-White Redesign)", color = TextSecondary, fontSize = 14.sp)
        
        Spacer(Modifier.height(32.dp))
        
        PremiumCard {
            SettingsInfoRow(
                icon = Icons.Default.Code,
                title = "Core Architecture",
                subtitle = "Powered by Google Gemini Multimodal Live API (WebSocket 16/24kHz PCM), Jetpack Compose, and SQLite Room vector memory."
            )
            SettingsInfoRow(
                icon = Icons.Default.Person,
                title = "Developer",
                subtitle = "Satish Acharya (satish.com.np)"
            )
            SettingsInfoRow(
                icon = Icons.Default.AutoAwesome,
                title = "Assistant Role",
                subtitle = "Registered Voice Interaction Service on Android"
            )
        }
    }
}

@Composable
fun SettingsFloatingOverlayScreen(appSettings: AppSettings?) {
    val scrollState = rememberScrollState()
    var visualizerMode by remember { mutableStateOf(appSettings?.visualizerMode ?: "ORB") }
    val isAvatarMode = visualizerMode.equals("AVATAR", ignoreCase = true)
    var windowSize by remember { mutableFloatStateOf((appSettings?.getFloatingSizeDp() ?: 100).toFloat()) }

    val characters = listOf(
        AppSettings.CHARACTER_LUCY,
        AppSettings.CHARACTER_MAYA,
        AppSettings.CHARACTER_FRIDAY,
        AppSettings.CHARACTER_VENOM
    )
    var selectedChar by remember {
        mutableStateOf(appSettings?.characterPersona ?: AppSettings.CHARACTER_LUCY)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PremiumCard(title = "Visualizer Mode") {
            SettingsSwitchRow(
                icon = if (isAvatarMode) Icons.Default.SmartToy else Icons.Default.GraphicEq,
                title = if (isAvatarMode) "Virtual Avatar Mode" else "Sound Orb Mode",
                subtitle = if (isAvatarMode)
                    "Displaying interactive floating 3D/2D avatar character over apps."
                else
                    "Displaying minimal luminous sound orb ring visualizer over apps.",
                checked = isAvatarMode,
                onCheckedChange = { isAvatar ->
                    val newMode = if (isAvatar) "AVATAR" else "ORB"
                    visualizerMode = newMode
                    appSettings?.visualizerMode = newMode
                    OverlayService.getInstance()?.let { service ->
                        service.updateVisualizer(newMode, selectedChar)
                        service.updateWindowSize(windowSize.toInt())
                    }
                }
            )
        }

        PremiumCard(title = "Floating Window Dimensions") {
            SettingsSliderRow(
                icon = Icons.Default.AspectRatio,
                title = "Floating Window Size",
                value = windowSize,
                valueRange = 40f..250f,
                steps = 42,
                onValueChange = { newSize ->
                    windowSize = newSize
                    val sizeInt = newSize.toInt()
                    appSettings?.floatingOrbSizeDp = sizeInt
                    appSettings?.floatingAvatarSizeDp = sizeInt
                    OverlayService.getInstance()?.updateWindowSize(sizeInt)
                },
                valueLabel = "${windowSize.toInt()} dp"
            )
            Text(
                text = "Single unified size controller for both Sound Orb and Virtual Avatar over other apps (40dp to 250dp). Recommended: 100dp.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
        }

        if (isAvatarMode) {
            PremiumCard(title = "Avatar Character") {
                SettingsDropdownRow(
                    icon = Icons.Default.Person,
                    title = "Active Character",
                    selectedOption = selectedChar,
                    options = characters,
                    onOptionSelected = { char ->
                        selectedChar = char
                        appSettings?.characterPersona = char
                        OverlayService.getInstance()?.updateVisualizer("AVATAR", char)
                    }
                )
            }
        }

        PremiumCard(title = "Interaction Tips") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsInfoRow(
                    icon = Icons.Default.TouchApp,
                    title = "Drag to Reposition",
                    subtitle = "Hold and drag the floating overlay to dock it anywhere on screen edges."
                )
                SettingsInfoRow(
                    icon = Icons.Default.Mic,
                    title = "Tap to Wake Assistant",
                    subtitle = "Tap the overlay while using any app to instantly activate voice listening."
                )
            }
        }

        PremiumCard(title = "Legal & Attribution") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Live2D Cubism SDK is copyright © Live2D Inc. Used in accordance with Live2D Open Software / Indie License Agreement.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Text(
                    text = "AI Voice Engine powered by Google Gemini Live API. Speech and conversational queries are processed securely.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

