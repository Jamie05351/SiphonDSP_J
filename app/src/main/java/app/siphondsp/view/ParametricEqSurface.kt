package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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
 * modelled signal chain (via [BmwResponseCalculator]) and a numbered node for every band in
 * all three PEQ banks. Read-only: nodes and tilt markers are not draggable (that only ever
 * shifted filters by accident and was never used for real tuning); tapping a node pops a
 * short-lived, auto-hiding info card for it and, for an active-bank node, calls
 * [onPointSelected] so the caller can open it in the numeric editor.
 */
class ParametricEqSurface(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class ChannelDisplay { BOTH, LEFT, RIGHT }
    enum class DisplayMode { MAGNITUDE, PHASE, MAGNITUDE_PHASE, GROUP_DELAY }

    var onPointSelected: ((UUID) -> Unit)? = null

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
    // Tapping a node only makes sense against the magnitude curve it sits on, so node taps are
    // accepted in MAGNITUDE mode only -- enforced in onTouchEvent, independent of [interactive]
    // below (which callers use for a different reason: a wholly static preview instance).
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

    // Dry (pre-DSP) reference trace -- a solid, clearly readable line. It's the "before" anchor
    // the eye holds the live wet trace against, so a barely-there dashed hint defeated the point;
    // the wet trace is drawn brighter and with a glow on top, so this still reads as secondary.
    private val dryStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(android.R.attr.textColorPrimary)
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        alpha = 205
    }
    // Shaded gap between the dry and wet traces: the app's brand green where the filters are
    // adding energy at that frequency right now, brand red where they're cutting it. This is
    // what actually shows what the filters are doing to the live audio, rather than two
    // independently overlaid lines -- so it's drawn with real weight, not a faint wash.
    private val spectrumBoostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_GREEN
        style = Paint.Style.FILL
        alpha = 125
    }
    private val spectrumCutFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_RED
        style = Paint.Style.FILL
        alpha = 125
    }
    private val spectrumStrokePath = Path()
    private val spectrumFillPath = Path()
    private val dryStrokePath = Path()
    private val deltaFillPath = Path()
    private val spectrumXs = FloatArray(SPECTRUM_STEPS + 1)
    private val spectrumDryYs = FloatArray(SPECTRUM_STEPS + 1)
    private val spectrumWetYs = FloatArray(SPECTRUM_STEPS + 1)

    // --- Band state (UNIFIED_SYSTEM: renderBands is only the active bank's bands, mirroring
    // how the fragment already scopes bands per bank; the other two banks' bands live in
    // allLowBandBands/allMidBandBands/allFullRangeBands below and are drawn read-only) --------
    private var renderBands: List<ParametricEqBand> = emptyList()
    private var selectedId: UUID? = null
    private var downX = 0f
    private var downY = 0f
    private var downConsumed = false
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

    // --- Tapped-node info card: nodes themselves are always drawn now, but tapping one pops a
    // small read-only detail card (filter type, freq/gain/Q, channel, which bank) that holds
    // briefly then fades, so the graph doesn't stay cluttered with a card you're done reading.
    private val infoFadeHandler = Handler(Looper.getMainLooper())
    private var infoBand: ParametricEqBand? = null
    private var infoBandBank: BmwPeqBank? = null
    private var infoBandNumber = 0
    private var infoShownAtMs = 0L
    private val infoFadeTick = object : Runnable {
        override fun run() {
            invalidate()
            if (infoCardAlphaFraction() > 0f) infoFadeHandler.postDelayed(this, 32L)
            else { infoBand = null; infoBandBank = null }
        }
    }

    private fun showInfoCard(band: ParametricEqBand, bank: BmwPeqBank, number: Int) {
        infoBand = band
        infoBandBank = bank
        infoBandNumber = number
        infoShownAtMs = SystemClock.uptimeMillis()
        infoFadeHandler.removeCallbacks(infoFadeTick)
        infoFadeHandler.postDelayed(infoFadeTick, 32L)
    }

    private fun dismissInfoCard() {
        infoFadeHandler.removeCallbacks(infoFadeTick)
        infoBand = null
        infoBandBank = null
    }

    private fun infoCardAlphaFraction(): Float {
        if (infoBand == null) return 0f
        val sinceHold = SystemClock.uptimeMillis() - infoShownAtMs - INFO_CARD_HOLD_MS
        return when {
            sinceHold <= 0L -> 1f
            sinceHold >= INFO_CARD_FADE_MS -> 0f
            else -> 1f - sinceHold.toFloat() / INFO_CARD_FADE_MS
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

    // No solid background fill any more (see drawUnifiedSystem) -- the workspace's own designed
    // background shows through instead. bgBottomColor survives as nodeRingPaint's stroke colour
    // below, a leftover dark tone that still reads correctly against either background.
    private val bgBottomColor = Color.rgb(13, 13, 15)
    private val unifiedGridColor = Color.rgb(58, 60, 66)
    private val unifiedTextColor = Color.rgb(176, 178, 186)
    // Neon revision of the existing colour map: same identities (Full = white, Low = blue,
    // Mid = yellow/amber, sum = white) but pushed to full-chroma so the thin sharp lines below
    // actually read on the dark workspace instead of sitting there as dull grey-ish traces.
    private val bankColorFull = Color.rgb(255, 255, 255)
    private val bankColorLow = Color.rgb(0, 209, 255)
    private val bankColorMid = Color.rgb(255, 224, 0)
    private val sumColor = Color.rgb(255, 255, 255)

    // Per-band palette (FabFilter Pro-Q-style): each band gets its own colour, cycling by index
    // within its bank's band list -- the SAME colour is used for that band's node dot, its
    // isolated-response overlay line, its shaded fill, and its tap-info card, so "this dot" and
    // "this shape" are unmistakably the same filter. Neon, to match the line revision above.
    private val perBandPalette = intArrayOf(
        Color.rgb(255, 23, 68),   // red
        Color.rgb(224, 64, 251),  // violet
        Color.rgb(41, 121, 255),  // blue
        Color.rgb(0, 230, 118),   // green
        Color.rgb(255, 145, 0),   // orange
        Color.rgb(0, 229, 255),   // cyan
        Color.rgb(255, 64, 129),  // pink
        Color.rgb(198, 255, 0),   // lime
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
        strokeWidth = 1.1f * density
    }
    // Scratch paint for the neon "bloom" under every curve: the real line is drawn thin and
    // sharp, then this is stroked wide and translucent beneath it (configured per call in
    // [strokeNeon]) so a 1px line still reads as glowing rather than hairline-faint.
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        strokeWidth = 1.4f * density
        color = bankColorLow
    }
    private val midBranchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = bankColorMid
    }
    private val sumPaintSolid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * density
        color = sumColor
    }
    private val sumPaintDashed = Paint(sumPaintSolid).apply {
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    // MAGNITUDE_PHASE overlay: same colour family as the sum curve it accompanies, thinner and
    // dashed so it reads as an annotation on its own implicit degree scale, not a second sum.
    private val sumPhaseOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = sumColor
        alpha = 170
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    // Light blue (matches the app's accent colour) -- the grey used everywhere else in this
    // unified view reads as barely-visible background noise for a live spectrum trace.
    private val spectrumAccentColor = Color.rgb(79, 195, 247)
    // Vertical gradient under the wet trace: accent near the trace fading to nothing toward the
    // floor, so the fill gives the trace body without flattening into a solid slab. The shader is
    // rebuilt in drawUnifiedSpectrum whenever the plot's top/bottom change.
    private val unifiedSpectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = spectrumAccentColor // fallback until the gradient shader is installed
    }
    private var spectrumFillShaderTop = Float.NaN
    private var spectrumFillShaderBottom = Float.NaN
    private val unifiedSpectrumStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = spectrumAccentColor
        alpha = 190
    }
    private val unifiedOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        alpha = 90
    }
    private val unifiedOverlayDashEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    private val nodeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
    // Tapped-node info card (see drawInfoCard): a dark rounded panel, edged in that band's own
    // palette colour, holding its type / freq / gain / Q / channel / bank for a few seconds.
    private val infoCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(18, 19, 22)
    }
    private val infoCardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    private val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 234, 240)
        textSize = 10f * density
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
        renderBands = bands.toList()
        this.selectedId = selectedId?.takeIf { id -> renderBands.any { it.uuid == id } }
        this.sampleRate = sampleRate
        maximumFrequency = min(PeqGraphMath.MAX_FREQUENCY, sampleRate * 0.5 * 0.999)
        dismissInfoCard()
        updateContentDescription()
        invalidate()
    }

    fun selectBand(id: UUID?) {
        selectedId = id?.takeIf { candidate -> renderBands.any { it.uuid == candidate } }
        updateContentDescription()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        dismissInfoCard()
        stopSpectrum()
        super.onDetachedFromWindow()
    }

    /**
     * Full rebind for the surface: [values] is the 35-float native BMW DSP config array,
     * [peq] the already-loaded three-bank PEQ state, [activeBank] which bank a tapped node
     * reports back through [onPointSelected]. All three banks' nodes are drawn.
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
        calculator.invalidateAll()
        recomputeSystemResponse()
        invalidate()
    }

    private fun recomputeSystemResponse() {
        val maxFreq = min(20_000.0, sampleRate * 0.5 * 0.999)
        calculator.configureAxis(sampleRate, 20.0, maxFreq)
        calculator.compute(systemValues, peqState, curves)
        invalidate()
    }

    private fun currentTiltValues(): BmwGraphGestureMath.TiltValues =
        BmwGraphGestureMath.TiltValues(systemValues[NativeBmwDspValues.INDEX_TILT_FREQ], systemValues[NativeBmwDspValues.INDEX_TILT_AMOUNT])

    // --- Shared touch handling -----------------------------------------------------------

    private class NodeHit(val band: ParametricEqBand, val bank: BmwPeqBank, val number: Int)

    // Read-only now: a touch that lands on a node pops that node's info card (and, for an
    // active-bank node, opens it in the numeric editor via onPointSelected). A touch that
    // misses every node is not consumed, so it still bubbles to the parent (e.g. the preview
    // card's tap-to-collapse). Dragging was removed entirely -- it only ever moved filters by
    // accident -- so any real finger travel just cancels the pending tap.
    private var pendingHit: NodeHit? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        if (displayMode != DisplayMode.MAGNITUDE) return false
        if (event.pointerCount > 1) {
            pendingHit = null
            downConsumed = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pendingHit = hitTestAnyBank(event.x, event.y)
                downConsumed = pendingHit != null
                return downConsumed
            }
            MotionEvent.ACTION_MOVE -> {
                if (downConsumed && hypot(event.x - downX, event.y - downY) >= touchSlop) {
                    pendingHit = null
                    downConsumed = false
                }
                return downConsumed
            }
            MotionEvent.ACTION_UP -> {
                val hit = pendingHit
                pendingHit = null
                if (!downConsumed || hit == null) {
                    downConsumed = false
                    return false
                }
                downConsumed = false
                openNodeInfo(hit)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingHit = null
                downConsumed = false
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun openNodeInfo(hit: NodeHit) {
        selectedId = hit.band.uuid
        showInfoCard(hit.band, hit.bank, hit.number)
        if (hit.bank == activeBank) onPointSelected?.invoke(hit.band.uuid)
        updateContentDescription()
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Nearest node within [NODE_TOUCH_RADIUS_DP] across every bank currently drawn on screen:
     * Low and Mid are always shown, Input Correction (FULL) only while it is the active bank.
     * Numbers are 1-based within each bank, matching the node labels.
     */
    private fun hitTestAnyBank(x: Float, y: Float): NodeHit? {
        val radius = NODE_TOUCH_RADIUS_DP * density
        var best: NodeHit? = null
        var bestDistance = Float.MAX_VALUE
        fun consider(bands: List<ParametricEqBand>, bank: BmwPeqBank) {
            bands.forEachIndexed { index, band ->
                val distance = hypot(x - xForFrequency(band.frequency), y - yForGain(band.gain))
                if (distance <= radius && distance < bestDistance) {
                    bestDistance = distance
                    best = NodeHit(band, bank, index + 1)
                }
            }
        }
        if (activeBank == BmwPeqBank.FULL) consider(allFullRangeBands, BmwPeqBank.FULL)
        consider(allLowBandBands, BmwPeqBank.LOW)
        consider(allMidBandBands, BmwPeqBank.MID)
        return best
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
                drawMonoBassRegion(canvas, left, right, top, bottom)
                if (showSpectrum && spectrumActive) drawUnifiedSpectrum(canvas, left, right, top, bottom)
                drawBranchCurves(canvas, left, right, top, bottom)
                drawFilterOverlays(canvas, left, right)
                drawPerBandFills(canvas, left, right)
                drawSumCurve(canvas, left, right, top, bottom)
                if (showTiltHandles) drawTiltHandles(canvas)
                drawMultiBankNodes(canvas)
                drawInfoCard(canvas, left, right, top, bottom)
                if (showGainMeters) drawGainMeters(canvas, right, top, bottom)
            }
            DisplayMode.MAGNITUDE_PHASE -> {
                drawUnifiedGrid(canvas, left, right, top, bottom)
                drawCrossoverShading(canvas, left, right, top, bottom)
                drawMonoBassRegion(canvas, left, right, top, bottom)
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
        drawUnifiedGridLines(canvas, left, right, top, bottom, floatArrayOf(6f, 0f, -6f, -12f, -18f, -24f), zeroLine = 0f, toY = ::yForGain)
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

    private fun monoBassActive(): Boolean =
        systemValues[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED] >= .5f &&
            systemValues[NativeBmwDspValues.INDEX_MONO_BASS_BLEND] > 0f &&
            systemValues[NativeBmwDspValues.INDEX_LPF_PASS] < .5f

    private fun monoBassFrequency(): Double =
        systemValues[NativeBmwDspValues.INDEX_MONO_BASS_FREQ].toDouble().coerceIn(20.0, maximumFrequency)

    /**
     * Blend fraction (0..1) the two sum curves are pulled toward their L/R mean by, at [frequency]:
     * full below the mono-bass corner, ramping back to 0 across the half-octave above it. This is a
     * display-only cue -- [BmwResponseCalculator] still models the mono-bass low branch under an
     * L=R assumption, so it can't actually show L/R polarity cancellation; visibly collapsing the
     * L and R sum lines together where mono bass engages at least makes "the low end is mono here"
     * unmissable on the graph. A full stereo mono-sum model is deferred (see the calculator).
     */
    private fun monoBassBlendAt(frequency: Double): Float {
        if (!monoBassActive()) return 0f
        val corner = monoBassFrequency()
        val strength = (systemValues[NativeBmwDspValues.INDEX_MONO_BASS_BLEND] * .01f).coerceIn(0f, 1f)
        return when {
            frequency <= corner -> strength
            frequency >= corner * 1.5 -> 0f
            else -> strength * (1f - ((frequency - corner) / (corner * 0.5)).toFloat())
        }
    }

    private fun drawMonoBassRegion(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (!monoBassActive()) return
        val cornerX = xForFrequency(monoBassFrequency()).coerceIn(left, right)
        canvas.drawRect(left, top, cornerX, bottom, crossoverShadePaint)
        canvas.drawLine(cornerX, top, cornerX, bottom, unifiedGridPaint)
        val label = "MONO BASS ▸ ${monoBassFrequency().roundToInt()} Hz"
        canvas.drawText(label, left + 6f * density, bottom - 6f * density, tiltLabelPaint)
    }

    /**
     * Draws [path] as a neon line: a wide, translucent "bloom" pass of [paint]'s colour under a
     * sharp core stroke of [paint] itself. Keeps the thin 1px lines readable on the dark
     * workspace without a software-layer blur.
     */
    private fun strokeNeon(canvas: Canvas, path: Path, paint: Paint) {
        glowPaint.color = paint.color
        glowPaint.alpha = (Color.alpha(paint.color) * 0.16f).roundToInt()
        glowPaint.strokeWidth = paint.strokeWidth * 3.4f
        glowPaint.pathEffect = paint.pathEffect
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, paint)
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
        if (top != spectrumFillShaderTop || bottom != spectrumFillShaderBottom) {
            unifiedSpectrumFillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                ColorUtils.setAlphaComponent(spectrumAccentColor, 150),
                ColorUtils.setAlphaComponent(spectrumAccentColor, 0),
                Shader.TileMode.CLAMP,
            )
            spectrumFillShaderTop = top
            spectrumFillShaderBottom = bottom
        }
        canvas.drawPath(spectrumFillPath, unifiedSpectrumFillPaint)
        canvas.drawPath(dryStrokePath, dryStrokePaint)
        strokeNeon(canvas, spectrumStrokePath, unifiedSpectrumStrokePaint)
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
     * (cycling through [perBandPalette] by index within its bank's list, the same index
     * [drawBankNodes] numbers its nodes with) -- filled between the real curve (with this band
     * included, exactly
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
        val path = Path()
        forEachVisibleBank { bank, bands ->
            if (bands.isEmpty()) return@forEachVisibleBank
            val referenceCurve = referenceCurveForBank(bank) ?: return@forEachVisibleBank
            bands.forEachIndexed { index, band ->
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
    }

    /**
     * The real combined curve [drawPerBandFills] should hug for [bank] -- the same arithmetic
     * L/R mean [drawSystemCurve] already draws for that bank's branch/pre-split line, so a
     * fill's top edge sits exactly on the visible line for that bank, not a separately-computed
     * approximation of it. Low and Mid are always available; Full only while it is active.
     */
    private fun referenceCurveForBank(bank: BmwPeqBank): DoubleArray? {
        val perChannel = when (bank) {
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

    /**
     * Low Band and Mid Band are always visible so their filters stay on screen no matter which
     * scope chip is selected; Input Correction (FULL) is only shown while it is the active bank.
     * 1-based index within each list matches the node number and the tap-info card's "#n".
     */
    private inline fun forEachVisibleBank(action: (BmwPeqBank, List<ParametricEqBand>) -> Unit) {
        if (activeBank == BmwPeqBank.FULL) action(BmwPeqBank.FULL, allFullRangeBands)
        action(BmwPeqBank.LOW, allLowBandBands)
        action(BmwPeqBank.MID, allMidBandBands)
    }

    private fun drawSumCurve(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val leftDb = curves.sumDb[BmwOutputChannel.LEFT.ordinal]
        val rightDb = curves.sumDb[BmwOutputChannel.RIGHT.ordinal]
        if (channelDisplay != ChannelDisplay.RIGHT) {
            drawSumChannelMonoAware(canvas, leftDb, rightDb, left, right, sumPaintSolid)
        }
        if (channelDisplay != ChannelDisplay.LEFT) {
            drawSumChannelMonoAware(canvas, rightDb, leftDb, left, right, sumPaintDashed)
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
        strokeNeon(canvas, path, sumPaintSolid)
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
        strokeNeon(canvas, path, paint)
    }

    private fun drawSystemCurveForChannel(canvas: Canvas, values: DoubleArray, left: Float, right: Float, paint: Paint, toY: (Double) -> Float) {
        if (values.isEmpty()) return
        val path = Path()
        for (i in values.indices) {
            val x = left + (i.toFloat() / (values.size - 1).coerceAtLeast(1)) * (right - left)
            val y = toY(values[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        strokeNeon(canvas, path, paint)
    }

    /**
     * L/R sum for magnitude view: like [drawSystemCurveForChannel] but, where Mono Bass is
     * engaged ([monoBassBlendAt]), each point is pulled toward the L/R mean so the solid-L and
     * dashed-R lines visibly converge across the mono-bass region.
     */
    private fun drawSumChannelMonoAware(canvas: Canvas, self: DoubleArray, other: DoubleArray, left: Float, right: Float, paint: Paint) {
        if (self.isEmpty()) return
        val path = Path()
        for (i in self.indices) {
            val frequency = curves.frequencies.getOrElse(i) { maximumFrequency }
            val blend = monoBassBlendAt(frequency)
            val value = if (blend <= 0f) self[i] else self[i] + (((self[i] + other[i]) * 0.5) - self[i]) * blend
            val x = left + (i.toFloat() / (self.size - 1).coerceAtLeast(1)) * (right - left)
            val y = yForGain(value)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        strokeNeon(canvas, path, paint)
    }

    private fun drawFilterOverlays(canvas: Canvas, left: Float, right: Float) {
        if (!showIndividualFilters) return
        forEachVisibleBank { bank, bands ->
            bands.forEachIndexed { index, band ->
                val response = BiquadUtils.computeCombinedResponse(
                    listOf(band), OVERLAY_POINT_COUNT, PeqGraphMath.MIN_FREQUENCY, maximumFrequency, sampleRate, band.channel,
                )
                if (response.isEmpty()) return@forEachIndexed
                val path = Path()
                response.forEachIndexed { i, (_, gain) ->
                    val x = left + (i.toFloat() / (response.size - 1).coerceAtLeast(1)) * (right - left)
                    val y = yForGain(gain)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                // Same palette entry as this band's node dot, fill, and info card -- "this shape"
                // and "this dot" are unmistakably one filter.
                unifiedOverlayPaint.color = perBandPalette[index % perBandPalette.size]
                unifiedOverlayPaint.alpha = if (band.uuid == selectedId && bank == activeBank) 235 else 130
                unifiedOverlayPaint.pathEffect = if (band.channel == ParametricEqChannel.RIGHT) unifiedOverlayDashEffect else null
                canvas.drawPath(path, unifiedOverlayPaint)
            }
        }
    }

    private fun drawMultiBankNodes(canvas: Canvas) {
        forEachVisibleBank { bank, bands -> drawBankNodes(canvas, bands, bank, emphasised = bank == activeBank) }
    }

    private fun drawBankNodes(canvas: Canvas, bands: List<ParametricEqBand>, bank: BmwPeqBank, emphasised: Boolean) {
        val baseRadiusDp = if (emphasised) ACTIVE_NODE_RADIUS_DP else SECONDARY_NODE_RADIUS_DP
        bands.forEachIndexed { index, band ->
            val color = perBandPalette[index % perBandPalette.size]
            val x = xForFrequency(band.frequency)
            val y = yForGain(band.gain)
            val selected = emphasised && band.uuid == selectedId
            val highlighted = selected || (infoBand?.uuid == band.uuid && infoBandBank == bank)
            if (highlighted) {
                nodeHaloPaint.color = color
                nodeHaloPaint.alpha = 60
                canvas.drawCircle(x, y, baseRadiusDp * density + 7f * density, nodeHaloPaint)
            }
            val radius = (if (selected) baseRadiusDp + 1.5f else baseRadiusDp) * density
            nodeFillPaint.color = color
            nodeFillPaint.alpha = 255
            canvas.drawCircle(x, y, radius, nodeFillPaint)
            // Right-only bands get a dark ring on top of the fill -- same solid(L)/marked(R)
            // convention as the dashed R sum curve -- since color alone is hard to read at
            // this size for colorblind users and small screens.
            if (band.channel == ParametricEqChannel.RIGHT) {
                nodeRingPaint.alpha = 255
                canvas.drawCircle(x, y, radius, nodeRingPaint)
            }
            nodeTextPaint.color =
                if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
            val baseline = y - (nodeTextPaint.ascent() + nodeTextPaint.descent()) / 2
            canvas.drawText((index + 1).toString(), x, baseline, nodeTextPaint)
        }
    }

    private fun bankLabel(bank: BmwPeqBank): String = when (bank) {
        BmwPeqBank.FULL -> "Input Correction"
        BmwPeqBank.LOW -> "Low Band"
        BmwPeqBank.MID -> "Mid Band"
    }

    private fun drawInfoCard(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val band = infoBand ?: return
        val bank = infoBandBank ?: return
        val fraction = infoCardAlphaFraction()
        if (fraction <= 0f) return
        val a = (fraction * 255f).roundToInt()
        val lines = arrayOf(
            "#$infoBandNumber · ${band.filterType.displayLabel} · ${band.channel.displayLabel}",
            "${band.frequency.roundToInt()} Hz · ${"%+.1f".format(band.gain)} dB · Q ${"%.2f".format(band.q)}",
            "${bankLabel(bank)} band",
        )
        val pad = 8f * density
        val lineHeight = infoTextPaint.textSize * 1.42f
        val boxWidth = (lines.maxOf { infoTextPaint.measureText(it) } + pad * 2f).coerceAtMost(right - left)
        val boxHeight = lineHeight * lines.size + pad * 2f
        val nodeX = xForFrequency(band.frequency)
        val nodeY = yForGain(band.gain)
        val boxLeft = (nodeX - boxWidth / 2f).coerceIn(left, (right - boxWidth).coerceAtLeast(left))
        val above = nodeY - 15f * density - boxHeight
        val boxTop = if (above >= top) above else (nodeY + 15f * density).coerceAtMost(bottom - boxHeight)
        val rect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        infoCardBgPaint.alpha = (a * 0.94f).roundToInt()
        infoCardStrokePaint.color = perBandPalette[(infoBandNumber - 1).coerceAtLeast(0) % perBandPalette.size]
        infoCardStrokePaint.alpha = a
        infoTextPaint.alpha = a
        canvas.drawRoundRect(rect, 6f * density, 6f * density, infoCardBgPaint)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, infoCardStrokePaint)
        lines.forEachIndexed { i, text ->
            canvas.drawText(text, boxLeft + pad, boxTop + pad + lineHeight * (i + 0.82f), infoTextPaint)
        }
    }

    /** Static tilt markers -- pivot frequency (diamond) and tilt amount (circle). Read-only:
     *  tilt is adjusted numerically on its own page now, not dragged here. */
    private fun drawTiltHandles(canvas: Canvas) {
        val tilt = currentTiltValues()
        val enabled = systemValues[NativeBmwDspValues.INDEX_TILT_ENABLED] >= .5f
        val pivotX = xForFrequency(tilt.frequencyHz.toDouble())
        val pivotY = yForGain(0.0)
        val amountX = pivotX + 22f * density
        val amountY = yForGain(tilt.amountDb.toDouble())
        val paint = if (enabled) tiltHandlePaint else tiltHandleDimPaint

        canvas.drawLine(pivotX, amountY, amountX, amountY, paint)
        canvas.drawLine(pivotX, pivotY, pivotX, amountY, paint)

        val r = TILT_HANDLE_DRAW_RADIUS_DP * density
        val diamond = Path().apply {
            moveTo(pivotX, pivotY - r)
            lineTo(pivotX + r, pivotY)
            lineTo(pivotX, pivotY + r)
            lineTo(pivotX - r, pivotY)
            close()
        }
        canvas.drawPath(diamond, paint)
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
        val sumNote = if (monoBassActive()) {
            "FINAL SUM (L solid / R dashed, mono below ${monoBassFrequency().roundToInt()} Hz)"
        } else {
            "FINAL SUM (L solid / R dashed) · compressor not shown (nonlinear)"
        }
        canvas.drawText(sumNote, left + 112f * density, baseline, unifiedLegendPaint)
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
        // Plot-point count across the log-frequency x-axis. Higher than it used to be because the
        // trace is now bin-interpolated (SpectrumEngine.magnitudeDbAt) -- the extra points buy
        // smoothness instead of just resampling the same coarse bins.
        private const val SPECTRUM_STEPS = 240
        // Non-active banks (always drawn now) sit a touch smaller than the active bank's so the
        // bank you're actually editing still reads as the primary set.
        private const val SECONDARY_NODE_RADIUS_DP = 6.5f
        private const val ACTIVE_NODE_RADIUS_DP = 8f
        private const val NODE_TOUCH_RADIUS_DP = 22f
        private const val TILT_HANDLE_DRAW_RADIUS_DP = 8f
        private const val PHASE_MIN_DEG = -180.0
        private const val PHASE_MAX_DEG = 180.0
        private const val GROUP_DELAY_MIN_MS = -2.0
        private const val GROUP_DELAY_MAX_MS = 10.0

        private const val METER_FLOOR_DB = -50f
        private const val METER_CEILING_DB = 0f
        // Low enough that 2-3 overlapping bands visibly blend into a mixed color instead of the
        // top-most one just opaquely covering the ones underneath.
        private const val BAND_FILL_ALPHA = 48
        private const val BAND_STROKE_ALPHA = 170

        // Tap-info card: holds long enough to read, then a short fade so the last instant still
        // reads as a fade-out rather than an abrupt cut. Nodes themselves no longer fade.
        private const val INFO_CARD_HOLD_MS = 3600L
        private const val INFO_CARD_FADE_MS = 500L

        private val FREQ_SCALE = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
        )
    }
}
