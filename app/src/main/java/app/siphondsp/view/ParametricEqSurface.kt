package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwPeqBank
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.utils.extensions.prettyNumberFormat
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min

/**
 * PEQ graph view with two personalities, selected by [surfaceMode]:
 *
 * - [SurfaceMode.PEQ_ONLY] (default): the original intent-only single-bank PEQ graph.
 *   Committed bands are supplied by the caller; the view owns only a single visual drag
 *   draft and never persists or configures audio. Used by non-interactive/legacy
 *   consumers (live EQ preview, graphic EQ, preference dialogs) -- this mode's behavior
 *   is unchanged from before the unified-visualiser rework.
 * - [SurfaceMode.UNIFIED_SYSTEM]: the full-screen PEQ editor's unified BMW response
 *   visualiser -- shows the complete modelled signal chain (via [BmwResponseCalculator]),
 *   draggable nodes for all three PEQ banks simultaneously (only the active bank's nodes
 *   are draggable), and draggable tilt handles. Still intent-only: nothing here writes to
 *   SharedPreferences or configures the native engine directly, everything flows back
 *   through [onDragCommitted]/[onTiltDragCommitted] for the caller to validate and apply.
 */
class ParametricEqSurface(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class ChannelDisplay { BOTH, LEFT, RIGHT }
    enum class SurfaceMode { PEQ_ONLY, UNIFIED_SYSTEM }
    private enum class TiltHandle { PIVOT, AMOUNT }

    var onPointSelected: ((UUID) -> Unit)? = null
    var onDragCommitted: ((ParametricEqBand) -> Unit)? = null
    var onTiltDragCommitted: ((tiltFrequencyHz: Float, tiltAmountDb: Float) -> Unit)? = null
    var onTiltDragPreview: ((tiltFrequencyHz: Float, tiltAmountDb: Float) -> Unit)? = null

    var surfaceMode: SurfaceMode = SurfaceMode.PEQ_ONLY
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var showTiltHandles = false
        set(value) {
            field = value
            invalidate()
        }
    var showGainMeters = false
        set(value) {
            field = value
            invalidate()
        }
    // Static/read-only consumers (e.g. the Settings preview row) set this false so the view
    // never starts a drag -- onTouchEvent then declines every event, letting it bubble up to
    // whatever click handling the parent (e.g. a Preference row) wants to do instead.
    var interactive = true

    private val density = resources.displayMetrics.density

    // --- PEQ_ONLY paints (unchanged) -----------------------------------------------------
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

    // --- Shared band-drag state (used by both modes; UNIFIED_SYSTEM always represents
    // only the active bank's bands here, mirroring how the fragment already scopes
    // committedBands/renderBands per bank) --------------------------------------------
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
    private val levelScratch = FloatArray(4)
    private val leftMeter = PeakHoldMeter(floorDb = SpectrumEngine.LEVEL_FLOOR_DB)
    private val rightMeter = PeakHoldMeter(floorDb = SpectrumEngine.LEVEL_FLOOR_DB)
    private val spectrumTick = object : Runnable {
        override fun run() {
            if (showGainMeters) updateGainMeters()
            invalidate()
            spectrumHandler.postDelayed(this, 33L)
        }
    }

    private fun updateGainMeters() {
        SpectrumEngine.channelLevelsInto(levelScratch)
        val now = System.currentTimeMillis()
        leftMeter.update(levelScratch[0], levelScratch[1], now)
        rightMeter.update(levelScratch[2], levelScratch[3], now)
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
        leftMeter.reset()
        rightMeter.reset()
    }

    // --- UNIFIED_SYSTEM state -----------------------------------------------------------
    private var systemValues: FloatArray = FloatArray(BmwSignalChain.VALUE_COUNT)
    private var peqState: BmwPeqState = BmwPeqState.empty()
    private var activeBank: BmwPeqBank = BmwPeqBank.FULL
    private var allFullRangeBands: List<ParametricEqBand> = emptyList()
    private var allLowBandBands: List<ParametricEqBand> = emptyList()
    private var allMidBandBands: List<ParametricEqBand> = emptyList()
    private val calculator = BmwResponseCalculator(pointCount = SYSTEM_POINT_COUNT)
    private val curves = BmwResponseCurves(SYSTEM_POINT_COUNT)
    private var tiltDraft: BmwGraphGestureMath.TiltValues? = null
    private var tiltDragHandle: TiltHandle? = null

    // Dark, fixed palette for the unified visualiser (deliberate design commitment, matching
    // the existing compressor visualisers' dark aesthetic rather than following the light/
    // dark app theme -- PEQ_ONLY's themeColor()-based paints above are unaffected).
    private val bgTopColor = Color.rgb(20, 21, 24)
    private val bgBottomColor = Color.rgb(13, 13, 15)
    private val unifiedGridColor = Color.rgb(58, 60, 66)
    private val unifiedTextColor = Color.rgb(176, 178, 186)
    private val bankColorFull = Color.rgb(230, 232, 238)
    private val bankColorLow = Color.rgb(88, 164, 255)
    private val bankColorMid = Color.rgb(238, 164, 64)
    private val sumColor = Color.rgb(255, 255, 255)

    private var backgroundShader: LinearGradient? = null
    private val backgroundPaint = Paint()
    private val unifiedGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = unifiedGridColor
    }
    private val unifiedZeroPaint = Paint(unifiedGridPaint).apply { strokeWidth = 1.6f * density; alpha = 200 }
    private val unifiedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textSize = 9.5f * density
    }
    private val unifiedLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textSize = 10.5f * density
    }
    private val crossoverShadePaint = Paint().apply {
        style = Paint.Style.FILL
        color = unifiedTextColor
        alpha = 18
    }
    private val lowBranchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = bankColorLow
        alpha = 150
    }
    private val midBranchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = bankColorMid
        alpha = 150
    }
    private val sumPaintSolid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        color = sumColor
    }
    private val sumPaintDashed = Paint(sumPaintSolid).apply {
        alpha = 200
        pathEffect = DashPathEffect(floatArrayOf(9f * density, 6f * density), 0f)
    }
    // Light blue (matches the app's accent colour) -- the grey used everywhere else in this
    // unified view reads as barely-visible background noise for a live spectrum trace.
    private val spectrumAccentColor = Color.rgb(79, 195, 247)
    private val unifiedSpectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = spectrumAccentColor
        alpha = 40
    }
    private val unifiedSpectrumStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = spectrumAccentColor
        alpha = 190
    }
    private val unifiedOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        alpha = 90
    }
    private val unifiedOverlayDashEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    private val nodeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; alpha = 90 }
    private val nodeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = bgBottomColor
    }
    private val nodeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * density
    }
    private val tiltHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor(android.R.attr.colorAccent)
    }
    private val tiltHandleDimPaint = Paint(tiltHandlePaint).apply { alpha = 100 }
    private val tiltLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textAlign = Paint.Align.LEFT
        textSize = 9.5f * density
    }
    private val meterTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = unifiedGridColor
    }
    private val meterRmsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bankColorLow
    }
    private val meterPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = sumColor
    }
    private val meterHoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = sumColor
    }
    private val meterLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * density
    }

    private val padLeft = 34f * density
    private val padTop = 16f * density
    private val padRight = 44f * density
    private val padBottom = 22f * density

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Interactive parametric equalizer graph"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (showSpectrum) startSpectrum()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            backgroundShader = LinearGradient(0f, 0f, 0f, h.toFloat(), bgTopColor, bgBottomColor, Shader.TileMode.CLAMP)
            backgroundPaint.shader = backgroundShader
        }
    }

    // --- PEQ_ONLY public API (unchanged) -------------------------------------------------

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
        tiltDraft = null
        tiltDragHandle = null
        renderBands = committedBands
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        recomputeResponses()
        if (surfaceMode == SurfaceMode.UNIFIED_SYSTEM) {
            calculator.invalidateAll()
            recomputeSystemResponse()
        }
        invalidate()
    }

    fun hasActiveDraft(): Boolean = draft != null || tiltDraft != null

    override fun onDetachedFromWindow() {
        cancelDraft()
        stopSpectrum()
        super.onDetachedFromWindow()
    }

    // --- UNIFIED_SYSTEM public API -------------------------------------------------------

    /**
     * Full rebind for the unified surface: [values] is the 35-float native BMW DSP config
     * array, [peq] the already-loaded three-bank PEQ state, [activeBank] which bank's nodes
     * are draggable right now. Mirrors [setBands] for the active bank's list so dragging
     * behaves identically to PEQ_ONLY; the other two banks are stored read-only for display.
     */
    fun setSystemState(
        values: FloatArray,
        peq: BmwPeqState,
        activeBank: BmwPeqBank,
        selectedId: UUID?,
        sampleRate: Double,
    ) {
        if (values.size != BmwSignalChain.VALUE_COUNT) return
        systemValues = values.copyOf()
        peqState = peq
        this.activeBank = activeBank
        allFullRangeBands = peq.fullRangeBands.toList()
        allLowBandBands = peq.lowBandBands.toList()
        allMidBandBands = peq.midBandBands.toList()
        tiltDraft = null
        tiltDragHandle = null

        val activeBands = when (activeBank) {
            BmwPeqBank.FULL -> allFullRangeBands
            BmwPeqBank.LOW -> allLowBandBands
            BmwPeqBank.MID -> allMidBandBands
        }
        val activePreamp = if (activeBank == BmwPeqBank.FULL) peq.preampDb.toDouble() else 0.0
        setBands(
            ParametricEqBandList().apply { addAll(activeBands) },
            preampDb = activePreamp,
            selectedId = selectedId,
            sampleRate = sampleRate,
        )
        calculator.invalidateAll()
        recomputeSystemResponse()
    }

    /** Cheap partial update when only the 35-float config changed (e.g. an external broadcast). */
    fun setSystemValues(values: FloatArray) {
        if (values.size != BmwSignalChain.VALUE_COUNT) return
        systemValues = values.copyOf()
        tiltDraft = null
        tiltDragHandle = null
        calculator.invalidateAll()
        recomputeSystemResponse()
        invalidate()
    }

    private fun peqStateForDisplay(): BmwPeqState {
        val activeList = ParametricEqBandList().apply { addAll(renderBands) }
        return when (activeBank) {
            BmwPeqBank.FULL -> peqState.copy(fullRangeBands = activeList)
            BmwPeqBank.LOW -> peqState.copy(lowBandBands = activeList)
            BmwPeqBank.MID -> peqState.copy(midBandBands = activeList)
        }
    }

    private fun valuesForDisplay(): FloatArray {
        val draft = tiltDraft ?: return systemValues
        val copy = systemValues.copyOf()
        copy[NativeBmwDspValues.INDEX_TILT_AMOUNT] = draft.amountDb
        copy[NativeBmwDspValues.INDEX_TILT_FREQ] = draft.frequencyHz
        return copy
    }

    private fun recomputeSystemResponse() {
        val maxFreq = min(20_000.0, sampleRate * 0.5 * 0.999)
        calculator.configureAxis(sampleRate, 20.0, maxFreq)
        calculator.compute(valuesForDisplay(), peqStateForDisplay(), curves)
        invalidate()
    }

    private fun currentTiltValues(): BmwGraphGestureMath.TiltValues =
        BmwGraphGestureMath.TiltValues(systemValues[NativeBmwDspValues.INDEX_TILT_FREQ], systemValues[NativeBmwDspValues.INDEX_TILT_AMOUNT])

    // --- Shared touch handling -----------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        if (event.pointerCount > 1) {
            cancelDraft()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return handleActionDown(event)
            MotionEvent.ACTION_MOVE -> return handleActionMove(event)
            MotionEvent.ACTION_UP -> return handleActionUp()
            MotionEvent.ACTION_CANCEL -> {
                cancelDraft()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleActionDown(event: MotionEvent): Boolean {
        val hit = hitTest(event.x, event.y)
        if (hit != null) {
            selectedId = hit.uuid
            draft = hit
            tiltDraft = null
            tiltDragHandle = null
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
        if (surfaceMode == SurfaceMode.UNIFIED_SYSTEM && showTiltHandles && systemValues[NativeBmwDspValues.INDEX_TILT_ENABLED] >= .5f) {
            val handle = hitTestTiltHandle(event.x, event.y)
            if (handle != null) {
                tiltDragHandle = handle
                tiltDraft = currentTiltValues()
                draft = null
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun handleActionMove(event: MotionEvent): Boolean {
        if ((draft == null && tiltDraft == null) || event.findPointerIndex(activePointerId) < 0) return false
        val pointerIndex = event.findPointerIndex(activePointerId)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (!dragging && hypot(x - downX, y - downY) >= touchSlop) dragging = true
        if (!dragging) return true

        if (draft != null) {
            val original = committedBands.firstOrNull { it.uuid == selectedId } ?: return true
            draft = PeqGraphMath.draggedBand(
                original,
                (x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
                (y / height.coerceAtLeast(1)).coerceIn(0f, 1f),
                maximumFrequency,
            )
            renderBands = committedBands.map { if (it.uuid == selectedId) draft!! else it }
            recomputeResponses()
            if (surfaceMode == SurfaceMode.UNIFIED_SYSTEM) {
                calculator.invalidateBank(activeBank)
                recomputeSystemResponse()
            }
            updateContentDescription()
        } else {
            val handle = tiltDragHandle ?: return true
            val base = tiltDraft ?: return true
            val plotW = (plotRight() - plotLeft()).coerceAtLeast(1f)
            val plotH = (plotBottom() - plotTop()).coerceAtLeast(1f)
            val xFraction = ((x - plotLeft()) / plotW).coerceIn(0f, 1f)
            val yFraction = ((y - plotTop()) / plotH).coerceIn(0f, 1f)
            val updated = when (handle) {
                TiltHandle.PIVOT -> BmwGraphGestureMath.draggedTiltFrequency(base, xFraction)
                TiltHandle.AMOUNT -> BmwGraphGestureMath.draggedTiltAmount(base, yFraction)
            }
            tiltDraft = updated
            onTiltDragPreview?.invoke(updated.frequencyHz, updated.amountDb)
            calculator.invalidate(BmwResponseCalculator.Stage.TILT)
            recomputeSystemResponse()
        }
        return true
    }

    private fun handleActionUp(): Boolean {
        if (draft == null && tiltDraft == null) return false
        parent?.requestDisallowInterceptTouchEvent(false)
        val wasDragging = dragging
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragging = false

        if (draft != null) {
            val committedDraft = draft
            if (wasDragging && committedDraft != null) {
                onDragCommitted?.invoke(committedDraft)
            } else {
                draft = null
                renderBands = committedBands
                recomputeResponses()
                if (surfaceMode == SurfaceMode.UNIFIED_SYSTEM) recomputeSystemResponse()
            }
        } else {
            val committedTilt = tiltDraft
            if (wasDragging && committedTilt != null) {
                onTiltDragCommitted?.invoke(committedTilt.frequencyHz, committedTilt.amountDb)
            } else {
                tiltDraft = null
                tiltDragHandle = null
                calculator.invalidate(BmwResponseCalculator.Stage.TILT)
                recomputeSystemResponse()
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // --- Coordinate mapping (UNIFIED_SYSTEM only; PEQ_ONLY keeps its own edge-to-edge math) --

    private fun plotLeft(): Float = paddingLeft + padLeft
    private fun plotRight(): Float = width - paddingRight - padRight
    private fun plotTop(): Float = paddingTop + padTop
    private fun plotBottom(): Float = height - paddingBottom - padBottom

    private fun xForFrequency(frequency: Double): Float {
        val fraction = PeqGraphMath.frequencyToFraction(frequency, PeqGraphMath.MIN_FREQUENCY, maximumFrequency)
        return plotLeft() + fraction * (plotRight() - plotLeft())
    }

    private fun yForGain(gain: Double): Float {
        val fraction = PeqGraphMath.gainToFraction(gain)
        return plotTop() + fraction * (plotBottom() - plotTop())
    }

    private fun hitTestTiltHandle(x: Float, y: Float): TiltHandle? {
        val tilt = currentTiltValues()
        val pivotX = xForFrequency(tilt.frequencyHz.toDouble())
        val pivotY = yForGain(0.0)
        val amountX = pivotX + 22f * density
        val amountY = yForGain(tilt.amountDb.toDouble())
        if (hypot(x - amountX, y - amountY) <= TILT_HANDLE_RADIUS_DP * density) return TiltHandle.AMOUNT
        if (hypot(x - pivotX, y - pivotY) <= TILT_HANDLE_RADIUS_DP * density) return TiltHandle.PIVOT
        return null
    }

    // --- Drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (surfaceMode == SurfaceMode.UNIFIED_SYSTEM) {
            drawUnifiedSystem(canvas)
        } else {
            drawPeqOnly(canvas)
        }
    }

    private fun drawPeqOnly(canvas: Canvas) {
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

    private fun drawUnifiedSystem(canvas: Canvas) {
        val left = plotLeft()
        val right = plotRight()
        val top = plotTop()
        val bottom = plotBottom()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (right <= left || bottom <= top) return

        drawUnifiedGrid(canvas, left, right, top, bottom)
        drawCrossoverShading(canvas, left, right, top, bottom)
        if (showSpectrum && spectrumActive) drawUnifiedSpectrum(canvas, left, right, top, bottom)
        drawBranchCurves(canvas, left, right, top, bottom)
        drawActiveBankOverlays(canvas, left, right, top, bottom)
        drawSumCurve(canvas, left, right, top, bottom)
        if (showTiltHandles) drawTiltHandles(canvas)
        drawMultiBankNodes(canvas)
        if (showGainMeters) drawGainMeters(canvas, right, top, bottom)
        drawUnifiedLegend(canvas, left, top)
    }

    private fun drawGainMeters(canvas: Canvas, plotRight: Float, top: Float, bottom: Float) {
        val barWidth = 8f * density
        val gap = 4f * density
        val leftBarX = plotRight + 5f * density
        val rightBarX = leftBarX + barWidth + gap
        drawMeterBar(canvas, leftBarX, top, bottom, barWidth, leftMeter)
        drawMeterBar(canvas, rightBarX, top, bottom, barWidth, rightMeter)
        canvas.drawText("L", leftBarX + barWidth / 2f, bottom + 15f * density, meterLabelPaint)
        canvas.drawText("R", rightBarX + barWidth / 2f, bottom + 15f * density, meterLabelPaint)
    }

    private fun drawMeterBar(canvas: Canvas, x: Float, top: Float, bottom: Float, width: Float, meter: PeakHoldMeter) {
        canvas.drawRect(x, top, x + width, bottom, meterTrackPaint)
        val rmsFraction = PeakHoldMeter.fractionFor(meter.rmsDb, METER_FLOOR_DB, METER_CEILING_DB)
        val rmsY = bottom - rmsFraction * (bottom - top)
        canvas.drawRect(x, rmsY, x + width, bottom, meterRmsPaint)
        val peakFraction = PeakHoldMeter.fractionFor(meter.peakDb, METER_FLOOR_DB, METER_CEILING_DB)
        val peakY = bottom - peakFraction * (bottom - top)
        canvas.drawLine(x, peakY, x + width, peakY, meterPeakPaint)
        val holdFraction = PeakHoldMeter.fractionFor(meter.holdDb, METER_FLOOR_DB, METER_CEILING_DB)
        val holdY = (bottom - holdFraction * (bottom - top)).coerceIn(top, bottom - 1.5f * density)
        canvas.drawRect(x, holdY - 1.5f * density, x + width, holdY + 1.5f * density, meterHoldPaint)
    }

    private fun drawUnifiedGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f).forEach { db ->
            val y = yForGain(db.toDouble())
            canvas.drawLine(left, y, right, y, if (db == 0f) unifiedZeroPaint else unifiedGridPaint)
            canvas.drawText("${db.toInt()}", 4f * density, y + 3f * density, unifiedLabelPaint)
        }
        FREQ_SCALE.forEach { frequency ->
            val x = xForFrequency(frequency)
            canvas.drawLine(x, top, x, bottom, unifiedGridPaint)
            val label = frequency.prettyNumberFormat()
            canvas.drawText(label, x - unifiedLabelPaint.measureText(label) / 2f, bottom + 15f * density, unifiedLabelPaint)
        }
    }

    private fun drawCrossoverShading(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val values = systemValues
        if (values[1] >= .5f || values[2] >= .5f) return
        val lowFreq = values[15].toDouble().coerceIn(20.0, maximumFrequency)
        val midFreq = values[18].toDouble().coerceIn(20.0, maximumFrequency)
        if (midFreq <= lowFreq) return
        canvas.drawRect(xForFrequency(lowFreq).coerceIn(left, right), top, xForFrequency(midFreq).coerceIn(left, right), bottom, crossoverShadePaint)
    }

    private fun drawUnifiedSpectrum(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        spectrumStrokePath.rewind()
        spectrumFillPath.rewind()
        spectrumFillPath.moveTo(left, bottom)
        for (i in 0..SPECTRUM_STEPS) {
            val fraction = i / SPECTRUM_STEPS.toFloat()
            val freq = PeqGraphMath.fractionToFrequency(fraction, PeqGraphMath.MIN_FREQUENCY, maximumFrequency)
            val spectrumDb = SpectrumEngine.magnitudeDbAt(freq)
            val graphGain = PeqGraphMath.spectrumDbToGraphGain(spectrumDb, SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB)
            val x = left + fraction * (right - left)
            val y = yForGain(graphGain)
            if (i == 0) spectrumStrokePath.moveTo(x, y) else spectrumStrokePath.lineTo(x, y)
            spectrumFillPath.lineTo(x, y)
        }
        spectrumFillPath.lineTo(right, bottom)
        spectrumFillPath.close()
        canvas.drawPath(spectrumFillPath, unifiedSpectrumFillPaint)
        canvas.drawPath(spectrumStrokePath, unifiedSpectrumStrokePaint)
    }

    private fun drawBranchCurves(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        drawSystemCurve(canvas, curves.lowBranchDb, left, right, lowBranchPaint)
        drawSystemCurve(canvas, curves.midBranchDb, left, right, midBranchPaint)
    }

    private fun drawSumCurve(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        drawSystemCurveForChannel(canvas, curves.sumDb[BmwOutputChannel.LEFT.ordinal], left, right, sumPaintSolid)
        if (channelDisplay != ChannelDisplay.LEFT) {
            drawSystemCurveForChannel(canvas, curves.sumDb[BmwOutputChannel.RIGHT.ordinal], left, right, sumPaintDashed)
        }
    }

    private fun drawSystemCurve(canvas: Canvas, perChannelDb: Array<DoubleArray>, left: Float, right: Float, paint: Paint) {
        // Branch curves show the arithmetic mean of L/R (they are context, not the primary
        // per-channel readout -- the dominant sum curve below shows true L/R separation).
        val leftDb = perChannelDb[BmwOutputChannel.LEFT.ordinal]
        val rightDb = perChannelDb[BmwOutputChannel.RIGHT.ordinal]
        if (leftDb.isEmpty()) return
        val path = Path()
        for (i in leftDb.indices) {
            val avg = (leftDb[i] + rightDb[i]) * 0.5
            val x = left + (i.toFloat() / (leftDb.size - 1).coerceAtLeast(1)) * (right - left)
            val y = yForGain(avg)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSystemCurveForChannel(canvas: Canvas, db: DoubleArray, left: Float, right: Float, paint: Paint) {
        if (db.isEmpty()) return
        val path = Path()
        for (i in db.indices) {
            val x = left + (i.toFloat() / (db.size - 1).coerceAtLeast(1)) * (right - left)
            val y = yForGain(db[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawActiveBankOverlays(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (!showIndividualFilters) return
        renderBands.forEach { band ->
            val response = BiquadUtils.computeCombinedResponse(
                listOf(band), OVERLAY_POINT_COUNT, PeqGraphMath.MIN_FREQUENCY, maximumFrequency, sampleRate, band.channel,
            )
            if (response.isEmpty()) return@forEach
            val path = Path()
            response.forEachIndexed { index, (_, gain) ->
                val x = left + (index.toFloat() / (response.size - 1).coerceAtLeast(1)) * (right - left)
                val y = yForGain(gain)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            unifiedOverlayPaint.color = colorForBand(band, activeBank)
            unifiedOverlayPaint.alpha = if (band.uuid == selectedId) 200 else 70
            unifiedOverlayPaint.pathEffect = if (band.channel == ParametricEqChannel.RIGHT) unifiedOverlayDashEffect else null
            canvas.drawPath(path, unifiedOverlayPaint)
        }
    }

    private fun drawMultiBankNodes(canvas: Canvas) {
        if (activeBank != BmwPeqBank.FULL) drawInactiveBankNodes(canvas, allFullRangeBands, BmwPeqBank.FULL)
        if (activeBank != BmwPeqBank.LOW) drawInactiveBankNodes(canvas, allLowBandBands, BmwPeqBank.LOW)
        if (activeBank != BmwPeqBank.MID) drawInactiveBankNodes(canvas, allMidBandBands, BmwPeqBank.MID)
        drawActiveBankNodes(canvas)
    }

    private fun drawInactiveBankNodes(canvas: Canvas, bands: List<ParametricEqBand>, bank: BmwPeqBank) {
        bands.forEach { band ->
            nodeDimPaint.color = colorForBand(band, bank)
            val x = xForFrequency(band.frequency)
            val y = yForGain(band.gain)
            canvas.drawCircle(x, y, INACTIVE_NODE_RADIUS_DP * density, nodeDimPaint)
            if (band.channel == ParametricEqChannel.RIGHT) {
                canvas.drawCircle(x, y, INACTIVE_NODE_RADIUS_DP * density, nodeRingPaint)
            }
        }
    }

    private fun drawActiveBankNodes(canvas: Canvas) {
        renderBands.forEachIndexed { index, band ->
            val color = colorForBand(band, activeBank)
            nodeFillPaint.color = color
            nodeHaloPaint.color = color
            val x = xForFrequency(band.frequency)
            val y = yForGain(band.gain)
            val selected = band.uuid == selectedId
            if (selected) {
                nodeHaloPaint.alpha = 60
                canvas.drawCircle(x, y, ACTIVE_NODE_RADIUS_DP * density + 8f * density, nodeHaloPaint)
            }
            val radius = (if (selected) ACTIVE_NODE_RADIUS_DP + 1.5f else ACTIVE_NODE_RADIUS_DP) * density
            canvas.drawCircle(x, y, radius, nodeFillPaint)
            // Right-only bands get a dark ring on top of the fill -- same solid(L)/marked(R)
            // convention as the dashed R sum curve -- since color alone is hard to read at
            // this size for colorblind users and small screens.
            if (band.channel == ParametricEqChannel.RIGHT) {
                canvas.drawCircle(x, y, radius, nodeRingPaint)
            }
            val baseline = y - (nodeTextPaint.ascent() + nodeTextPaint.descent()) / 2
            canvas.drawText((index + 1).toString(), x, baseline, nodeTextPaint)
        }
    }

    private fun drawTiltHandles(canvas: Canvas) {
        val tilt = tiltDraft ?: currentTiltValues()
        val enabled = systemValues[NativeBmwDspValues.INDEX_TILT_ENABLED] >= .5f
        val pivotX = xForFrequency(tilt.frequencyHz.toDouble())
        val pivotY = yForGain(0.0)
        val amountX = pivotX + 22f * density
        val amountY = yForGain(tilt.amountDb.toDouble())
        val paint = if (enabled) tiltHandlePaint else tiltHandleDimPaint

        canvas.drawLine(pivotX, amountY, amountX, amountY, paint)
        canvas.drawLine(pivotX, pivotY, pivotX, amountY, paint)

        // Pivot: diamond marker (frequency-only drag).
        val r = TILT_HANDLE_DRAW_RADIUS_DP * density
        val diamond = Path().apply {
            moveTo(pivotX, pivotY - r)
            lineTo(pivotX + r, pivotY)
            lineTo(pivotX, pivotY + r)
            lineTo(pivotX - r, pivotY)
            close()
        }
        canvas.drawPath(diamond, paint)

        // Amount: circular marker (amount-only drag).
        canvas.drawCircle(amountX, amountY, r, paint)

        if (!enabled) {
            canvas.drawText("TILT BYPASSED", pivotX + r + 6f * density, pivotY - 6f * density, tiltLabelPaint)
        }
    }

    private fun drawUnifiedLegend(canvas: Canvas, left: Float, top: Float) {
        val baseline = top - 6f * density
        val fullPaint = Paint(unifiedLegendPaint).apply { color = bankColorFull }
        val lowPaint = Paint(unifiedLegendPaint).apply { color = bankColorLow }
        val midPaint = Paint(unifiedLegendPaint).apply { color = bankColorMid }
        canvas.drawText("FULL", left, baseline, fullPaint)
        canvas.drawText("LOW", left + 38f * density, baseline, lowPaint)
        canvas.drawText("MID", left + 74f * density, baseline, midPaint)
        canvas.drawText(
            "FINAL SUM (L solid / R dashed) · compressor not shown (nonlinear)",
            left + 112f * density,
            baseline,
            unifiedLegendPaint,
        )
    }

    private fun colorForBank(bank: BmwPeqBank): Int = when (bank) {
        BmwPeqBank.FULL -> bankColorFull
        BmwPeqBank.LOW -> bankColorLow
        BmwPeqBank.MID -> bankColorMid
    }

    /**
     * Bank color alone doesn't distinguish a band's channel, so a Left and a Right filter in
     * the same bank used to render identically -- unreadable once a bank has independent L/R
     * bands. Right-only bands get a lightened tint of the bank color (plus a dashed overlay
     * line / dark ring on their node, applied by the callers) so channel is visible even
     * without color vision; Left-only and Left+Right bands keep the plain bank color.
     */
    private fun colorForBand(band: ParametricEqBand, bank: BmwPeqBank): Int {
        val base = colorForBank(bank)
        return if (band.channel == ParametricEqChannel.RIGHT) {
            ColorUtils.blendARGB(base, Color.WHITE, 0.4f)
        } else {
            base
        }
    }

    // --- PEQ_ONLY drawing helpers (unchanged) --------------------------------------------

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
            val pointX: Float
            val pointY: Float
            if (surfaceMode == SurfaceMode.UNIFIED_SYSTEM) {
                pointX = xForFrequency(band.frequency)
                pointY = yForGain(band.gain)
            } else {
                pointX = PeqGraphMath.frequencyToFraction(band.frequency, PeqGraphMath.MIN_FREQUENCY, maximumFrequency) * width
                pointY = PeqGraphMath.gainToFraction(band.gain) * height
            }
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
        private const val SYSTEM_POINT_COUNT = 192
        private const val OVERLAY_POINT_COUNT = 96
        private const val SPECTRUM_STEPS = 160
        private const val INACTIVE_NODE_RADIUS_DP = 4.5f
        private const val ACTIVE_NODE_RADIUS_DP = 8f
        private const val TILT_HANDLE_RADIUS_DP = 20f
        private const val TILT_HANDLE_DRAW_RADIUS_DP = 8f

        private const val METER_FLOOR_DB = -50f
        private const val METER_CEILING_DB = 0f

        private val FREQ_SCALE = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
        )
    }
}
