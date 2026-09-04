package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

    // Every configured Paint / colour / palette this view draws with (see PeqSurfacePaints),
    // reached below as paints.<name>. The two theme colours are resolved here once and handed in.
    private val paints = PeqSurfacePaints(
        density,
        themeTextColor = themeColor(android.R.attr.textColorPrimary),
        themeAccentColor = themeColor(android.R.attr.colorAccent),
    )

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
                if (value) spectrumTicker.start() else spectrumTicker.stop()
            }
        }

    private val spectrumTicker = SpectrumTicker(
        onTick = ::invalidate,
        gainMetersEnabled = { showGainMeters },
    )

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

    // Reused, cleared-and-rebuilt-per-band scratch cascade/accumulator for drawPerBandFills --
    // avoids allocating a new BiquadCascade/ComplexAcc for every band on every frame.
    private val bandCascade = BiquadCascade(1)
    private val bandAcc = ComplexAcc()
    private val fillX = FloatArray(SYSTEM_POINT_COUNT)
    private val fillTopY = FloatArray(SYSTEM_POINT_COUNT)
    private val fillBottomY = FloatArray(SYSTEM_POINT_COUNT)
    private val referenceCurveScratch = DoubleArray(SYSTEM_POINT_COUNT)

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
        if (showSpectrum) spectrumTicker.start()
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
        spectrumTicker.stop()
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
     * Nearest node within [NODE_TOUCH_RADIUS_DP] across all three banks (all always drawn).
     * Numbers are the global 1-based filter number ([bankNumberOffset]), matching the labels.
     */
    private fun hitTestAnyBank(x: Float, y: Float): NodeHit? {
        refreshGeometry()
        val radius = NODE_TOUCH_RADIUS_DP * density
        var best: NodeHit? = null
        var bestDistance = Float.MAX_VALUE
        fun consider(bands: List<ParametricEqBand>, bank: BmwPeqBank) {
            val numberOffset = bankNumberOffset(bank)
            bands.forEachIndexed { index, band ->
                val distance = hypot(x - xForFrequency(band.frequency), y - yForGain(band.gain))
                if (distance <= radius && distance < bestDistance) {
                    bestDistance = distance
                    best = NodeHit(band, bank, numberOffset + index + 1)
                }
            }
        }
        consider(allFullRangeBands, BmwPeqBank.FULL)
        consider(allLowBandBands, BmwPeqBank.LOW)
        consider(allMidBandBands, BmwPeqBank.MID)
        return best
    }

    // --- Coordinate mapping ----------------------------------------------------------------

    private fun plotLeft(): Float = paddingLeft + padLeft
    private fun plotRight(): Float = width - paddingRight - padRight
    private fun plotTop(): Float = paddingTop + padTop
    private fun plotBottom(): Float = height - paddingBottom - padBottom

    // Rebuilt (see refreshGeometry) at the top of every draw pass and every hit-test -- the only
    // two entry points that read plot coordinates -- so the per-call mappers below stay
    // allocation-free in the tight draw loops.
    private var geometry = PeqPlotGeometry(0f, 0f, 0f, 0f, maximumFrequency)

    private fun refreshGeometry() {
        geometry = PeqPlotGeometry(plotLeft(), plotRight(), plotTop(), plotBottom(), maximumFrequency)
    }

    private fun xForFrequency(frequency: Double): Float = geometry.xForFrequency(frequency)
    private fun yForGain(gain: Double): Float = geometry.yForGain(gain)
    private fun yForRange(value: Double, min: Double, max: Double): Float = geometry.yForRange(value, min, max)
    private fun yForPhaseDeg(deg: Double): Float = geometry.yForPhaseDeg(deg)
    private fun yForGroupDelayMs(ms: Double): Float = geometry.yForGroupDelayMs(ms)

    // --- Drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawUnifiedSystem(canvas)
    }

    private fun drawUnifiedSystem(canvas: Canvas) {
        refreshGeometry()
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
                if (showSpectrum && spectrumTicker.isActive) drawUnifiedSpectrum(canvas, left, right, top, bottom)
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
                if (showSpectrum && spectrumTicker.isActive) drawUnifiedSpectrum(canvas, left, right, top, bottom)
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
        drawMeterBar(canvas, leftBarX, top, bottom, barWidth, spectrumTicker.leftMeter)
        drawMeterBar(canvas, rightBarX, top, bottom, barWidth, spectrumTicker.rightMeter)
        canvas.drawText("L", leftBarX + barWidth / 2f, bottom + 15f * density, paints.meterLabelPaint)
        canvas.drawText("R", rightBarX + barWidth / 2f, bottom + 15f * density, paints.meterLabelPaint)
    }

    private fun drawMeterBar(canvas: Canvas, x: Float, top: Float, bottom: Float, width: Float, meter: PeakHoldMeter) {
        canvas.drawRect(x, top, x + width, bottom, paints.meterTrackPaint)
        val rmsFraction = PeakHoldMeter.fractionFor(meter.rmsDb, METER_FLOOR_DB, METER_CEILING_DB)
        val rmsY = bottom - rmsFraction * (bottom - top)
        canvas.drawRect(x, rmsY, x + width, bottom, paints.meterRmsPaint)
        val peakFraction = PeakHoldMeter.fractionFor(meter.peakDb, METER_FLOOR_DB, METER_CEILING_DB)
        val peakY = bottom - peakFraction * (bottom - top)
        canvas.drawLine(x, peakY, x + width, peakY, paints.meterPeakPaint)
        val holdFraction = PeakHoldMeter.fractionFor(meter.holdDb, METER_FLOOR_DB, METER_CEILING_DB)
        val holdY = (bottom - holdFraction * (bottom - top)).coerceIn(top, bottom - 1.5f * density)
        canvas.drawRect(x, holdY - 1.5f * density, x + width, holdY + 1.5f * density, paints.meterHoldPaint)
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
            canvas.drawLine(left, y, right, y, if (value == zeroLine) paints.unifiedZeroPaint else paints.unifiedGridPaint)
            canvas.drawText("${value.toInt()}", 4f * density, y + 3f * density, paints.unifiedLabelPaint)
        }
        FREQ_SCALE.forEach { frequency ->
            val x = xForFrequency(frequency)
            canvas.drawLine(x, top, x, bottom, paints.unifiedGridPaint)
            val label = frequency.prettyNumberFormat()
            canvas.drawText(label, x - paints.unifiedLabelPaint.measureText(label) / 2f, bottom + 15f * density, paints.unifiedLabelPaint)
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
        canvas.drawRect(xForFrequency(lowFreq).coerceIn(left, right), top, xForFrequency(midFreq).coerceIn(left, right), bottom, paints.crossoverShadePaint)
    }

    // Mono-bass display-cue math lives in MonoBassCue now (pure, tested); these stay as the
    // in-view names the draw code and legend already call.
    private fun monoBassActive(): Boolean = MonoBassCue.isActive(systemValues)

    private fun monoBassFrequency(): Double = MonoBassCue.frequency(systemValues, maximumFrequency)

    private fun monoBassBlendAt(frequency: Double): Float =
        MonoBassCue.blendAt(systemValues, frequency, maximumFrequency)

    private fun drawMonoBassRegion(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (!monoBassActive()) return
        val cornerX = xForFrequency(monoBassFrequency()).coerceIn(left, right)
        canvas.drawRect(left, top, cornerX, bottom, paints.crossoverShadePaint)
        canvas.drawLine(cornerX, top, cornerX, bottom, paints.unifiedGridPaint)
        val label = "MONO BASS ▸ ${monoBassFrequency().roundToInt()} Hz"
        canvas.drawText(label, left + 6f * density, bottom - 6f * density, paints.tiltLabelPaint)
    }

    /**
     * Draws [path] as a neon line: a wide, translucent "bloom" pass of [paint]'s colour under a
     * sharp core stroke of [paint] itself. Keeps the thin 1px lines readable on the dark
     * workspace without a software-layer blur.
     */
    private fun strokeNeon(canvas: Canvas, path: Path, paint: Paint) {
        paints.glowPaint.color = paint.color
        paints.glowPaint.alpha = (Color.alpha(paint.color) * 0.16f).roundToInt()
        paints.glowPaint.strokeWidth = paint.strokeWidth * 3.4f
        paints.glowPaint.pathEffect = paint.pathEffect
        canvas.drawPath(path, paints.glowPaint)
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
        if (top != paints.spectrumFillShaderTop || bottom != paints.spectrumFillShaderBottom) {
            paints.unifiedSpectrumFillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                ColorUtils.setAlphaComponent(paints.spectrumAccentColor, 150),
                ColorUtils.setAlphaComponent(paints.spectrumAccentColor, 0),
                Shader.TileMode.CLAMP,
            )
            paints.spectrumFillShaderTop = top
            paints.spectrumFillShaderBottom = bottom
        }
        canvas.drawPath(spectrumFillPath, paints.unifiedSpectrumFillPaint)
        canvas.drawPath(dryStrokePath, paints.dryStrokePaint)
        strokeNeon(canvas, spectrumStrokePath, paints.unifiedSpectrumStrokePaint)
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
        canvas.drawPath(deltaFillPath, if (boost) paints.spectrumBoostFillPaint else paints.spectrumCutFillPaint)
    }

    private fun drawBranchCurves(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        drawBranchChannelPair(canvas, curves.lowBranchDb, left, right, paints.lowBranchPaint, paints.lowBranchPaintDashed)
        drawBranchChannelPair(canvas, curves.midBranchDb, left, right, paints.midBranchPaint, paints.midBranchPaintDashed)
    }

    /** A branch's Low/Mid magnitude curve drawn per channel -- L solid, R dashed -- honouring
     *  the L/R/Both channel-display toggle, the same separation the sum curve shows. */
    private fun drawBranchChannelPair(
        canvas: Canvas,
        perChannelValues: Array<DoubleArray>,
        left: Float,
        right: Float,
        solid: Paint,
        dashed: Paint,
    ) {
        if (channelDisplay != ChannelDisplay.RIGHT) {
            drawSystemCurveForChannel(canvas, perChannelValues[BmwOutputChannel.LEFT.ordinal], left, right, solid, ::yForGain)
        }
        if (channelDisplay != ChannelDisplay.LEFT) {
            drawSystemCurveForChannel(canvas, perChannelValues[BmwOutputChannel.RIGHT.ordinal], left, right, dashed, ::yForGain)
        }
    }

    /**
     * Each band's own contribution to the *actual* combined curve for its bank (in its global
     * [paints.perBandPalette] colour, the same one [drawBankNodes] gives its node) -- filled between the
     * real curve (with this band included, exactly
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
                val color = paints.perBandPalette[(bankNumberOffset(bank) + index) % paints.perBandPalette.size]
                // Paint.setColor() overwrites alpha along with RGB (Color.rgb()'s palette entries are
                // fully opaque), so alpha has to be re-applied after color on every draw -- setting it
                // once in the Paint initializer got silently clobbered the instant color was assigned
                // here, which is why overlapping fills were reading as solid opaque blocks instead of
                // blending together.
                paints.bandFillPaint.color = color
                paints.bandFillPaint.alpha = BAND_FILL_ALPHA
                canvas.drawPath(path, paints.bandFillPaint)
                paints.bandStrokePaint.color = color
                paints.bandStrokePaint.alpha = BAND_STROKE_ALPHA
                canvas.drawPath(path, paints.bandStrokePaint)
            }
        }
    }

    /**
     * The real combined curve [drawPerBandFills] should hug for [bank] -- the primary channel
     * curve [drawBranchChannelPair]/[drawSystemCurve] draws for that bank's branch/pre-split
     * line (left for Both/Left, right for Right), so a fill's top edge sits exactly on the
     * visible line for that bank. All three banks are always available.
     */
    private fun referenceCurveForBank(bank: BmwPeqBank): DoubleArray? {
        val perChannel = when (bank) {
            BmwPeqBank.FULL -> curves.preSplitDb
            BmwPeqBank.LOW -> curves.lowBranchDb
            BmwPeqBank.MID -> curves.midBranchDb
        }
        val channelIndex =
            if (channelDisplay == ChannelDisplay.RIGHT) BmwOutputChannel.RIGHT.ordinal else BmwOutputChannel.LEFT.ordinal
        val values = perChannel[channelIndex]
        if (values.size != SYSTEM_POINT_COUNT) return null
        for (i in 0 until SYSTEM_POINT_COUNT) referenceCurveScratch[i] = values[i]
        return referenceCurveScratch
    }

    /**
     * 1-based global filter number in draw order -- Input Correction, then Low Band, then Mid
     * Band -- so every filter on the graph has a unique number and a unique [paints.perBandPalette]
     * colour, instead of each bank restarting at 1/red.
     */
    private fun bankNumberOffset(bank: BmwPeqBank): Int = when (bank) {
        BmwPeqBank.FULL -> 0
        BmwPeqBank.LOW -> allFullRangeBands.size
        BmwPeqBank.MID -> allFullRangeBands.size + allLowBandBands.size
    }

    /**
     * All three banks' filters stay on screen no matter which scope chip is selected, so nothing
     * disappears when you switch scope; the active bank is still emphasised (brighter overlays,
     * bigger nodes). Global filter numbers ([bankNumberOffset]) match the node labels and the
     * tap-info card's "#n".
     */
    private inline fun forEachVisibleBank(action: (BmwPeqBank, List<ParametricEqBand>) -> Unit) {
        action(BmwPeqBank.FULL, allFullRangeBands)
        action(BmwPeqBank.LOW, allLowBandBands)
        action(BmwPeqBank.MID, allMidBandBands)
    }

    private fun drawSumCurve(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val leftDb = curves.sumDb[BmwOutputChannel.LEFT.ordinal]
        val rightDb = curves.sumDb[BmwOutputChannel.RIGHT.ordinal]
        if (channelDisplay != ChannelDisplay.RIGHT) {
            drawSumChannelMonoAware(canvas, leftDb, rightDb, left, right, paints.sumPaintSolid)
        }
        if (channelDisplay != ChannelDisplay.LEFT) {
            drawSumChannelMonoAware(canvas, rightDb, leftDb, left, right, paints.sumPaintDashed)
        }
    }

    /** Sum phase overlaid on the magnitude graph in MAGNITUDE_PHASE mode -- its own implicit
     *  -180..180 degree scale sharing the plot area, not the dB gridlines shown alongside it. */
    private fun drawSumPhaseOverlay(canvas: Canvas, left: Float, right: Float) {
        drawSystemCurveForChannel(canvas, curves.sumPhase[BmwOutputChannel.LEFT.ordinal], left, right, paints.sumPhaseOverlayPaint) { yForPhaseDeg(Math.toDegrees(it)) }
    }

    private fun drawPhaseCurves(canvas: Canvas, left: Float, right: Float) {
        drawSystemCurve(canvas, curves.lowBranchPhase, left, right, paints.lowBranchPaint) { yForPhaseDeg(Math.toDegrees(it)) }
        drawSystemCurve(canvas, curves.midBranchPhase, left, right, paints.midBranchPaint) { yForPhaseDeg(Math.toDegrees(it)) }
        if (channelDisplay != ChannelDisplay.RIGHT) {
            drawSystemCurveForChannel(canvas, curves.sumPhase[BmwOutputChannel.LEFT.ordinal], left, right, paints.sumPaintSolid) { yForPhaseDeg(Math.toDegrees(it)) }
        }
        if (channelDisplay != ChannelDisplay.LEFT) {
            drawSystemCurveForChannel(canvas, curves.sumPhase[BmwOutputChannel.RIGHT.ordinal], left, right, paints.sumPaintDashed) { yForPhaseDeg(Math.toDegrees(it)) }
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
        strokeNeon(canvas, path, paints.sumPaintSolid)
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
                paints.unifiedOverlayPaint.color = paints.perBandPalette[(bankNumberOffset(bank) + index) % paints.perBandPalette.size]
                paints.unifiedOverlayPaint.alpha = if (band.uuid == selectedId && bank == activeBank) 235 else 130
                paints.unifiedOverlayPaint.pathEffect = if (band.channel == ParametricEqChannel.RIGHT) paints.unifiedOverlayDashEffect else null
                canvas.drawPath(path, paints.unifiedOverlayPaint)
            }
        }
    }

    private fun drawMultiBankNodes(canvas: Canvas) {
        forEachVisibleBank { bank, bands -> drawBankNodes(canvas, bands, bank, emphasised = bank == activeBank) }
    }

    private fun drawBankNodes(canvas: Canvas, bands: List<ParametricEqBand>, bank: BmwPeqBank, emphasised: Boolean) {
        val baseRadiusDp = if (emphasised) ACTIVE_NODE_RADIUS_DP else SECONDARY_NODE_RADIUS_DP
        val numberOffset = bankNumberOffset(bank)
        bands.forEachIndexed { index, band ->
            val color = paints.perBandPalette[(numberOffset + index) % paints.perBandPalette.size]
            val x = xForFrequency(band.frequency)
            val y = yForGain(band.gain)
            val selected = emphasised && band.uuid == selectedId
            val highlighted = selected || (infoBand?.uuid == band.uuid && infoBandBank == bank)
            if (highlighted) {
                paints.nodeHaloPaint.color = color
                paints.nodeHaloPaint.alpha = 60
                canvas.drawCircle(x, y, baseRadiusDp * density + 7f * density, paints.nodeHaloPaint)
            }
            val radius = (if (selected) baseRadiusDp + 1.5f else baseRadiusDp) * density
            paints.nodeFillPaint.color = color
            paints.nodeFillPaint.alpha = 255
            canvas.drawCircle(x, y, radius, paints.nodeFillPaint)
            // Right-only bands get a dark ring on top of the fill -- same solid(L)/marked(R)
            // convention as the dashed R sum curve -- since color alone is hard to read at
            // this size for colorblind users and small screens.
            if (band.channel == ParametricEqChannel.RIGHT) {
                paints.nodeRingPaint.alpha = 255
                canvas.drawCircle(x, y, radius, paints.nodeRingPaint)
            }
            paints.nodeTextPaint.color =
                if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
            val baseline = y - (paints.nodeTextPaint.ascent() + paints.nodeTextPaint.descent()) / 2
            canvas.drawText((numberOffset + index + 1).toString(), x, baseline, paints.nodeTextPaint)
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
        val lineHeight = paints.infoTextPaint.textSize * 1.42f
        val boxWidth = (lines.maxOf { paints.infoTextPaint.measureText(it) } + pad * 2f).coerceAtMost(right - left)
        val boxHeight = lineHeight * lines.size + pad * 2f
        val nodeX = xForFrequency(band.frequency)
        val nodeY = yForGain(band.gain)
        val boxLeft = (nodeX - boxWidth / 2f).coerceIn(left, (right - boxWidth).coerceAtLeast(left))
        val above = nodeY - 15f * density - boxHeight
        val boxTop = if (above >= top) above else (nodeY + 15f * density).coerceAtMost(bottom - boxHeight)
        val rect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        paints.infoCardBgPaint.alpha = (a * 0.94f).roundToInt()
        paints.infoCardStrokePaint.color = paints.perBandPalette[(infoBandNumber - 1).coerceAtLeast(0) % paints.perBandPalette.size]
        paints.infoCardStrokePaint.alpha = a
        paints.infoTextPaint.alpha = a
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paints.infoCardBgPaint)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paints.infoCardStrokePaint)
        lines.forEachIndexed { i, text ->
            canvas.drawText(text, boxLeft + pad, boxTop + pad + lineHeight * (i + 0.82f), paints.infoTextPaint)
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
        val paint = if (enabled) paints.tiltHandlePaint else paints.tiltHandleDimPaint

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
            canvas.drawText("TILT BYPASSED", pivotX + r + 6f * density, pivotY - 6f * density, paints.tiltLabelPaint)
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
                    paints.unifiedLegendPaint,
                )
                return
            }
            DisplayMode.PHASE -> {
                val lowPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorLow }
                val midPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorMid }
                canvas.drawText("LOW", left, baseline, lowPaint)
                canvas.drawText("MID", left + 38f * density, baseline, midPaint)
                canvas.drawText(
                    "FINAL SUM PHASE (L solid / R dashed) · compressor not shown (nonlinear)",
                    left + 76f * density,
                    baseline,
                    paints.unifiedLegendPaint,
                )
                return
            }
            DisplayMode.MAGNITUDE_PHASE -> {
                val fullPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorFull }
                val lowPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorLow }
                val midPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorMid }
                canvas.drawText("FULL", left, baseline, fullPaint)
                canvas.drawText("LOW", left + 38f * density, baseline, lowPaint)
                canvas.drawText("MID", left + 74f * density, baseline, midPaint)
                canvas.drawText(
                    "SUM SOLID = MAGNITUDE, THIN DASHED = PHASE",
                    left + 112f * density,
                    baseline,
                    paints.unifiedLegendPaint,
                )
                return
            }
            DisplayMode.MAGNITUDE -> Unit
        }
        val fullPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorFull }
        val lowPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorLow }
        val midPaint = Paint(paints.unifiedLegendPaint).apply { color = paints.bankColorMid }
        canvas.drawText("FULL", left, baseline, fullPaint)
        canvas.drawText("LOW", left + 38f * density, baseline, lowPaint)
        canvas.drawText("MID", left + 74f * density, baseline, midPaint)
        val sumNote = if (monoBassActive()) {
            "FINAL SUM (L solid / R dashed, mono below ${monoBassFrequency().roundToInt()} Hz)"
        } else {
            "FINAL SUM (L solid / R dashed) · compressor not shown (nonlinear)"
        }
        canvas.drawText(sumNote, left + 112f * density, baseline, paints.unifiedLegendPaint)
    }

    private fun updateContentDescription() {
        val band = renderBands.firstOrNull { it.uuid == selectedId }
        contentDescription = if (band == null) {
            "Interactive parametric equalizer graph, ${renderBands.size} filters"
        } else {
            val number = bankNumberOffset(activeBank) + renderBands.indexOfFirst { it.uuid == band.uuid } + 1
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
