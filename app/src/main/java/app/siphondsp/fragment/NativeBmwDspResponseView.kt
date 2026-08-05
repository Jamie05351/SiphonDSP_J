package app.siphondsp.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.log10
import kotlin.math.pow

/**
 * Read-only electrical response preview of the native BMW DSP. Driven by the shared
 * [BmwResponseCalculator] model (the same engine the full-screen unified PEQ visualiser
 * uses) so this thumbnail and the interactive graph never disagree about the math.
 *
 * The display combines Full Range PEQ/preamp, headroom, low and mid crossover branches,
 * branch PEQ/gain/delay/polarity, complex summation, tilt and post gain. A live
 * post-processing spectrum is drawn behind the modelled curves. Compressor action is
 * deliberately not folded in -- it is nonlinear and programme-dependent, and remains on
 * its own dedicated visualiser.
 */
class NativeBmwDspResponseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    /** Selectable graph modes -- see class doc for what each combines. */
    enum class DisplayMode { MAGNITUDE, PHASE, MAGNITUDE_PHASE, GROUP_DELAY }

    var displayMode: DisplayMode = DisplayMode.MAGNITUDE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        alpha = 62
    }
    private val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 28
    }
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        alpha = 105
    }
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * density
        alpha = 180
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * density
        alpha = 180
    }
    private val sumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.1f * density
    }
    private val phasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private var values = NativeBmwDspValues.DEFAULTS.copyOf()
    private var peqState = BmwPeqState.load(context)
    private val calculator = BmwResponseCalculator(pointCount = POINT_COUNT)
    private val curves = BmwResponseCurves(POINT_COUNT)

    private val handler = Handler(Looper.getMainLooper())
    private var spectrumActive = false
    private val spectrumTick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 33L)
        }
    }

    init {
        gridPaint.color = resolveColor(android.R.attr.textColorSecondary)
        spectrumFillPaint.color = resolveColor(android.R.attr.textColorSecondary)
        spectrumPaint.color = resolveColor(android.R.attr.textColorSecondary)
        lowPaint.color = resolveColor(android.R.attr.colorAccent)
        midPaint.color = resolveColor(android.R.attr.textColorLink)
        sumPaint.color = resolveColor(android.R.attr.textColorPrimary)
        phasePaint.color = resolveColor(android.R.attr.textColorPrimary)
        labelPaint.color = resolveColor(android.R.attr.textColorSecondary)
        legendPaint.color = resolveColor(android.R.attr.textColorPrimary)
        contentDescription = "Unified live BMW DSP response, PEQ, crossover, tilt and spectrum"
    }

    fun setValues(newValues: FloatArray) {
        if (newValues.size == BmwSignalChain.VALUE_COUNT) {
            values = newValues.copyOf()
            peqState = BmwPeqState.load(context)
            calculator.invalidateAll()
            invalidate()
        }
    }

    fun refreshPeqState() {
        peqState = BmwPeqState.load(context)
        calculator.invalidateAll()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!spectrumActive) {
            spectrumActive = true
            SpectrumEngine.acquire()
            handler.post(spectrumTick)
        }
    }

    override fun onDetachedFromWindow() {
        if (spectrumActive) {
            spectrumActive = false
            handler.removeCallbacks(spectrumTick)
            SpectrumEngine.release()
        }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (250f * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 42f * density
        val right = width - paddingRight - 12f * density
        val top = paddingTop + 24f * density
        val bottom = height - paddingBottom - 28f * density
        if (right <= left || bottom <= top) return
        drawLegend(canvas, left, top - 10f * density)
        when (displayMode) {
            DisplayMode.MAGNITUDE -> {
                drawGrid(canvas, left, right, top, bottom, dbGridLines(), "dB") { db -> dbToY(db, top, bottom) }
                drawSpectrum(canvas, left, right, top, bottom)
            }
            DisplayMode.PHASE -> drawGrid(canvas, left, right, top, bottom, phaseGridLines(), "deg") { deg -> valueToY(deg, -180f, 180f, top, bottom) }
            DisplayMode.MAGNITUDE_PHASE -> {
                drawGrid(canvas, left, right, top, bottom, dbGridLines(), "dB") { db -> dbToY(db, top, bottom) }
                drawSpectrum(canvas, left, right, top, bottom)
            }
            DisplayMode.GROUP_DELAY -> drawGrid(canvas, left, right, top, bottom, groupDelayGridLines(), "ms") { ms -> valueToY(ms, -2f, 10f, top, bottom) }
        }
        drawResponse(canvas, left, right, top, bottom)
    }

    private fun dbGridLines() = floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f, -24f)
    private fun phaseGridLines() = floatArrayOf(180f, 90f, 0f, -90f, -180f)
    private fun groupDelayGridLines() = floatArrayOf(10f, 6f, 2f, 0f, -2f)

    private fun drawLegend(canvas: Canvas, left: Float, baseline: Float) {
        when (displayMode) {
            DisplayMode.GROUP_DELAY -> canvas.drawText("GROUP DELAY · FINAL SUM", left, baseline, legendPaint)
            else -> {
                canvas.drawText("LOW", left, baseline, lowPaint)
                canvas.drawText("MID", left + 42f * density, baseline, midPaint)
                val label = when (displayMode) {
                    DisplayMode.PHASE -> "PHASE · FINAL SUM · PEQ · TILT · GAINS"
                    DisplayMode.MAGNITUDE_PHASE -> "SOLID = MAGNITUDE, DASHED = PHASE"
                    else -> "FINAL SUM · PEQ · TILT · GAINS"
                }
                canvas.drawText(label, left + 88f * density, baseline, legendPaint)
            }
        }
    }

    private fun drawGrid(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        lines: FloatArray,
        @Suppress("UNUSED_PARAMETER") unit: String,
        valueToY: (Float) -> Float,
    ) {
        lines.forEach { value ->
            val y = valueToY(value)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("${value.toInt()}", 5f * density, y + 4f * density, labelPaint)
        }
        floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f).forEach { frequency ->
            val x = frequencyToX(frequency, left, right)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val label = if (frequency >= 1000f) "${(frequency / 1000f).toInt()}k" else frequency.toInt().toString()
            canvas.drawText(label, x - labelPaint.measureText(label) / 2f, bottom + 18f * density, labelPaint)
        }
    }

    private fun drawSpectrum(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (!spectrumActive) return
        val line = Path()
        val fill = Path().apply { moveTo(left, bottom) }
        val points = 180
        for (i in 0 until points) {
            val fraction = i.toDouble() / (points - 1)
            val frequency = 20.0 * 1000.0.pow(fraction)
            val spectrumDb = SpectrumEngine.magnitudeDbAt(frequency)
            val displayDb = ((spectrumDb - SpectrumEngine.FLOOR_DB) /
                (SpectrumEngine.CEILING_DB - SpectrumEngine.FLOOR_DB) * 36f - 24f)
            val x = left + fraction.toFloat() * (right - left)
            val y = dbToY(displayDb, top, bottom)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(right, bottom)
        fill.close()
        canvas.drawPath(fill, spectrumFillPaint)
        canvas.drawPath(line, spectrumPaint)
    }

    private fun drawResponse(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        calculator.configureAxis(SAMPLE_RATE, 20.0, 20_000.0)
        calculator.compute(values, peqState, curves)
        val lastIndex = POINT_COUNT - 1

        fun pathFor(sample: (Int) -> Float): Path = Path().apply {
            for (i in 0 until POINT_COUNT) {
                val x = left + (i.toFloat() / lastIndex) * (right - left)
                val y = sample(i)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        when (displayMode) {
            DisplayMode.MAGNITUDE -> {
                canvas.drawPath(pathFor { i -> dbToY(averageDb(curves.lowBranchDbFor(BmwOutputChannel.LEFT)[i], curves.lowBranchDbFor(BmwOutputChannel.RIGHT)[i]), top, bottom) }, lowPaint)
                canvas.drawPath(pathFor { i -> dbToY(averageDb(curves.midBranchDbFor(BmwOutputChannel.LEFT)[i], curves.midBranchDbFor(BmwOutputChannel.RIGHT)[i]), top, bottom) }, midPaint)
                canvas.drawPath(pathFor { i -> dbToY(averageDb(curves.sumDbFor(BmwOutputChannel.LEFT)[i], curves.sumDbFor(BmwOutputChannel.RIGHT)[i]), top, bottom) }, sumPaint)
            }
            DisplayMode.PHASE -> {
                canvas.drawPath(pathFor { i -> valueToY(averageDeg(curves.lowBranchPhaseFor(BmwOutputChannel.LEFT)[i], curves.lowBranchPhaseFor(BmwOutputChannel.RIGHT)[i]), -180f, 180f, top, bottom) }, lowPaint)
                canvas.drawPath(pathFor { i -> valueToY(averageDeg(curves.midBranchPhaseFor(BmwOutputChannel.LEFT)[i], curves.midBranchPhaseFor(BmwOutputChannel.RIGHT)[i]), -180f, 180f, top, bottom) }, midPaint)
                canvas.drawPath(pathFor { i -> valueToY(averageDeg(curves.sumPhaseFor(BmwOutputChannel.LEFT)[i], curves.sumPhaseFor(BmwOutputChannel.RIGHT)[i]), -180f, 180f, top, bottom) }, sumPaint)
            }
            DisplayMode.MAGNITUDE_PHASE -> {
                canvas.drawPath(pathFor { i -> dbToY(averageDb(curves.lowBranchDbFor(BmwOutputChannel.LEFT)[i], curves.lowBranchDbFor(BmwOutputChannel.RIGHT)[i]), top, bottom) }, lowPaint)
                canvas.drawPath(pathFor { i -> dbToY(averageDb(curves.midBranchDbFor(BmwOutputChannel.LEFT)[i], curves.midBranchDbFor(BmwOutputChannel.RIGHT)[i]), top, bottom) }, midPaint)
                canvas.drawPath(pathFor { i -> dbToY(averageDb(curves.sumDbFor(BmwOutputChannel.LEFT)[i], curves.sumDbFor(BmwOutputChannel.RIGHT)[i]), top, bottom) }, sumPaint)
                canvas.drawPath(pathFor { i -> valueToY(averageDeg(curves.sumPhaseFor(BmwOutputChannel.LEFT)[i], curves.sumPhaseFor(BmwOutputChannel.RIGHT)[i]), -180f, 180f, top, bottom) }, phasePaint)
            }
            DisplayMode.GROUP_DELAY -> {
                val leftDelay = curves.groupDelayMsFor(BmwOutputChannel.LEFT)
                val rightDelay = curves.groupDelayMsFor(BmwOutputChannel.RIGHT)
                canvas.drawPath(pathFor { i -> valueToY((((leftDelay[i] + rightDelay[i]) * 0.5).toFloat()).coerceIn(-2f, 10f), -2f, 10f, top, bottom) }, sumPaint)
            }
        }
    }

    private fun averageDb(left: Double, right: Double): Float =
        ((left + right) * 0.5).toFloat().coerceIn(-24f, 12f)

    private fun averageDeg(left: Double, right: Double): Float =
        Math.toDegrees((left + right) * 0.5).toFloat().coerceIn(-180f, 180f)

    private fun dbToY(db: Float, top: Float, bottom: Float): Float = valueToY(db, -24f, 12f, top, bottom)

    private fun valueToY(value: Float, min: Float, max: Float, top: Float, bottom: Float): Float {
        val clamped = value.coerceIn(min, max)
        return top + (max - clamped) / (max - min) * (bottom - top)
    }

    private fun frequencyToX(frequency: Float, left: Float, right: Float): Float {
        val fraction = log10(frequency / 20f) / log10(1000f)
        return left + fraction * (right - left)
    }

    private fun resolveColor(attribute: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, typedValue, true)
        return if (typedValue.resourceId != 0) ContextCompat.getColor(context, typedValue.resourceId) else typedValue.data
    }

    companion object {
        private const val POINT_COUNT = 192
        private const val SAMPLE_RATE = 48_000.0
    }
}
