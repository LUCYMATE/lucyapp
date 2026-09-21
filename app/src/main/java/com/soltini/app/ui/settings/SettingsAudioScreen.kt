package com.soltini.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.MainViewModel
import com.soltini.app.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAudioScreen(viewModel: MainViewModel?) {
    val scrollState = rememberScrollState()
    
    var micGain by remember { mutableStateOf(viewModel?.appSettings?.micGain ?: 1.5f) }
    var vadSens by remember { mutableStateOf(viewModel?.appSettings?.vadSensitivity ?: AppSettings.SENSITIVITY_BALANCED) }
    var autoSleep by remember { mutableStateOf(viewModel?.appSettings?.autoSleepMinutes ?: 3) }
    var isScreenRecMode by remember { mutableStateOf(viewModel?.appSettings?.isScreenRecordingModeEnabled ?: false) }
    var audioSource by remember { mutableStateOf(viewModel?.appSettings?.audioSource ?: "VOICE_COMMUNICATION") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PremiumCard(title = "Voice Activity Detection (VAD)") {
            SettingsDropdownRow(
                icon = Icons.Default.Hearing,
                title = "VAD Sensitivity",
                selectedOption = vadSens,
                options = AppSettings.AVAILABLE_SENSITIVITIES,
                onOptionSelected = {
                    vadSens = it
                    viewModel?.appSettings?.vadSensitivity = it
                }
            )
        }

        PremiumCard(title = "Microphone & Input") {
            SettingsDropdownRow(
                icon = Icons.Default.Mic,
                title = "Audio Source",
                selectedOption = if (audioSource == "VOICE_COMMUNICATION") "Voice Comm (AEC)" else "Raw Mic",
                options = listOf("Voice Communication (Echo Cancel & Safe)", "Raw Mic (Low Latency)"),
                onOptionSelected = { 
                    val src = if (it.startsWith("Voice")) "VOICE_COMMUNICATION" else "MIC"
                    audioSource = src
                    viewModel?.appSettings?.audioSource = src
                }
            )

            SettingsSliderRow(
                icon = Icons.Default.VolumeUp,
                title = "Mic Gain Booster",
                value = micGain,
                valueRange = 1.0f..3.0f,
                steps = 3,
                onValueChange = {
                    micGain = it
                    viewModel?.appSettings?.micGain = it
                },
                valueLabel = String.format(Locale.US, "%.1fx", micGain)
            )
            Text(
                text = "Boosts quiet speech so Gemini never misses your voice.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)
            )
        }

        PremiumCard(title = "Power & Anti-Echo") {
            SettingsDropdownRow(
                icon = Icons.Default.Bedtime,
                title = "Auto-Sleep Inactivity",
                selectedOption = if (autoSleep == 0) "Never" else "$autoSleep Minutes",
                options = listOf("Never", "1 Minute", "3 Minutes", "5 Minutes", "10 Minutes"),
                onOptionSelected = {
                    val minutes = when (it) {
                        "Never" -> 0
                        "1 Minute" -> 1
                        "3 Minutes" -> 3
                        "5 Minutes" -> 5
                        else -> 10
                    }
                    autoSleep = minutes
                    viewModel?.appSettings?.autoSleepMinutes = minutes
                }
            )

            SettingsSwitchRow(
                icon = Icons.Default.Videocam,
                title = "Screen Recording Mode (Anti-Echo)",
                subtitle = "Applies hardware echo canceler and communication audio mode while recording.",
                checked = isScreenRecMode,
                onCheckedChange = {
                    isScreenRecMode = it
                    viewModel?.appSettings?.isScreenRecordingModeEnabled = it
                }
            )
        }
    }
}
