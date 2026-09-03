package com.soltini.app.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val AmberWarning = Color(0xFFFFA726)
private val Purple = Color(0xFF9C27B0)

enum class PermTier { REQUIRED, AGENT, RECOMMENDED }

@Composable
fun SettingsPermissionsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    var hasMic by remember { mutableStateOf(SettingsPermChecks.checkMic(context)) }
    var hasOverlay by remember { mutableStateOf(SettingsPermChecks.checkOverlay(context)) }
    var hasBattery by remember { mutableStateOf(SettingsPermChecks.checkBattery(context)) }
    var hasAccessibility by remember { mutableStateOf(SettingsPermChecks.checkAccessibility(context)) }
    var hasAssistantRole by remember { mutableStateOf(SettingsPermChecks.checkAssistantRole(context)) }
    var hasRestrictedSettings by remember { mutableStateOf(SettingsPermChecks.checkRestrictedSettings(context)) }
    
    var hasLocation by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var hasContacts by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var hasCallLog by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) }
    var hasAnswerCalls by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) }
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var hasWriteSettings by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var hasNotifListener by remember { mutableStateOf(
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName, ignoreCase = true) == true
    )}

    val requiresAutoStart by remember { mutableStateOf(SettingsPermChecks.checkRequiresAutoStart(context)) }
    var autoStartRequested by remember {
        mutableStateOf(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("autostart_requested", false))
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasMic = granted }
    val multiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocation
        hasContacts = permissions[Manifest.permission.READ_CONTACTS] ?: hasContacts
        hasCallLog = permissions[Manifest.permission.READ_CALL_LOG] ?: hasCallLog
        hasAnswerCalls = permissions[Manifest.permission.ANSWER_PHONE_CALLS] ?: hasAnswerCalls
        hasCamera = permissions[Manifest.permission.CAMERA] ?: hasCamera
    }

    val roleLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasAssistantRole = SettingsPermChecks.checkAssistantRole(context)
        }
    } else null

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMic = SettingsPermChecks.checkMic(context)
                hasOverlay = SettingsPermChecks.checkOverlay(context)
                hasBattery = SettingsPermChecks.checkBattery(context)
                hasAccessibility = SettingsPermChecks.checkAccessibility(context)
                hasAssistantRole = SettingsPermChecks.checkAssistantRole(context)
                hasRestrictedSettings = SettingsPermChecks.checkRestrictedSettings(context)
                hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                hasCallLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                hasAnswerCalls = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                hasWriteSettings = Settings.System.canWrite(context)
                hasNotifListener = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName, ignoreCase = true) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text("Permissions & Capabilities", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 24.dp))
        
        SectionLabel("Required Permissions")
        PermissionCard(Icons.Default.Mic, "Microphone", "Capture your voice for real-time AI conversation.", hasMic, PermTier.REQUIRED) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        PermissionCard(Icons.Default.Layers, "Display Over Other Apps", "Show the floating orb on top of any screen.", hasOverlay, PermTier.REQUIRED) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("AI Agent Capabilities")
        PermissionCard(Icons.Default.Assistant, "Default Device Assistant", "Replace Google Assistant. Long-press home to trigger.", hasAssistantRole, PermTier.AGENT) {
            var launched = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                    roleLauncher?.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    launched = true
                } catch (e: Exception) {}
            }
            if (!launched) {
                try {
                    context.startActivity(Intent("com.android.settings.action.MANAGE_VOICE_INPUT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {}
            }
        }
        PermissionCard(Icons.Default.Accessibility, "Accessibility Service", "Enables screen control and automation gestures.", hasAccessibility, PermTier.AGENT) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        
        if (!hasRestrictedSettings && !hasAccessibility) {
            RestrictedSettingsCard()
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Communications")
        PermissionCard(Icons.Default.Notifications, "Notification Listener", "Read messages to reply hands-free.", hasNotifListener, PermTier.AGENT) {
            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PermissionCard(Icons.Default.PhoneCallback, "Answer Calls", "Answer or decline calls by voice.", hasAnswerCalls, PermTier.AGENT) {
            multiPermLauncher.launch(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS))
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Data & Privacy")
        PermissionCard(Icons.Default.Contacts, "Read Contacts", "Identify callers and make calls.", hasContacts, PermTier.AGENT) { multiPermLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
        PermissionCard(Icons.Default.Call, "Read Call Log", "Summarize recent calls.", hasCallLog, PermTier.AGENT) { multiPermLauncher.launch(arrayOf(Manifest.permission.READ_CALL_LOG)) }
        PermissionCard(Icons.Default.LocationOn, "Location Access", "Provide local weather and navigation.", hasLocation, PermTier.AGENT) { multiPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        PermissionCard(Icons.Default.CameraAlt, "Camera Access", "Use vision tools.", hasCamera, PermTier.AGENT) { multiPermLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }

        Spacer(Modifier.height(24.dp))
        SectionLabel("System Background")
        PermissionCard(Icons.Default.Build, "Write System Settings", "Change screen brightness and volume.", hasWriteSettings, PermTier.AGENT) {
            try { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply { data = Uri.parse("package:${context.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {}
        }
        PermissionCard(Icons.Default.BatteryAlert, "Ignore Battery Optimization", "Prevents Android from killing the assistant.", hasBattery, PermTier.RECOMMENDED) {
            try { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(e: Exception) {}
        }
        
        if (requiresAutoStart) {
            PermissionCard(Icons.Default.PowerSettingsNew, "Auto-Start", "Allow starting in the background.", autoStartRequested, PermTier.RECOMMENDED) {
                SettingsPermChecks.launchAutoStart(context)
                autoStartRequested = true
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().putBoolean("autostart_requested", true).apply()
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(3.dp).height(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    tier: PermTier,
    onGrant: () -> Unit
) {
    val tierColor = when (tier) {
        PermTier.REQUIRED -> MaterialTheme.colorScheme.error
        PermTier.AGENT -> Purple
        PermTier.RECOMMENDED -> AmberWarning
    }
    
    val borderColor by animateColorAsState(if (isGranted) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline)
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f)

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (isGranted) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) else tierColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (isGranted) Icons.Default.CheckCircle else icon, null, tint = if (isGranted) MaterialTheme.colorScheme.tertiary else tierColor, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (!expanded && !isGranted) Text("Tap to expand", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 11.sp)
                }
                if (!isGranted) {
                    Button(
                        onClick = { expanded = false; onGrant() },
                        colors = ButtonDefaults.buttonColors(containerColor = tierColor.copy(alpha = 0.2f), contentColor = tierColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text("Grant", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(arrowRotation))
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = AmberWarning, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("Enable Restricted Settings", color = AmberWarning, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("On Android 13+, sideloaded apps need extra permission to access Accessibility.\n\n1. Settings → Apps → Soltini\n2. Tap (⋮) at the top right\n3. Tap 'Allow restricted settings'\n4. Tap Grant on Accessibility", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
