package app.siphondsp.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Read-only electrical response preview of the native BMW DSP.
 *
 * The preview mirrors the native processor's digital biquad/one-pole equations,
 * delay, polarity and complex summation. It redraws only when UI values change
 * and never touches the audio path.
 */
class NativeBmwDspResponseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        alpha = 72
    }
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val sumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private var values = NativeBmwDspBottomSheet.DEFAULTS.copyOf()

    init {
        gridPaint.color = resolveColor(android.R.attr.textColorSecondary)
        lowPaint.color = resolveColor(android.R.attr.colorAccent)
        midPaint.color = resolveColor(android.R.attr.textColorLink)
        sumPaint.color = resolveColor(android.R.attr.textColorPrimary)
        labelPaint.color = resolveColor(android.R.attr.textColorPrimary)
    }

    fun setValues(newValues: FloatArray) {
        if (newValues.size == NativeBmwDspBottomSheet.DEFAULTS.size) {
            values = newValues.copyOf()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (210f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = paddingLeft + 42f * density
        val right = width - paddingRight - 12f * density
        val top = paddingTop + 12f * density
        val bottom = height - paddingBottom - 28f * density
        if (right <= left || bottom <= top) return
        drawGrid(canvas, left, right, top, bottom)
        drawResponse(canvas, left, right, top, bottom)
    }

    private fun drawGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val density = resources.displayMetrics.density
        floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f, -24f).forEach { db ->
            val y = dbToY(db, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("${db.toInt()} dB", 2f * density, y + 4f, labelPaint)
        }
        floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f).forEach { frequency ->
            val x = frequencyToX(frequency, left, right)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val label = if (frequency >= 1000f) "${(frequency / 1000f).toInt()}k" else frequency.toInt().toString()
            canvas.drawText(label, x - labelPaint.measureText(label) / 2f, bottom + 18f * density, labelPaint)
        }
    }

    private fun drawResponse(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val lowPath = Path()
        val midPath = Path()
        val sumPath = Path()
        val points = 300
        for (i in 0 until points) {
            val fraction = i.toDouble() / (points - 1)
            val frequency = 20.0 * 1000.0.pow(fraction)

            val leftResponse = channelResponse(frequency, leftChannel = true)
            val rightResponse = channelResponse(frequency, leftChannel = false)

            val lowDb = averageMagnitudeDb(leftResponse.low, rightResponse.low)
            val midDb = averageMagnitudeDb(leftResponse.mid, rightResponse.mid)
            val sumDb = averageMagnitudeDb(leftResponse.sum, rightResponse.sum)
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
        if (values[0] < .5f) {
            return ChannelResponse(Complex.ZERO, Complex.ZERO, Complex.ONE)
        }

        var low = Complex.ONE
        var mid = Complex.ONE

        if (values[1] < .5f) {
            if (values[12] >= .5f) {
                low *= highPass(frequency, values[13].toDouble(), BW4_Q1)
                low *= highPass(frequency, values[13].toDouble(), BW4_Q2)
            }

            if (values[16] >= .5f) {
                low *= lowPass(frequency, values[15].toDouble(), BUTTERWORTH_Q)
                low *= lowPass(frequency, values[15].toDouble(), BUTTERWORTH_Q)
            } else {
                low *= lowPass(frequency, values[15].toDouble(), 1.0)
                low *= onePoleLow(frequency, values[15].toDouble())
            }

            val delayMs = if (leftChannel) values[23] else values[24]
            low *= delay(frequency, delayMs.toDouble())
            low *= dbToLinear(if (leftChannel) values[6].toDouble() else values[7].toDouble())
        }

        if (values[2] < .5f) {
            mid *= highPass(frequency, values[18].toDouble(), BUTTERWORTH_Q)
            mid *= highPass(frequency, values[18].toDouble(), BUTTERWORTH_Q)
            val delayMs = if (leftChannel) values[21] else values[22]
            mid *= delay(frequency, delayMs.toDouble())
            mid *= dbToLinear(if (leftChannel) values[8].toDouble() else values[9].toDouble())
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

        return ChannelResponse(low, mid, sum)
    }

    private fun lowPass(frequency: Double, cutoff: Double, q: Double): Complex {
        val w = 2.0 * PI * cutoff.coerceIn(20.0, SAMPLE_RATE * .49) / SAMPLE_RATE
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        val d = 1.0 + alpha
        return biquadResponse(
            frequency,
            ((1.0 - c) * .5) / d,
            (1.0 - c) / d,
            ((1.0 - c) * .5) / d,
            (-2.0 * c) / d,
            (1.0 - alpha) / d,
        )
    }

    private fun highPass(frequency: Double, cutoff: Double, q: Double): Complex {
        val w = 2.0 * PI * cutoff.coerceIn(20.0, SAMPLE_RATE * .49) / SAMPLE_RATE
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        val d = 1.0 + alpha
        return biquadResponse(
            frequency,
            ((1.0 + c) * .5) / d,
            (-(1.0 + c)) / d,
            ((1.0 + c) * .5) / d,
            (-2.0 * c) / d,
            (1.0 - alpha) / d,
        )
    }

    private fun onePoleLow(frequency: Double, cutoff: Double): Complex {
        val k = kotlin.math.tan(PI * cutoff.coerceIn(20.0, SAMPLE_RATE * .49) / SAMPLE_RATE)
        val a0 = k / (k + 1.0)
        val a1 = a0
        val b1 = (k - 1.0) / (k + 1.0)
        val z1 = unitDelay(frequency)
        return (Complex(a0, 0.0) + z1 * a1) / (Complex.ONE + z1 * b1)
    }

    private fun lowShelf(frequency: Double, cutoff: Double, gainDb: Double): Complex {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * cutoff / SAMPLE_RATE
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * BUTTERWORTH_Q)
        val rootA = sqrt(a)
        val inverse = 1.0 / ((a + 1.0) + (a - 1.0) * c + 2.0 * rootA * alpha)
        return biquadResponse(
            frequency,
            a * ((a + 1.0) - (a - 1.0) * c + 2.0 * rootA * alpha) * inverse,
            2.0 * a * ((a - 1.0) - (a + 1.0) * c) * inverse,
            a * ((a + 1.0) - (a - 1.0) * c - 2.0 * rootA * alpha) * inverse,
            -2.0 * ((a - 1.0) + (a + 1.0) * c) * inverse,
            ((a + 1.0) + (a - 1.0) * c - 2.0 * rootA * alpha) * inverse,
        )
    }

    private fun highShelf(frequency: Double, cutoff: Double, gainDb: Double): Complex {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * cutoff / SAMPLE_RATE
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * BUTTERWORTH_Q)
        val rootA = sqrt(a)
        val inverse = 1.0 / ((a + 1.0) - (a - 1.0) * c + 2.0 * rootA * alpha)
        return biquadResponse(
            frequency,
            a * ((a + 1.0) + (a - 1.0) * c + 2.0 * rootA * alpha) * inverse,
            -2.0 * a * ((a - 1.0) + (a + 1.0) * c) * inverse,
            a * ((a + 1.0) + (a - 1.0) * c - 2.0 * rootA * alpha) * inverse,
            2.0 * ((a - 1.0) - (a + 1.0) * c) * inverse,
            ((a + 1.0) - (a - 1.0) * c - 2.0 * rootA * alpha) * inverse,
        )
    }

    private fun biquadResponse(
        frequency: Double,
        b0: Double,
        b1: Double,
        b2: Double,
        a1: Double,
        a2: Double,
    ): Complex {
        val z1 = unitDelay(frequency)
        val z2 = z1 * z1
        val numerator = Complex(b0, 0.0) + z1 * b1 + z2 * b2
        val denominator = Complex.ONE + z1 * a1 + z2 * a2
        return numerator / denominator
    }

    private fun unitDelay(frequency: Double): Complex {
        val angle = -2.0 * PI * frequency / SAMPLE_RATE
        return Complex(cos(angle), sin(angle))
    }

    private fun delay(frequency: Double, milliseconds: Double): Complex {
        val angle = -2.0 * PI * frequency * milliseconds / 1000.0
        return Complex(cos(angle), sin(angle))
    }

    private fun averageMagnitudeDb(left: Complex, right: Complex): Float {
        val averageMagnitude = (left.magnitude + right.magnitude) * .5
        return amplitudeToDb(averageMagnitude)
    }

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    private fun amplitudeToDb(amplitude: Double): Float =
        (20.0 * log10(amplitude.coerceAtLeast(1e-9))).toFloat().coerceIn(-24f, 12f)

    private fun dbToY(db: Float, top: Float, bottom: Float): Float {
        val clamped = db.coerceIn(-24f, 12f)
        return top + (12f - clamped) / 36f * (bottom - top)
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

    private data class ChannelResponse(val low: Complex, val mid: Complex, val sum: Complex)

    private data class Complex(val real: Double, val imaginary: Double) {
        val magnitude: Double get() = sqrt(real * real + imaginary * imaginary)

        operator fun plus(other: Complex) = Complex(real + other.real, imaginary + other.imaginary)
        operator fun times(other: Complex) = Complex(
            real * other.real - imaginary * other.imaginary,
            real * other.imaginary + imaginary * other.real,
        )
        operator fun times(value: Double) = Complex(real * value, imaginary * value)
        operator fun div(other: Complex): Complex {
            val denominator = other.real * other.real + other.imaginary * other.imaginary
            return if (denominator <= 1e-24) ZERO else Complex(
                (real * other.real + imaginary * other.imaginary) / denominator,
                (imaginary * other.real - real * other.imaginary) / denominator,
            )
        }

        companion object {
            val ZERO = Complex(0.0, 0.0)
            val ONE = Complex(1.0, 0.0)
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000.0
        private const val BUTTERWORTH_Q = .7071067812
        private const val BW4_Q1 = .5411961
        private const val BW4_Q2 = 1.3065630
    }
}
