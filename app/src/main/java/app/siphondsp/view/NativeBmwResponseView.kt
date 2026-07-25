package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class NativeBmwResponseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33444444
        strokeWidth = resources.displayMetrics.density
    }
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff4aa3ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffff5a5f.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val sumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xfff2f2f2.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffb8b8b8.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private var values = FloatArray(35)

    fun setConfiguration(configuration: FloatArray) {
        if (configuration.size == values.size) {
            values = configuration.copyOf()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 8f
        val right = width - paddingRight - 8f
        val top = paddingTop + 8f
        val bottom = height - paddingBottom - 18f
        if (right <= left || bottom <= top) return

        val dbMin = -36f
        val dbMax = 12f
        val frequencies = floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
        frequencies.forEach { frequency ->
            val x = frequencyToX(frequency, left, right)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            if (frequency == 20f || frequency == 100f || frequency == 1000f || frequency == 10000f) {
                val label = when (frequency) {
                    1000f -> "1k"
                    10000f -> "10k"
                    else -> frequency.toInt().toString()
                }
                canvas.drawText(label, x + 3f, height - 3f, textPaint)
            }
        }
        for (db in -36..12 step 12) {
            val y = dbToY(db.toFloat(), dbMin, dbMax, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("${db}dB", left + 3f, y - 3f, textPaint)
        }

        val lowPath = Path()
        val midPath = Path()
        val sumPath = Path()
        val samples = 180
        for (index in 0 until samples) {
            val xRatio = index.toFloat() / (samples - 1)
            val frequency = 20f * (1000f.pow(xRatio * 3f))
            val lowDb = lowMagnitudeDb(frequency)
            val midDb = midMagnitudeDb(frequency)
            val sumLinear = 10f.pow(lowDb / 20f) + 10f.pow(midDb / 20f)
            val sumDb = 20f * log10(sumLinear.coerceAtLeast(1e-6f)) + tiltDb(frequency)
            val x = left + (right - left) * xRatio
            val lowY = dbToY(lowDb, dbMin, dbMax, top, bottom)
            val midY = dbToY(midDb, dbMin, dbMax, top, bottom)
            val sumY = dbToY(sumDb, dbMin, dbMax, top, bottom)
            if (index == 0) {
                lowPath.moveTo(x, lowY)
                midPath.moveTo(x, midY)
                sumPath.moveTo(x, sumY)
            } else {
                lowPath.lineTo(x, lowY)
                midPath.lineTo(x, midY)
                sumPath.lineTo(x, sumY)
            }
        }
        canvas.drawPath(lowPath, lowPaint)
        canvas.drawPath(midPath, midPaint)
        canvas.drawPath(sumPath, sumPaint)
    }

    private fun lowMagnitudeDb(frequency: Float): Float {
        if (values[14] >= .5f || values[4].toInt() == 1) return -60f
        if (values[1] >= .5f) return values[6]
        val cutoff = values[15].coerceAtLeast(20f)
        val order = if (values[16] >= .5f) 4f else 3f
        val ratio = frequency / cutoff
        val attenuation = -10f * log10(1f + ratio.pow(2f * order))
        return attenuation + values[6]
    }

    private fun midMagnitudeDb(frequency: Float): Float {
        if (values[17] >= .5f || values[4].toInt() == 2) return -60f
        if (values[2] >= .5f) return values[8]
        val cutoff = values[18].coerceAtLeast(20f)
        val ratio = cutoff / frequency.coerceAtLeast(1f)
        val attenuation = -10f * log10(1f + ratio.pow(8f))
        return attenuation + values[8]
    }

    private fun tiltDb(frequency: Float): Float {
        if (values[25] < .5f) return 0f
        val amount = values[26]
        val pivot = values[27].coerceAtLeast(20f)
        val octaves = log10(frequency / pivot) / log10(2f)
        return (-amount * octaves / 3f).coerceIn(-abs(amount), abs(amount))
    }

    private fun frequencyToX(frequency: Float, left: Float, right: Float): Float {
        val ratio = log10(frequency / 20f) / log10(1000f)
        return left + (right - left) * ratio
    }

    private fun dbToY(db: Float, min: Float, max: Float, top: Float, bottom: Float): Float {
        val clamped = db.coerceIn(min, max)
        return bottom - (clamped - min) / (max - min) * (bottom - top)
    }
}
