package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.withStyledAttributes
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.utils.extensions.prettyNumberFormat
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min

/**
 * Intent-only PEQ graph. Committed bands are supplied by the fragment; the view
 * owns only a single visual drag draft and never persists or configures audio.
 */
class ParametricEqSurface(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class ChannelDisplay { BOTH, LEFT, RIGHT }

    var onPointSelected: ((UUID) -> Unit)? = null
    var onDragCommitted: ((ParametricEqBand) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.colorControlHighlight)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val zeroPaint = Paint(gridPaint).apply { strokeWidth = 2f * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorPrimary)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
        )
    }
    private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.colorAccent)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorPrimary)
        alpha = 190
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorSecondary)
        alpha = 80
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val selectedOverlayPaint = Paint(leftPaint).apply { strokeWidth = 2f * density }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.colorAccent)
        style = Paint.Style.FILL
    }
    private val selectedPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.colorAccent)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val pointTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
    }
    private val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorSecondary)
        style = Paint.Style.FILL
        alpha = 30
    }
    private val spectrumStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorSecondary)
        style = Paint.Style.STROKE
        strokeWidth = density
        alpha = 90
    }
    private val spectrumStrokePath = Path()
    private val spectrumFillPath = Path()

    private val leftPath = Path()
    private val rightPath = Path()
    private val overlayPaths = mutableListOf<Path>()
    private var committedBands: List<ParametricEqBand> = emptyList()
    private var renderBands: List<ParametricEqBand> = emptyList()
    private var selectedId: UUID? = null
    private var draft: ParametricEqBand? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var sampleRate = 48_000.0
    private var maximumFrequency = PeqGraphMath.MAX_FREQUENCY
    private var preampDb = 0.0
    private var leftGains = DoubleArray(0)
    private var rightGains = DoubleArray(0)
    private var overlayGains: List<DoubleArray> = emptyList()
    private var frequencies = DoubleArray(0)

    var showIndividualFilters = true
        set(value) {
            field = value
            invalidate()
        }
    var channelDisplay = ChannelDisplay.BOTH
        set(value) {
            field = value
            invalidate()
        }

    /** Opt-in live spectrum trace behind the curve; off by default (e.g. small preview thumbnails). */
    var showSpectrum = false
        set(value) {
            if (field == value) return
            field = value
            if (isAttachedToWindow) {
                if (value) startSpectrum() else stopSpectrum()
            }
        }
    private val spectrumHandler = Handler(Looper.getMainLooper())
    private var spectrumActive = false
    private val spectrumTick = object : Runnable {
        override fun run() {
            invalidate()
            spectrumHandler.postDelayed(this, 33L)
        }
    }

    private fun startSpectrum() {
        if (spectrumActive) return
        spectrumActive = true
        SpectrumEngine.acquire()
        spectrumHandler.post(spectrumTick)
    }

    private fun stopSpectrum() {
        if (!spectrumActive) return
        spectrumActive = false
        spectrumHandler.removeCallbacks(spectrumTick)
        SpectrumEngine.release()
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Interactive parametric equalizer graph"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (showSpectrum) startSpectrum()
    }

    fun setBands(
        bands: ParametricEqBandList,
        preampDb: Double = this.preampDb,
        selectedId: UUID? = this.selectedId,
        sampleRate: Double = this.sampleRate,
    ) {
        committedBands = bands.toList()
        renderBands = committedBands
        draft = null
        dragging = false
        this.preampDb = preampDb
        this.selectedId = selectedId?.takeIf { id -> committedBands.any { it.uuid == id } }
        this.sampleRate = sampleRate
        maximumFrequency = min(PeqGraphMath.MAX_FREQUENCY, sampleRate * 0.5 * 0.999)
        recomputeResponses()
        updateContentDescription()
    }

    fun selectBand(id: UUID?) {
        selectedId = id?.takeIf { candidate -> committedBands.any { it.uuid == candidate } }
        updateContentDescription()
        invalidate()
    }

    fun cancelDraft() {
        draft = null
        renderBands = committedBands
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        recomputeResponses()
    }

    fun hasActiveDraft(): Boolean = draft != null

    override fun onDetachedFromWindow() {
        cancelDraft()
        stopSpectrum()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            cancelDraft()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.x, event.y) ?: return false
                selectedId = hit.uuid
                draft = hit
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                onPointSelected?.invoke(hit.uuid)
                updateContentDescription()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draft == null || event.findPointerIndex(activePointerId) < 0) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (!dragging && hypot(x - downX, y - downY) >= touchSlop) dragging = true
                if (dragging) {
                    val original = committedBands.firstOrNull { it.uuid == selectedId } ?: return true
                    draft = PeqGraphMath.draggedBand(
                        original,
                        (x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (y / height.coerceAtLeast(1)).coerceIn(0f, 1f),
                        maximumFrequency,
                    )
                    renderBands = committedBands.map { if (it.uuid == selectedId) draft!! else it }
                    recomputeResponses()
                    updateContentDescription()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (draft == null) return false
                val committedDraft = draft
                val shouldCommit = dragging
                parent?.requestDisallowInterceptTouchEvent(false)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                dragging = false
                if (shouldCommit && committedDraft != null) {
                    onDragCommitted?.invoke(committedDraft)
                } else {
                    draft = null
                    renderBands = committedBands
                    recomputeResponses()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDraft()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        if (showSpectrum && spectrumActive) drawSpectrum(canvas)
        buildPath(leftPath, leftGains)
        buildPath(rightPath, rightGains)

        if (showIndividualFilters) {
            overlayGains.forEachIndexed { index, gains ->
                while (overlayPaths.size <= index) overlayPaths.add(Path())
                val path = overlayPaths[index]
                buildPath(path, gains, includePreamp = false)
                canvas.drawPath(
                    path,
                    if (renderBands.getOrNull(index)?.uuid == selectedId) selectedOverlayPaint else overlayPaint,
                )
            }
        }
        if (channelDisplay != ChannelDisplay.RIGHT) canvas.drawPath(leftPath, leftPaint)
        if (channelDisplay != ChannelDisplay.LEFT) canvas.drawPath(rightPath, rightPaint)
        drawPoints(canvas)
        drawChannelLabels(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        FREQ_SCALE.forEach { frequency ->
            val x = PeqGraphMath.frequencyToFraction(frequency, PeqGraphMath.MIN_FREQUENCY, maximumFrequency) * width
            canvas.drawText(frequency.prettyNumberFormat(), x, height - 4f * density, textPaint)
        }
        var gain = PeqGraphMath.MIN_GAIN.toInt() + 3
        while (gain < PeqGraphMath.MAX_GAIN) {
            val y = PeqGraphMath.gainToFraction(gain.toDouble()) * height
            canvas.drawLine(0f, y, width.toFloat(), y, if (gain == 0) zeroPaint else gridPaint)
            gain += 3
        }
    }

    private fun drawSpectrum(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        spectrumStrokePath.rewind()
        spectrumFillPath.rewind()
        spectrumFillPath.moveTo(0f, h)
        val dbSpan = SpectrumEngine.CEILING_DB - SpectrumEngine.FLOOR_DB
        for (i in 0..SPECTRUM_STEPS) {
            val fraction = i / SPECTRUM_STEPS.toFloat()
            val freq = PeqGraphMath.fractionToFrequency(fraction, PeqGraphMath.MIN_FREQUENCY, maximumFrequency)
            val db = SpectrumEngine.magnitudeDbAt(freq)
            val x = fraction * w
            val y = h * (SpectrumEngine.CEILING_DB - db) / dbSpan
            if (i == 0) spectrumStrokePath.moveTo(x, y) else spectrumStrokePath.lineTo(x, y)
            spectrumFillPath.lineTo(x, y)
        }
        spectrumFillPath.lineTo(w, h)
        spectrumFillPath.close()
        canvas.drawPath(spectrumFillPath, spectrumFillPaint)
        canvas.drawPath(spectrumStrokePath, spectrumStrokePaint)
    }

    private fun drawPoints(canvas: Canvas) {
        renderBands.forEachIndexed { index, band ->
            val x = PeqGraphMath.frequencyToFraction(
                band.frequency, PeqGraphMath.MIN_FREQUENCY, maximumFrequency
            ) * width
            val y = PeqGraphMath.gainToFraction(band.gain) * height
            val selected = band.uuid == selectedId
            val radius = (if (selected) 10f else 8f) * density
            canvas.drawCircle(x, y, radius, pointPaint)
            if (selected) canvas.drawCircle(x, y, radius + 4f * density, selectedPointPaint)
            val baseline = y - (pointTextPaint.ascent() + pointTextPaint.descent()) / 2
            canvas.drawText((index + 1).toString(), x, baseline, pointTextPaint)
        }
    }

    private fun drawChannelLabels(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.LEFT
        val label = when (channelDisplay) {
            ChannelDisplay.BOTH -> "L solid   R dashed"
            ChannelDisplay.LEFT -> "Left"
            ChannelDisplay.RIGHT -> "Right"
        }
        canvas.drawText(label, 8f * density, 14f * density, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun hitTest(x: Float, y: Float): ParametricEqBand? {
        val radius = 24f * density
        return renderBands.map { band ->
            val pointX = PeqGraphMath.frequencyToFraction(
                band.frequency, PeqGraphMath.MIN_FREQUENCY, maximumFrequency
            ) * width
            val pointY = PeqGraphMath.gainToFraction(band.gain) * height
            band to hypot(x - pointX, y - pointY)
        }.filter { it.second <= radius }.minByOrNull { it.second }?.first
    }

    private fun recomputeResponses() {
        fun response(channel: ParametricEqChannel) = BiquadUtils.computeCombinedResponse(
            renderBands,
            numPoints = POINT_COUNT,
            minFreq = PeqGraphMath.MIN_FREQUENCY,
            maxFreq = maximumFrequency,
            sampleRate = sampleRate,
            channel = channel,
        )
        val left = response(ParametricEqChannel.LEFT)
        val right = response(ParametricEqChannel.RIGHT)
        val source = if (left.isNotEmpty()) left else right
        frequencies = DoubleArray(source.size) { source[it].first }
        leftGains = DoubleArray(frequencies.size) { left.getOrNull(it)?.second ?: 0.0 }
        rightGains = DoubleArray(frequencies.size) { right.getOrNull(it)?.second ?: 0.0 }
        overlayGains = renderBands.map { band ->
            val channel = when {
                channelDisplay == ChannelDisplay.LEFT -> ParametricEqChannel.LEFT
                channelDisplay == ChannelDisplay.RIGHT -> ParametricEqChannel.RIGHT
                band.channel == ParametricEqChannel.RIGHT -> ParametricEqChannel.RIGHT
                else -> ParametricEqChannel.LEFT
            }
            val result = BiquadUtils.computeCombinedResponse(
                listOf(band), POINT_COUNT, PeqGraphMath.MIN_FREQUENCY,
                maximumFrequency, sampleRate, channel,
            )
            DoubleArray(frequencies.size) { result.getOrNull(it)?.second ?: 0.0 }
        }
        invalidate()
    }

    private fun buildPath(path: Path, gains: DoubleArray, includePreamp: Boolean = true) {
        path.rewind()
        if (frequencies.isEmpty() || gains.size != frequencies.size) {
            val y = PeqGraphMath.gainToFraction(if (includePreamp) preampDb else 0.0) * height
            path.moveTo(0f, y)
            path.lineTo(width.toFloat(), y)
            return
        }
        gains.indices.forEach { index ->
            val x = PeqGraphMath.frequencyToFraction(
                frequencies[index], PeqGraphMath.MIN_FREQUENCY, maximumFrequency
            ) * width
            val y = PeqGraphMath.gainToFraction(
                gains[index] + if (includePreamp) preampDb else 0.0
            ) * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private fun updateContentDescription() {
        val band = renderBands.firstOrNull { it.uuid == selectedId }
        contentDescription = if (band == null) {
            "Interactive parametric equalizer graph, ${renderBands.size} filters"
        } else {
            val number = renderBands.indexOfFirst { it.uuid == band.uuid } + 1
            "Selected filter $number, ${band.filterType.displayLabel}, " +
                "${band.frequency.toInt()} hertz, ${"%.1f".format(band.gain)} decibels, " +
                "Q ${"%.2f".format(band.q)}, ${band.channel.displayLabel}"
        }
    }

    private fun themeColor(attribute: Int): Int {
        if (isInEditMode) return Color.BLACK
        var color = Color.BLACK
        context.withStyledAttributes(TypedValue().data, intArrayOf(attribute)) {
            color = getColor(0, Color.BLACK)
        }
        return color
    }

    companion object {
        private const val POINT_COUNT = 256
        private const val SPECTRUM_STEPS = 160
        private val FREQ_SCALE = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
        )
    }
}
