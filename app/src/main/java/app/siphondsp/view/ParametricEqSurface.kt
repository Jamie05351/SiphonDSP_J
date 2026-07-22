package app.siphondsp.view

import android.content.Context
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
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.os.bundleOf
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.utils.extensions.CompatExtensions.getParcelableAs
import app.siphondsp.utils.extensions.prettyNumberFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

class ParametricEqSurface(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private val gridLines = Paint()
    private val gridThickLines = Paint()
    private val controlBarText = Paint()
    private val frequencyResponseBg = Paint()
    private val leftResponsePaint = Paint()
    private val rightResponsePaint = Paint()

    private var viewHeight = 0.0f
    private var viewWidth = 0.0f

    private var curveFreqs = DoubleArray(0)
    private var leftCurveGains = DoubleArray(0)
    private var rightCurveGains = DoubleArray(0)
    private var preampDb = 0.0

    private val nPts = 256

    init {
        gridLines.color = getColor(android.R.attr.colorControlHighlight)
        gridLines.style = Paint.Style.STROKE
        gridLines.strokeWidth = 4f

        gridThickLines.color = getColor(android.R.attr.colorControlHighlight)
        gridThickLines.style = Paint.Style.STROKE
        gridThickLines.strokeWidth = 8f

        controlBarText.textAlign = Paint.Align.CENTER
        controlBarText.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f,
            getContext().resources.displayMetrics,
        )
        controlBarText.color = getColor(android.R.attr.textColorPrimary)
        controlBarText.isAntiAlias = true

        frequencyResponseBg.style = Paint.Style.FILL
        frequencyResponseBg.alpha = 96

        leftResponsePaint.style = Paint.Style.STROKE
        leftResponsePaint.color = getColor(android.R.attr.colorAccent)
        leftResponsePaint.isAntiAlias = true
        leftResponsePaint.strokeWidth = 8f

        rightResponsePaint.style = Paint.Style.STROKE
        rightResponsePaint.color = getColor(android.R.attr.textColorPrimary)
        rightResponsePaint.alpha = 210
        rightResponsePaint.isAntiAlias = true
        rightResponsePaint.strokeWidth = 6f
        rightResponsePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private fun getColor(colorAttribute: Int): Int {
        if (isInEditMode) return Color.BLACK
        var color = 0
        context.withStyledAttributes(TypedValue().data, intArrayOf(colorAttribute)) {
            color = getColor(0, 0)
        }
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

        val responseColors = intArrayOf(leftResponsePaint.color, getColor(android.R.color.transparent))
        val responsePositions = floatArrayOf(0.0f, 1f)
        frequencyResponseBg.shader = LinearGradient(
            0f,
            0f,
            0f,
            viewHeight,
            responseColors,
            responsePositions,
            Shader.TileMode.CLAMP,
        )
    }

    private val leftPath = Path()
    private val rightPath = Path()
    private val responseBgPath = Path()

    override fun onDraw(canvas: android.graphics.Canvas) {
        leftPath.rewind()
        rightPath.rewind()
        responseBgPath.rewind()

        buildPath(leftPath, leftCurveGains)
        buildPath(rightPath, rightCurveGains)

        for (scale in FREQ_SCALE) {
            val x = projectX(scale) * viewWidth
            canvas.drawText(scale.prettyNumberFormat(), x, viewHeight - 16, controlBarText)
        }

        var db = minDb + 3
        while (db <= maxDb - 3) {
            val y = projectY(db.toFloat()) * viewHeight
            canvas.drawLine(0f, y, viewWidth, y, if (db == 0) gridThickLines else gridLines)
            db += 3
        }

        if (!leftPath.isEmpty) {
            responseBgPath.addPath(leftPath)
            responseBgPath.offset(0f, -4f)
            if (curveFreqs.isNotEmpty()) {
                responseBgPath.lineTo(projectX(curveFreqs.last()) * viewWidth, viewHeight)
                responseBgPath.lineTo(projectX(curveFreqs.first()) * viewWidth, viewHeight)
            } else {
                responseBgPath.lineTo(viewWidth, viewHeight)
                responseBgPath.lineTo(0f, viewHeight)
            }
            responseBgPath.close()
            canvas.drawPath(responseBgPath, frequencyResponseBg)
        }

        canvas.drawPath(leftPath, leftResponsePaint)
        canvas.drawPath(rightPath, rightResponsePaint)

        canvas.drawText("L", 24f, 28f, controlBarText.apply { textAlign = Paint.Align.LEFT })
        canvas.drawText("R - -", 56f, 28f, controlBarText)
        controlBarText.textAlign = Paint.Align.CENTER
    }

    private fun buildPath(path: Path, gains: DoubleArray) {
        if (curveFreqs.isNotEmpty() && gains.size == curveFreqs.size) {
            path.moveTo(
                projectX(curveFreqs[0]) * viewWidth,
                projectY(gains[0].toFloat() + preampDb.toFloat()) * viewHeight,
            )
            for (i in 1 until curveFreqs.size) {
                path.lineTo(
                    projectX(curveFreqs[i]) * viewWidth,
                    projectY(gains[i].toFloat() + preampDb.toFloat()) * viewHeight,
                )
            }
        } else {
            val y = projectY(preampDb.toFloat()) * viewHeight
            path.moveTo(0f, y)
            path.lineTo(viewWidth, y)
        }
    }

    fun setBands(bands: ParametricEqBandList, preampDb: Double = this.preampDb) {
        this.preampDb = preampDb
        if (bands.isEmpty()) {
            curveFreqs = DoubleArray(0)
            leftCurveGains = DoubleArray(0)
            rightCurveGains = DoubleArray(0)
        } else {
            val left = BiquadUtils.computeCombinedResponse(
                bands,
                numPoints = nPts,
                minFreq = MIN_FREQ,
                maxFreq = MAX_FREQ,
                channel = ParametricEqChannel.LEFT,
            )
            val right = BiquadUtils.computeCombinedResponse(
                bands,
                numPoints = nPts,
                minFreq = MIN_FREQ,
                maxFreq = MAX_FREQ,
                channel = ParametricEqChannel.RIGHT,
            )
            val source = if (left.isNotEmpty()) left else right
            curveFreqs = DoubleArray(source.size) { source[it].first }
            leftCurveGains = DoubleArray(curveFreqs.size) { left.getOrNull(it)?.second ?: 0.0 }
            rightCurveGains = DoubleArray(curveFreqs.size) { right.getOrNull(it)?.second ?: 0.0 }
        }

        updateDbRange()
        postInvalidate()
    }

    fun setPreampDb(preampDb: Double) {
        this.preampDb = preampDb
        updateDbRange()
        postInvalidate()
    }

    private fun updateDbRange() {
        val allGains = leftCurveGains.asSequence() + rightCurveGains.asSequence()
        val minGain = (allGains.minOrNull() ?: 0.0) + preampDb
        val maxGain = (allGains.maxOrNull() ?: 0.0) + preampDb
        minDb = floor(minOf(minGain, -15.0)).toInt()
        maxDb = ceil(maxOf(maxGain, 15.0)).toInt()
    }

    private fun projectX(frequency: Double): Float {
        val position = ln(frequency)
        val minimumPosition = ln(MIN_FREQ)
        val maximumPosition = ln(MAX_FREQ)
        return ((position - minimumPosition) / (maximumPosition - minimumPosition)).toFloat()
    }

    private fun projectY(db: Float): Float {
        val pos = (db - minDb) / (maxDb - minDb)
        return 1.0f - pos
    }

    private var minDb = -15
    private var maxDb = 15

    companion object {
        private const val STATE_FREQ = "peq_curve_freq"
        private const val STATE_LEFT_GAIN = "peq_curve_left_gain"
        private const val STATE_RIGHT_GAIN = "peq_curve_right_gain"
        private const val STATE_PREAMP = "peq_curve_preamp"

        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0

        private val FREQ_SCALE = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
        )
    }
}
