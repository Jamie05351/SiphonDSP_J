package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Compact gain-reduction meter used on the multiband-compressor screen -- one per band page and
 * one per bus / for the master limiter on the Driver-protection and Gains pages. A thin
 * horizontal track with a bar that grows from the right as reduction increases, plus a
 * slow-decaying peak-hold tick and a digital `x.x dB` readout with a one-word state.
 *
 * Everything is coloured by a health zone rather than always-red, so a glance says whether the
 * attached stage is idle (green / CLEAR), doing normal work (amber / WORKING) or being driven
 * too hard (red / CLAMPING). The zone thresholds differ by [stage]: a *limiter* pulling 3 dB is
 * alarming, a compressor *band* pulling 3 dB is routine.
 *
 * Fed [setGainReductionDb] from the fragment's meter tick (readMbcMeter / readBusLimiterMeter).
 * Display-only.
 */
class MbcBandGrMeter(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Which kind of dynamics stage this meter is attached to -- sets the health-zone limits. */
    enum class Stage(
        /** GR below this reads as green / CLEAR. */
        val clearMaxDb: Float,
        /** GR below this reads as amber / WORKING; at or above it is red / CLAMPING. */
        val workingMaxDb: Float,
        /** Bar full-scale, chosen so the stage's useful GR range fills a readable span. */
        val fullScaleDb: Float,
    ) {
        COMPRESSOR_BAND(clearMaxDb = 1.5f, workingMaxDb = 6f, fullScaleDb = 18f),
        LIMITER(clearMaxDb = 0.5f, workingMaxDb = 3f, fullScaleDb = 12f),
    }

    var stage: Stage = Stage.COMPRESSOR_BAND
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 23, 28) }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 74, 82); style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var grDb = 0f
    private var holdDb = 0f

    fun setGainReductionDb(db: Float) {
        grDb = db.coerceIn(0f, stage.fullScaleDb)
        holdDb = if (grDb >= holdDb) grDb else holdDb * HOLD_DECAY + grDb * (1f - HOLD_DECAY)
        invalidate()
    }

    private fun zoneColor(db: Float): Int = when {
        db < stage.clearMaxDb -> CLEAR_GREEN
        db < stage.workingMaxDb -> WORKING_AMBER
        else -> BmwDashboardSkin.M_RED
    }

    private fun zoneWord(db: Float): String = when {
        db < stage.clearMaxDb -> "CLEAR"
        db < stage.workingMaxDb -> "WORKING"
        else -> "CLAMPING"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((20f * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val zone = zoneColor(grDb)
        val readout = "${"%.1f".format(grDb)} dB · ${zoneWord(grDb)}"

        // readout is the headline, tinted to the current zone
        labelPaint.color = if (grDb < stage.clearMaxDb) DIM_GREEN else zone
        val labelW = labelPaint.measureText(readout).coerceAtLeast(labelPaint.measureText("00.0 dB · CLAMPING"))
        val midY = height / 2f
        canvas.drawText(readout, 0f, midY + 3.7f * density, labelPaint)

        val left = labelW + 8f * density
        val right = width.toFloat()
        if (right <= left) return
        val trackH = 6f * density
        val top = midY - trackH / 2f
        val bot = midY + trackH / 2f
        canvas.drawRoundRect(left, top, right, bot, trackH / 2f, trackH / 2f, trackPaint)

        val span = right - left
        fun xForDb(db: Float) = right - span * (db / stage.fullScaleDb)

        // faint threshold ticks: past the amber tick = working, past the red tick = clamping
        for (mark in floatArrayOf(stage.clearMaxDb, stage.workingMaxDb)) {
            val x = xForDb(mark)
            canvas.drawLine(x, top - 2f * density, x, bot + 2f * density, tickPaint)
        }

        val barW = span * (grDb / stage.fullScaleDb)
        if (barW > 0.5f) {
            barPaint.color = zone
            canvas.drawRoundRect(right - barW, top, right, bot, trackH / 2f, trackH / 2f, barPaint)
        }

        // peak-hold tick in a brighter tint of the held value's zone
        val holdX = xForDb(holdDb)
        holdPaint.color = tintTowardsWhite(zoneColor(holdDb), 0.35f)
        canvas.drawRect(holdX - 1f * density, top - 1.5f * density, holdX + 1f * density, bot + 1.5f * density, holdPaint)
    }

    private fun tintTowardsWhite(color: Int, t: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * t).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * t).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * t).toInt(),
    )

    companion object {
        private const val HOLD_DECAY = 0.92f
        private val CLEAR_GREEN = BmwDashboardSkin.M_GREEN
        private val DIM_GREEN = Color.rgb(0x3A, 0x8F, 0x5E)
        private val WORKING_AMBER = Color.rgb(0xF2, 0xB3, 0x3D)
    }
}
