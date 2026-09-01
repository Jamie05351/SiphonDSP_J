package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.extensions.prettyNumberFormat

/**
 * Pinned visualiser for the pre-crossover multiband compressor, a display-only sibling of
 * [ParametricEqSurface]: log-frequency X-axis, a single dBFS Y-axis, and on it the live dry/wet
 * spectrum (via [SpectrumEngine]), the four shaded crossover-band regions with their split
 * lines, each band's threshold line, and the applied gain-reduction curve hanging off the 0 dB
 * reference. Nothing here is interactive -- editing (split handles, threshold drag, the band
 * cards) lands in a later PR.
 *
 * Feed it [setSystemValues] (the [NativeBmwDspValues] array) and [setMbcMeter] (the 12-float
 * `readMbcMeter` output: 4 bands x [inDb, outDb, grDb]); it drives its own spectrum tick while
 * attached.
 */
class CompressorSurface(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val padLeft = 28f * density
    private val padRight = 10f * density
    private val padTop = 12f * density
    private val padBottom = 20f * density

    private var systemValues = NativeBmwDspValues.DEFAULTS.copyOf()
    private var mbcMeter = FloatArray(NativeBmwDspValues.MBC_BAND_COUNT * 3) { if (it % 3 == 2) 0f else -60f }

    // --- paints ---------------------------------------------------------------------------
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 60, 66); style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 122, 130); style = Paint.Style.STROKE; strokeWidth = 1.3f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(176, 178, 186); textSize = 9.5f * density
    }
    private val splitLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 152, 160); style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val bandFillPaints = intArrayOf(
        BmwDashboardSkin.M_BLUE, 0xFF6E7BFF.toInt(), 0xFFF2B33D.toInt(), 0xFFE86A4A.toInt(),
    ).map { tint ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(tint, BAND_FILL_ALPHA); style = Paint.Style.FILL }
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorPrimary); style = Paint.Style.STROKE; strokeWidth = 1.4f * density
    }
    private val dryStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorPrimary); style = Paint.Style.STROKE
        strokeWidth = 1.5f * density; alpha = 150
    }
    private val wetStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_GREEN; style = Paint.Style.STROKE; strokeWidth = 1.8f * density
    }
    private val boostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_GREEN; style = Paint.Style.FILL; alpha = 90
    }
    private val cutFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_RED; style = Paint.Style.FILL; alpha = 90
    }
    private val gainCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_RED; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 202, 210); textSize = 10f * density; textAlign = Paint.Align.CENTER
    }

    private val wetPath = Path()
    private val dryPath = Path()
    private val fillPath = Path()
    private val xs = FloatArray(SPECTRUM_STEPS + 1)
    private val dryYs = FloatArray(SPECTRUM_STEPS + 1)
    private val wetYs = FloatArray(SPECTRUM_STEPS + 1)

    // --- spectrum lifecycle (mirrors ParametricEqSurface) -------------------------------
    private val handler = Handler(Looper.getMainLooper())
    private var spectrumActive = false
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 33L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!spectrumActive) {
            spectrumActive = true
            SpectrumEngine.acquire()
            handler.post(tick)
        }
    }

    override fun onDetachedFromWindow() {
        if (spectrumActive) {
            spectrumActive = false
            handler.removeCallbacks(tick)
            SpectrumEngine.release()
        }
        super.onDetachedFromWindow()
    }

    fun setSystemValues(values: FloatArray) {
        if (values.size < NativeBmwDspValues.SIZE) return
        systemValues = values.copyOf()
        invalidate()
    }

    fun setMbcMeter(meter: FloatArray) {
        if (meter.size < mbcMeter.size) return
        System.arraycopy(meter, 0, mbcMeter, 0, mbcMeter.size)
        invalidate()
    }

    // --- coordinate mapping -------------------------------------------------------------
    private fun plotLeft() = paddingLeft + padLeft
    private fun plotRight() = width - paddingRight - padRight
    private fun plotTop() = paddingTop + padTop
    private fun plotBottom() = height - paddingBottom - padBottom

    private fun xForFrequency(hz: Double): Float =
        plotLeft() + CompressorSurfaceMath.frequencyToFraction(hz) * (plotRight() - plotLeft())

    private fun yForDb(db: Double): Float =
        plotTop() + CompressorSurfaceMath.dbToFraction(db) * (plotBottom() - plotTop())

    // --- drawing ----------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = plotLeft()
        val right = plotRight()
        val top = plotTop()
        val bottom = plotBottom()
        if (right <= left || bottom <= top) return

        val splits = CompressorSurfaceMath.splitFrequencies(systemValues)
        drawBandRegions(canvas, top, bottom, splits)
        drawGrid(canvas, left, right, top, bottom)
        if (spectrumActive) drawSpectrum(canvas, left, right)
        drawThresholdLines(canvas, left, right, splits)
        if (systemValues[NativeBmwDspValues.INDEX_MBC_ENABLED] >= .5f) drawGainCurve(canvas, left, right, splits)
        drawBandReadouts(canvas, top, splits)
    }

    private fun drawGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        for (db in CompressorSurfaceMath.GRID_DB) {
            val y = yForDb(db)
            canvas.drawLine(left, y, right, y, if (db == 0.0) zeroPaint else gridPaint)
            canvas.drawText("${db.toInt()}", 3f * density, y + 3.2f * density, labelPaint)
        }
        for (hz in FREQ_SCALE) {
            val x = xForFrequency(hz)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val label = hz.prettyNumberFormat()
            canvas.drawText(label, x - labelPaint.measureText(label) / 2f, bottom + 14f * density, labelPaint)
        }
    }

    private fun drawBandRegions(canvas: Canvas, top: Float, bottom: Float, splits: DoubleArray) {
        for (band in 0 until CompressorSurfaceMath.BAND_COUNT) {
            val (lowHz, highHz) = CompressorSurfaceMath.bandRange(band, splits)
            val x0 = xForFrequency(lowHz)
            val x1 = xForFrequency(highHz)
            canvas.drawRect(x0, top, x1, bottom, bandFillPaints[band])
        }
        for (hz in splits) {
            val x = xForFrequency(hz)
            canvas.drawLine(x, top, x, bottom, splitLinePaint)
        }
    }

    private fun drawSpectrum(canvas: Canvas, left: Float, right: Float) {
        wetPath.rewind(); dryPath.rewind()
        for (i in 0..SPECTRUM_STEPS) {
            val fraction = i / SPECTRUM_STEPS.toFloat()
            val freq = PeqGraphMath.fractionToFrequency(fraction, CompressorSurfaceMath.MIN_FREQUENCY, CompressorSurfaceMath.MAX_FREQUENCY)
            val x = left + fraction * (right - left)
            val wetY = yForDb(SpectrumEngine.magnitudeDbAt(freq).toDouble())
            val dryY = yForDb(SpectrumEngine.dryMagnitudeDbAt(freq).toDouble())
            xs[i] = x; wetYs[i] = wetY; dryYs[i] = dryY
            if (i == 0) { wetPath.moveTo(x, wetY); dryPath.moveTo(x, dryY) }
            else { wetPath.lineTo(x, wetY); dryPath.lineTo(x, dryY) }
        }
        drawSpectrumDelta(canvas)
        canvas.drawPath(dryPath, dryStrokePaint)
        canvas.drawPath(wetPath, wetStrokePaint)
    }

    /** Shades the gap between the dry and wet traces, green where wet sits louder, red where quieter. */
    private fun drawSpectrumDelta(canvas: Canvas) {
        var start = 0
        var boost = wetYs[0] <= dryYs[0]
        for (i in 1..SPECTRUM_STEPS) {
            val isBoost = wetYs[i] <= dryYs[i]
            if (isBoost != boost) { fillDelta(canvas, start, i, boost); start = i; boost = isBoost }
        }
        fillDelta(canvas, start, SPECTRUM_STEPS, boost)
    }

    private fun fillDelta(canvas: Canvas, from: Int, to: Int, boost: Boolean) {
        if (to <= from) return
        fillPath.rewind()
        fillPath.moveTo(xs[from], dryYs[from])
        for (i in from..to) fillPath.lineTo(xs[i], dryYs[i])
        for (i in to downTo from) fillPath.lineTo(xs[i], wetYs[i])
        fillPath.close()
        canvas.drawPath(fillPath, if (boost) boostFillPaint else cutFillPaint)
    }

    private fun drawThresholdLines(canvas: Canvas, left: Float, right: Float, splits: DoubleArray) {
        for (band in 0 until CompressorSurfaceMath.BAND_COUNT) {
            val enabled = systemValues[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_ENABLED)] >= .5f
            val thr = systemValues[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_THRESHOLD)].toDouble()
            val (lowHz, highHz) = CompressorSurfaceMath.bandRange(band, splits)
            val x0 = xForFrequency(lowHz).coerceAtLeast(left)
            val x1 = xForFrequency(highHz).coerceAtMost(right)
            val y = yForDb(thr)
            thresholdPaint.alpha = if (enabled) 210 else 70
            canvas.drawLine(x0, y, x1, y, thresholdPaint)
        }
    }

    /**
     * The applied gain-reduction curve: a line at the 0 dB reference that dips to `-GR` within
     * each band, per the live meter. Piecewise-flat per band with a steep step at each crossover,
     * which is what a 4-band compressor's broadband gain actually looks like.
     */
    private fun drawGainCurve(canvas: Canvas, left: Float, right: Float, splits: DoubleArray) {
        wetPath.rewind()
        var started = false
        for (i in 0..SPECTRUM_STEPS) {
            val fraction = i / SPECTRUM_STEPS.toFloat()
            val freq = PeqGraphMath.fractionToFrequency(fraction, CompressorSurfaceMath.MIN_FREQUENCY, CompressorSurfaceMath.MAX_FREQUENCY)
            val band = CompressorSurfaceMath.bandForFrequency(freq, splits)
            val gr = mbcMeter[band * 3 + 2]
            val x = left + fraction * (right - left)
            val y = yForDb(CompressorSurfaceMath.gainCurveDbForReduction(gr))
            if (!started) { wetPath.moveTo(x, y); started = true } else wetPath.lineTo(x, y)
        }
        canvas.drawLine(left, yForDb(0.0), right, yForDb(0.0), gridPaint)
        canvas.drawPath(wetPath, gainCurvePaint)
    }

    private fun drawBandReadouts(canvas: Canvas, top: Float, splits: DoubleArray) {
        val mbcOn = systemValues[NativeBmwDspValues.INDEX_MBC_ENABLED] >= .5f
        for (band in 0 until CompressorSurfaceMath.BAND_COUNT) {
            val (lowHz, highHz) = CompressorSurfaceMath.bandRange(band, splits)
            val cx = (xForFrequency(lowHz) + xForFrequency(highHz)) / 2f
            val enabled = mbcOn && systemValues[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_ENABLED)] >= .5f
            val gr = mbcMeter[band * 3 + 2]
            val text = if (enabled) "GR ${"%.1f".format(gr)} dB" else "—"
            canvas.drawText(text, cx, top + 12f * density, readoutPaint)
        }
    }

    private fun themeColor(attribute: Int): Int {
        if (isInEditMode) return Color.WHITE
        var color = Color.WHITE
        context.withStyledAttributes(TypedValue().data, intArrayOf(attribute)) {
            color = getColor(0, Color.WHITE)
        }
        return color
    }

    companion object {
        private const val SPECTRUM_STEPS = 200
        private const val BAND_FILL_ALPHA = 34

        private val FREQ_SCALE = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
        )
    }
}
