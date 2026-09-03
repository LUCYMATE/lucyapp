package com.soltini.app.ui.onboarding

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.ui.theme.BlushSurfaceElevated
import com.soltini.app.ui.theme.EmeraldLive
import com.soltini.app.ui.theme.PearlWhiteSurface
import com.soltini.app.ui.theme.PinkyBorder
import com.soltini.app.ui.theme.PinkyWhiteBackground
import com.soltini.app.ui.theme.PinkyWhiteBackgroundEnd
import com.soltini.app.ui.theme.RosePink
import com.soltini.app.ui.theme.SoftBlush
import com.soltini.app.ui.theme.SoftViolet
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

private val MiuiOrange = Color(0xFFFF6D00)

@Composable
fun UnifiedOnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(0) }
    
    val manufacturer = Build.MANUFACTURER.lowercase()
    val isMiui = manufacturer == "xiaomi" || manufacturer == "poco" || manufacturer == "redmi"

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) currentStep++
    }

    val assistantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        currentStep++
    }
    
    val baseSteps = listOf(
        OnboardingStep(
            badge = "CORE AUDIO ENGINE",
            title = "Voice Microphone Access",
            description = "Enables real-time, full-duplex conversational voice AI with ultra-low latency.",
            icon = Icons.Default.Mic,
            iconTint = RosePink,
            actionLabel = "Grant Microphone Permission",
            hasAction = true,
            onAction = {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        ),
        OnboardingStep(
            badge = "SYSTEM INTEGRATION",
            title = "Default Assistant Role",
            description = "Make Soltini your default Android assistant for instant power-button, swipe-up, and headset access.",
            icon = Icons.Default.Assistant,
            iconTint = RosePink,
            actionLabel = "Set as Default Assistant",
            hasAction = true,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                        assistantLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                        return@OnboardingStep
                    }
                }
                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                currentStep++
            }
        ),
        OnboardingStep(
            badge = "MULTITASKING ORB",
            title = "Floating Voice Orb",
            description = "Keep Soltini accessible on top of any app through a luminous, draggable luxury floating bubble.",
            icon = Icons.Default.Layers,
            iconTint = SoftBlush,
            actionLabel = "Enable Floating Orb Permission",
            hasAction = true,
            onAction = {
                context.startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                currentStep++
            }
        ),
        OnboardingStep(
            badge = "AGENTIC AUTOMATION",
            title = "Accessibility Service",
            description = "Allows Soltini to control applications, perform gestures, tap buttons, and interact with your screen on command.",
            icon = Icons.Default.Accessibility,
            iconTint = SoftViolet,
            actionLabel = "Open Accessibility Settings",
            hasAction = true,
            onAction = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                currentStep++
            }
        ),
        OnboardingStep(
            badge = "SMART REPLIES",
            title = "Notification Intelligence",
            description = "Enables reading and hands-free voice announcements and conversational replies for messaging apps.",
            icon = Icons.Default.Notifications,
            iconTint = SoftViolet,
            actionLabel = "Enable Notification Access",
            hasAction = true,
            onAction = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                currentStep++
            }
        ),
        OnboardingStep(
            badge = "ALWAYS ON & RESILIENT",
            title = "Background Resilience",
            description = "Disables aggressive battery killing so Soltini stays alert, ready, and responsive in the background.",
            icon = Icons.Default.BatteryAlert,
            iconTint = RosePink,
            actionLabel = "Disable Battery Restrictions",
            hasAction = true,
            onAction = {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
                if (!isMiui) {
                    onComplete()
                } else {
                    currentStep++
                }
            }
        )
    )

    val miuiSteps = if (isMiui) {
        listOf(
            OnboardingStep(
                badge = "MIUI / HYPEROS FIX",
                title = "Enable Autostart",
                description = "MIUI terminates background apps that cannot autostart. Open Security app and toggle Autostart ON for Soltini.",
                icon = Icons.Default.PowerSettingsNew,
                iconTint = MiuiOrange,
                actionLabel = "Open Security App",
                hasAction = true,
                onAction = {
                    launchAutoStartSettings(context)
                }
            ),
            OnboardingStep(
                badge = "MIUI BATTERY FIX",
                title = "No Battery Restrictions",
                description = "Set MIUI battery saver for Soltini to \"No restrictions\" so voice hotwords remain instantly active.",
                icon = Icons.Default.Battery5Bar,
                iconTint = EmeraldLive,
                actionLabel = "Open App Battery Settings",
                hasAction = true,
                onAction = {
                    launchBatterySettings(context)
                }
            ),
            OnboardingStep(
                badge = "PREVENT TASK KILLING",
                title = "Lock in Recent Apps",
                description = "Prevents \"Clear All\" from killing Soltini:\n1. Open Recent Apps (□)\n2. Long-press the Soltini card\n3. Tap 🔒 lock icon",
                icon = Icons.Default.Lock,
                iconTint = RosePink,
                actionLabel = null,
                hasAction = false,
                onAction = { }
            )
        )
    } else emptyList()

    val steps = baseSteps + miuiSteps

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = PinkyWhiteBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(PinkyWhiteBackground, PinkyWhiteBackgroundEnd)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── 1. Luxury Header & Pill Progress Indicator ────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Small Luxury Brand Badge
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(BlushSurfaceElevated)
                            .border(1.dp, PinkyBorder, CircleShape)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = RosePink,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "SOLTINI LUXURY AI",
                                color = RosePink,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Welcome to Soltini",
                        color = TextPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "STEP ${currentStep + 1} OF ${steps.size}",
                        color = RosePink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // ── Fluid Pill Progress Indicator ────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in steps.indices) {
                            val isCurrent = i == currentStep
                            val isCompleted = i < currentStep

                            val pillWidth by animateDpAsState(
                                targetValue = if (isCurrent) 36.dp else if (isCompleted) 12.dp else 8.dp,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "pillWidth"
                            )

                            val pillColor by animateColorAsState(
                                targetValue = when {
                                    isCurrent -> RosePink
                                    isCompleted -> RosePink.copy(alpha = 0.45f)
                                    else -> PinkyBorder
                                },
                                animationSpec = tween(300),
                                label = "pillColor"
                            )

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .width(pillWidth)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(pillColor)
                            )
                        }
                    }
                }

                // ── 2. Fluid Animated Step Card with Glowing Icon Badge ───────────────
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        slideInHorizontally(animationSpec = tween(320)) { width -> (width * 0.35f).toInt() } + fadeIn(tween(320)) togetherWith
                                slideOutHorizontally(animationSpec = tween(260)) { width -> (-width * 0.35f).toInt() } + fadeOut(tween(260))
                    },
                    label = "stepCardTransition",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(vertical = 12.dp)
                ) { stepIdx ->
                    val step = steps[stepIdx]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
                        shape = RoundedCornerShape(26.dp),
                        border = BorderStroke(1.5.dp, PinkyBorder),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Glowing Icon Badge
                            Box(
                                modifier = Modifier.size(96.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Layer 1: Outer soft luminous halo
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(
                                                    step.iconTint.copy(alpha = 0.20f),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                )

                                // Layer 2: Middle frosted translucent ring
                                Box(
                                    modifier = Modifier
                                        .size(74.dp)
                                        .clip(CircleShape)
                                        .background(step.iconTint.copy(alpha = 0.12f))
                                        .border(1.5.dp, step.iconTint.copy(alpha = 0.30f), CircleShape)
                                )

                                // Layer 3: Inner radiant pearl core
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(PearlWhiteSurface, BlushSurfaceElevated)
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = step.icon,
                                        contentDescription = null,
                                        tint = step.iconTint,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Step Category Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BlushSurfaceElevated)
                                    .border(1.dp, PinkyBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = step.badge,
                                    color = RosePink,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Step Title
                            Text(
                                text = step.title,
                                color = TextPrimary,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Step Description
                            Text(
                                text = step.description,
                                color = TextSecondary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }

                // ── 3. Tactile Action Buttons & Navigation ────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val step = steps[currentStep]
                    val isLastStep = currentStep == steps.size - 1

                    if (step.hasAction && step.actionLabel != null) {
                        var actionTapped by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                step.onAction()
                                actionTapped = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (actionTapped) RosePink.copy(alpha = 0.75f) else RosePink,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (actionTapped) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Opened — Tap Next Step Below",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                } else {
                                    Text(
                                        text = step.actionLabel,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Next / Complete Button
                    Button(
                        onClick = {
                            if (isLastStep) {
                                if (isMiui) {
                                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        .edit().putBoolean("miui_onboarding_complete", true).apply()
                                }
                                onComplete()
                            } else {
                                currentStep++
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLastStep) RosePink else PearlWhiteSurface,
                            contentColor = if (isLastStep) Color.White else TextPrimary
                        ),
                        border = if (isLastStep) null else BorderStroke(1.5.dp, PinkyBorder),
                        elevation = if (isLastStep) ButtonDefaults.buttonElevation(defaultElevation = 4.dp) else ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Text(
                            text = if (isLastStep) "✨ Start Soltini Experience" else "Next Step →",
                            color = if (isLastStep) Color.White else TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Back & Skip Navigation Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentStep > 0) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { currentStep-- }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Back",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = "Back",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            Spacer(Modifier.width(10.dp))
                        }

                        Text(
                            text = "Skip Setup",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (isMiui) {
                                        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                            .edit().putBoolean("miui_onboarding_complete", true).apply()
                                    }
                                    onComplete()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class OnboardingStep(
    val badge: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val iconTint: Color,
    val actionLabel: String?,
    val hasAction: Boolean,
    val onAction: () -> Unit
)

private fun launchAutoStartSettings(context: Context) {
    val intents = listOf(
        Intent().apply {
            component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        },
        Intent().apply {
            component = ComponentName("com.miui.securitycenter", "com.miui.securitycenter.MainActivity")
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    tryStartActivities(context, intents)
}

private fun launchBatterySettings(context: Context) {
    val intents = mutableListOf<Intent>()
    intents.add(Intent().apply {
        component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    intents.add(Intent().apply {
        action = "miui.intent.action.POWER_HIDE_MODE_APP_LIST"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    tryStartActivities(context, intents)
}

private fun tryStartActivities(context: Context, intents: List<Intent>) {
    for (intent in intents) {
        try {
            if (intent.component != null) {
                val resolved = context.packageManager.resolveActivity(intent, 0)
                if (resolved != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } else {
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {
            continue
        }
    }
}
