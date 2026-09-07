package app.siphondsp.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Read-only Low/Mid handoff visualiser -- a picture, not an input. Draws, from the shared
 * [BmwResponseCalculator] (the same model the full-screen PEQ visualiser uses, so the two never
 * disagree):
 *
 *  - the low branch, the mid branch and their complex sum (L/R average);
 *  - the Subsonic high-pass roll-off at the low end (the axis runs down to 10 Hz for it);
 *  - a non-draggable dashed marker + Hz label at each crossover corner
 *    ([NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] / `INDEX_MID_CROSSOVER_FREQ`);
 *  - a shaded band + marker where Mono Bass engages ([MonoBassCue]);
 *  - the worst-case flat-sum deviation over the handoff octave, top-right.
 *
 * Crossover frequencies are set by the slider rows below the graph; this view repaints itself on
 * the [Constants.ACTION_NATIVE_BMW_DSP_UPDATED] broadcast those rows send.
 */
class CrossoverHandoffSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var values = NativeBmwDspValues.DEFAULTS.copyOf()
    private var peqState = BmwPeqState.empty()
    private val calculator = BmwResponseCalculator(POINT_COUNT)
    private val curves = BmwResponseCurves(POINT_COUNT)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.rgb(47, 53, 61)
    }
    private val lowPaint = strokePaint(BmwDashboardSkin.LIGHT_BLUE, 2.1f, 190)
    private val midPaint = strokePaint(BmwDashboardSkin.MID_BAND_YELLOW, 2.1f, 190)
    private val sumPaint = strokePaint(Color.WHITE, 3.1f, 255)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val monoShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BmwDashboardSkin.LIGHT_BLUE
        alpha = 26
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        color = Color.WHITE
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val dashed = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)

    // The slider rows on this page write the fragment's config array and broadcast; the graph
    // keeps its own copy, so without this it only caught up on the next fragment rebuild. The
    // payload carries the whole config array, so take it straight from the intent -- no disk
    // read on the drag path -- and keep the existing PEQ snapshot (a crossover slider can't
    // change it; the fragment re-binds with a fresh one on resume).
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)
                ?.takeIf { it.size == BmwSignalChain.VALUE_COUNT }
                ?.let { bind(it, peqState) }
        }
    }

    /** Copies in a fresh config + PEQ snapshot and redraws. */
    fun bind(newValues: FloatArray, newPeqState: BmwPeqState) {
        if (newValues.size == BmwSignalChain.VALUE_COUNT) values = newValues.copyOf()
        peqState = newPeqState
        calculator.invalidateAll()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerLocalReceiver(configReceiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
    }

    override fun onDetachedFromWindow() {
        context.unregisterLocalReceiver(configReceiver)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (182f * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 8f * density
        val right = width - paddingRight - 8f * density
        val top = paddingTop + 8f * density
        val bottom = height - paddingBottom - 8f * density
        if (right <= left || bottom <= top) return
        canvas.drawColor(PANEL_BG)

        calculator.configureAxis(SAMPLE_RATE, MIN_HZ, MAX_HZ)
        calculator.compute(values, peqState, curves)

        drawGrid(canvas, left, right, top, bottom)
        drawMonoBass(canvas, left, right, top, bottom)
        drawCurve(canvas, left, right, top, bottom, ::lowAt, lowPaint)
        drawCurve(canvas, left, right, top, bottom, ::midAt, midPaint)
        drawCurve(canvas, left, right, top, bottom, ::sumAt, sumPaint)
        drawCorners(canvas, left, right, top, bottom)
        drawFlatness(canvas, right, top)
    }

    private fun drawGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        floatArrayOf(6f, 0f, -6f, -12f).forEach { db ->
            val y = dbToY(db, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        labelPaint.alpha = 140
        floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f).forEach { hz ->
            val x = hzToX(hz, left, right)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val label = if (hz >= 1000f) "${(hz / 1000f).roundToInt()}k" else hz.roundToInt().toString()
            canvas.drawText(label, x - labelPaint.measureText(label) / 2f, bottom + 7f * density, labelPaint)
        }
        labelPaint.alpha = 255
    }

    private fun drawCurve(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        sample: (Int) -> Double,
        paint: Paint,
    ) {
        val path = Path()
        for (i in 0 until POINT_COUNT) {
            val x = hzToX(curves.frequencies[i].toFloat(), left, right)
            val y = dbToY(sample(i).toFloat(), top, bottom)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    /** Non-draggable dashed marker + Hz label at each crossover corner. */
    private fun drawCorners(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        drawCornerMarker(canvas, values[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ], BmwDashboardSkin.LIGHT_BLUE, top + 10f * density, left, right, top, bottom)
        drawCornerMarker(canvas, values[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ], BmwDashboardSkin.MID_BAND_YELLOW, top + 23f * density, left, right, top, bottom)
    }

    private fun drawCornerMarker(
        canvas: Canvas,
        hz: Float,
        color: Int,
        labelY: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ) {
        val x = hzToX(hz, left, right).coerceIn(left, right)
        markerPaint.color = color
        markerPaint.alpha = 220
        markerPaint.pathEffect = dashed
        canvas.drawLine(x, top, x, bottom, markerPaint)
        markerPaint.pathEffect = null
        val label = "${hz.roundToInt()} Hz"
        val tx = (x + 5f * density).coerceAtMost(right - labelPaint.measureText(label))
        labelPaint.color = color
        canvas.drawText(label, tx, labelY, labelPaint)
        labelPaint.color = Color.WHITE
    }

    /**
     * Display-only cue: below the Mono Bass corner the low end is summed to mono. The calculator
     * models that branch under an L=R assumption so there's no magnitude curve to draw for it
     * (see [MonoBassCue]); a shaded band up to the corner plus a marker makes "mono below N Hz"
     * visible on the graph.
     */
    private fun drawMonoBass(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (!MonoBassCue.isActive(values)) return
        val hz = MonoBassCue.frequency(values, MAX_HZ)
        val cornerX = hzToX(hz.toFloat(), left, right).coerceIn(left, right)
        canvas.drawRect(left, top, cornerX, bottom, monoShadePaint)
        markerPaint.color = BmwDashboardSkin.LIGHT_BLUE
        markerPaint.alpha = 150
        markerPaint.pathEffect = dashed
        canvas.drawLine(cornerX, top, cornerX, bottom, markerPaint)
        markerPaint.pathEffect = null
        labelPaint.color = BmwDashboardSkin.LIGHT_BLUE
        canvas.drawText("MONO ${hz.roundToInt()} Hz", left + 4f * density, bottom - 4f * density, labelPaint)
        labelPaint.color = Color.WHITE
    }

    private fun drawFlatness(canvas: Canvas, right: Float, top: Float) {
        val worst = worstSumDeviationDb()
        val text = "Σ ±%.1f dB".format(worst)
        readoutPaint.color = when {
            worst < 1.0 -> BmwDashboardSkin.M_GREEN
            worst < 3.0 -> BmwDashboardSkin.MID_BAND_YELLOW
            else -> BmwDashboardSkin.M_RED
        }
        canvas.drawText(text, right - readoutPaint.measureText(text), top + 11f * density, readoutPaint)
    }

    /**
     * Worst |sum - pre-split| deviation, in dB, over the octave either side of the geometric
     * mean of the two crossover corners -- i.e. how far the reconstructed Low+Mid sum strays
     * from the signal that entered the split. Referencing the pre-split curve (from the same
     * [BmwResponseCurves]) cancels the broadband PEQ/tilt/gain offset so the number reflects the
     * crossover's own summation error and the effect of the all-pass phase alignment, not the
     * overall level. Worst of the two channels.
     */
    private fun worstSumDeviationDb(): Double {
        val f = sqrt(
            values[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ].toDouble() *
                values[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ].toDouble(),
        )
        val lo = 0.5 * f
        val hi = 2.0 * f
        var worst = 0.0
        for (i in 0 until POINT_COUNT) {
            val hz = curves.frequencies[i]
            if (hz < lo || hz > hi) continue
            for (ch in 0..1) {
                val dev = abs(curves.sumDb[ch][i] - curves.preSplitDb[ch][i])
                if (dev > worst) worst = dev
            }
        }
        return worst
    }

    private fun lowAt(i: Int) = avg(curves.lowBranchDb, i)
    private fun midAt(i: Int) = avg(curves.midBranchDb, i)
    private fun sumAt(i: Int) = avg(curves.sumDb, i)
    private fun avg(bands: Array<DoubleArray>, i: Int) =
        (bands[BmwOutputChannel.LEFT.ordinal][i] + bands[BmwOutputChannel.RIGHT.ordinal][i]) * 0.5

    private fun dbToY(db: Float, top: Float, bottom: Float): Float {
        val clamped = db.coerceIn(MIN_DB, MAX_DB)
        return top + (MAX_DB - clamped) / (MAX_DB - MIN_DB) * (bottom - top)
    }

    private fun hzToX(hz: Float, left: Float, right: Float): Float =
        left + PeqGraphMath.frequencyToFraction(hz.toDouble(), MIN_HZ, MAX_HZ) * (right - left)

    private fun strokePaint(colorInt: Int, widthDp: Float, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
        color = colorInt
        this.alpha = alpha
    }

    companion object {
        private const val POINT_COUNT = 192
        private const val SAMPLE_RATE = 48_000.0
        // Runs down to 10 Hz (not 20) so the Subsonic high-pass, whose corner is 20-60 Hz, has
        // room to show its roll-off below the corner instead of being clipped at the axis edge.
        private const val MIN_HZ = 10.0
        private const val MAX_HZ = 20_000.0
        private const val MIN_DB = -18f
        private const val MAX_DB = 12f
        private const val PANEL_BG = 0xFF0C0E12.toInt()
    }
}
