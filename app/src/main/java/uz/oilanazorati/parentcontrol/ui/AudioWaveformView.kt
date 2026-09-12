package uz.oilanazorati.parentcontrol.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Jonli ovoz" ekrani uchun animatsion to'lqin ko'rinishi.
 *
 * Faol (isActive=true) bo'lganda tinimsiz to'lqinlanadi (ovoz eshitilayotganini
 * bildiradi); nofaol bo'lganda tekis, past chiziqlar bilan tinch turadi.
 * Animatsiya faqat faol paytda ishlaydi — batareyani tejash uchun.
 */
class AudioWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val BAR_COUNT = 32
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var phase = 0f
    private var isActive = false
    private val barSeeds = FloatArray(BAR_COUNT) { Random.nextFloat() * 6.28f }

    private val animator = ValueAnimator.ofFloat(0f, 6.2832f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        if (active) {
            if (!animator.isRunning) animator.start()
        } else {
            animator.cancel()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val barSpace = w / BAR_COUNT
        val strokeWidth = (barSpace * 0.45f).coerceAtLeast(3f)
        paint.strokeWidth = strokeWidth
        paint.color = if (isActive) 0xFFE74C3C.toInt() else 0xFF8B96A5.toInt()

        val centerY = h / 2f
        val maxAmplitude = h * 0.42f
        val minAmplitude = h * 0.04f

        for (i in 0 until BAR_COUNT) {
            val x = barSpace * i + barSpace / 2f
            val amplitude = if (isActive) {
                val wave = abs(sin(phase + barSeeds[i]))
                minAmplitude + wave * (maxAmplitude - minAmplitude)
            } else {
                minAmplitude
            }
            canvas.drawLine(x, centerY - amplitude, x, centerY + amplitude, paint)
        }
    }
}
