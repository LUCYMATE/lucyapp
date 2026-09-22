package com.lucy.mate.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lucy.mate.ui.theme.RosePink
import com.lucy.mate.ui.theme.SoftBlush
import com.lucy.mate.ui.theme.SoftViolet

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
    val pulseScaleAnim by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
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

    val baseScale = if (isLive) pulseScaleAnim else 1f
    val dynamicRadiusScale = baseScale * (1f + animatedAmp * 1.6f) // Siri/Gemini dynamic bounce

    // Target colors for Pinky-White theme
    val targetCenter = when {
        !isLive -> Color(0xFFFCE7F3)
        isMuted -> Color(0xFFE2E8F0)
        isSpeaking -> RosePink
        else -> SoftBlush
    }

    val targetEdge = when {
        !isLive -> Color(0xFFF472B6).copy(alpha = 0.5f)
        isMuted -> Color(0xFF94A3B8)
        isSpeaking -> SoftViolet
        else -> RosePink
    }

    val colorCenter by animateColorAsState(targetCenter, tween(500), label = "centerColor")
    val colorEdge by animateColorAsState(targetEdge, tween(500), label = "edgeColor")

    val auraPath = remember { Path() }
    val corePath = remember { Path() }
    val vertexX: FloatArray = remember { FloatArray(8) }
    val vertexY: FloatArray = remember { FloatArray(8) }
    val colorStops = remember(colorCenter, colorEdge) {
        arrayOf(
            0.0f to Color.White,
            0.4f to colorCenter,
            0.8f to colorEdge,
            1.0f to Color.Transparent
        )
    }

    Box(
        modifier = modifier
            .size(300.dp)
            .testTag("audio_visualizer_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.25f // Base size of the orb
            
            val currentRadius = baseRadius * dynamicRadiusScale

            // Compute an infinite phase for the morphing animation
            val phase = (System.currentTimeMillis() % 10000L) / 10000f * (2 * Math.PI.toFloat())
            
            // Zero-allocation smooth blob path generator
            fun updateBlobPath(targetPath: Path, baseR: Float, ampMultiplier: Float = 1f): Path {
                targetPath.reset()
                val points = 8
                val angleStep = (Math.PI * 2 / points).toFloat()
                
                for (i in 0 until points) {
                    val angle = i * angleStep
                    val wave1 = kotlin.math.sin(angle * 3 + phase) * 0.08f
                    val wave2 = kotlin.math.cos(angle * 2 - phase * 1.5f) * 0.12f
                    
                    val deformation = (wave1 + wave2) * (1f + animatedAmp * 2.5f * ampMultiplier)
                    val r = baseR * (1f + deformation)
                    
                    vertexX[i] = center.x + r * kotlin.math.cos(angle)
                    vertexY[i] = center.y + r * kotlin.math.sin(angle)
                }
                
                // Draw smooth curve using midpoints
                for (i in 0 until points) {
                    val next = (i + 1) % points
                    val midX = (vertexX[i] + vertexX[next]) / 2f
                    val midY = (vertexY[i] + vertexY[next]) / 2f
                    
                    if (i == 0) {
                        targetPath.moveTo(midX, midY)
                    } else {
                        targetPath.quadraticTo(vertexX[i], vertexY[i], midX, midY)
                    }
                }
                // Close the loop smoothly
                val midFirstX = (vertexX[0] + vertexX[1]) / 2f
                val midFirstY = (vertexY[0] + vertexY[1]) / 2f
                targetPath.quadraticTo(vertexX[0], vertexY[0], midFirstX, midFirstY)
                targetPath.close()
                return targetPath
            }

            // 1. Outer faint ring (circle)
            drawCircle(
                color = colorEdge.copy(alpha = 0.15f),
                radius = currentRadius * 1.8f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // 2. Middle aura (morphing blob)
            drawPath(
                path = updateBlobPath(auraPath, currentRadius * 1.3f, 0.5f),
                color = colorCenter.copy(alpha = 0.25f)
            )

            // 3. Inner core gradient orb (Siri-style morphing blob)
            drawPath(
                path = updateBlobPath(corePath, currentRadius, 1f),
                brush = Brush.radialGradient(
                    colorStops = colorStops,
                    center = center,
                    radius = currentRadius * 1.2f
                )
            )
        }
    }
}
