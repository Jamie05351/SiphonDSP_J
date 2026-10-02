package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import app.siphondsp.audio.SpectrumEngine
import kotlin.math.ceil

/**
 * The front page's output meter on the left panel: two tall purple LED columns, L then R, lit from
 * the bottom (post-DSP RMS illumination plus a peak-hold outline from [SpectrumEngine]'s analyzer).
 * Unlit segments stay as dim purple glass so the meter reads as hardware even in silence.
 *
 * The analyzer thread only runs while something holds [SpectrumEngine.acquire]; this view holds
 * it only while it is attached, its window and view are visible, and [pageActive] is true (the
 * pager is on the artwork page), so nothing extra runs once the user has moved on to a DSP
 * screen or swiped to the settings page.
 */
class HomeLevelBars @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val leftMeter = PeakHoldMeter(floorDb = FLOOR_DB)
    private val rightMeter = PeakHoldMeter(floorDb = FLOOR_DB)
    private val levels = FloatArray(4)
    private var acquired = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            SpectrumEngine.channelLevelsInto(levels)
            val now = System.currentTimeMillis()
            leftMeter.update(levels[0], levels[1], now)
            rightMeter.update(levels[2], levels[3], now)
            invalidate()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0x6A, 0x5C, 0xA8) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 0xB1, 0x4D, 0xFF) }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xED, 0xD8, 0xFF)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val rect = RectF()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    /**
     * False while the pager is showing another page. The artwork page stays attached and
     * "visible" to the window when swiped away (ViewPager2 only translates it, and DspFragment
     * keeps offscreenPageLimit = 1), so window/view visibility alone can't tell whether the bars
     * are actually on screen; the fragment drives this from the pager selection.
     */
    var pageActive: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateRunning()
        }

    private fun updateRunning() {
        if (pageActive && isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (acquired) return
        acquired = true
        SpectrumEngine.acquire()
        handler.post(tick)
    }

    private fun stop() {
        if (!acquired) return
        acquired = false
        handler.removeCallbacks(tick)
        SpectrumEngine.release()
        leftMeter.reset()
        rightMeter.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Two columns with a gap a fifth of a column wide between them.
        val columnW = w / 2.2f
        drawColumn(canvas, leftMeter, 0f, columnW, h)
        drawColumn(canvas, rightMeter, w - columnW, columnW, h)
    }

    /** One LED column, segment 0 at the bottom. */
    private fun drawColumn(canvas: Canvas, meter: PeakHoldMeter, left: Float, columnW: Float, h: Float) {
        // Bar = RMS (average loudness); the outlined segment = peak hold. Filling to instantaneous
        // peak pinned the bar near full on any mastered music.
        val fraction = PeakHoldMeter.fractionFor(meter.rmsDb, FLOOR_DB, CEILING_DB)
        val segmentCount = SEGMENT_COUNT
        val pitch = h / segmentCount
        val segmentH = pitch * 0.62f
        val glowPad = pitch * 0.12f
        val radius = segmentH * 0.22f
        val right = left + columnW
        val active = if (fraction <= 0f) 0 else ceil(fraction * segmentCount).toInt().coerceAtMost(segmentCount)
        fillPaint.shader = LinearGradient(
            left,
            0f,
            right,
            0f,
            intArrayOf(PURPLE_SHADOW, PURPLE, PURPLE_HIGHLIGHT, PURPLE, PURPLE_SHADOW),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        repeat(segmentCount) { index ->
            val bottom = h - index * pitch - (pitch - segmentH) / 2f
            rect.set(left, bottom - segmentH, right, bottom)
            if (index < active) {
                rect.inset(-glowPad, -glowPad)
                canvas.drawRoundRect(rect, radius + glowPad, radius + glowPad, glowPaint)
                rect.inset(glowPad, glowPad)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                canvas.drawLine(
                    rect.left + columnW * 0.12f,
                    rect.top + density,
                    rect.right - columnW * 0.12f,
                    rect.top + density,
                    highlightPaint,
                )
            } else {
                canvas.drawRoundRect(rect, radius, radius, trackPaint)
            }
        }

        val hold = PeakHoldMeter.fractionFor(meter.holdDb, FLOOR_DB, CEILING_DB)
        if (hold > 0f) {
            val index = (ceil(hold * segmentCount).toInt() - 1).coerceIn(0, segmentCount - 1)
            val bottom = h - index * pitch - (pitch - segmentH) / 2f
            rect.set(left, bottom - segmentH, right, bottom)
            canvas.drawRoundRect(rect, radius, radius, holdPaint)
        }
    }

    private companion object {
        const val FRAME_MS = 50L
        const val FLOOR_DB = -60f
        const val CEILING_DB = 0f
        const val SEGMENT_COUNT = 28
        val PURPLE = BmwDashboardSkin.SLIDER_HEADROOM_COLOR
        val PURPLE_HIGHLIGHT = Color.rgb(0xE2, 0xC2, 0xFF)
        val PURPLE_SHADOW = Color.rgb(0x54, 0x16, 0x88)
    }
}
