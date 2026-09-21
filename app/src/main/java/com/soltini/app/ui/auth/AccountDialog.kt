package com.soltini.app.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.soltini.app.auth.UserManager
import com.soltini.app.ui.theme.*

/**
 * Account Profile Dialog accessible directly from VoiceScreen Top Bar
 */
@Composable
fun AccountDialog(
    onDismiss: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val userManager = remember { UserManager.getInstance(context) }
    val session by userManager.currentUser.collectAsState()
    val isSigningIn by userManager.isSigningIn.collectAsState()
    val authError by userManager.authError.collectAsState()

    val currentSession = session

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
            border = BorderStroke(1.5.dp, PinkyBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LUCY Account",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // User Avatar
                if (!currentSession?.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = currentSession?.avatarUrl,
                        contentDescription = "User Avatar",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(2.dp, RosePink, CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(SoftBlush)
                            .border(1.5.dp, PinkyBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = RosePink,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // User Name & Pro Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentSession?.name ?: "LUCY User",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    val isPro = currentSession?.isPro == true
                    val isPcOnlyPro = currentSession?.isPcOnlyPro == true
                    val isTrial = currentSession?.isTrial == true
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isTrial) Color(0xFFF59E0B)
                                else if (isPro) RosePink 
                                else if (isPcOnlyPro) SoftViolet 
                                else TextSecondary.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = when {
                                isTrial -> "TRIAL"
                                currentSession?.isBundlePro == true -> "BUNDLE PRO"
                                currentSession?.isMobilePro == true -> "MOBILE PRO"
                                isPro -> "PRO"
                                isPcOnlyPro -> "PC PRO"
                                else -> "FREE"
                            },
                            color = if (isPro || isPcOnlyPro || isTrial) Color.White else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = currentSession?.email ?: "",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = currentSession?.planDisplayName ?: "LUCY Free Plan",
                    color = if (currentSession?.isPro == true) RosePink else if (currentSession?.isPcOnlyPro == true) SoftViolet else TextSecondary.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (currentSession?.isTrial == true) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFEF3C7))
                            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "⏳ 10-Day Trial (${currentSession.daysRemaining} days left)",
                            color = Color(0xFF92400E),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Upgrade action for Free or PC-only users
                val isUserPro = currentSession?.isPro == true
                if (!isUserPro) {
                    Button(
                        onClick = {
                            val url = userManager.getUpgradeCheckoutUrl()
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback
                            }
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        if (isPcOnlyPro) listOf(SoftViolet, RosePink)
                                        else listOf(RosePink, SoftViolet)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isPcOnlyPro) "Upgrade to Ultimate Bundle" else "Upgrade to LUCY Pro", 
                                    color = Color.White, 
                                    fontWeight = FontWeight.Bold, 
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // Sign out action
                Button(
                    onClick = {
                        userManager.logout()
                        onDismiss()
                        onSignOut()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RosePink.copy(alpha = 0.12f),
                        contentColor = RosePink
                    ),
                    border = BorderStroke(1.dp, PinkyBorder)
                ) {
                    Text("Sign Out", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                if (authError != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = authError ?: "",
                        color = Color(0xFFE11D48),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
