package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.dsp.BiquadCascade
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwPeqBank
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.dsp.ComplexAcc
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.utils.extensions.prettyNumberFormat
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The full-screen PEQ editor's unified BMW response visualiser -- shows the complete
 * modelled signal chain (via [BmwResponseCalculator]), draggable nodes for all three PEQ
 * banks simultaneously (only the active bank's nodes are draggable), and draggable tilt
 * handles. Intent-only: nothing here writes to SharedPreferences or configures the native
 * engine directly, everything flows back through [onDragCommitted]/[onTiltDragCommitted]
 * for the caller to validate and apply.
 */
class ParametricEqSurface(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class ChannelDisplay { BOTH, LEFT, RIGHT }
    enum class DisplayMode { MAGNITUDE, PHASE, MAGNITUDE_PHASE, GROUP_DELAY }
    private enum class TiltHandle { PIVOT, AMOUNT }

    var onPointSelected: ((UUID) -> Unit)? = null
    var onDragCommitted: ((ParametricEqBand) -> Unit)? = null
    var onTiltDragCommitted: ((tiltFrequencyHz: Float, tiltAmountDb: Float) -> Unit)? = null
    var onTiltDragPreview: ((tiltFrequencyHz: Float, tiltAmountDb: Float) -> Unit)? = null

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
    // UNIFIED_SYSTEM only. Node dragging and tilt handles are only meaningful against the
    // magnitude curve they were positioned on, so non-magnitude modes are read-only --
    // enforced in onTouchEvent, independent of [interactive] below (which callers use for a
    // different reason: a wholly static preview instance).
    var displayMode: DisplayMode = DisplayMode.MAGNITUDE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    // Static/read-only consumers (e.g. the Settings preview row) set this false so the view
    // never starts a drag -- onTouchEvent then declines every event, letting it bubble up to
    // whatever click handling the parent (e.g. a Preference row) wants to do instead.
    var interactive = true

    private val density = resources.displayMetrics.density

    // Dry (pre-DSP) reference trace -- dashed and faint so it reads as "before" rather than
    // competing with the live wet trace drawn on top of it.
    private val dryStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorSecondary)
        style = Paint.Style.STROKE
        strokeWidth = density
        alpha = 130
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    // Shaded gap between the dry and wet traces: green where the filters are adding energy at
    // that frequency right now, amber where they're cutting it. This is what actually shows what
    // the filters are doing to the live audio, rather than two independently overlaid lines.
    private val spectrumBoostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(102, 210, 130)
        style = Paint.Style.FILL
        alpha = 70
    }
    private val spectrumCutFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 140, 60)
        style = Paint.Style.FILL
        alpha = 70
    }
    private val spectrumStrokePath = Path()
    private val spectrumFillPath = Path()
    private val dryStrokePath = Path()
    private val deltaFillPath = Path()
    private val spectrumXs = FloatArray(SPECTRUM_STEPS + 1)
    private val spectrumDryYs = FloatArray(SPECTRUM_STEPS + 1)
    private val spectrumWetYs = FloatArray(SPECTRUM_STEPS + 1)

    // --- Band-drag state (UNIFIED_SYSTEM always represents only the active bank's bands
    // here, mirroring how the fragment already scopes committedBands/renderBands per bank) --
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

    // --- Node auto-hide: once bands are set (typed or dragged), node markers/labels stay
    // visible briefly then fade out, leaving just the curve. Re-armed by any touch drag or by
    // setBands()/setSystemState(), so it covers both drag edits and REW-value entry. ---------

    private val nodeFadeHandler = Handler(Looper.getMainLooper())
    private var lastNodeInteractionAtMs = 0L
    private val nodeFadeTick = object : Runnable {
        override fun run() {
            invalidate()
            if (nodeAlphaFraction() > 0f) nodeFadeHandler.postDelayed(this, 32L)
        }
    }

    private fun noteNodeInteraction() {
        lastNodeInteractionAtMs = SystemClock.uptimeMillis()
        nodeFadeHandler.removeCallbacks(nodeFadeTick)
        nodeFadeHandler.postDelayed(nodeFadeTick, 32L)
    }

    private fun nodeAlphaFraction(): Float {
        if (draft != null || tiltDraft != null) return 1f
        val sinceHold = SystemClock.uptimeMillis() - lastNodeInteractionAtMs - NODE_IDLE_HOLD_MS
        return when {
            sinceHold <= 0L -> 1f
            sinceHold >= NODE_FADE_DURATION_MS -> 0f
            else -> 1f - sinceHold.toFloat() / NODE_FADE_DURATION_MS
        }
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

    // No solid background fill any more (see drawUnifiedSystem) -- the workspace's own designed
    // background shows through instead. bgBottomColor survives as nodeRingPaint's stroke colour
    // below, a leftover dark tone that still reads correctly against either background.
    private val bgBottomColor = Color.rgb(13, 13, 15)
    private val unifiedGridColor = Color.rgb(58, 60, 66)
    private val unifiedTextColor = Color.rgb(176, 178, 186)
    private val bankColorFull = Color.rgb(230, 232, 238)
    private val bankColorLow = Color.rgb(88, 164, 255)
    private val bankColorMid = BmwDashboardSkin.MID_BAND_YELLOW
    private val sumColor = Color.rgb(255, 255, 255)

    // Per-band fill palette (FabFilter Pro-Q-style): each band gets its own colour, cycling by
    // index within renderBands -- the same index drawActiveBankNodes already uses for its "1",
    // "2", "3"... node labels, so a fill and its numbered node always match.
    private val perBandPalette = intArrayOf(
        Color.rgb(230, 76, 76),   // red
        Color.rgb(158, 74, 230),  // violet
        Color.rgb(74, 128, 230),  // blue
        Color.rgb(96, 191, 96),   // green
        Color.rgb(230, 158, 51),  // orange
        Color.rgb(51, 191, 191),  // teal
        Color.rgb(230, 102, 179), // pink
        Color.rgb(191, 191, 51),  // olive
    )

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
    // Per-band filled region + its outline (see drawPerBandFills) -- color AND alpha are both
    // set per band on every draw call (setColor() overwrites alpha too), so nothing meaningful
    // is configured here beyond style/stroke width.
    private val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bandStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    // Reused, cleared-and-rebuilt-per-band scratch cascade/accumulator for drawPerBandFills --
    // avoids allocating a new BiquadCascade/ComplexAcc for every band on every frame.
    private val bandCascade = BiquadCascade(1)
    private val bandAcc = ComplexAcc()
    private val fillX = FloatArray(SYSTEM_POINT_COUNT)
    private val fillTopY = FloatArray(SYSTEM_POINT_COUNT)
    private val fillBottomY = FloatArray(SYSTEM_POINT_COUNT)
    private val referenceCurveScratch = DoubleArray(SYSTEM_POINT_COUNT)

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
    // MAGNITUDE_PHASE overlay: same colour family as the sum curve it accompanies, thinner and
    // dashed so it reads as an annotation on its own implicit degree scale, not a second sum.
    private val sumPhaseOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = sumColor
        alpha = 170
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
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


    // --- Band state API ---------------------------------------------------------------

    private fun setBands(
        bands: ParametricEqBandList,
        selectedId: UUID? = this.selectedId,
        sampleRate: Double = this.sampleRate,
    ) {
        committedBands = bands.toList()
        renderBands = committedBands
        draft = null
        dragging = false
        this.selectedId = selectedId?.takeIf { id -> committedBands.any { it.uuid == id } }
        this.sampleRate = sampleRate
        maximumFrequency = min(PeqGraphMath.MAX_FREQUENCY, sampleRate * 0.5 * 0.999)
        updateContentDescription()
        noteNodeInteraction()
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
        calculator.invalidateAll()
        recomputeSystemResponse()
        invalidate()
    }

    fun hasActiveDraft(): Boolean = draft != null || tiltDraft != null

    override fun onDetachedFromWindow() {
        cancelDraft()
        stopSpectrum()
        nodeFadeHandler.removeCallbacks(nodeFadeTick)
        super.onDetachedFromWindow()
    }

    /**
     * Full rebind for the surface: [values] is the 35-float native BMW DSP config array,
     * [peq] the already-loaded three-bank PEQ state, [activeBank] which bank's nodes are
     * draggable right now. The other two banks are stored read-only for display.
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
        setBands(
            ParametricEqBandList().apply { addAll(activeBands) },
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
        if (displayMode != DisplayMode.MAGNITUDE) return false
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
        noteNodeInteraction()
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
        if (showTiltHandles && systemValues[NativeBmwDspValues.INDEX_TILT_ENABLED] >= .5f) {
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
            calculator.invalidateBank(activeBank)
            recomputeSystemResponse()
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
        noteNodeInteraction()
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
                recomputeSystemResponse()
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

    // --- Coordinate mapping ----------------------------------------------------------------

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

    private fun yForRange(value: Double, min: Double, max: Double): Float {
        val clamped = value.coerceIn(min, max)
        val fraction = (max - clamped) / (max - min)
        return plotTop() + fraction.toFloat() * (plotBottom() - plotTop())
    }

    private fun yForPhaseDeg(deg: Double): Float = yForRange(deg, PHASE_MIN_DEG, PHASE_MAX_DEG)
    private fun yForGroupDelayMs(ms: Double): Float = yForRange(ms, GROUP_DELAY_MIN_MS, GROUP_DELAY_MAX_MS)

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
        drawUnifiedSystem(canvas)
    }

    private fun drawUnifiedSystem(canvas: Canvas) {
        val left = plotLeft()
        val right = plotRight()
        val top = plotTop()
        val bottom = plotBottom()
        // No solid fill: the workspace's own designed background shows through behind the plot
        // now, with only the grid wireframe (drawUnifiedGrid etc., below) and the curves/fills
        // drawn on top of it -- see BmwDashboardSkin.styleCard()'s matching change for the card
        // wrapping this view.
        if (right <= left || bottom <= top) return

        when (displayMode) {
            DisplayMode.MAGNITUDE -> {
                drawUnifiedGrid(canvas, left, right, top, bottom)
                drawCrossoverShading(canvas, left, right, top, bottom)
                if (showSpectrum && spectrumActive) drawUnifiedSpectrum(canvas, left, right, top, bottom)
                drawBranchCurves(canvas, left, right, top, bottom)
                drawActiveBankOverlays(canvas, left, right, top, bottom)
                drawPerBandFills(canvas, left, right)
                drawSumCurve(canvas, left, right, top, bottom)
                if (showTiltHandles) drawTiltHandles(canvas)
                drawMultiBankNodes(canvas)
                if (showGainMeters) drawGainMeters(canvas, right, top, bottom)
            }
            DisplayMode.MAGNITUDE_PHASE -> {
                drawUnifiedGrid(canvas, left, right, top, bottom)
                drawCrossoverShading(canvas, left, right, top, bottom)
                if (showSpectrum && spectrumActive) drawUnifiedSpectrum(canvas, left, right, top, bottom)
                drawBranchCurves(canvas, left, right, top, bottom)
                drawSumCurve(canvas, left, right, top, bottom)
                drawSumPhaseOverlay(canvas, left, right)
            }
            DisplayMode.PHASE -> {
                drawPhaseGrid(canvas, left, right, top, bottom)
                drawCrossoverShading(canvas, left, right, top, bottom)
                drawPhaseCurves(canvas, left, right)
            }
            DisplayMode.GROUP_DELAY -> {
                drawGroupDelayGrid(canvas, left, right, top, bottom)
                drawCrossoverShading(canvas, left, right, top, bottom)
                drawGroupDelayCurve(canvas, left, right)
            }
        }
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
        drawUnifiedGridLines(canvas, left, right, top, bottom, floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f), zeroLine = 0f, toY = ::yForGain)
    }

    private fun drawPhaseGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        drawUnifiedGridLines(canvas, left, right, top, bottom, floatArrayOf(180f, 90f, 0f, -90f, -180f), zeroLine = 0f) { yForPhaseDeg(it) }
    }

    private fun drawGroupDelayGrid(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        drawUnifiedGridLines(canvas, left, right, top, bottom, floatArrayOf(10f, 6f, 2f, 0f, -2f), zeroLine = 0f) { yForGroupDelayMs(it) }
    }

    private fun drawUnifiedGridLines(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        lines: FloatArray,
        zeroLine: Float,
        toY: (Double) -> Float,
    ) {
        lines.forEach { value ->
            val y = toY(value.toDouble())
            canvas.drawLine(left, y, right, y, if (value == zeroLine) unifiedZeroPaint else unifiedGridPaint)
            canvas.drawText("${value.toInt()}", 4f * density, y + 3f * density, unifiedLabelPaint)
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
        // Read the live per-output crossover fields rather than the legacy scalar indices --
        // those are only kept mirrored by the UI's write path, so reading them directly here
        // stays correct even if a future write path updates the per-output block only.
        val lowFreq = values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)]
            .toDouble().coerceIn(20.0, maximumFrequency)
        val midFreq = values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)]
            .toDouble().coerceIn(20.0, maximumFrequency)
        if (midFreq <= lowFreq) return
        canvas.drawRect(xForFrequency(lowFreq).coerceIn(left, right), top, xForFrequency(midFreq).coerceIn(left, right), bottom, crossoverShadePaint)
    }

    private fun drawUnifiedSpectrum(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        spectrumStrokePath.rewind()
        spectrumFillPath.rewind()
        dryStrokePath.rewind()
        spectrumFillPath.moveTo(left, bottom)
        for (i in 0..SPECTRUM_STEPS) {
            val fraction = i / SPECTRUM_STEPS.toFloat()
            val freq = PeqGraphMath.fractionToFrequency(fraction, PeqGraphMath.MIN_FREQUENCY, maximumFrequency)
            val wetGain = PeqGraphMath.spectrumDbToGraphGain(SpectrumEngine.magnitudeDbAt(freq), SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB)
            val dryGain = PeqGraphMath.spectrumDbToGraphGain(SpectrumEngine.dryMagnitudeDbAt(freq), SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB)
            val x = left + fraction * (right - left)
            val wetY = yForGain(wetGain)
            val dryY = yForGain(dryGain)
            spectrumXs[i] = x
            spectrumWetYs[i] = wetY
            spectrumDryYs[i] = dryY
            if (i == 0) {
                spectrumStrokePath.moveTo(x, wetY)
                dryStrokePath.moveTo(x, dryY)
            } else {
                spectrumStrokePath.lineTo(x, wetY)
                dryStrokePath.lineTo(x, dryY)
            }
            spectrumFillPath.lineTo(x, wetY)
        }
        spectrumFillPath.lineTo(right, bottom)
        spectrumFillPath.close()
        drawSpectrumDelta(canvas, SPECTRUM_STEPS + 1)
        canvas.drawPath(spectrumFillPath, unifiedSpectrumFillPaint)
        canvas.drawPath(dryStrokePath, dryStrokePaint)
        canvas.drawPath(spectrumStrokePath, unifiedSpectrumStrokePaint)
    }

    /**
     * Shades the gap between the dry and wet traces already written into [spectrumXs] /
     * [spectrumDryYs] / [spectrumWetYs] by the caller, split at each sign change so a boost and a
     * cut get different fill colors instead of one flat band. Y is screen space (smaller = higher
     * gain/louder), so "boost" is wherever the wet trace sits at or above the dry one.
     */
    private fun drawSpectrumDelta(canvas: Canvas, pointCount: Int) {
        if (pointCount < 2) return
        var segmentStart = 0
        var segmentBoost = spectrumWetYs[0] <= spectrumDryYs[0]
        for (i in 1 until pointCount) {
            val isBoost = spectrumWetYs[i] <= spectrumDryYs[i]
            if (isBoost != segmentBoost) {
                fillDeltaSegment(canvas, segmentStart, i, segmentBoost)
                segmentStart = i
                segmentBoost = isBoost
            }
        }
        fillDeltaSegment(canvas, segmentStart, pointCount - 1, segmentBoost)
    }

    private fun fillDeltaSegment(canvas: Canvas, startIndex: Int, endIndex: Int, boost: Boolean) {
        if (endIndex <= startIndex) return
        deltaFillPath.rewind()
        deltaFillPath.moveTo(spectrumXs[startIndex], spectrumWetYs[startIndex])
        for (i in startIndex + 1..endIndex) deltaFillPath.lineTo(spectrumXs[i], spectrumWetYs[i])
        for (i in endIndex downTo startIndex) deltaFillPath.lineTo(spectrumXs[i], spectrumDryYs[i])
        deltaFillPath.close()
        canvas.drawPath(deltaFillPath, if (boost) spectrumBoostFillPaint else spectrumCutFillPaint)
    }

    private fun drawBranchCurves(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        drawSystemCurve(canvas, curves.lowBranchDb, left, right, lowBranchPaint, ::yForGain)
        drawSystemCurve(canvas, curves.midBranchDb, left, right, midBranchPaint, ::yForGain)
    }

    /**
     * Each active-bank band's own contribution to the *actual* combined curve for this bank
     * (cycling through [perBandPalette] by render-order index, same index [drawActiveBankNodes]
     * numbers its nodes with) -- filled between the real curve (with this band included, exactly
     * as already drawn by [drawBranchCurves]/[drawSumCurve]) and where that same curve would sit
     * with this one band's own dB contribution subtracted back out. The fill's own edge always
     * hugs the true curve's shape instead of a flat, context-free 0dB reference plane, the same
     * convention FabFilter Pro-Q and similar EQs use. Drawn before [drawSumCurve] so the bright
     * combined curve sits on top of every fill instead of being obscured by them.
     *
     * This subtraction is exact, not an approximation: every stage in this bank's cascade
     * (crossover, subsonic, other PEQ bands) is a plain multiplicative LTI filter, and complex
     * magnitudes multiply in a cascade -- which is exactly addition once everything is expressed
     * in dB. So "curve with band i" minus "band i's own isolated dB response", at every
     * frequency, is exactly "curve without band i", regardless of how many other stages sit
     * before or after it in the chain.
     */
    private fun drawPerBandFills(canvas: Canvas, left: Float, right: Float) {
        if (renderBands.isEmpty()) return
        val referenceCurve = referenceCurveForActiveBank() ?: return
        val path = Path()
        renderBands.forEachIndexed { index, band ->
            bandCascade.clear()
            bandCascade.addPeqBand(band, sampleRate)
            path.rewind()
            for (i in 0 until SYSTEM_POINT_COUNT) {
                val fraction = i.toFloat() / (SYSTEM_POINT_COUNT - 1)
                val frequency = curves.frequencies[i]
                val w = 2.0 * PI * frequency / sampleRate
                val cosW = cos(w)
                val sinW = sin(w)
                val cos2W = 2.0 * cosW * cosW - 1.0
                val sin2W = 2.0 * sinW * cosW
                bandAcc.setUnity()
                bandCascade.accumulate(cosW, sinW, cos2W, sin2W, bandAcc)
                val withBandDb = referenceCurve[i]
                val withoutBandDb = withBandDb - bandAcc.magnitudeDb()
                fillX[i] = left + fraction * (right - left)
                fillTopY[i] = yForGain(withBandDb)
                fillBottomY[i] = yForGain(withoutBandDb)
            }
            for (i in 0 until SYSTEM_POINT_COUNT) {
                if (i == 0) path.moveTo(fillX[i], fillTopY[i]) else path.lineTo(fillX[i], fillTopY[i])
            }
            for (i in SYSTEM_POINT_COUNT - 1 downTo 0) {
                path.lineTo(fillX[i], fillBottomY[i])
            }
            path.close()
            val color = perBandPalette[index % perBandPalette.size]
            // Paint.setColor() overwrites alpha along with RGB (Color.rgb()'s palette entries are
            // fully opaque), so alpha has to be re-applied after color on every draw -- setting it
            // once in the Paint initializer got silently clobbered the instant color was assigned
            // here, which is why overlapping fills were reading as solid opaque blocks instead of
            // blending together.
            bandFillPaint.color = color
            bandFillPaint.alpha = BAND_FILL_ALPHA
            canvas.drawPath(path, bandFillPaint)
            bandStrokePaint.color = color
            bandStrokePaint.alpha = BAND_STROKE_ALPHA
            canvas.drawPath(path, bandStrokePaint)
        }
    }

    /**
     * The real curve [drawPerBandFills] should hug for the bank currently being edited -- the
     * same arithmetic L/R mean [drawSystemCurve] already draws for branch curves, so a fill's
     * top edge sits exactly on top of the visible blue/orange/white line for that bank, not a
     * separately-computed approximation of it.
     */
    private fun referenceCurveForActiveBank(): DoubleArray? {
        val perChannel = when (activeBank) {
            BmwPeqBank.FULL -> curves.preSplitDb
            BmwPeqBank.LOW -> curves.lowBranchDb
            BmwPeqBank.MID -> curves.midBranchDb
        }
        val leftValues = perChannel[BmwOutputChannel.LEFT.ordinal]
        val rightValues = perChannel[BmwOutputChannel.RIGHT.ordinal]
        if (leftValues.size != SYSTEM_POINT_COUNT) return null
        for (i in 0 until SYSTEM_POINT_COUNT) {
            referenceCurveScratch[i] = (leftValues[i] + rightValues[i]) * 0.5
        }
        return referenceCurveScratch
    }

    private fun drawSumCurve(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (channelDisplay != ChannelDisplay.RIGHT) {
            drawSystemCurveForChannel(canvas, curves.sumDb[BmwOutputChannel.LEFT.ordinal], left, right, sumPaintSolid, ::yForGain)
        }
        if (channelDisplay != ChannelDisplay.LEFT) {
            drawSystemCurveForChannel(canvas, curves.sumDb[BmwOutputChannel.RIGHT.ordinal], left, right, sumPaintDashed, ::yForGain)
        }
    }

    /** Sum phase overlaid on the magnitude graph in MAGNITUDE_PHASE mode -- its own implicit
     *  -180..180 degree scale sharing the plot area, not the dB gridlines shown alongside it. */
    private fun drawSumPhaseOverlay(canvas: Canvas, left: Float, right: Float) {
        drawSystemCurveForChannel(canvas, curves.sumPhase[BmwOutputChannel.LEFT.ordinal], left, right, sumPhaseOverlayPaint) { yForPhaseDeg(Math.toDegrees(it)) }
    }

    private fun drawPhaseCurves(canvas: Canvas, left: Float, right: Float) {
        drawSystemCurve(canvas, curves.lowBranchPhase, left, right, lowBranchPaint) { yForPhaseDeg(Math.toDegrees(it)) }
        drawSystemCurve(canvas, curves.midBranchPhase, left, right, midBranchPaint) { yForPhaseDeg(Math.toDegrees(it)) }
        if (channelDisplay != ChannelDisplay.RIGHT) {
            drawSystemCurveForChannel(canvas, curves.sumPhase[BmwOutputChannel.LEFT.ordinal], left, right, sumPaintSolid) { yForPhaseDeg(Math.toDegrees(it)) }
        }
        if (channelDisplay != ChannelDisplay.LEFT) {
            drawSystemCurveForChannel(canvas, curves.sumPhase[BmwOutputChannel.RIGHT.ordinal], left, right, sumPaintDashed) { yForPhaseDeg(Math.toDegrees(it)) }
        }
    }

    private fun drawGroupDelayCurve(canvas: Canvas, left: Float, right: Float) {
        val leftDelay = curves.groupDelayMsFor(BmwOutputChannel.LEFT)
        val rightDelay = curves.groupDelayMsFor(BmwOutputChannel.RIGHT)
        if (leftDelay.isEmpty()) return
        val path = Path()
        for (i in leftDelay.indices) {
            val avg = (leftDelay[i] + rightDelay[i]) * 0.5
            val x = left + (i.toFloat() / (leftDelay.size - 1).coerceAtLeast(1)) * (right - left)
            val y = yForGroupDelayMs(avg)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, sumPaintSolid)
    }

    private fun drawSystemCurve(canvas: Canvas, perChannelValues: Array<DoubleArray>, left: Float, right: Float, paint: Paint, toY: (Double) -> Float) {
        // Branch curves show the arithmetic mean of L/R (they are context, not the primary
        // per-channel readout -- the dominant sum curve below shows true L/R separation).
        val leftValues = perChannelValues[BmwOutputChannel.LEFT.ordinal]
        val rightValues = perChannelValues[BmwOutputChannel.RIGHT.ordinal]
        if (leftValues.isEmpty()) return
        val path = Path()
        for (i in leftValues.indices) {
            val avg = (leftValues[i] + rightValues[i]) * 0.5
            val x = left + (i.toFloat() / (leftValues.size - 1).coerceAtLeast(1)) * (right - left)
            val y = toY(avg)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSystemCurveForChannel(canvas: Canvas, values: DoubleArray, left: Float, right: Float, paint: Paint, toY: (Double) -> Float) {
        if (values.isEmpty()) return
        val path = Path()
        for (i in values.indices) {
            val x = left + (i.toFloat() / (values.size - 1).coerceAtLeast(1)) * (right - left)
            val y = toY(values[i])
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
        val nodeAlpha = (nodeAlphaFraction() * 255f).roundToInt()
        if (nodeAlpha <= 0) return
        if (activeBank != BmwPeqBank.FULL) drawInactiveBankNodes(canvas, allFullRangeBands, BmwPeqBank.FULL, nodeAlpha)
        if (activeBank != BmwPeqBank.LOW) drawInactiveBankNodes(canvas, allLowBandBands, BmwPeqBank.LOW, nodeAlpha)
        if (activeBank != BmwPeqBank.MID) drawInactiveBankNodes(canvas, allMidBandBands, BmwPeqBank.MID, nodeAlpha)
        drawActiveBankNodes(canvas, nodeAlpha)
    }

    private fun drawInactiveBankNodes(canvas: Canvas, bands: List<ParametricEqBand>, bank: BmwPeqBank, nodeAlpha: Int) {
        bands.forEach { band ->
            nodeDimPaint.color = colorForBand(band, bank)
            nodeDimPaint.alpha = nodeDimPaint.alpha * nodeAlpha / 255
            nodeRingPaint.alpha = nodeAlpha
            val x = xForFrequency(band.frequency)
            val y = yForGain(band.gain)
            canvas.drawCircle(x, y, INACTIVE_NODE_RADIUS_DP * density, nodeDimPaint)
            if (band.channel == ParametricEqChannel.RIGHT) {
                canvas.drawCircle(x, y, INACTIVE_NODE_RADIUS_DP * density, nodeRingPaint)
            }
        }
    }

    private fun drawActiveBankNodes(canvas: Canvas, nodeAlpha: Int) {
        renderBands.forEachIndexed { index, band ->
            val color = colorForBand(band, activeBank)
            nodeFillPaint.color = color
            nodeFillPaint.alpha = nodeAlpha
            nodeHaloPaint.color = color
            nodeRingPaint.alpha = nodeAlpha
            nodeTextPaint.alpha = nodeAlpha
            val x = xForFrequency(band.frequency)
            val y = yForGain(band.gain)
            val selected = band.uuid == selectedId
            if (selected) {
                nodeHaloPaint.alpha = 60 * nodeAlpha / 255
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
        when (displayMode) {
            DisplayMode.GROUP_DELAY -> {
                canvas.drawText(
                    "FINAL SUM GROUP DELAY (L/R averaged) · compressor not shown (nonlinear)",
                    left,
                    baseline,
                    unifiedLegendPaint,
                )
                return
            }
            DisplayMode.PHASE -> {
                val lowPaint = Paint(unifiedLegendPaint).apply { color = bankColorLow }
                val midPaint = Paint(unifiedLegendPaint).apply { color = bankColorMid }
                canvas.drawText("LOW", left, baseline, lowPaint)
                canvas.drawText("MID", left + 38f * density, baseline, midPaint)
                canvas.drawText(
                    "FINAL SUM PHASE (L solid / R dashed) · compressor not shown (nonlinear)",
                    left + 76f * density,
                    baseline,
                    unifiedLegendPaint,
                )
                return
            }
            DisplayMode.MAGNITUDE_PHASE -> {
                val fullPaint = Paint(unifiedLegendPaint).apply { color = bankColorFull }
                val lowPaint = Paint(unifiedLegendPaint).apply { color = bankColorLow }
                val midPaint = Paint(unifiedLegendPaint).apply { color = bankColorMid }
                canvas.drawText("FULL", left, baseline, fullPaint)
                canvas.drawText("LOW", left + 38f * density, baseline, lowPaint)
                canvas.drawText("MID", left + 74f * density, baseline, midPaint)
                canvas.drawText(
                    "SUM SOLID = MAGNITUDE, THIN DASHED = PHASE",
                    left + 112f * density,
                    baseline,
                    unifiedLegendPaint,
                )
                return
            }
            DisplayMode.MAGNITUDE -> Unit
        }
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

    private fun hitTest(x: Float, y: Float): ParametricEqBand? {
        val radius = 24f * density
        return renderBands.map { band ->
            val pointX = xForFrequency(band.frequency)
            val pointY = yForGain(band.gain)
            band to hypot(x - pointX, y - pointY)
        }.filter { it.second <= radius }.minByOrNull { it.second }?.first
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
        private const val SYSTEM_POINT_COUNT = 192
        private const val OVERLAY_POINT_COUNT = 96
        private const val SPECTRUM_STEPS = 160
        private const val INACTIVE_NODE_RADIUS_DP = 4.5f
        private const val ACTIVE_NODE_RADIUS_DP = 8f
        private const val TILT_HANDLE_RADIUS_DP = 20f
        private const val TILT_HANDLE_DRAW_RADIUS_DP = 8f
        private const val PHASE_MIN_DEG = -180.0
        private const val PHASE_MAX_DEG = 180.0
        private const val GROUP_DELAY_MIN_MS = -2.0
        private const val GROUP_DELAY_MAX_MS = 10.0

        private const val METER_FLOOR_DB = -50f
        private const val METER_CEILING_DB = 0f
        // Low enough that 2-3 overlapping bands visibly blend into a mixed color instead of the
        // top-most one just opaquely covering the ones underneath.
        private const val BAND_FILL_ALPHA = 55
        private const val BAND_STROKE_ALPHA = 190

        // Total time nodes/labels stay visible after the last interaction before they're fully
        // gone is HOLD + FADE (~10s), per explicit request to extend it well past the previous
        // ~1.6s (1200 hold + 400 fade) -- kept the fade transition itself short so the last
        // instant still reads as a fade-out, not an abrupt cut.
        private const val NODE_IDLE_HOLD_MS = 9600L
        private const val NODE_FADE_DURATION_MS = 400L

        private val FREQ_SCALE = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
        )
    }
}
