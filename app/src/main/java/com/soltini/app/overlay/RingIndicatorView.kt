package com.soltini.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

enum class RingState { IDLE, LISTENING, SPEAKING, THINKING }

class RingIndicatorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    
    private var alpha = 0f
    private var pulseAnimator: ValueAnimator? = null
    
    var state: RingState = RingState.IDLE
        set(value) {
            field = value
            pulseAnimator?.cancel()
            when (value) {
                RingState.IDLE -> { alpha = 0f; invalidate() }
                RingState.LISTENING -> startPulse(Color.parseColor("#EC4899"))
                RingState.SPEAKING -> startPulse(Color.parseColor("#8B5CF6"))
                RingState.THINKING -> startPulse(Color.parseColor("#6366F1"))
            }
        }
    
    private var ringColor = Color.parseColor("#EC4899")
    
    private fun startPulse(color: Int) {
        ringColor = color
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 1f, 0.3f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { 
                alpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        if (alpha <= 0f) return
        paint.color = ringColor
        paint.alpha = (alpha * 255).toInt()
        val inset = paint.strokeWidth / 2f
        canvas.drawOval(inset, inset, width - inset, height - inset, paint)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}
