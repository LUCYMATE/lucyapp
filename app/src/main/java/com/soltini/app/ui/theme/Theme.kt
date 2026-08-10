package com.soltini.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ProfessionalPolishColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = BlueSecondary,
    onPrimaryContainer = BluePrimary,
    secondary = BlueSecondary,
    onSecondary = TextPrimary,
    background = LightBackground,
    onBackground = TextPrimary,
    surface = LightSurface,
    onSurface = TextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = LightBorder,
    error = RedError,
    onError = Color.White
)

@Composable
fun GeminiVoiceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ProfessionalPolishColorScheme,
        typography = Typography,
        content = content
    )
}
