package com.soltini.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.soltini.app.ui.theme.BluePrimary
import com.soltini.app.ui.theme.BlueSecondary
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.RedError

@Composable
fun AudioVisualizerOrb(
    micAmplitude: Float,
    speakerAmplitude: Float,
    isLive: Boolean,
    isSpeaking: Boolean,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val activeAmplitude = if (isSpeaking) speakerAmplitude else micAmplitude
    val animatedAmp by animateFloatAsState(
        targetValue = activeAmplitude,
        animationSpec = tween(durationMillis = 80),
        label = "amp"
    )

    val baseScale = if (isLive) pulseAnim else 0.9f
    val dynamicRadiusScale = baseScale + (animatedAmp * 0.45f)

    val primaryColor = when {
        !isLive -> Color.Gray
        isSpeaking -> BluePrimary
        isMuted -> RedError
        else -> BluePrimary
    }

    val secondaryColor = when {
        !isLive -> Color.LightGray
        isSpeaking -> BlueSecondary
        else -> GreenLive
    }

    Box(
        modifier = modifier
            .size(240.dp)
            .testTag("audio_visualizer_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 4.2f

            // Outer soft aura 3
            drawCircle(
                color = BlueSecondary.copy(alpha = 0.35f * (1f + animatedAmp)),
                radius = baseRadius * dynamicRadiusScale * 1.8f,
                center = center
            )

            // Outer ripple ring 2
            drawCircle(
                color = primaryColor.copy(alpha = 0.15f * (1f + animatedAmp)),
                radius = baseRadius * dynamicRadiusScale * 1.5f,
                center = center
            )

            // Outer ripple ring 1
            drawCircle(
                color = secondaryColor.copy(alpha = 0.25f * (1f + animatedAmp)),
                radius = baseRadius * dynamicRadiusScale * 1.25f,
                center = center
            )

            // Dynamic Wave outline
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor, BlueSecondary),
                    center = center,
                    radius = baseRadius * dynamicRadiusScale * 1.15f
                ),
                radius = baseRadius * dynamicRadiusScale * 1.12f,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            // Inner Core Glowing Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor, BlueSecondary, Color.White),
                    center = center,
                    radius = baseRadius * dynamicRadiusScale
                ),
                radius = baseRadius * dynamicRadiusScale,
                center = center
            )
        }
    }
}
