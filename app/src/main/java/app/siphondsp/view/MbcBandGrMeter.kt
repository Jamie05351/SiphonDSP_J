package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Compact per-band gain-reduction meter for the multiband-compressor band pages: a thin
 * horizontal track with a bar that grows from the right as reduction increases, plus a
 * slow-decaying peak-hold tick. Fed [setGainReductionDb] from the fragment's meter tick
 * (readMbcMeter). Display-only.
 */
class MbcBandGrMeter(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 23, 28) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BmwDashboardSkin.M_RED }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 180, 160) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 192, 200); textSize = 10f * density
    }

    private var grDb = 0f
    private var holdDb = 0f

    fun setGainReductionDb(db: Float) {
        grDb = db.coerceIn(0f, FULL_SCALE_DB)
        holdDb = if (grDb >= holdDb) grDb else holdDb * HOLD_DECAY + grDb * (1f - HOLD_DECAY)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((18f * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelW = labelPaint.measureText("GR 00.0 dB") + 6f * density
        val left = labelW
        val right = width.toFloat()
        val midY = height / 2f
        val trackH = 6f * density
        canvas.drawRoundRect(left, midY - trackH / 2f, right, midY + trackH / 2f, trackH / 2f, trackH / 2f, trackPaint)

        val span = right - left
        val barW = span * (grDb / FULL_SCALE_DB)
        if (barW > 0f) {
            canvas.drawRoundRect(right - barW, midY - trackH / 2f, right, midY + trackH / 2f, trackH / 2f, trackH / 2f, barPaint)
        }
        val holdX = right - span * (holdDb / FULL_SCALE_DB)
        canvas.drawRect(holdX - 1f * density, midY - trackH / 2f - 1.5f * density, holdX + 1f * density, midY + trackH / 2f + 1.5f * density, holdPaint)

        canvas.drawText("GR ${"%.1f".format(grDb)} dB", 0f, midY + 3.5f * density, labelPaint)
    }

    companion object {
        private const val FULL_SCALE_DB = 24f
        private const val HOLD_DECAY = 0.92f
    }
}
