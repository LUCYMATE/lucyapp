package com.soltini.app.ui.settings

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
import com.soltini.app.memory.MemoryManager
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.MainViewModel
import com.soltini.app.ui.theme.*

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
                subtitle = "Saves past conversations so Soltini remembers context over days and weeks.",
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
                subtitle = "Enables speakerphone automatically when taking hands-free calls through Soltini.",
                checked = autoSpeaker,
                onCheckedChange = { autoSpeaker = it }
            )

            SettingsSwitchRow(
                icon = Icons.Default.RecordVoiceOver,
                title = "Announce Caller Name",
                subtitle = "Soltini announces incoming caller names out loud via TTS.",
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
                subtitle = "Allow Soltini to query schedule, add events, and set reminders.",
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
        Text("Soltini Voice Agent", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
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
