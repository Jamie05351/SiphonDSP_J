package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import app.siphondsp.audio.MeasurementSpectrumAnalyzer
import kotlin.math.max
import kotlin.math.min

/**
 * Static raw-in vs output dB-vs-log-frequency comparison chart for a finished measurement
 * capture. Not live -- populated once via [setData] after
 * [app.siphondsp.audio.MeasurementSpectrumAnalyzer] finishes.
 */
class MeasurementResponseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var sampleRate = 48000
    private var fftSize = MeasurementSpectrumAnalyzer.WINDOW_SIZE
    private var rawInDb: FloatArray? = null
    private var outDb: FloatArray? = null

    private val density = context.resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = density * 10f
    }
    private val rawInPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 170, 178, 186)
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
    }
    private val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.LIGHT_BLUE
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
    }

    fun setData(rawInDb: FloatArray, outDb: FloatArray, sampleRate: Int, fftSize: Int) {
        this.rawInDb = rawInDb
        this.outDb = outDb
        this.sampleRate = sampleRate
        this.fftSize = fftSize
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rawIn = rawInDb ?: return
        val out = outDb ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        var minDb = Float.MAX_VALUE
        var maxDb = -Float.MAX_VALUE
        for (v in rawIn) if (v > FLOOR_DB) { minDb = min(minDb, v); maxDb = max(maxDb, v) }
        for (v in out) if (v > FLOOR_DB) { minDb = min(minDb, v); maxDb = max(maxDb, v) }
        if (minDb > maxDb) { minDb = FLOOR_DB; maxDb = 0f }
        minDb -= 6f
        maxDb += 6f

        for (freq in FREQ_TICKS) {
            val x = PeqGraphMath.frequencyToFraction(freq.toDouble()) * w
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawText(formatFreq(freq), x + density * 2f, h - density * 2f, labelPaint)
        }

        canvas.drawPath(buildPath(rawIn, minDb, maxDb, w, h), rawInPaint)
        canvas.drawPath(buildPath(out, minDb, maxDb, w, h), outPaint)
    }

    private fun buildPath(db: FloatArray, minDb: Float, maxDb: Float, w: Float, h: Float): Path {
        val path = Path()
        var started = false
        val span = (maxDb - minDb).coerceAtLeast(1f)
        for (bin in db.indices) {
            val freq = bin.toDouble() * sampleRate / fftSize
            val x = PeqGraphMath.frequencyToFraction(freq) * w
            val yFraction = ((db[bin] - minDb) / span).coerceIn(0f, 1f)
            val y = h - yFraction * h
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        return path
    }

    private fun formatFreq(freq: Int): String = if (freq >= 1000) "${freq / 1000}k" else "$freq"

    companion object {
        private const val FLOOR_DB = -150f
        private val FREQ_TICKS = intArrayOf(20, 100, 1000, 10000, 20000)
    }
}
