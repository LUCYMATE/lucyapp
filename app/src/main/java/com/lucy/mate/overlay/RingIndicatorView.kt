package com.lucy.mate.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

enum class RingState { IDLE, LISTENING, SPEAKING, THINKING, SLEEPING }

class RingIndicatorView(context: Context) : View(context) {
    companion object {
        val COLOR_ROSE_PINK = Color.parseColor("#F43F8E")
        val COLOR_ROSE_PINK_DEEP = Color.parseColor("#E11D48")
        val COLOR_SOFT_BLUSH = Color.parseColor("#FB7185")
        val COLOR_SOFT_VIOLET = Color.parseColor("#8B5CF6")
        val COLOR_SLEEP_BLUSH = Color.parseColor("#FDA4AF")
        val COLOR_SLEEP_PINK = Color.parseColor("#FCE7F3")
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private var pulseAlpha = 0.75f
    private var pulseScale = 1f
    private var pulseAnimator: ValueAnimator? = null
    
    @Volatile
    var amplitude: Float = 0f
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    var state: RingState = RingState.IDLE
        set(value) {
            field = value
            pulseAnimator?.cancel()
            when (value) {
                RingState.IDLE -> { 
                    colorCenter = COLOR_ROSE_PINK
                    colorEdge = COLOR_SOFT_BLUSH
                    pulseAlpha = 0.75f 
                    invalidate() 
                } 
                RingState.SLEEPING -> {
                    // Soft, calming luxury rose-blush breathe pulse — tap to wake
                    colorCenter = COLOR_SLEEP_BLUSH
                    colorEdge = COLOR_SLEEP_PINK
                    pulseAnimator = ValueAnimator.ofFloat(0.35f, 0.65f, 0.35f).apply {
                        duration = 3200
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        addUpdateListener {
                            pulseAlpha = it.animatedValue as Float
                            pulseScale = 1f
                            invalidate()
                        }
                        start()
                    }
                }
                RingState.LISTENING -> startPulse(COLOR_SOFT_BLUSH, COLOR_ROSE_PINK)
                RingState.SPEAKING -> startPulse(COLOR_ROSE_PINK, COLOR_SOFT_BLUSH)
                RingState.THINKING -> startPulse(COLOR_SOFT_VIOLET, COLOR_ROSE_PINK)
            }
        }
    
    private var colorCenter = COLOR_ROSE_PINK
    private var colorEdge = COLOR_SOFT_BLUSH
    
    init {
        // Radiant Rose Pink & Soft Blush luxury default
        colorCenter = COLOR_ROSE_PINK
        colorEdge = COLOR_SOFT_BLUSH
        pulseAlpha = 0.75f
    }
    
    private fun startPulse(center: Int, edge: Int) {
        colorCenter = center
        colorEdge = edge
        pulseAnimator = ValueAnimator.ofFloat(0.65f, 1.0f, 0.65f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { 
                pulseAlpha = it.animatedValue as Float
                pulseScale = 1f + ((it.animatedValue as Float - 0.65f) * 0.18f) // subtle scale
                invalidate()
            }
            start()
        }
    }
    
    private val points = 8
    private val angleStep = (Math.PI * 2 / points).toFloat()
    private val vertexX = FloatArray(points)
    private val vertexY = FloatArray(points)
    private val blobPathOuter = android.graphics.Path()
    private val blobPathMiddle = android.graphics.Path()
    private val blobPathCore = android.graphics.Path()

    private val gradientColors = intArrayOf(Color.WHITE, 0, 0, Color.TRANSPARENT)
    private val gradientStops = floatArrayOf(0f, 0.4f, 0.8f, 1f)
    private var cachedGradient: RadialGradient? = null
    private var cachedGradientRadius = -1f
    private var cachedColorCenter = 0
    private var cachedColorEdge = 0

    private fun updateBlobPath(targetPath: android.graphics.Path, cx: Float, cy: Float, baseRadius: Float, ampMultiplier: Float, phase: Float): android.graphics.Path {
        targetPath.reset()
        
        for (i in 0 until points) {
            val angle = i * angleStep
            val wave1 = kotlin.math.sin(angle * 3 + phase) * 0.08f
            val wave2 = kotlin.math.cos(angle * 2 - phase * 1.5f) * 0.12f
            
            // amplitude is the raw voice amplitude property of this view
            val deformation = (wave1 + wave2) * (1f + amplitude * 2.5f * ampMultiplier)
            val r = baseRadius * (1f + deformation)
            
            vertexX[i] = cx + r * kotlin.math.cos(angle)
            vertexY[i] = cy + r * kotlin.math.sin(angle)
        }
        
        for (i in 0 until points) {
            val next = (i + 1) % points
            val midX = (vertexX[i] + vertexX[next]) / 2f
            val midY = (vertexY[i] + vertexY[next]) / 2f
            
            if (i == 0) {
                targetPath.moveTo(midX, midY)
            } else {
                targetPath.quadTo(vertexX[i], vertexY[i], midX, midY)
            }
        }
        val midFirstX = (vertexX[0] + vertexX[1]) / 2f
        val midFirstY = (vertexY[0] + vertexY[1]) / 2f
        targetPath.quadTo(vertexX[0], vertexY[0], midFirstX, midFirstY)
        targetPath.close()
        
        return targetPath
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        
        // Base radius leaves room for glow and amplitude expansion
        val baseRadius = min(cx, cy) * 0.4f
        
        // Add amplitude effect (voice bouncing)
        val currentRadius = baseRadius * pulseScale * (1f + (amplitude * 1.5f))
        val phase = (System.currentTimeMillis() % 10000L) / 10000f * (2 * Math.PI.toFloat())
        
        // 1. Draw outer faint glow
        paint.shader = null
        paint.color = colorEdge
        paint.alpha = (pulseAlpha * 40).toInt()
        canvas.drawPath(updateBlobPath(blobPathOuter, cx, cy, currentRadius * 1.8f, 0.2f, phase), paint)

        // 2. Draw middle glow
        paint.color = colorCenter
        paint.alpha = (pulseAlpha * 80).toInt()
        canvas.drawPath(updateBlobPath(blobPathMiddle, cx, cy, currentRadius * 1.4f, 0.5f, phase), paint)
        
        // 3. Draw Inner core with gradient (reused cached gradient)
        if (cachedGradient == null || cachedGradientRadius != currentRadius || cachedColorCenter != colorCenter || cachedColorEdge != colorEdge) {
            gradientColors[1] = colorCenter
            gradientColors[2] = colorEdge
            cachedGradient = RadialGradient(
                cx, cy, currentRadius * 1.2f,
                gradientColors,
                gradientStops,
                Shader.TileMode.CLAMP
            )
            cachedGradientRadius = currentRadius
            cachedColorCenter = colorCenter
            cachedColorEdge = colorEdge
        }
        paint.shader = cachedGradient
        paint.alpha = (pulseAlpha * 255).toInt()
        
        canvas.drawPath(updateBlobPath(blobPathCore, cx, cy, currentRadius, 1f, phase), paint)
        
        // Keep animating continuously only when active (active state, voice amplitude, or pulse animator)
        if (state != RingState.IDLE || amplitude > 0.01f || pulseAnimator?.isRunning == true) {
            postInvalidateOnAnimation()
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        cachedGradient = null
    }
}
