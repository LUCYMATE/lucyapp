package com.soltini.app.ui.agent

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenSearchDesktop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.ui.MainViewModel
import com.soltini.app.ui.theme.EmeraldLive
import com.soltini.app.ui.theme.PearlWhiteSurface
import com.soltini.app.ui.theme.PinkyBorder
import com.soltini.app.ui.theme.PinkyWhiteBackground
import com.soltini.app.ui.theme.RosePink
import com.soltini.app.ui.theme.RubyShield
import com.soltini.app.ui.theme.SoftViolet
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

@Composable
fun AgentHubScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = PinkyWhiteBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Agent Studio",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Autonomous device controls & vision",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(RosePink.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(EmeraldLive)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "A11y Ready",
                            color = EmeraldLive,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Screen Perception Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PinkyBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(RosePink.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Visibility, null, tint = RosePink, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Screen Perception", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Live Vision & OCR", color = TextSecondary, fontSize = 11.sp)
                            }
                        }

                        Icon(Icons.Default.CheckCircle, null, tint = EmeraldLive, modifier = Modifier.size(18.dp))
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Soltini reads and controls any screen on your device with high-speed accessibility injection.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(RosePink)
                                .clickable {
                                    viewModel.sendTextMessage("Take a screenshot and describe everything you see on my screen right now")
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Analyze Screen", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(PearlWhiteSurface)
                                .border(1.dp, PinkyBorder, RoundedCornerShape(14.dp))
                                .clickable {
                                    viewModel.sendTextMessage("Read the accessibility hierarchy and find all clickable elements on my screen")
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Read Hierarchy", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // App Automators Section
            Text(
                text = "App Automators",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AgentAppCard(
                    title = "YouTube AI",
                    subtitle = "Search, skip ads, seek",
                    icon = "▶️",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.sendTextMessage("Open YouTube and search for lo-fi chill beats")
                    }
                )

                AgentAppCard(
                    title = "WhatsApp Bot",
                    subtitle = "Send text & auto-call",
                    icon = "💬",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.sendTextMessage("Help me send a message on WhatsApp")
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AgentAppCard(
                    title = "Reels Autopilot",
                    subtitle = "Hands-free scrolling",
                    icon = "📱",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.sendTextMessage("Scroll to the next short video reel")
                    }
                )

                AgentAppCard(
                    title = "Termux Bridge",
                    subtitle = "Execute scripts",
                    icon = "⚡",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.sendTextMessage("Check if Termux bridge is available to run commands")
                    }
                )
            }

            // System Hardware Navigation Controls
            Text(
                text = "Hardware Navigation Controls",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PinkyBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NavButton(text = "Home", modifier = Modifier.weight(1f)) {
                        viewModel.sendTextMessage("Press home button")
                    }
                    NavButton(text = "Back", modifier = Modifier.weight(1f)) {
                        viewModel.sendTextMessage("Press back button")
                    }
                    NavButton(text = "Recents", modifier = Modifier.weight(1f)) {
                        viewModel.sendTextMessage("Press recents button")
                    }
                    NavButton(text = "Lock", isDanger = true, modifier = Modifier.weight(1f)) {
                        viewModel.sendTextMessage("Lock the device now")
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun AgentAppCard(
    title: String,
    subtitle: String,
    icon: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PinkyBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(subtitle, color = TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun NavButton(
    text: String,
    isDanger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDanger) RubyShield.copy(alpha = 0.10f) else PinkyWhiteBackground)
            .border(1.dp, if (isDanger) RubyShield.copy(alpha = 0.3f) else PinkyBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isDanger) RubyShield else TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}
