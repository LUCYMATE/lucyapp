package com.soltini.app.ui.auth

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.soltini.app.auth.UserManager
import com.soltini.app.auth.UserSession
import com.soltini.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated User Profile & Account Management Screen
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userManager = remember { UserManager.getInstance(context) }
    val session by userManager.currentUser.collectAsState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshNotice by remember { mutableStateOf<String?>(null) }
    var showUpgradeDialog by remember { mutableStateOf(false) }

    val current = session
    val isPro = current?.isPro == true

    val expiryDateStr = remember(current?.expiresAt) {
        val exp = current?.expiresAt ?: 0L
        if (exp > 0) {
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(exp))
        } else {
            "Lifetime Active"
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = PinkyWhiteBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // ── Top Navigation Bar ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(PearlWhiteSurface)
                        .border(1.dp, PinkyBorder, RoundedCornerShape(14.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = RosePink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Back", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "User Profile",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )

                IconButton(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            refreshNotice = null
                            val success = userManager.verifyAndRefreshSession()
                            isRefreshing = false
                            refreshNotice = if (success) "Synced with Cloud" else "Cloud sync offline"
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = RosePink, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = RosePink, modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (refreshNotice != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = refreshNotice!!,
                    color = EmeraldLive,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(18.dp))

            // ── Hero Profile Card ───────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
                border = BorderStroke(1.5.dp, PinkyBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar with glowing ring
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(RosePink.copy(alpha = 0.25f), Color.Transparent)
                                    )
                                )
                        )
                        if (!current?.avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = current?.avatarUrl,
                                contentDescription = "Profile Avatar",
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .border(2.5.dp, RosePink, CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(SoftBlush)
                                    .border(2.dp, RosePink, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (current?.name?.take(1) ?: "L").uppercase(),
                                    color = RosePink,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = current?.name ?: "LUCY User",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = current?.email ?: "user@lucy.local",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(12.dp))

                    // Tier Badge
                    val isPcOnlyPro = current?.isPcOnlyPro == true
                    val isTrial = current?.isTrial == true
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isTrial) Color(0xFFF59E0B)
                                else if (isPro) RosePink 
                                else if (isPcOnlyPro) SoftViolet 
                                else TextSecondary.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isTrial) Icons.Default.Schedule else if (isPro) Icons.Default.AutoAwesome else if (isPcOnlyPro) Icons.Default.Computer else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (isPro || isPcOnlyPro || isTrial) Color.White else TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isTrial -> "LUCY ULTIMATE BUNDLE (10-DAY TRIAL)"
                                    current?.isBundlePro == true -> "LUCY ULTIMATE BUNDLE PRO"
                                    current?.isMobilePro == true -> "LUCY MOBILE PRO SUBSCRIBER"
                                    isPro -> "LUCY PRO SUBSCRIBER"
                                    isPcOnlyPro -> "LUCY PC PRO (FREE ON MOBILE)"
                                    else -> "LUCY FREE EDITION"
                                },
                                color = if (isPro || isPcOnlyPro || isTrial) Color.White else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Account & Subscription Details ──────────────────────────────
            Text(
                text = "Subscription & License",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
                border = BorderStroke(1.dp, PinkyBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val planName = current?.planDisplayName ?: "LUCY Free Plan"
                    ProfileInfoRow(
                        label = "Tier Plan", 
                        value = planName, 
                        valueColor = if (isTrial) Color(0xFFD97706) else if (isPro) RosePink else if (isPcOnlyPro) SoftViolet else TextSecondary
                    )
                    if (isTrial) {
                        HorizontalDivider(color = PinkyBorder.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 10.dp))
                        ProfileInfoRow(
                            label = "Trial Countdown",
                            value = "${current?.daysRemaining ?: 0} days remaining",
                            valueColor = Color(0xFFD97706)
                        )
                    }
                    HorizontalDivider(color = PinkyBorder.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 10.dp))
                    ProfileInfoRow(label = "Account Status", value = if (isTrial) "10-Day Free Trial" else "Active & Verified", valueColor = if (isTrial) Color(0xFFD97706) else EmeraldLive)
                    HorizontalDivider(color = PinkyBorder.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 10.dp))
                    ProfileInfoRow(label = "Access Expiry", value = if (isTrial) "${current?.daysRemaining ?: 0} days remaining" else if (isPro) expiryDateStr else if (isPcOnlyPro) "PC Pro Active" else "Ongoing Free Access")
                }
            }

            // ── Upgrade to Pro Banner (for Free or PC-only Users) ───────────
            if (!isPro) {
                Spacer(Modifier.height(18.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = BlushSurfaceElevated),
                    border = BorderStroke(1.2.dp, PinkyBorderFocused)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = if (isPcOnlyPro) SoftViolet else RosePink, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isPcOnlyPro) "Upgrade to Ultimate Bundle" else "Upgrade to LUCY Pro",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isPcOnlyPro) {
                                "You have an active Windows PC Pro plan! Upgrade to the Ultimate Bundle for just $1 (₹100) more to unlock Android mobile Pro across all your screens."
                            } else {
                                "Unlock sub-second Gemini 3.1 Live multimodal voice, autonomous device screen vision, and persistent memory vault."
                            },
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (isPcOnlyPro) {
                                    val url = userManager.getBundleCheckoutUrl()
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {}
                                } else {
                                    showUpgradeDialog = true
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isPcOnlyPro) SoftViolet else RosePink)
                        ) {
                            Text(
                                text = if (isPcOnlyPro) "Get Ultimate Bundle (Dual Device)" else "View Pro Features & Pricing", 
                                fontSize = 13.sp, 
                                fontWeight = FontWeight.Bold, 
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Sign Out Button ─────────────────────────────────────────────
            Button(
                onClick = {
                    userManager.clearSession()
                    onSignOut()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFF1F2),
                    contentColor = Color(0xFFE11D48)
                ),
                border = BorderStroke(1.dp, Color(0xFFFECDD3))
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Sign Out from LUCY",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showUpgradeDialog) {
        ProUpgradeDialog(onDismiss = { showUpgradeDialog = false })
    }
}

@Composable
private fun ProfileInfoRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(text = value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

