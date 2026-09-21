package com.soltini.app.overlay

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
    
    private val blobPath = android.graphics.Path()

    private fun getBlobPath(cx: Float, cy: Float, baseRadius: Float, ampMultiplier: Float): android.graphics.Path {
        blobPath.reset()
        val phase = (System.currentTimeMillis() % 10000L) / 10000f * (2 * Math.PI.toFloat())
        val points = 8
        val angleStep = Math.PI * 2 / points
        
        val vertexX = FloatArray(points)
        val vertexY = FloatArray(points)
        
        for (i in 0 until points) {
            val angle = (i * angleStep).toFloat()
            val wave1 = kotlin.math.sin(angle * 3 + phase).toFloat() * 0.08f
            val wave2 = kotlin.math.cos(angle * 2 - phase * 1.5f).toFloat() * 0.12f
            
            // amplitude is the raw voice amplitude property of this view
            val deformation = (wave1 + wave2) * (1f + amplitude * 2.5f * ampMultiplier)
            val r = baseRadius * (1f + deformation)
            
            vertexX[i] = cx + r * kotlin.math.cos(angle).toFloat()
            vertexY[i] = cy + r * kotlin.math.sin(angle).toFloat()
        }
        
        for (i in 0 until points) {
            val next = (i + 1) % points
            val midX = (vertexX[i] + vertexX[next]) / 2f
            val midY = (vertexY[i] + vertexY[next]) / 2f
            
            if (i == 0) {
                blobPath.moveTo(midX, midY)
            } else {
                blobPath.quadTo(vertexX[i], vertexY[i], midX, midY)
            }
        }
        val midFirstX = (vertexX[0] + vertexX[1]) / 2f
        val midFirstY = (vertexY[0] + vertexY[1]) / 2f
        blobPath.quadTo(vertexX[0], vertexY[0], midFirstX, midFirstY)
        blobPath.close()
        
        return blobPath
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        
        // Base radius leaves room for glow and amplitude expansion
        val baseRadius = min(cx, cy) * 0.4f
        
        // Add amplitude effect (voice bouncing)
        val currentRadius = baseRadius * pulseScale * (1f + (amplitude * 1.5f))
        
        // 1. Draw outer faint glow
        paint.shader = null
        paint.color = colorEdge
        paint.alpha = (pulseAlpha * 40).toInt()
        canvas.drawPath(getBlobPath(cx, cy, currentRadius * 1.8f, 0.2f), paint)

        // 2. Draw middle glow
        paint.color = colorCenter
        paint.alpha = (pulseAlpha * 80).toInt()
        canvas.drawPath(getBlobPath(cx, cy, currentRadius * 1.4f, 0.5f), paint)
        
        // 3. Draw Inner core with gradient
        paint.shader = RadialGradient(
            cx, cy, currentRadius * 1.2f,
            intArrayOf(Color.WHITE, colorCenter, colorEdge, Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.alpha = (pulseAlpha * 255).toInt()
        
        canvas.drawPath(getBlobPath(cx, cy, currentRadius, 1f), paint)
        
        // Keep animating continuously to make the blob morph over time
        invalidate()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}
