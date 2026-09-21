package com.lucy.mate.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucy.mate.settings.AppSettings
import com.lucy.mate.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAiConfigScreen(
    appSettings: AppSettings?,
    onSaveAndReconnect: () -> Unit
) {
    var apiKey by remember { mutableStateOf(appSettings?.geminiApiKey ?: "") }
    var persona by remember { mutableStateOf(appSettings?.aiPersona ?: AppSettings.DEFAULT_PERSONA) }
    var voice by remember { mutableStateOf(appSettings?.voiceName ?: AppSettings.DEFAULT_VOICE) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PremiumCard(title = "Gemini Live API Key") {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste your Gemini API key (AIzaSy...)", color = TextSecondary) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = RosePink
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RosePink,
                    unfocusedBorderColor = PinkyBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = PinkyWhiteBackground,
                    unfocusedContainerColor = PinkyWhiteBackground,
                    cursorColor = RosePink
                ),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Leave blank to use the compiled default key. Your key is stored securely on-device.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        PremiumCard(title = "Prebuilt Gemini Voice (16 Options)") {
            Text(
                "Selected voice: $voice",
                color = RosePink,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSettings.AVAILABLE_VOICES.forEach { v ->
                    val isSelected = (voice == v)
                    FilterChip(
                        selected = isSelected,
                        onClick = { voice = v; saved = false },
                        label = { Text(v, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RosePink,
                            selectedLabelColor = Color.White,
                            containerColor = PinkyWhiteBackground,
                            labelColor = TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            selectedBorderColor = RosePink,
                            borderColor = PinkyBorder
                        )
                    )
                }
            }
        }

        PremiumCard(title = "AI Personality & System Instructions") {
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it; saved = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                placeholder = { Text("Define the AI's personality, language, and behavior...", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RosePink,
                    unfocusedBorderColor = PinkyBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = PinkyWhiteBackground,
                    unfocusedContainerColor = PinkyWhiteBackground,
                    cursorColor = RosePink
                ),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Injected automatically at the start of every Gemini Live WebSocket session.",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                appSettings?.geminiApiKey = apiKey
                appSettings?.voiceName = voice
                appSettings?.aiPersona = persona
                saved = true
                onSaveAndReconnect()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RosePink),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(if (saved) "Saved & Reconnected!" else "Save & Reconnect", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
        }

        Spacer(Modifier.height(30.dp))
    }
}
