package com.lucy.mate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.lucy.mate.auth.UserManager
import com.lucy.mate.ui.theme.*

@Composable
fun SettingsMainScreen(
    onNavigate: (SettingsRoute) -> Unit,
    onSignOut: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // Header
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = "Studio Settings",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Configure AI engine, audio, shield & devices",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        // Account & Cloud Authentication Banner
        AccountBannerCard(
            onOpenProfile = { onNavigate(SettingsRoute.PROFILE) },
            onSignOut = onSignOut
        )

        Spacer(Modifier.height(16.dp))

        // Section 1: AI & Audio Engine
        Text(
            text = "AI Core & Voice Engine",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SettingsMenuCard(
            title = "AI Persona & Voices",
            subtitle = "Gemini API key, 16 voice models, prompt",
            icon = Icons.Default.Person,
            iconTint = RosePink,
            onClick = { onNavigate(SettingsRoute.API) }
        )

        SettingsMenuCard(
            title = "Audio & Detection Engine",
            subtitle = "VAD sensitivity, Mic gain booster, Anti-Echo",
            icon = Icons.Default.VolumeUp,
            iconTint = SoftViolet,
            onClick = { onNavigate(SettingsRoute.AUDIO) }
        )

        SettingsMenuCard(
            title = "Floating Assistant & Window Size",
            subtitle = "Dynamic resize slider, Sound Orb & Avatar scaling",
            icon = Icons.Default.AspectRatio,
            iconTint = RosePink,
            onClick = { onNavigate(SettingsRoute.OVERLAY) }
        )

        Spacer(Modifier.height(14.dp))

        // Section 2: Security & Shield
        Text(
            text = "Privacy & Safety",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SettingsMenuCard(
            title = "Banking & Privacy Shield",
            subtitle = "Auto-silence mic on 26 banking apps",
            icon = Icons.Default.Shield,
            iconTint = RubyShield,
            onClick = { onNavigate(SettingsRoute.SHIELD) }
        )

        SettingsMenuCard(
            title = "Permissions & Diagnostics",
            subtitle = "Microphone, Accessibility, Assistant Role",
            icon = Icons.Default.Security,
            iconTint = EmeraldLive,
            onClick = { onNavigate(SettingsRoute.PERMISSIONS) }
        )

        Spacer(Modifier.height(14.dp))

        // Section 3: Device & System
        Text(
            text = "System & Communications",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SettingsMenuCard(
            title = "Calls & Automations",
            subtitle = "Speakerphone, WhatsApp calls, notifications",
            icon = Icons.Default.Call,
            iconTint = Color(0xFF3B82F6),
            onClick = { onNavigate(SettingsRoute.CALLS) }
        )

        SettingsMenuCard(
            title = "Connectors & Integrations",
            subtitle = "Google Calendar, Gmail, Spotify",
            icon = Icons.Default.Extension,
            iconTint = SoftViolet,
            onClick = { onNavigate(SettingsRoute.CONNECTORS) }
        )

        SettingsMenuCard(
            title = "Diagnostic Console Logs",
            subtitle = "Live streaming WebSocket event trace",
            icon = Icons.Default.BugReport,
            iconTint = AmberConnecting,
            onClick = { onNavigate(SettingsRoute.LOGS) }
        )


        SettingsMenuCard(
            title = "About LUCY",
            subtitle = "v2.0 • Architecture & Developer",
            icon = Icons.Default.Info,
            iconTint = TextSecondary,
            onClick = { onNavigate(SettingsRoute.ABOUT) }
        )

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun SettingsMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = RosePink,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun AccountBannerCard(
    onOpenProfile: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val userManager = androidx.compose.runtime.remember { UserManager.getInstance(context) }
    val session by userManager.currentUser.collectAsState()
    val isSigningIn by userManager.isSigningIn.collectAsState()
    val authError by userManager.authError.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenProfile() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        border = androidx.compose.foundation.BorderStroke(1.2.dp, PinkyBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val currentSession = session
            if (currentSession != null && !currentSession.isRevoked && !currentSession.email.endsWith("@soltini.local")) {
                // Logged In State
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!currentSession.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = currentSession.avatarUrl,
                            contentDescription = "User Avatar",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, RosePink, CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(SoftBlush),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = RosePink,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentSession.name,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            val isTrial = currentSession.isTrial
                            val isPro = currentSession.isPro
                            val isPcOnlyPro = currentSession.isPcOnlyPro
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isTrial) Color(0xFFF59E0B)
                                        else if (isPro) RosePink 
                                        else if (isPcOnlyPro) SoftViolet 
                                        else TextSecondary.copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = when {
                                        isTrial -> "TRIAL"
                                        currentSession.isBundlePro -> "BUNDLE PRO"
                                        currentSession.isMobilePro -> "MOBILE PRO"
                                        isPro -> "PRO"
                                        isPcOnlyPro -> "PC PRO"
                                        else -> "FREE"
                                    },
                                    color = if (isPro || isPcOnlyPro || isTrial) Color.White else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.8.sp,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = currentSession.email,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Logout Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(RosePink.copy(alpha = 0.08f))
                            .clickable {
                                userManager.logout()
                                onSignOut()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Sign Out",
                            color = RosePink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false,
                            maxLines = 1
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = "View Profile",
                        tint = TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (currentSession.isTrial) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFEF3C7))
                            .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "10-Day Bundle Trial • ${currentSession.daysRemaining} days left", 
                                    color = Color(0xFF92400E), 
                                    fontSize = 12.sp, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Active", 
                                color = Color(0xFFD97706), 
                                fontSize = 11.sp, 
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                } else if (!currentSession.isPro) {
                    val isPcOnlyPro = currentSession.isPcOnlyPro
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background((if (isPcOnlyPro) SoftViolet else RosePink).copy(alpha = 0.08f))
                            .clickable {
                                val url = if (isPcOnlyPro) userManager.getBundleCheckoutUrl() else userManager.getUpgradeCheckoutUrl()
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    context.startActivity(intent)
                                } catch (e: Exception) {}
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = if (isPcOnlyPro) SoftViolet else RosePink, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isPcOnlyPro) "PC Pro Active • Upgrade to Bundle" else "Free Tier • Upgrade to Pro", 
                                    color = if (isPcOnlyPro) SoftViolet else RosePink, 
                                    fontSize = 12.sp, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = if (isPcOnlyPro) "Bundle →" else "Upgrade →", 
                                color = if (isPcOnlyPro) SoftViolet else RosePink, 
                                fontSize = 11.sp, 
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            } else {
                // Not Signed In (Guest State)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(RosePink.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = RosePink,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Sign In to LUCY",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RosePink)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "PRO",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }
                        Text(
                            text = "Sync licenses, cloud memory & Pro access",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity != null) {
                            userManager.signInWithGoogle(activity)
                        } else {
                            userManager.startWebSignIn(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RosePink,
                        contentColor = Color.White
                    ),
                    enabled = !isSigningIn
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting to Google...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Sign In with Google",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                if (authError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = authError ?: "",
                        color = Color(0xFFE11D48),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { userManager.startWebSignIn(context) }
                    ) {
                        Text(
                            text = "Sign in via browser instead",
                            color = RosePink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

