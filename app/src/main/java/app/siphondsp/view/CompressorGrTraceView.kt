package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Scrolling time-domain trace of the low-band compressor: input level fills from the
 * bottom, gain-reduction dips from the top and never crosses the threshold line, and
 * output level draws as a third line so the compression's effect on level is visible
 * directly rather than only inferred from the GR dip.
 * Modeled on Equalizer314's LimiterWaveformView (GPLv3), adapted to a single band and
 * fed directly from the native meter (already attack/release-smoothed in
 * NativeBmwDspProcessor::processFrame) instead of re-smoothing here.
 */
class CompressorGrTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val density = resources.displayMetrics.density
    private val dbMax = 6f
    private val dbMin = -60f
    private val dbRange = dbMax - dbMin
    private val grMax = 24f

    private val bufferSize = 300
    private var writeIdx = 0
    private val inputHistory = FloatArray(bufferSize) { dbMin }
    private val outputHistory = FloatArray(bufferSize) { dbMin }
    private val grHistory = FloatArray(bufferSize)
    private var hasData = false

    private val inputFillPath = Path()
    private val inputStrokePath = Path()
    private val outputStrokePath = Path()
    private val grLinePath = Path()
    private val grFillPath = Path()

    var thresholdDb = -12f
        set(value) { field = value; invalidate() }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(62, 62, 66); strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 170, 178); textSize = 10f * density
    }
    private val inputFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 232, 240); style = Paint.Style.FILL; alpha = 30
    }
    private val inputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 232, 240); style = Paint.Style.STROKE
        strokeWidth = density; alpha = 70
    }
    private val outputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(67, 137, 255); style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val grTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 76, 92); style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val grFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 76, 92); style = Paint.Style.FILL; alpha = 40
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 76, 92); strokeWidth = density; alpha = 110
    }

    /** Called once per meter poll (see NativeBmwCompressorFragment's meterTick). */
    fun pushFrame(inputDb: Float, outputDb: Float, gainReductionDb: Float) {
        val idx = writeIdx % bufferSize
        inputHistory[idx] = inputDb.coerceIn(dbMin, dbMax)
        outputHistory[idx] = outputDb.coerceIn(dbMin, dbMax)
        grHistory[idx] = gainReductionDb.coerceIn(0f, grMax)
        writeIdx++
        hasData = true
        invalidate()
    }

    fun reset() {
        inputHistory.fill(dbMin)
        outputHistory.fill(dbMin)
        grHistory.fill(0f)
        writeIdx = 0
        hasData = false
        invalidate()
    }

    private fun ringIdx(sampleOffset: Int): Int =
        ((writeIdx - bufferSize + sampleOffset) % bufferSize + bufferSize) % bufferSize

    private fun levelDbToY(db: Float, h: Float): Float = h * (dbMax - db) / dbRange
    private fun grDbToY(gr: Float, h: Float): Float = h * gr / grMax

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // No solid fill: the workspace's own designed background shows through behind the trace
        // now, with only the grid wireframe below drawn on top of it.

        for (db in intArrayOf(0, -20, -40, -60)) {
            val y = levelDbToY(db.toFloat(), h)
            canvas.drawLine(0f, y, w, y, gridPaint)
            canvas.drawText(db.toString(), 3f * density, y + 3f * density, textPaint)
        }

        if (!hasData) return
        val pxPerSample = w / bufferSize

        inputFillPath.rewind(); inputFillPath.moveTo(0f, h)
        inputStrokePath.rewind()
        outputStrokePath.rewind()
        for (s in 0 until bufferSize) {
            val idx = ringIdx(s)
            val x = s * pxPerSample
            val inputY = levelDbToY(inputHistory[idx], h)
            inputFillPath.lineTo(x, inputY)
            if (s == 0) inputStrokePath.moveTo(x, inputY) else inputStrokePath.lineTo(x, inputY)

            val outputY = levelDbToY(outputHistory[idx], h)
            if (s == 0) outputStrokePath.moveTo(x, outputY) else outputStrokePath.lineTo(x, outputY)
        }
        inputFillPath.lineTo(w, h)
        inputFillPath.close()
        canvas.drawPath(inputFillPath, inputFillPaint)
        canvas.drawPath(inputStrokePath, inputStrokePaint)
        canvas.drawPath(outputStrokePath, outputStrokePaint)

        grLinePath.rewind()
        grFillPath.rewind(); grFillPath.moveTo(0f, 0f)
        for (s in 0 until bufferSize) {
            val idx = ringIdx(s)
            val x = s * pxPerSample
            val y = grDbToY(grHistory[idx], h)
            if (s == 0) grLinePath.moveTo(x, y) else grLinePath.lineTo(x, y)
            grFillPath.lineTo(x, y)
        }
        grFillPath.lineTo(w, 0f)
        grFillPath.close()
        canvas.drawPath(grFillPath, grFillPaint)
        canvas.drawPath(grLinePath, grTracePaint)

        val threshY = levelDbToY(thresholdDb, h)
        canvas.drawLine(0f, threshY, w, threshY, thresholdPaint)
    }
}
