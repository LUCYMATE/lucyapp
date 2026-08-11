package com.soltini.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.soltini.app.agent.SoltiniAccessibilityService
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.RedError
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

private val AmberWarning = Color(0xFFFFA726)
private val Purple = Color(0xFF9C27B0)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    // --- Permission states ---
    var hasMic by remember { mutableStateOf(SettingsPermChecks.checkMic(context)) }
    var hasOverlay by remember { mutableStateOf(SettingsPermChecks.checkOverlay(context)) }
    var hasBattery by remember { mutableStateOf(SettingsPermChecks.checkBattery(context)) }
    var hasAccessibility by remember { mutableStateOf(SettingsPermChecks.checkAccessibility(context)) }
    var hasAssistantRole by remember { mutableStateOf(SettingsPermChecks.checkAssistantRole(context)) }
    var hasRestrictedSettings by remember { mutableStateOf(SettingsPermChecks.checkRestrictedSettings(context)) }
    val requiresAutoStart by remember { mutableStateOf(SettingsPermChecks.checkRequiresAutoStart(context)) }
    var autoStartRequested by remember {
        mutableStateOf(
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("autostart_requested", false)
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    val roleLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasAssistantRole = SettingsPermChecks.checkAssistantRole(context)
        }
    } else null

    // Refresh all states when user comes back from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMic = SettingsPermChecks.checkMic(context)
                hasOverlay = SettingsPermChecks.checkOverlay(context)
                hasBattery = SettingsPermChecks.checkBattery(context)
                hasAccessibility = SettingsPermChecks.checkAccessibility(context)
                hasAssistantRole = SettingsPermChecks.checkAssistantRole(context)
                hasRestrictedSettings = SettingsPermChecks.checkRestrictedSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Header ─────────────────────────────────────────────────────
            SettingsHeader()

            Spacer(modifier = Modifier.height(24.dp))

            // ── Section: AI Configuration (separate from permissions) ────────
            AiConfigSection(
                appSettings = viewModel?.getAppSettings(),
                onSaveAndReconnect = { viewModel?.reconnect() }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Memory ────────────────────────────────────────
            MemorySection(
                memoryManager = viewModel?.getMemoryManager()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Required for Basic Function ────────────────────────
            SectionLabel("Required Permissions")
            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Default.Mic,
                title = "Microphone",
                description = "Capture your voice for real-time AI conversation.",
                isGranted = hasMic,
                tier = PermTier.REQUIRED,
                onGrant = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionCard(
                icon = Icons.Default.Layers,
                title = "Display Over Other Apps",
                description = "Show the Soltini floating orb on top of any screen.",
                isGranted = hasOverlay,
                tier = PermTier.REQUIRED,
                onGrant = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Agentic (AI Agent Capabilities) ─────────────────────
            SectionLabel("AI Agent Capabilities")
            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Default.Assistant,
                title = "Default Device Assistant",
                description = "Replace Google Assistant / Gemini. Long-press home to trigger Soltini — exactly like Gemini.",
                isGranted = hasAssistantRole,
                tier = PermTier.AGENT,
                onGrant = {
                    // Layered fallback to open the correct assistant settings page.
                    // isRoleAvailable() returns false for sideloaded APKs on many devices,
                    // so we try every known method in order of specificity.
                    var launched = false

                    // Tier 1: RoleManager intent (Android 10+, works when role is available)
                    if (!launched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                            val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                            roleLauncher?.launch(roleIntent)
                            launched = true
                        } catch (_: Exception) {}
                    }

                    // Tier 2: Deep-link to the Voice Input / Assistant sub-page
                    if (!launched) {
                        try {
                            context.startActivity(
                                Intent("com.android.settings.action.MANAGE_VOICE_INPUT_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            launched = true
                        } catch (_: Exception) {}
                    }

                    // Tier 3: ACTION_VOICE_INPUT_SETTINGS (works on AOSP)
                    if (!launched) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            launched = true
                        } catch (_: Exception) {}
                    }

                    // Tier 4: Last resort — generic default apps page
                    if (!launched) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                    }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionCard(
                icon = Icons.Default.Accessibility,
                title = "Accessibility Service",
                description = "Enables locking the device screen by voice. Soltini only uses this to perform lock/unlock actions.",
                isGranted = hasAccessibility,
                tier = PermTier.AGENT,
                onGrant = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            // Restricted settings warning for Android 13+ sideloaded APKs
            if (!hasRestrictedSettings && !hasAccessibility) {
                Spacer(modifier = Modifier.height(10.dp))
                RestrictedSettingsCard()
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Background & System ────────────────────────────────
            SectionLabel("Background & System")
            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Default.BatteryAlert,
                title = "Ignore Battery Optimization",
                description = "Prevents Android from killing the assistant in the background.",
                isGranted = hasBattery,
                tier = PermTier.RECOMMENDED,
                onGrant = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            if (requiresAutoStart) {
                Spacer(modifier = Modifier.height(10.dp))
                PermissionCard(
                    icon = Icons.Default.PowerSettingsNew,
                    title = "Auto-Start (${Build.MANUFACTURER})",
                    description = "Allow Soltini to automatically start in the background on your device.",
                    isGranted = autoStartRequested,
                    tier = PermTier.RECOMMENDED,
                    onGrant = {
                        SettingsPermChecks.launchAutoStart(context)
                        autoStartRequested = true
                        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("autostart_requested", true).apply()
                    }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Summary bar ─────────────────────────────────────────────────
            val totalRequired = 2
            val grantedRequired = listOf(hasMic, hasOverlay).count { it }
            val totalAgent = 2
            val grantedAgent = listOf(hasAssistantRole, hasAccessibility).count { it }

            PermissionSummaryBar(
                grantedRequired = grantedRequired,
                totalRequired = totalRequired,
                grantedAgent = grantedAgent,
                totalAgent = totalAgent
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────
@Composable
private fun SettingsHeader() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(AccentBlue.copy(0.4f), AccentBlue.copy(0.05f)))
                )
                .border(1.dp, AccentBlue.copy(0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Settings, null, tint = AccentBlue, modifier = Modifier.size(44.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Permissions & Capabilities",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Only Microphone is required to start. Grant Agent capabilities\nto unlock voice-controlled device actions.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

// ── Section Label ─────────────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(3.dp).height(18.dp).background(AccentBlue, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

// ── Permission Tiers ─────────────────────────────────────────────────────────
enum class PermTier { REQUIRED, AGENT, RECOMMENDED }

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
        PermTier.REQUIRED -> RedError
        PermTier.AGENT -> Purple
        PermTier.RECOMMENDED -> AmberWarning
    }
    val tierLabel = when (tier) {
        PermTier.REQUIRED -> "Required"
        PermTier.AGENT -> "Agent"
        PermTier.RECOMMENDED -> "Recommended"
    }

    val borderColor by animateColorAsState(
        if (isGranted) GreenLive.copy(alpha = 0.5f) else DarkBorder,
        label = "border"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isGranted) GreenLive.copy(alpha = 0.12f)
                        else tierColor.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (isGranted) GreenLive else tierColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (!isGranted) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(tierColor.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                tierLabel,
                                color = tierColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    description, color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }

            if (!isGranted) {
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tierColor.copy(alpha = 0.15f),
                        contentColor = tierColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Grant", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Restricted Settings Warning ───────────────────────────────────────────────
@Composable
private fun RestrictedSettingsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = AmberWarning, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    "Enable Restricted Settings",
                    color = AmberWarning,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "On Android 13+, sideloaded apps need extra permission to access Accessibility.\n\n" +
                            "1. Go to Settings → Apps → Soltini\n" +
                            "2. Tap the three-dot menu (⋮) at the top right\n" +
                            "3. Tap \"Allow restricted settings\"\n" +
                            "4. Come back and tap Grant on Accessibility Service above.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ── Summary Bar ───────────────────────────────────────────────────────────────
@Composable
private fun PermissionSummaryBar(
    grantedRequired: Int,
    totalRequired: Int,
    grantedAgent: Int,
    totalAgent: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryItem(
                label = "Core",
                granted = grantedRequired,
                total = totalRequired,
                color = if (grantedRequired == totalRequired) GreenLive else RedError
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(DarkBorder)
            )
            SummaryItem(
                label = "Agent",
                granted = grantedAgent,
                total = totalAgent,
                color = if (grantedAgent == totalAgent) GreenLive else Purple
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, granted: Int, total: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$granted / $total",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

// ── Permission Check Helpers ──────────────────────────────────────────────────
object SettingsPermChecks {

    fun checkMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun checkOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun checkBattery(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    fun checkAccessibility(context: Context): Boolean {
        if (SoltiniAccessibilityService.isActive()) return true
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    fun checkAssistantRole(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            // Fallback: check the secure setting
            val setting = Settings.Secure.getString(
                context.contentResolver,
                "assistant"
            )
            setting?.contains(context.packageName, ignoreCase = true) == true
        }
    }

    /**
     * Heuristic check for Android 13+ Restricted Settings.
     * If Accessibility is not active AND we're on API 33+, it's likely blocked.
     */
    fun checkRestrictedSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // If accessibility service IS active, restricted settings must already be allowed
            checkAccessibility(context)
        } else {
            true // Not relevant below Android 13
        }
    }

    fun checkRequiresAutoStart(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        when (manufacturer) {
            "xiaomi" -> intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo" -> intent.component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            "vivo" -> intent.component = ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "huawei", "honor" -> intent.component = ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            else -> return false
        }
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun launchAutoStart(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        when (manufacturer) {
            "xiaomi" -> intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo" -> intent.component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            "vivo" -> intent.component = ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "huawei", "honor" -> intent.component = ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AI Configuration Section
// ─────────────────────────────────────────────────────────────────────────────

private val CardBg      = Color(0xFF141828)
private val AccentPurple = Color(0xFF7C5CBF)
private val ChipSelected = Color(0xFF7C5CBF)
private val ChipUnselected = Color(0xFF232740)

@Composable
fun AiConfigSection(
    appSettings: AppSettings?,
    onSaveAndReconnect: () -> Unit
) {
    // Local draft state — only written to AppSettings on Save
    var apiKey    by remember { mutableStateOf(appSettings?.geminiApiKey ?: "") }
    var persona   by remember { mutableStateOf(appSettings?.aiPersona ?: AppSettings.DEFAULT_PERSONA) }
    var voice     by remember { mutableStateOf(appSettings?.voiceName ?: AppSettings.DEFAULT_VOICE) }
    var showKey   by remember { mutableStateOf(false) }
    var saved     by remember { mutableStateOf(false) }

    SectionLabel("⚙️  AI Configuration")
    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2F4A))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {

            // ── API Key ─────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Gemini API Key", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste your API key here…", color = TextSecondary, fontSize = 13.sp) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Box(
                        modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle key visibility",
                            tint = TextSecondary, modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = Color(0xFF2A2F4A),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Text(
                if (apiKey.isBlank()) "Using built-in key" else "Your key will be used",
                color = if (apiKey.isBlank()) TextSecondary else GreenLive,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp, start = 2.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── AI Persona ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI Persona", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it; saved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Describe Soltini's personality…", color = TextSecondary, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = Color(0xFF2A2F4A),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Text(
                "${persona.length} chars",
                color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 3.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── Voice Model ─────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Voice", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSettings.AVAILABLE_VOICES.forEach { v ->
                    val selected = (v == voice)
                    FilterChip(
                        selected = selected,
                        onClick = { voice = v; saved = false },
                        label = { Text(v, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ChipSelected,
                            selectedLabelColor = Color.White,
                            containerColor = ChipUnselected,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            selectedBorderColor = ChipSelected,
                            borderColor = Color(0xFF2A2F4A)
                        )
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Save & Reconnect button ──────────────────────────────────────
            Button(
                onClick = {
                    appSettings?.geminiApiKey = apiKey
                    appSettings?.aiPersona    = persona
                    appSettings?.voiceName    = voice
                    saved = true
                    onSaveAndReconnect()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (saved) GreenLive else AccentPurple
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (saved) {
                    Icon(Icons.Default.Check, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saved & Reconnecting…", fontWeight = FontWeight.Bold)
                } else {
                    Text("Save & Reconnect", fontWeight = FontWeight.Bold)
                }
            }

            Text(
                "Settings saved to device. A new Gemini session will start.",
                color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Memory Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MemorySection(memoryManager: com.soltini.app.memory.MemoryManager?) {
    var expanded    by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var memCount    by remember { mutableStateOf(memoryManager?.getCount() ?: 0) }
    var memories    by remember { mutableStateOf(memoryManager?.getAllMemories() ?: emptyList()) }

    fun refresh() {
        memCount  = memoryManager?.getCount() ?: 0
        memories  = memoryManager?.getAllMemories() ?: emptyList()
    }

    SectionLabel("🧠  Memory")
    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2F4A))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {

            // Header row with count + expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, contentDescription = null,
                        tint = AccentPurple, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Stored Memories", color = TextPrimary,
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "$memCount / ${com.soltini.app.memory.MemoryDatabase.MAX_MEMORIES} memories  •  ~${memCount * 3}KB",
                            color = TextSecondary, fontSize = 11.sp
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (memCount > 0 && !showClearConfirm) {
                        Icon(
                            Icons.Default.ExpandMore.let { if (expanded) Icons.Default.ExpandLess else it },
                            contentDescription = "Toggle memory list",
                            tint = TextSecondary, modifier = Modifier.size(22.dp).clickable {
                                expanded = !expanded
                                if (expanded) refresh()
                            }
                        )
                    }
                }
            }

            // Expandable memory list
            AnimatedVisibility(visible = expanded && memCount > 0,
                enter = expandVertically(), exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    memories.take(50).forEach { mem ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("•", color = AccentPurple, fontSize = 13.sp,
                                modifier = Modifier.padding(end = 6.dp, top = 2.dp))
                            Text(mem.content, color = TextPrimary, fontSize = 13.sp,
                                lineHeight = 18.sp)
                        }
                    }
                    if (memories.size > 50) {
                        Text("… and ${memories.size - 50} more",
                            color = TextSecondary, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Clear All button
            if (memCount == 0) {
                Text("No memories stored yet. Soltini will remember important facts from your conversations.",
                    color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            } else if (showClearConfirm) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            memoryManager?.clearAll()
                            showClearConfirm = false
                            refresh()
                            expanded = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedError),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("Yes, clear all", fontSize = 13.sp) }
                    Button(
                        onClick = { showClearConfirm = false },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("Cancel", fontSize = 13.sp) }
                }
            } else {
                Button(
                    onClick = { showClearConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1A1A)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = RedError)
                    Spacer(Modifier.width(6.dp))
                    Text("Clear All Memories", color = RedError, fontSize = 13.sp)
                }
            }
        }
    }
}

