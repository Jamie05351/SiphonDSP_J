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
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unified electrical overview for the complete native BMW signal path.
 *
 * The display combines Full Range PEQ/preamp, headroom, low and mid crossover
 * branches, branch PEQ/gain/delay/polarity, complex summation, tilt and post
 * gain. A live post-processing spectrum is drawn behind the modelled curves.
 * Compressor action remains on its dedicated nonlinear visualiser.
 */
class NativeBmwDspResponseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private var values = NativeBmwDspBottomSheet.DEFAULTS.copyOf()
    private var peqState = BmwPeqState.load(context)
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
        labelPaint.color = resolveColor(android.R.attr.textColorSecondary)
        legendPaint.color = resolveColor(android.R.attr.textColorPrimary)
        contentDescription = "Unified live BMW DSP response, PEQ, crossover, tilt and spectrum"
    }

    fun setValues(newValues: FloatArray) {
        if (newValues.size == NativeBmwDspBottomSheet.DEFAULTS.size) {
            values = newValues.copyOf()
            peqState = BmwPeqState.load(context)
            invalidate()
        }
    }

    fun refreshPeqState() {
        peqState = BmwPeqState.load(context)
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
        drawGrid(canvas, left, right, top, bottom)
        drawSpectrum(canvas, left, right, top, bottom)
        drawResponse(canvas, left, right, top, bottom)
    }

    private fun drawLegend(canvas: Canvas, left: Float, baseline: Float) {
        canvas.drawText("LOW", left, baseline, lowPaint)
        canvas.drawText("MID", left + 42f * density, baseline, midPaint)
        canvas.drawText("FINAL SUM · PEQ · TILT · GAINS", left + 88f * density, baseline, legendPaint)
    }

    private fun drawGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f, -24f).forEach { db ->
            val y = dbToY(db, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("${db.toInt()}", 5f * density, y + 4f * density, labelPaint)
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
        val lowPath = Path()
        val midPath = Path()
        val sumPath = Path()
        val points = 320
        for (i in 0 until points) {
            val fraction = i.toDouble() / (points - 1)
            val frequency = 20.0 * 1000.0.pow(fraction)
            val l = channelResponse(frequency, true)
            val r = channelResponse(frequency, false)
            val lowDb = averageMagnitudeDb(l.low, r.low)
            val midDb = averageMagnitudeDb(l.mid, r.mid)
            val sumDb = averageMagnitudeDb(l.sum, r.sum)
            val x = left + fraction.toFloat() * (right - left)
            if (i == 0) {
                lowPath.moveTo(x, dbToY(lowDb, top, bottom))
                midPath.moveTo(x, dbToY(midDb, top, bottom))
                sumPath.moveTo(x, dbToY(sumDb, top, bottom))
            } else {
                lowPath.lineTo(x, dbToY(lowDb, top, bottom))
                midPath.lineTo(x, dbToY(midDb, top, bottom))
                sumPath.lineTo(x, dbToY(sumDb, top, bottom))
            }
        }
        canvas.drawPath(lowPath, lowPaint)
        canvas.drawPath(midPath, midPaint)
        canvas.drawPath(sumPath, sumPaint)
    }

    private fun channelResponse(frequency: Double, leftChannel: Boolean): ChannelResponse {
        if (values[0] < .5f) return ChannelResponse(Complex.ZERO, Complex.ZERO, Complex.ONE)
        val target = if (leftChannel) ParametricEqChannel.LEFT else ParametricEqChannel.RIGHT
        val fullDb = if (peqState.enabled) peqState.preampDb + peqGainDb(peqState.fullRangeBands, frequency, target) else 0f
        val inputGain = dbToLinear(values[5].toDouble() + fullDb)
        var low = Complex.ONE * inputGain
        var mid = Complex.ONE * inputGain

        if (values[1] < .5f) {
            if (values[12] >= .5f) low *= highPass(frequency, values[13].toDouble(), BUTTERWORTH_Q)
            if (values[16] >= .5f) {
                low *= lowPass(frequency, values[15].toDouble(), BUTTERWORTH_Q)
                low *= lowPass(frequency, values[15].toDouble(), BUTTERWORTH_Q)
            } else {
                low *= lowPass(frequency, values[15].toDouble(), 1.0)
                low *= onePoleLow(frequency, values[15].toDouble())
            }
            if (peqState.enabled) low *= dbToLinear(peqGainDb(peqState.lowBandBands, frequency, target).toDouble())
            low *= delay(frequency, (if (leftChannel) values[23] else values[24]).toDouble())
            low *= dbToLinear((if (leftChannel) values[6] else values[7]).toDouble())
        }

        if (values[2] < .5f) {
            mid *= highPass(frequency, values[18].toDouble(), BUTTERWORTH_Q)
            mid *= highPass(frequency, values[18].toDouble(), BUTTERWORTH_Q)
            if (peqState.enabled) mid *= dbToLinear(peqGainDb(peqState.midBandBands, frequency, target).toDouble())
            mid *= delay(frequency, (if (leftChannel) values[21] else values[22]).toDouble())
            mid *= dbToLinear((if (leftChannel) values[8] else values[9]).toDouble())
        }

        if (values[19] >= .5f) low *= -1.0
        if (values[20] >= .5f) mid *= -1.0
        if (values[14] >= .5f || values[4].toInt() == 1) low = Complex.ZERO
        if (values[17] >= .5f || values[4].toInt() == 2) mid = Complex.ZERO

        var sum = low + mid
        if (values[25] >= .5f) {
            val shelfGain = values[26].toDouble() * .75
            repeat(2) {
                sum *= lowShelf(frequency, values[27].toDouble(), shelfGain)
                sum *= highShelf(frequency, values[27].toDouble(), -shelfGain)
            }
        }
        sum *= dbToLinear((if (leftChannel) values[10] else values[11]).toDouble())
        return ChannelResponse(low, mid, sum)
    }

    private fun peqGainDb(bands: ParametricEqBandList, frequency: Double, target: ParametricEqChannel): Float {
        var total = 0.0
        bands.forEach { band ->
            if (band.channel == ParametricEqChannel.LEFT_RIGHT || band.channel == target) {
                val coefficients = BiquadUtils.computeCoefficients(
                    band.frequency, band.gain, band.q, band.filterType, SAMPLE_RATE
                )
                total += BiquadUtils.magnitudeResponse(coefficients, frequency, SAMPLE_RATE)
            }
        }
        return total.toFloat()
    }

    private fun lowPass(f: Double, cutoff: Double, q: Double) = biquad(f, cutoff, q, true)
    private fun highPass(f: Double, cutoff: Double, q: Double) = biquad(f, cutoff, q, false)

    private fun biquad(f: Double, cutoff: Double, q: Double, lowPass: Boolean): Complex {
        val w = 2.0 * PI * cutoff.coerceIn(20.0, SAMPLE_RATE * .49) / SAMPLE_RATE
        val c = cos(w); val s = sin(w); val alpha = s / (2.0 * q); val d = 1.0 + alpha
        val b0: Double; val b1: Double; val b2: Double
        if (lowPass) { b0 = ((1.0 - c) * .5) / d; b1 = (1.0 - c) / d; b2 = b0 }
        else { b0 = ((1.0 + c) * .5) / d; b1 = (-(1.0 + c)) / d; b2 = b0 }
        return biquadResponse(f, b0, b1, b2, (-2.0 * c) / d, (1.0 - alpha) / d)
    }

    private fun onePoleLow(f: Double, cutoff: Double): Complex {
        val k = kotlin.math.tan(PI * cutoff.coerceIn(20.0, SAMPLE_RATE * .49) / SAMPLE_RATE)
        val a0 = k / (k + 1.0); val z1 = unitDelay(f)
        return (Complex(a0, 0.0) + z1 * a0) / (Complex.ONE + z1 * ((k - 1.0) / (k + 1.0)))
    }

    private fun lowShelf(f: Double, cutoff: Double, gainDb: Double): Complex = shelf(f, cutoff, gainDb, false)
    private fun highShelf(f: Double, cutoff: Double, gainDb: Double): Complex = shelf(f, cutoff, gainDb, true)

    private fun shelf(f: Double, cutoff: Double, gainDb: Double, high: Boolean): Complex {
        val a = 10.0.pow(gainDb / 40.0); val w = 2.0 * PI * cutoff / SAMPLE_RATE
        val c = cos(w); val s = sin(w); val alpha = s / (2.0 * BUTTERWORTH_Q); val rootA = sqrt(a)
        return if (!high) {
            val inv = 1.0 / ((a + 1.0) + (a - 1.0) * c + 2.0 * rootA * alpha)
            biquadResponse(f,
                a * ((a + 1.0) - (a - 1.0) * c + 2.0 * rootA * alpha) * inv,
                2.0 * a * ((a - 1.0) - (a + 1.0) * c) * inv,
                a * ((a + 1.0) - (a - 1.0) * c - 2.0 * rootA * alpha) * inv,
                -2.0 * ((a - 1.0) + (a + 1.0) * c) * inv,
                ((a + 1.0) + (a - 1.0) * c - 2.0 * rootA * alpha) * inv)
        } else {
            val inv = 1.0 / ((a + 1.0) - (a - 1.0) * c + 2.0 * rootA * alpha)
            biquadResponse(f,
                a * ((a + 1.0) + (a - 1.0) * c + 2.0 * rootA * alpha) * inv,
                -2.0 * a * ((a - 1.0) + (a + 1.0) * c) * inv,
                a * ((a + 1.0) + (a - 1.0) * c - 2.0 * rootA * alpha) * inv,
                2.0 * ((a - 1.0) - (a + 1.0) * c) * inv,
                ((a + 1.0) - (a - 1.0) * c - 2.0 * rootA * alpha) * inv)
        }
    }

    private fun biquadResponse(f: Double, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): Complex {
        val z1 = unitDelay(f); val z2 = z1 * z1
        return (Complex(b0, 0.0) + z1 * b1 + z2 * b2) / (Complex.ONE + z1 * a1 + z2 * a2)
    }

    private fun unitDelay(f: Double): Complex {
        val angle = -2.0 * PI * f / SAMPLE_RATE
        return Complex(cos(angle), sin(angle))
    }

    private fun delay(f: Double, ms: Double): Complex {
        val angle = -2.0 * PI * f * ms / 1000.0
        return Complex(cos(angle), sin(angle))
    }

    private fun averageMagnitudeDb(left: Complex, right: Complex) =
        amplitudeToDb((left.magnitude + right.magnitude) * .5)
    private fun dbToLinear(db: Double) = 10.0.pow(db / 20.0)
    private fun amplitudeToDb(amplitude: Double) =
        (20.0 * log10(amplitude.coerceAtLeast(1e-9))).toFloat().coerceIn(-24f, 12f)
    private fun dbToY(db: Float, top: Float, bottom: Float) =
        top + (12f - db.coerceIn(-24f, 12f)) / 36f * (bottom - top)
    private fun frequencyToX(frequency: Float, left: Float, right: Float) =
        left + log10(frequency / 20f) / log10(1000f) * (right - left)

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private data class ChannelResponse(val low: Complex, val mid: Complex, val sum: Complex)
    private data class Complex(val real: Double, val imaginary: Double) {
        val magnitude get() = sqrt(real * real + imaginary * imaginary)
        operator fun plus(other: Complex) = Complex(real + other.real, imaginary + other.imaginary)
        operator fun times(other: Complex) = Complex(real * other.real - imaginary * other.imaginary, real * other.imaginary + imaginary * other.real)
        operator fun times(value: Double) = Complex(real * value, imaginary * value)
        operator fun div(other: Complex): Complex {
            val d = other.real * other.real + other.imaginary * other.imaginary
            return if (d <= 1e-24) ZERO else Complex((real * other.real + imaginary * other.imaginary) / d, (imaginary * other.real - real * other.imaginary) / d)
        }
        companion object { val ZERO = Complex(0.0, 0.0); val ONE = Complex(1.0, 0.0) }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000.0
        private const val BUTTERWORTH_Q = .7071067812
    }
}
