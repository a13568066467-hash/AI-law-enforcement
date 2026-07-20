package com.aifieldcam.app.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.aifieldcam.app.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class ListeningWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class WaveProfile(
        val durationMillis: Long,
        val minimumHeightDp: Float,
        val maximumHeightDp: Float,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barWidth = 5f.dp
    private val barGap = 4f.dp
    private val phaseOffsets = floatArrayOf(0f, 1.55f, 0.75f, 2.2f, 1.1f, 2.65f, 0.35f)
    private val primaryColor = resources.getColor(R.color.home_text_primary, context.theme)
    private val accentColor = resources.getColor(R.color.home_blue, context.theme)

    private var state = AssistantWaveState.IDLE
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2f * PI).toFloat()).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setState(state: AssistantWaveState) {
        if (state == AssistantWaveState.IDLE) {
            stop()
            return
        }

        visibility = VISIBLE
        if (this.state == state && animator.isRunning) return

        this.state = state
        animator.cancel()
        animator.duration = state.profile.durationMillis
        animator.start()
    }

    fun stop() {
        state = AssistantWaveState.IDLE
        animator.cancel()
        phase = 0f
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == AssistantWaveState.IDLE) return

        val profile = state.profile
        val desiredWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * barGap
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        val scale = min(1f, availableWidth / desiredWidth)
        val scaledBarWidth = barWidth * scale
        val scaledGap = barGap * scale
        val waveWidth = BAR_COUNT * scaledBarWidth + (BAR_COUNT - 1) * scaledGap
        val startX = paddingLeft + (availableWidth - waveWidth) / 2f
        val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
        val minimumHeight = min(profile.minimumHeightDp.dp, availableHeight)
        val maximumHeight = min(profile.maximumHeightDp.dp, availableHeight)

        repeat(BAR_COUNT) { index ->
            val normalizedSine = ((sin(phase + phaseOffsets[index]) + 1f) / 2f)
            val barHeight = minimumHeight + (maximumHeight - minimumHeight) * normalizedSine
            val left = startX + index * (scaledBarWidth + scaledGap)
            val top = centerY - barHeight / 2f

            paint.color = if (index % 2 == 0) primaryColor else accentColor
            canvas.drawRoundRect(
                left,
                top,
                left + scaledBarWidth,
                centerY + barHeight / 2f,
                scaledBarWidth / 2f,
                scaledBarWidth / 2f,
                paint,
            )
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density

    private val AssistantWaveState.profile: WaveProfile
        get() = when (this) {
            AssistantWaveState.LISTENING -> LISTENING_PROFILE
            AssistantWaveState.PROCESSING -> PROCESSING_PROFILE
            AssistantWaveState.SPEAKING -> SPEAKING_PROFILE
            AssistantWaveState.IDLE -> IDLE_PROFILE
        }

    private companion object {
        const val BAR_COUNT = 7
        val LISTENING_PROFILE = WaveProfile(520L, 8f, 48f)
        val PROCESSING_PROFILE = WaveProfile(1_100L, 8f, 26f)
        val SPEAKING_PROFILE = WaveProfile(760L, 10f, 52f)
        val IDLE_PROFILE = WaveProfile(0L, 0f, 0f)
    }
}
