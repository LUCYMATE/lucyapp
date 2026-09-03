package com.soltini.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SoltiniColorScheme = lightColorScheme(
    primary = RosePink,
    onPrimary = Color.White,
    primaryContainer = RosePinkLight,
    onPrimaryContainer = RosePinkDeep,
    secondary = SoftViolet,
    onSecondary = Color.White,
    secondaryContainer = VioletLight,
    onSecondaryContainer = SoftViolet,
    background = PinkyWhiteBackground,
    onBackground = TextPrimary,
    surface = PearlWhiteSurface,
    onSurface = TextPrimary,
    surfaceVariant = BlushSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = PinkyBorder,
    outlineVariant = PinkyBorderSubtle,
    error = RedError,
    onError = Color.White,
    tertiary = EmeraldLive,
    tertiaryContainer = AmberConnecting
)

@Composable
fun GeminiVoiceTheme(
    darkTheme: Boolean = false, // Pinky-White theme is default across all modes
    content: @Composable () -> Unit
) {
    val colorScheme = SoltiniColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            // Always use dark status & nav bar icons on pinky-white background
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

