package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-band compressor transfer curve and live native meter.
 *
 * The interaction follows Equalizer314's useful convention: drag the threshold
 * point horizontally and drag the compressed part of the curve vertically to
 * adjust ratio. The knee handle -- where the soft-knee curve meets the straight
 * ratio segment -- drags horizontally to widen/narrow the knee. The glowing
 * trace is native detector/output telemetry.
 */
class NativeBmwCompressorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var thresholdDb = -12f
        set(value) { field = value.coerceIn(-24f, 0f); invalidate() }
    var ratio = 2f
        set(value) { field = value.coerceIn(1f, 10f); invalidate() }
    var kneeDb = 8f
        set(value) { field = value.coerceIn(0f, 12f); invalidate() }
    var makeupDb = 1.5f
        set(value) { field = value.coerceIn(0f, 6f); invalidate() }
    var compressorEnabled = true
        set(value) { field = value; invalidate() }
    var interactive = true

    var onThresholdChanged: ((Float) -> Unit)? = null
    var onRatioChanged: ((Float) -> Unit)? = null
    var onKneeChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val minDb = -60f
    private val maxDb = 6f
    private val padLeft = 34f * density
    private val padTop = 14f * density
    private val padRight = 25f * density
    private val padBottom = 24f * density
    private val thresholdHitRadiusPx = 34f * density
    private val kneeHitRadiusPx = 26f * density
    private var meterInputDb = -60f
    private var meterOutputDb = -60f
    private var gainReductionDb = 0f
    private val history = ArrayDeque<Pair<Float, Float>>()
    private var dragMode: CompressorGraphMath.DragMode? = null

    private val backgroundPaint = Paint().apply { color = Color.rgb(24, 24, 26) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(62, 62, 66); strokeWidth = density
    }
    private val unityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 100, 106); strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 232, 240); strokeWidth = 2.4f * density
        style = Paint.Style.STROKE
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 76, 92); strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val kneeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 183, 77); style = Paint.Style.FILL
    }
    private val kneeHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 183, 77); style = Paint.Style.STROKE
        strokeWidth = 1.5f * density; alpha = 160
    }
    private val liveTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(67, 137, 255); strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }
    private val liveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(88, 154, 255); style = Paint.Style.FILL
        setShadowLayer(9f * density, 0f, 0f, Color.rgb(64, 126, 255))
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 170, 178); textSize = 10f * density
    }
    private val reductionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 76, 92); style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    /** Clears the transfer-curve trace history, eg. when switching to a different band. */
    fun resetHistory() {
        history.clear()
        invalidate()
    }

    fun setMeter(inputDb: Float, outputDb: Float, gainReductionDb: Float) {
        meterInputDb = inputDb.coerceIn(minDb, maxDb)
        meterOutputDb = outputDb.coerceIn(minDb, maxDb)
        this.gainReductionDb = gainReductionDb.coerceIn(0f, 24f)
        if (compressorEnabled && inputDb > minDb + .5f) {
            history.addLast(meterInputDb to meterOutputDb)
            while (history.size > 36) history.removeFirst()
        } else if (!compressorEnabled) {
            history.clear()
        }
        invalidate()
    }

    private fun graphWidth() = (width - padLeft - padRight).coerceAtLeast(1f)
    private fun graphHeight() = (height - padTop - padBottom).coerceAtLeast(1f)
    private fun x(db: Float) = padLeft + graphWidth() * (db - minDb) / (maxDb - minDb)
    private fun y(db: Float) = padTop + graphHeight() * (1f - (db - minDb) / (maxDb - minDb))
    private fun dbAtX(value: Float) = minDb + (value - padLeft) / graphWidth() * (maxDb - minDb)
    private fun dbAtY(value: Float) = maxDb - (value - padTop) / graphHeight() * (maxDb - minDb)

    private fun outputFor(inputDb: Float): Float =
        CompressorGraphMath.outputFor(inputDb, thresholdDb, ratio, kneeDb, makeupDb, compressorEnabled)

    private fun kneeHandleX(): Float {
        val (inputDb, _) = CompressorGraphMath.kneeHandlePoint(thresholdDb, ratio, kneeDb, makeupDb, compressorEnabled)
        return x(inputDb)
    }

    private fun kneeHandleY(): Float {
        val (_, outputDb) = CompressorGraphMath.kneeHandlePoint(thresholdDb, ratio, kneeDb, makeupDb, compressorEnabled)
        return y(outputDb.coerceIn(minDb, maxDb))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        for (db in -60..0 step 10) {
            canvas.drawLine(x(db.toFloat()), padTop, x(db.toFloat()), height - padBottom, gridPaint)
            canvas.drawLine(padLeft, y(db.toFloat()), width - padRight, y(db.toFloat()), gridPaint)
            canvas.drawText(db.toString(), 3f * density, y(db.toFloat()) + 3f * density, textPaint)
        }
        canvas.drawLine(x(minDb), y(minDb), x(maxDb), y(maxDb), unityPaint)

        val curve = Path()
        for (step in 0..160) {
            val input = minDb + (maxDb - minDb) * step / 160f
            val output = outputFor(input).coerceIn(minDb, maxDb)
            if (step == 0) curve.moveTo(x(input), y(output)) else curve.lineTo(x(input), y(output))
        }
        curvePaint.alpha = if (compressorEnabled) 255 else 100
        canvas.drawPath(curve, curvePaint)

        val thresholdX = x(thresholdDb)
        val thresholdY = y(outputFor(thresholdDb).coerceIn(minDb, maxDb))
        canvas.drawLine(thresholdX, padTop, thresholdX, height - padBottom, thresholdPaint)
        canvas.drawCircle(thresholdX, thresholdY, 8f * density, liveDotPaint)

        if (kneeDb > 0f) {
            val kneeX = kneeHandleX()
            val kneeY = kneeHandleY()
            canvas.drawCircle(kneeX, kneeY, 7f * density, kneeHandlePaint)
            canvas.drawCircle(kneeX, kneeY, 7f * density, kneeHandleStrokePaint)
        }

        if (history.size > 1) {
            val trace = Path()
            history.forEachIndexed { index, point ->
                if (index == 0) trace.moveTo(x(point.first), y(point.second))
                else trace.lineTo(x(point.first), y(point.second))
            }
            canvas.drawPath(trace, liveTracePaint)
        }
        if (meterInputDb > minDb + .5f) {
            canvas.drawCircle(x(meterInputDb), y(meterOutputDb), 5f * density, liveDotPaint)
        }

        val meterLeft = width - 13f * density
        val meterTop = padTop
        val meterBottom = height - padBottom
        canvas.drawRect(meterLeft, meterTop, width - 7f * density, meterBottom, gridPaint)
        val reductionHeight = (meterBottom - meterTop) * (gainReductionDb / 24f)
        canvas.drawRect(meterLeft, meterTop, width - 7f * density, meterTop + reductionHeight, reductionPaint)
        canvas.drawText("IN", padLeft, height - 6f * density, textPaint)
        canvas.drawText("OUT", width - padRight - 26f * density, height - 6f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return super.onTouchEvent(event)
        if (!isEnabled) return false
        val thresholdX = x(thresholdDb)
        val thresholdY = y(outputFor(thresholdDb).coerceIn(minDb, maxDb))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragMode = CompressorGraphMath.pickDragMode(
                    event.x, event.y,
                    thresholdX, thresholdY,
                    kneeHandleX(), kneeHandleY(),
                    thresholdHitRadiusPx, kneeHitRadiusPx,
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    CompressorGraphMath.DragMode.THRESHOLD -> {
                        val value = (dbAtX(event.x).coerceIn(-24f, 0f) * 2f).roundToInt() / 2f
                        thresholdDb = value
                        onThresholdChanged?.invoke(value)
                    }
                    CompressorGraphMath.DragMode.KNEE -> {
                        val draggedInput = dbAtX(event.x)
                        val value = CompressorGraphMath.kneeFromDrag(draggedInput, thresholdDb)
                        if (value != kneeDb) {
                            kneeDb = value
                            onKneeChanged?.invoke(value)
                        }
                    }
                    CompressorGraphMath.DragMode.RATIO, null -> {
                        val input = dbAtX(event.x).coerceIn(thresholdDb + 1f, maxDb)
                        val output = (dbAtY(event.y) - makeupDb).coerceAtLeast(thresholdDb + .05f)
                        val snapped = CompressorGraphMath.ratioFromDrag(input, output, thresholdDb)
                        if (abs(snapped - ratio) >= .05f) {
                            ratio = snapped
                            onRatioChanged?.invoke(snapped)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragMode = null
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
