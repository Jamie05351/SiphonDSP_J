package app.siphondsp.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Lightweight, read-only visualisation of the native BMW DSP crossover and tilt.
 * It performs no audio processing and redraws only when UI values change.
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
        gridPaint.color = resolveColor(MaterialR.attr.colorOutline)
        lowPaint.color = resolveColor(MaterialR.attr.colorPrimary)
        midPaint.color = resolveColor(MaterialR.attr.colorSecondary)
        sumPaint.color = resolveColor(android.R.attr.colorAccent)
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
        val points = 240
        for (i in 0 until points) {
            val fraction = i.toFloat() / (points - 1)
            val frequency = 20f * 1000f.pow(fraction)
            val lowDb = lowResponseDb(frequency)
            val midDb = midResponseDb(frequency)
            val sumDb = combinedResponseDb(lowDb, midDb) + tiltDb(frequency)
            val x = left + fraction * (right - left)
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

    private fun lowResponseDb(frequency: Float): Float {
        if (values[1] >= .5f) return values[6]
        val fc = values[15].coerceAtLeast(1f)
        val order = if (values[16] >= .5f) 4 else 3
        val magnitude = 1.0 / sqrt(1.0 + (frequency / fc).toDouble().pow(2.0 * order))
        return amplitudeToDb(magnitude) + values[6]
    }

    private fun midResponseDb(frequency: Float): Float {
        if (values[2] >= .5f) return values[8]
        val fc = values[18].coerceAtLeast(1f)
        val magnitude = 1.0 / sqrt(1.0 + (fc / frequency).toDouble().pow(8.0))
        return amplitudeToDb(magnitude) + values[8]
    }

    private fun combinedResponseDb(lowDb: Float, midDb: Float): Float {
        val low = 10.0.pow(lowDb / 20.0)
        val mid = 10.0.pow(midDb / 20.0)
        return amplitudeToDb(low + mid)
    }

    private fun tiltDb(frequency: Float): Float {
        if (values[25] < .5f) return 0f
        val amount = values[26]
        val pivot = values[27].coerceAtLeast(20f)
        val octaves = (log10(frequency / pivot) / log10(2f)).coerceIn(-2f, 2f)
        return -amount * octaves * .5f
    }

    private fun amplitudeToDb(amplitude: Double): Float =
        (20.0 * log10(amplitude.coerceAtLeast(1e-6))).toFloat().coerceAtLeast(-24f)

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
}
