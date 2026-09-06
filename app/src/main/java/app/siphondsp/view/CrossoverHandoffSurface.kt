package app.siphondsp.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Interactive Low/Mid handoff graph -- the one place the crossover is tuned. Draws the low
 * branch, mid branch and their complex sum (from the shared [BmwResponseCalculator], the same
 * model the read-only full-screen PEQ visualiser uses, so the two never disagree) and hangs
 * three draggable vertical handles off it:
 *
 *  - Low corner   -> INDEX_LOW_CROSSOVER_FREQ + the two Low outputs' FIELD_CROSSOVER_FREQ
 *  - Mid HPF corner -> INDEX_MID_CROSSOVER_FREQ + the two Mid outputs' FIELD_CROSSOVER_FREQ
 *  - Mid LPF        -> INDEX_MID_LPF_FREQ (and flips INDEX_MID_LPF_ENABLED on first drag)
 *
 * The worst-case flat-sum deviation over the handoff octave is drawn live in the corner so the
 * effect of a drag is visible without leaving the screen. Read-only stages (PEQ, tilt, gains)
 * are folded in by the calculator exactly as elsewhere; this view writes only the three
 * crossover frequencies. L/R crossovers are linked, so the curves are drawn as the L/R average.
 */
class CrossoverHandoffSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Fragment wires this to NativeBmwDspValues.save + broadcast. */
    var onEdit: ((FloatArray) -> Unit)? = null

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
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val dashed = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)

    private enum class HandleId { LOW, MID_HPF, MID_LPF }

    private inner class Handle(
        val id: HandleId,
        val color: Int,
        val minHz: Float,
        val maxHz: Float,
        /** dp below the top edge for this handle's knob -- staggered so overlapping corners stay legible. */
        val knobDp: Float,
    ) {
        fun frequency(): Float = when (id) {
            HandleId.LOW -> values[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ]
            HandleId.MID_HPF -> values[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ]
            HandleId.MID_LPF -> values[NativeBmwDspValues.INDEX_MID_LPF_FREQ]
                .takeIf { it in minHz..maxHz } ?: NativeBmwDspValues.DEFAULT_MID_LPF_FREQ
        }

        /** Only meaningful for MID_LPF; the other two are always active. */
        fun enabled(): Boolean =
            id != HandleId.MID_LPF || values[NativeBmwDspValues.INDEX_MID_LPF_ENABLED] >= 0.5f

        fun write(hz: Float) {
            val clamped = hz.coerceIn(minHz, maxHz).roundToInt().toFloat()
            when (id) {
                HandleId.LOW -> {
                    values[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] = clamped
                    values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = clamped
                    values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = clamped
                }
                HandleId.MID_HPF -> {
                    values[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ] = clamped
                    values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = clamped
                    values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = clamped
                }
                HandleId.MID_LPF -> {
                    values[NativeBmwDspValues.INDEX_MID_LPF_FREQ] = clamped
                    values[NativeBmwDspValues.INDEX_MID_LPF_ENABLED] = 1f
                }
            }
        }
    }

    private val handles = listOf(
        Handle(HandleId.LOW, BmwDashboardSkin.LIGHT_BLUE, 80f, 200f, knobDp = 9f),
        Handle(HandleId.MID_HPF, BmwDashboardSkin.MID_BAND_YELLOW, 80f, 200f, knobDp = 22f),
        Handle(HandleId.MID_LPF, BmwDashboardSkin.MID_BAND_YELLOW, 1500f, 8000f, knobDp = 9f),
    )
    private var activeHandle: Handle? = null

    /** Copies in a fresh config + PEQ snapshot and redraws. */
    fun bind(newValues: FloatArray, newPeqState: BmwPeqState) {
        if (newValues.size == BmwSignalChain.VALUE_COUNT) values = newValues.copyOf()
        peqState = newPeqState
        calculator.invalidateAll()
        invalidate()
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
        drawCurve(canvas, left, right, top, bottom, ::lowAt, lowPaint)
        drawCurve(canvas, left, right, top, bottom, ::midAt, midPaint)
        drawCurve(canvas, left, right, top, bottom, ::sumAt, sumPaint)
        drawHandles(canvas, left, right, top, bottom)
        drawFlatness(canvas, right, top)
    }

    private fun drawGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        floatArrayOf(6f, 0f, -6f, -12f).forEach { db ->
            val y = dbToY(db, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        labelPaint.alpha = 140
        floatArrayOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f).forEach { hz ->
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

    private fun drawHandles(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        handles.forEach { handle ->
            val x = hzToX(handle.frequency(), left, right).coerceIn(left, right)
            val on = handle.enabled()
            handlePaint.color = handle.color
            handlePaint.alpha = if (on) 230 else 90
            handlePaint.pathEffect = if (on) null else dashed
            canvas.drawLine(x, top, x, bottom, handlePaint)
            handlePaint.pathEffect = null

            val knobY = top + handle.knobDp * density
            handleFillPaint.color = handle.color
            handleFillPaint.alpha = if (on) 255 else 70
            canvas.drawCircle(x, knobY, 4.5f * density, handleFillPaint)

            val label = when {
                handle.id == HandleId.MID_LPF && !on -> "LPF off"
                handle.frequency() >= 1000f -> "%.1fk".format(handle.frequency() / 1000f)
                else -> "${handle.frequency().roundToInt()}"
            }
            val tx = (x + 6f * density).coerceAtMost(right - labelPaint.measureText(label))
            labelPaint.color = handle.color
            canvas.drawText(label, tx, knobY + 4f * density, labelPaint)
            labelPaint.color = Color.WHITE
        }
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

    private fun xToHz(x: Float, left: Float, right: Float): Float =
        PeqGraphMath.fractionToFrequency(((x - left) / (right - left)).coerceIn(0f, 1f), MIN_HZ, MAX_HZ).toFloat()

    // Continuous drag control, not a click target -- there is no meaningful performClick() action,
    // so the accessibility lint check doesn't apply here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val left = paddingLeft + 8f * density
        val right = width - paddingRight - 8f * density
        if (right <= left) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val picked = handles.minByOrNull { abs(hzToX(it.frequency(), left, right) - event.x) }
                if (picked != null && abs(hzToX(picked.frequency(), left, right) - event.x) <= GRAB_DP * density) {
                    activeHandle = picked
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val handle = activeHandle ?: return false
                dragTo(handle, event.x, left, right)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun dragTo(handle: Handle, x: Float, left: Float, right: Float) {
        handle.write(xToHz(x, left, right))
        onEdit?.invoke(values)
        calculator.invalidateAll()
        invalidate()
    }

    private fun strokePaint(colorInt: Int, widthDp: Float, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
        color = colorInt
        this.alpha = alpha
    }

    companion object {
        private const val POINT_COUNT = 192
        private const val SAMPLE_RATE = 48_000.0
        private const val MIN_HZ = 20.0
        private const val MAX_HZ = 20_000.0
        private const val MIN_DB = -18f
        private const val MAX_DB = 12f
        private const val GRAB_DP = 40f
        private const val PANEL_BG = 0xFF0C0E12.toInt()
    }
}
