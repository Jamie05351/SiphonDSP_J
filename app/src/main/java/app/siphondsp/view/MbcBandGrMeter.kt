package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Compact gain-reduction meter used on the multiband-compressor screen -- one per band page and
 * one per bus / for the master limiter on the Driver-protection and Gains pages. A thin
 * horizontal track with a bar that grows from the left as reduction increases, plus a
 * slow-decaying peak-hold tick and a digital `x.x dB` readout with a one-word state. Bar length
 * follows a square-root curve of the reduction (see [fractionForDb]) rather than a linear one:
 * real-world reduction sits in the first few dB, so a linear 12/18 dB span left most of the bar
 * unused and made small threshold / ratio adjustments hard to read.
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
        /** Bar full-scale (the right end of the track). */
        val fullScaleDb: Float,
    ) {
        COMPRESSOR_BAND(clearMaxDb = 1.5f, workingMaxDb = 6f, fullScaleDb = 18f),
        LIMITER(clearMaxDb = 0.5f, workingMaxDb = 3f, fullScaleDb = 12f),
    }

    var stage: Stage = Stage.COMPRESSOR_BAND
        set(value) { field = value; readoutTenths = -1; invalidate() }

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

    // Widest readout the label column is sized for, so the track doesn't shift as the text changes.
    private val labelWidth = labelPaint.measureText("00.0 dB · CLAMPING")

    private var grDb = 0f
    private var holdDb = 0f

    // The `x.x dB · WORD` readout, rebuilt only when its shown tenth or zone word changes --
    // onDraw runs every meter tick (~33 ms) and used to format a new string each time.
    // readoutTenths = -1 forces a rebuild on next use.
    private var readout = ""
    private var readoutTenths = -1
    private var readoutWord = ""

    fun setGainReductionDb(db: Float) {
        grDb = db.coerceIn(0f, stage.fullScaleDb)
        holdDb = if (grDb >= holdDb) grDb else holdDb * HOLD_DECAY + grDb * (1f - HOLD_DECAY)
        invalidate()
    }

    private fun updateReadout() {
        val tenths = readoutTenths(grDb)
        val word = zoneWord(grDb)
        if (tenths == readoutTenths && word == readoutWord) return
        readoutTenths = tenths
        readoutWord = word
        readout = "${"%.1f".format(tenths / 10.0)} dB · $word"
    }

    /** 0..1 position along the track for [db] of reduction: sqrt-curved so small values get room. */
    private fun fractionForDb(db: Float): Float =
        sqrt((db / stage.fullScaleDb).coerceIn(0f, 1f))

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
            resolveSize((26f * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val zone = zoneColor(grDb)
        updateReadout()

        // readout is the headline, tinted to the current zone
        labelPaint.color = if (grDb < stage.clearMaxDb) DIM_GREEN else zone
        val labelW = labelWidth
        val midY = height / 2f
        canvas.drawText(readout, 0f, midY + 3.7f * density, labelPaint)

        val left = labelW + 8f * density
        val right = width.toFloat()
        if (right <= left) return
        val trackH = 12f * density
        val top = midY - trackH / 2f
        val bot = midY + trackH / 2f
        canvas.drawRoundRect(left, top, right, bot, trackH / 2f, trackH / 2f, trackPaint)

        val span = right - left
        fun xForDb(db: Float) = left + span * fractionForDb(db)

        // faint threshold ticks: past the amber tick = working, past the red tick = clamping
        val clearX = xForDb(stage.clearMaxDb)
        canvas.drawLine(clearX, top - 2f * density, clearX, bot + 2f * density, tickPaint)
        val workingX = xForDb(stage.workingMaxDb)
        canvas.drawLine(workingX, top - 2f * density, workingX, bot + 2f * density, tickPaint)

        val barW = span * fractionForDb(grDb)
        if (barW > 0.5f) {
            barPaint.color = zone
            canvas.drawRoundRect(left, top, left + barW, bot, trackH / 2f, trackH / 2f, barPaint)
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

/**
 * The tenth `"%.1f".format(db)` shows, as an Int: HALF_UP on the float's exact value. Multiplied
 * in double, where `db * 10` is exact (a float's 24-bit significand times 10 fits in 53 bits) --
 * in float it can round across the half, e.g. 1.15f (really 1.1499999...) would become 11.5 and
 * show 1.2 where the formatter shows 1.1. Top-level (not in the companion) so JVM tests can call
 * it without loading the View's android.graphics colour constants.
 */
internal fun readoutTenths(db: Float): Int = floor(db.toDouble() * 10.0 + 0.5).toInt()
