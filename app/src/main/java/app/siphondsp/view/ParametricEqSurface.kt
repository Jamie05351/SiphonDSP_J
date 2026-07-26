package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.os.bundleOf
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.utils.extensions.CompatExtensions.getParcelableAs
import app.siphondsp.utils.extensions.prettyNumberFormat
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round

class ParametricEqSurface(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val gridLines = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridThickLines = Paint(Paint.ANTI_ALIAS_FLAG)
    private val controlBarText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frequencyResponseBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leftResponsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rightResponsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPointPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var viewHeight = 0f
    private var viewWidth = 0f
    private var curveFreqs = DoubleArray(0)
    private var leftCurveGains = DoubleArray(0)
    private var rightCurveGains = DoubleArray(0)
    private var preampDb = 0.0
    private val nPts = 256

    private var editableBands: List<ParametricEqBand> = emptyList()
    private var selectedBandIndex = -1
    private var draggedBandIndex = -1
    private var dragStartFrequency = 0.0
    private var dragStartGain = 0.0
    private var dragStartX = 0f
    private var dragStartY = 0f

    var onBandSelected: ((Int) -> Unit)? = null
    var onBandChanged: ((Int, Double, Double) -> Unit)? = null
    var onBandChangeFinished: ((Int) -> Unit)? = null
    var onBandGainReset: ((Int) -> Unit)? = null
    var onEmptyLongPress: ((Double, Double) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val index = findBandAt(e.x, e.y)
            if (index >= 0) {
                selectedBandIndex = index
                onBandSelected?.invoke(index)
                onBandGainReset?.invoke(index)
                invalidate()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (findBandAt(e.x, e.y) < 0) {
                onEmptyLongPress?.invoke(unprojectX(e.x), unprojectY(e.y).toDouble())
            }
        }
    })

    init {
        isClickable = true
        isFocusable = true
        gridLines.color = getColor(android.R.attr.colorControlHighlight)
        gridLines.style = Paint.Style.STROKE
        gridLines.strokeWidth = dp(1f)
        gridThickLines.color = getColor(android.R.attr.colorControlHighlight)
        gridThickLines.style = Paint.Style.STROKE
        gridThickLines.strokeWidth = dp(2f)

        controlBarText.textAlign = Paint.Align.CENTER
        controlBarText.textSize = sp(11f)
        controlBarText.color = getColor(android.R.attr.textColorPrimary)

        frequencyResponseBg.style = Paint.Style.FILL
        frequencyResponseBg.alpha = 72

        leftResponsePaint.style = Paint.Style.STROKE
        leftResponsePaint.color = getColor(android.R.attr.colorAccent)
        leftResponsePaint.strokeWidth = dp(2.5f)

        rightResponsePaint.style = Paint.Style.STROKE
        rightResponsePaint.color = getColor(android.R.attr.textColorPrimary)
        rightResponsePaint.alpha = 180
        rightResponsePaint.strokeWidth = dp(2f)
        rightResponsePaint.pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)

        pointFillPaint.style = Paint.Style.FILL
        pointFillPaint.color = getColor(android.R.attr.colorBackground)
        pointRingPaint.style = Paint.Style.STROKE
        pointRingPaint.strokeWidth = dp(1.5f)
        pointRingPaint.color = getColor(android.R.attr.textColorPrimary)
        pointTextPaint.textAlign = Paint.Align.CENTER
        pointTextPaint.textSize = sp(10f)
        pointTextPaint.color = getColor(android.R.attr.textColorPrimary)
        pointTextPaint.isFakeBoldText = true
        selectedPointPaint.style = Paint.Style.FILL
        selectedPointPaint.color = getColor(android.R.attr.colorAccent)
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun getColor(colorAttribute: Int): Int {
        if (isInEditMode) return Color.BLACK
        var color = 0
        context.withStyledAttributes(TypedValue().data, intArrayOf(colorAttribute)) { color = getColor(0, 0) }
        return color
    }

    override fun onSaveInstanceState() = bundleOf(
        "super" to super.onSaveInstanceState(),
        STATE_FREQ to curveFreqs,
        STATE_LEFT_GAIN to leftCurveGains,
        STATE_RIGHT_GAIN to rightCurveGains,
        STATE_PREAMP to preampDb,
    )

    override fun onRestoreInstanceState(state: Parcelable?) {
        val bundle = state as? Bundle ?: return super.onRestoreInstanceState(state)
        super.onRestoreInstanceState(bundle.getParcelableAs("super"))
        curveFreqs = bundle.getDoubleArray(STATE_FREQ) ?: DoubleArray(0)
        leftCurveGains = bundle.getDoubleArray(STATE_LEFT_GAIN) ?: DoubleArray(0)
        rightCurveGains = bundle.getDoubleArray(STATE_RIGHT_GAIN) ?: DoubleArray(0)
        preampDb = bundle.getDouble(STATE_PREAMP, 0.0)
        updateDbRange()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        viewWidth = (right - left).toFloat()
        viewHeight = (bottom - top).toFloat()
        frequencyResponseBg.shader = LinearGradient(
            0f, 0f, 0f, viewHeight,
            intArrayOf(leftResponsePaint.color, getColor(android.R.color.transparent)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private val leftPath = Path()
    private val rightPath = Path()
    private val responseBgPath = Path()

    override fun onDraw(canvas: Canvas) {
        leftPath.rewind(); rightPath.rewind(); responseBgPath.rewind()
        buildPath(leftPath, leftCurveGains); buildPath(rightPath, rightCurveGains)

        for (scale in FREQ_SCALE) {
            canvas.drawText(scale.prettyNumberFormat(), projectX(scale) * viewWidth, viewHeight - dp(5f), controlBarText)
        }
        var db = minDb + 3
        while (db <= maxDb - 3) {
            val y = projectY(db.toFloat()) * graphHeight()
            canvas.drawLine(0f, y, viewWidth, y, if (db == 0) gridThickLines else gridLines)
            db += 3
        }

        if (!leftPath.isEmpty) {
            responseBgPath.addPath(leftPath)
            if (curveFreqs.isNotEmpty()) {
                responseBgPath.lineTo(projectX(curveFreqs.last()) * viewWidth, graphHeight())
                responseBgPath.lineTo(projectX(curveFreqs.first()) * viewWidth, graphHeight())
            }
            responseBgPath.close()
            canvas.drawPath(responseBgPath, frequencyResponseBg)
        }

        canvas.drawPath(leftPath, leftResponsePaint)
        canvas.drawPath(rightPath, rightResponsePaint)
        drawBandPoints(canvas)

        controlBarText.textAlign = Paint.Align.LEFT
        canvas.drawText("L", dp(8f), dp(15f), controlBarText)
        canvas.drawText("R – –", dp(25f), dp(15f), controlBarText)
        controlBarText.textAlign = Paint.Align.CENTER
    }

    private fun graphHeight() = (viewHeight - dp(18f)).coerceAtLeast(1f)

    private fun drawBandPoints(canvas: Canvas) {
        editableBands.forEachIndexed { index, band ->
            val x = projectX(band.frequency) * viewWidth
            val y = projectY((band.gain + preampDb).toFloat()) * graphHeight()
            val radius = if (index == selectedBandIndex) dp(11f) else dp(9f)
            if (index == selectedBandIndex) {
                canvas.drawCircle(x, y, radius, selectedPointPaint)
                pointTextPaint.color = getColor(android.R.attr.colorBackground)
            } else {
                canvas.drawCircle(x, y, radius, pointFillPaint)
                canvas.drawCircle(x, y, radius, pointRingPaint)
                pointTextPaint.color = getColor(android.R.attr.textColorPrimary)
            }
            val baseline = y - (pointTextPaint.ascent() + pointTextPaint.descent()) * 0.5f
            canvas.drawText((index + 1).toString(), x, baseline, pointTextPaint)
        }
    }

    private fun buildPath(path: Path, gains: DoubleArray) {
        if (curveFreqs.isNotEmpty() && gains.size == curveFreqs.size) {
            path.moveTo(projectX(curveFreqs[0]) * viewWidth, projectY(gains[0].toFloat() + preampDb.toFloat()) * graphHeight())
            for (i in 1 until curveFreqs.size) {
                path.lineTo(projectX(curveFreqs[i]) * viewWidth, projectY(gains[i].toFloat() + preampDb.toFloat()) * graphHeight())
            }
        } else {
            val y = projectY(preampDb.toFloat()) * graphHeight()
            path.moveTo(0f, y); path.lineTo(viewWidth, y)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggedBandIndex = findBandAt(event.x, event.y)
                if (draggedBandIndex >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    selectedBandIndex = draggedBandIndex
                    val band = editableBands[draggedBandIndex]
                    dragStartFrequency = band.frequency
                    dragStartGain = band.gain
                    dragStartX = event.x
                    dragStartY = event.y
                    onBandSelected?.invoke(draggedBandIndex)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggedBandIndex >= 0) {
                    val frequency = (dragStartFrequency * exp((event.x - dragStartX) / viewWidth * ln(MAX_FREQ / MIN_FREQ)))
                        .coerceIn(MIN_FREQ, MAX_FREQ)
                    val gainRange = (maxDb - minDb).toDouble()
                    val gain = (dragStartGain - (event.y - dragStartY) / graphHeight() * gainRange)
                        .coerceIn(MIN_EDIT_GAIN, MAX_EDIT_GAIN)
                    onBandChanged?.invoke(draggedBandIndex, roundFrequency(frequency), round(gain * 10.0) / 10.0)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggedBandIndex >= 0) {
                    onBandChangeFinished?.invoke(draggedBandIndex)
                    draggedBandIndex = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findBandAt(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = dp(22f)
        editableBands.forEachIndexed { index, band ->
            val px = projectX(band.frequency) * viewWidth
            val py = projectY((band.gain + preampDb).toFloat()) * graphHeight()
            val distance = hypot(x - px, y - py)
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    private fun unprojectX(x: Float): Double {
        val normal = (x / viewWidth).coerceIn(0f, 1f).toDouble()
        return exp(ln(MIN_FREQ) + normal * (ln(MAX_FREQ) - ln(MIN_FREQ)))
    }

    private fun unprojectY(y: Float): Float {
        val normal = (y / graphHeight()).coerceIn(0f, 1f)
        return maxDb - normal * (maxDb - minDb) - preampDb.toFloat()
    }

    private fun roundFrequency(frequency: Double): Double = when {
        frequency < 400.0 -> round(frequency / 5.0) * 5.0
        frequency < 1000.0 -> round(frequency / 10.0) * 10.0
        frequency < 5000.0 -> round(frequency / 50.0) * 50.0
        else -> round(frequency / 100.0) * 100.0
    }

    fun setInteractiveBands(bands: List<ParametricEqBand>, selectedIndex: Int = selectedBandIndex) {
        editableBands = bands
        selectedBandIndex = selectedIndex.coerceIn(-1, bands.lastIndex)
        postInvalidate()
    }

    fun setSelectedBand(index: Int) {
        selectedBandIndex = index.coerceIn(-1, editableBands.lastIndex)
        postInvalidate()
    }

    fun setBands(bands: ParametricEqBandList, preampDb: Double = this.preampDb) {
        this.preampDb = preampDb
        if (bands.isEmpty()) {
            curveFreqs = DoubleArray(0); leftCurveGains = DoubleArray(0); rightCurveGains = DoubleArray(0)
        } else {
            val left = BiquadUtils.computeCombinedResponse(bands, nPts, MIN_FREQ, MAX_FREQ, channel = ParametricEqChannel.LEFT)
            val right = BiquadUtils.computeCombinedResponse(bands, nPts, MIN_FREQ, MAX_FREQ, channel = ParametricEqChannel.RIGHT)
            val source = if (left.isNotEmpty()) left else right
            curveFreqs = DoubleArray(source.size) { source[it].first }
            leftCurveGains = DoubleArray(curveFreqs.size) { left.getOrNull(it)?.second ?: 0.0 }
            rightCurveGains = DoubleArray(curveFreqs.size) { right.getOrNull(it)?.second ?: 0.0 }
        }
        updateDbRange(); postInvalidate()
    }

    fun setBmwSystemResponse(
        fullRangeBands: ParametricEqBandList,
        lowBandBands: ParametricEqBandList,
        midBandBands: ParametricEqBandList,
        preampDb: Double,
        lowPassHz: Double,
        lowLr4: Boolean,
        highPassHz: Double,
    ) {
        val response = BiquadUtils.computeBmwSystemResponse(
            fullRangeBands, lowBandBands, midBandBands, preampDb,
            lowPassHz, lowLr4, highPassHz, nPts, MIN_FREQ, MAX_FREQ,
        )
        this.preampDb = 0.0
        curveFreqs = response.frequencies
        leftCurveGains = response.leftDb
        rightCurveGains = response.rightDb
        updateDbRange(); postInvalidate()
    }

    fun setPreampDb(preampDb: Double) {
        this.preampDb = preampDb
        updateDbRange(); postInvalidate()
    }

    private fun updateDbRange() {
        val allGains = leftCurveGains.asSequence() + rightCurveGains.asSequence() + editableBands.asSequence().map { it.gain }
        val minGain = (allGains.minOrNull() ?: 0.0) + preampDb
        val maxGain = (allGains.maxOrNull() ?: 0.0) + preampDb
        minDb = floor(minOf(minGain, -15.0) / 3.0).toInt() * 3
        maxDb = ceil(maxOf(maxGain, 15.0) / 3.0).toInt() * 3
    }

    private fun projectX(frequency: Double): Float =
        ((ln(frequency.coerceIn(MIN_FREQ, MAX_FREQ)) - ln(MIN_FREQ)) / (ln(MAX_FREQ) - ln(MIN_FREQ))).toFloat()

    private fun projectY(db: Float): Float = 1f - (db - minDb) / (maxDb - minDb)

    private var minDb = -15
    private var maxDb = 15

    companion object {
        private const val STATE_FREQ = "peq_curve_freq"
        private const val STATE_LEFT_GAIN = "peq_curve_left_gain"
        private const val STATE_RIGHT_GAIN = "peq_curve_right_gain"
        private const val STATE_PREAMP = "peq_curve_preamp"
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0
        private const val MIN_EDIT_GAIN = -30.0
        private const val MAX_EDIT_GAIN = 30.0
        private val FREQ_SCALE = doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0)
    }
}
