package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.service.RootlessAudioProcessorService
import kotlin.math.max

/**
 * Compact segmented output meter matching the new BMW dashboard language.
 * L/R come from the post-DSP stereo stream; LOW uses the native low-band compressor output meter
 * when available. The third column intentionally reads LOW rather than SUB because this DSP's
 * low branch feeds the under-seat woofers rather than a separate subwoofer output.
 */
class DspOutputLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 224, 230)
        textAlign = Paint.Align.CENTER
        textSize = 9f * density
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 157, 168)
        textAlign = Paint.Align.RIGHT
        textSize = 7.5f * density
    }
    private val levels = FloatArray(3) { FLOOR_DB }
    private val stereoScratch = FloatArray(4)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val tick = object : Runnable {
        override fun run() {
            SpectrumEngine.channelLevelsInto(stereoScratch)
            levels[0] = smooth(levels[0], max(stereoScratch[0], stereoScratch[1]))
            levels[1] = smooth(levels[1], max(stereoScratch[2], stereoScratch[3]))
            val compressor = RootlessAudioProcessorService.nativeBmwCompressorMeter()
            val low = compressor?.getOrNull(1) ?: FLOOR_DB
            levels[2] = smooth(levels[2], low.coerceIn(FLOOR_DB, 0f))
            invalidate()
            handler.postDelayed(this, 33L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!active) {
            active = true
            SpectrumEngine.acquire()
            handler.post(tick)
        }
    }

    override fun onDetachedFromWindow() {
        if (active) {
            active = false
            handler.removeCallbacks(tick)
            SpectrumEngine.release()
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = 24f * density
        val bottom = height - 20f * density
        if (bottom <= top) return
        val leftScale = 22f * density
        val available = width - leftScale - 4f * density
        val column = available / 3f
        val segmentGap = 1.5f * density
        val segmentCount = 22
        val segmentHeight = ((bottom - top) - segmentGap * (segmentCount - 1)) / segmentCount

        listOf(0, -6, -12, -18, -24, -36, -48, -60).forEach { db ->
            val y = yForDb(db.toFloat(), top, bottom)
            canvas.drawText(db.toString(), leftScale - 3f * density, y + 2.5f * density, scalePaint)
        }

        val labels = arrayOf("L", "R", "LOW")
        for (channel in 0..2) {
            val cx = leftScale + column * channel + column / 2f
            canvas.drawText(labels[channel], cx, 11f * density, textPaint)
            val barWidth = minOf(15f * density, column * .52f)
            val x0 = cx - barWidth / 2f
            val x1 = cx + barWidth / 2f
            val lit = (((levels[channel] - FLOOR_DB) / -FLOOR_DB) * segmentCount)
                .toInt().coerceIn(0, segmentCount)

            for (segment in 0 until segmentCount) {
                val y1 = bottom - segment * (segmentHeight + segmentGap)
                val y0 = y1 - segmentHeight
                val segmentDb = FLOOR_DB + (segment + 1f) / segmentCount * -FLOOR_DB
                val baseColor = when {
                    segmentDb >= -6f -> BmwDashboardSkin.M_RED
                    segmentDb >= -12f -> Color.rgb(228, 232, 235)
                    else -> BmwDashboardSkin.LIGHT_BLUE
                }
                paint.color = if (segment < lit) baseColor else Color.argb(75, 96, 105, 116)
                canvas.drawRoundRect(x0, y0, x1, y1, 1.2f * density, 1.2f * density, paint)
            }
        }
    }

    private fun yForDb(db: Float, top: Float, bottom: Float): Float {
        val fraction = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        return bottom - fraction * (bottom - top)
    }

    private fun smooth(previous: Float, next: Float): Float =
        if (next > previous) previous + (next - previous) * .62f else previous * .90f + next * .10f

    companion object {
        private const val FLOOR_DB = -60f
    }
}
