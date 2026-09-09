package app.siphondsp.compose.screens

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.compose.theme.BmwDspTheme
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
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.utils.extensions.prettyNumberFormat
import app.siphondsp.view.MonoBassCue
import app.siphondsp.view.PeakHoldMeter
import app.siphondsp.view.PeqGraphMath
import app.siphondsp.view.PeqPlotGeometry
import app.siphondsp.view.PeqSurfacePaints
import kotlinx.coroutines.delay
import android.graphics.Color as AndroidColor
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Compose-drawn Parametric EQ response graph (roadmap Phase 10c-i) — the replacement for the
 * `AndroidView(ParametricEqSurface)` wrapper. Built in stacked sub-PRs:
 *
 *  - 10c-i-a: the static plot frame — grid, axis labels, crossover + mono-bass shading, legend.
 *  - **10c-i-b (this):** the branch / per-band / sum curves, the individual-filter overlays, and
 *    the live spectrum trace. Adds [PeqGraph]; still verified via a temporary side-by-side
 *    `ComposeView` next to the live `ParametricEqSurface`, not wired as the real graph yet.
 *  - 10c-i-c: numbered nodes, tap hit-testing, the auto-fading detail callout, tilt handles,
 *    gain meters, and the graph-options menu.
 *
 * Drawing is kept 1:1 with `ParametricEqSurface`: geometry routes through the same
 * [PeqPlotGeometry] / [PeqGraphMath], the modelled response comes from the same
 * [BmwResponseCalculator] / [BmwResponseCurves], and every stroke uses the very same
 * [PeqSurfacePaints] the View draws with, so "the Compose graph" and "the old View graph" render
 * identically until 10c-ii deliberately changes the paint treatment.
 */

/**
 * Display modes the Compose graph supports. Deliberately just two (2026-09-09 direction):
 * `ParametricEqSurface.DisplayMode` still also carries `MAGNITUDE_PHASE` and `GROUP_DELAY` for the
 * not-yet-retired View fragment, but the port drops both.
 */
enum class PeqGraphMode { MAGNITUDE, PHASE }

/** Which channel(s) to draw — mirror of `ParametricEqSurface.ChannelDisplay`. */
enum class PeqChannelDisplay { BOTH, LEFT, RIGHT }

// --- constants, all 1:1 with ParametricEqSurface ------------------------------------------------

private const val SYSTEM_POINT_COUNT = 192
private const val OVERLAY_POINT_COUNT = 96
private const val SPECTRUM_STEPS = 240
private const val BAND_FILL_ALPHA = 48
private const val BAND_STROKE_ALPHA = 170

private const val SECONDARY_NODE_RADIUS_DP = 6.5f
private const val ACTIVE_NODE_RADIUS_DP = 8f
private const val NODE_TOUCH_RADIUS_DP = 22f
private const val TILT_HANDLE_DRAW_RADIUS_DP = 8f
private const val METER_FLOOR_DB = -50f
private const val METER_CEILING_DB = 0f

// Node auto-fade after idle (2026-09-09 direction): the dots recede so the response shape stays
// readable; curves never fade and hit-testing stays live. Any interaction snaps them back.
private const val NODE_IDLE_FADE_DELAY_MS = 6_000L
private const val NODE_FADE_DURATION_MS = 400

// Plot insets — 1:1 with ParametricEqSurface.padLeft/padTop/padRight/padBottom.
private val PlotPadLeft = 34.dp
private val PlotPadTop = 16.dp
private val PlotPadRight = 44.dp
private val PlotPadBottom = 22.dp

// Horizontal gridline values per mode — 1:1 with drawUnifiedGrid / drawPhaseGrid.
private val MagnitudeGridLines = floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f)
private val PhaseGridLines = floatArrayOf(180f, 90f, 0f, -90f, -180f)

// Decade-ish vertical frequency markers — 1:1 with ParametricEqSurface.FREQ_SCALE.
private val FreqScale = doubleArrayOf(
    25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
    1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
)

// --- public composables ----------------------------------------------------------------------

/**
 * The static plot frame only — grid + axis labels + crossover / mono-bass shading + legend.
 * No modelled curves, no nodes, no interaction. Kept as a standalone for previews and any
 * future static-preview use; [PeqGraph] draws the same frame plus the response.
 *
 * @param systemValues the 192-float native BMW DSP config array ([BmwSignalChain.VALUE_COUNT]);
 *        only the crossover-frequency and mono-bass fields are read. A wrong-sized array is
 *        tolerated — the shading passes just no-op, exactly as the View guards.
 */
@Composable
fun PeqGraphFrame(
    systemValues: FloatArray,
    modifier: Modifier = Modifier,
    mode: PeqGraphMode = PeqGraphMode.MAGNITUDE,
    sampleRate: Double = 48_000.0,
) {
    val paints = rememberPeqSurfacePaints()
    val maxFrequency = remember(sampleRate) {
        min(PeqGraphMath.MAX_FREQUENCY, sampleRate * 0.5 * 0.999)
    }
    ComposeCanvas(modifier) {
        val left = PlotPadLeft.toPx()
        val top = PlotPadTop.toPx()
        val right = size.width - PlotPadRight.toPx()
        val bottom = size.height - PlotPadBottom.toPx()
        if (right <= left || bottom <= top) return@ComposeCanvas
        val geometry = PeqPlotGeometry(left, right, top, bottom, maxFrequency)
        val gridLines = if (mode == PeqGraphMode.PHASE) PhaseGridLines else MagnitudeGridLines
        val toY: (Double) -> Float =
            if (mode == PeqGraphMode.PHASE) geometry::yForPhaseDeg else geometry::yForGain
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            drawGrid(nc, geometry, paints, density, gridLines, toY)
            drawCrossoverShading(nc, geometry, paints, systemValues, maxFrequency)
            if (mode == PeqGraphMode.MAGNITUDE) {
                drawMonoBassRegion(nc, geometry, paints, density, systemValues, maxFrequency)
            }
            drawLegend(nc, geometry, paints, density, systemValues, maxFrequency, mode)
        }
    }
}

/** The three graph-options callbacks, bundled so [PeqGraph] can host the ⋮ menu itself. Null =
 *  no menu affordance (the caller drives `mode` / `channelDisplay` / `showIndividualFilters` some
 *  other way). */
class PeqGraphOptions(
    val onModeChange: (PeqGraphMode) -> Unit,
    val onChannelDisplayChange: (PeqChannelDisplay) -> Unit,
    val onShowIndividualFiltersChange: (Boolean) -> Unit,
)

/**
 * The full response graph: [PeqGraphFrame]'s frame plus the modelled branch / per-band / sum
 * curves, the individual-filter overlays, the live spectrum trace (10c-i-b), and now (10c-i-c)
 * the numbered per-bank nodes with tap-to-detail, the 6 s idle node fade, the read-only tilt
 * handles, the L/R gain meters, and the ⋮ graph-options menu.
 *
 * Stateless: the caller passes the current native config, PEQ state, active bank and selection,
 * plus [onNodeTapped] (select the band + scroll its list row). Node fade is owned here — any tap
 * or any change to [peqState] / [activeBank] snaps the dots back to full opacity.
 */
@Composable
fun PeqGraph(
    systemValues: FloatArray,
    peqState: BmwPeqState,
    activeBank: BmwPeqBank,
    selectedBandId: UUID?,
    modifier: Modifier = Modifier,
    mode: PeqGraphMode = PeqGraphMode.MAGNITUDE,
    channelDisplay: PeqChannelDisplay = PeqChannelDisplay.BOTH,
    showIndividualFilters: Boolean = true,
    showSpectrum: Boolean = true,
    showTiltHandles: Boolean = false,
    showGainMeters: Boolean = false,
    sampleRate: Double = 48_000.0,
    onNodeTapped: (ParametricEqBand) -> Unit = {},
    graphOptions: PeqGraphOptions? = null,
) {
    val paints = rememberPeqSurfacePaints()
    val glass = rememberGlassPaints()
    val model = remember { PeqResponseModel() }
    DisposableEffect(model) { onDispose { model.recycleBitmaps() } }
    val maxFrequency = remember(sampleRate) {
        min(PeqGraphMath.MAX_FREQUENCY, sampleRate * 0.5 * 0.999)
    }

    // Synchronous recompute of the modelled curves on any real input change. contentHashCode()
    // keys it even when the fragment mutates the same FloatArray in place (it does, on the
    // native-DSP broadcast). configureAxis() inside is a no-op when the axis is unchanged.
    val valuesHash = systemValues.contentHashCode()
    remember(valuesHash, peqState, sampleRate) {
        model.recompute(systemValues, peqState, sampleRate)
    }

    // Spectrum + gain-meter poll, scoped to composition. Acquire/release bracket SpectrumEngine;
    // the tick counter is read only in the draw phase below so it never triggers recomposition.
    // The two PeakHoldMeters decay on the same tick (only while showGainMeters).
    //
    // ~20 fps, not 30: every tick invalidates the whole Canvas — grid, curves, the real-blur sum
    // glow, glass nodes and all — and the blur passes are the per-frame cost that pegs a
    // software-GL head unit. 20 fps + the §7 peak-hold markers still read as "alive".
    val spectrumTick = remember { mutableIntStateOf(0) }
    val leftMeter = remember { PeakHoldMeter(floorDb = SpectrumEngine.LEVEL_FLOOR_DB) }
    val rightMeter = remember { PeakHoldMeter(floorDb = SpectrumEngine.LEVEL_FLOOR_DB) }
    LaunchedEffect(showSpectrum, showGainMeters) {
        if (!showSpectrum && !showGainMeters) return@LaunchedEffect
        val levels = FloatArray(4)
        SpectrumEngine.acquire()
        try {
            while (true) {
                if (showGainMeters) {
                    SpectrumEngine.channelLevelsInto(levels)
                    val now = System.currentTimeMillis()
                    leftMeter.update(levels[0], levels[1], now)
                    rightMeter.update(levels[2], levels[3], now)
                }
                spectrumTick.intValue++
                delay(50L)
            }
        } finally {
            SpectrumEngine.release()
            leftMeter.reset()
            rightMeter.reset()
        }
    }

    // Node auto-fade: any node tap or any change to peqState / activeBank bumps interactionTick,
    // which restarts the 6 s idle timer and snaps nodeAlpha back to 1.
    val nodeAlpha = remember { Animatable(1f) }
    var interactionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(interactionTick, peqState, activeBank) {
        nodeAlpha.snapTo(1f)
        delay(NODE_IDLE_FADE_DELAY_MS)
        nodeAlpha.animateTo(0f, tween(NODE_FADE_DURATION_MS))
    }

    var callout by remember { mutableStateOf<NodeHit?>(null) }
    LaunchedEffect(callout) {
        if (callout != null) {
            delay(3_600L)
            callout = null
        }
    }

    Box(modifier) {
        ComposeCanvas(
            Modifier
                .fillMaxSize()
                .pointerInput(peqState, activeBank, channelDisplay, mode, maxFrequency) {
                    detectTapGestures { offset ->
                        interactionTick++
                        if (mode != PeqGraphMode.MAGNITUDE) {
                            callout = null
                            return@detectTapGestures
                        }
                        val left = PlotPadLeft.toPx()
                        val top = PlotPadTop.toPx()
                        val right = size.width - PlotPadRight.toPx()
                        val bottom = size.height - PlotPadBottom.toPx()
                        if (right <= left || bottom <= top) return@detectTapGestures
                        val geometry = PeqPlotGeometry(left, right, top, bottom, maxFrequency)
                        val hit = hitTestAnyBank(offset, geometry, peqState, density)
                        // Keep the callout on-screen: pin its top-left inside the graph bounds.
                        callout = hit?.let {
                            val maxX = (this.size.width - 190.dp.toPx()).coerceAtLeast(0f)
                            val maxY = (this.size.height - 64.dp.toPx()).coerceAtLeast(0f)
                            NodeHit(
                                it.band, it.bank, it.number,
                                Offset(it.anchor.x.coerceIn(0f, maxX), it.anchor.y.coerceIn(0f, maxY)),
                            )
                        }
                        // Match ParametricEqSurface: only an active-bank node opens in the list.
                        if (hit != null && hit.bank == activeBank) onNodeTapped(hit.band)
                    }
                },
        ) {
            // Read the tick + node alpha here (draw phase), not in composition.
            val spectrumFrame = spectrumTick.intValue
            val dotAlpha = nodeAlpha.value
            val left = PlotPadLeft.toPx()
            val top = PlotPadTop.toPx()
            val right = size.width - PlotPadRight.toPx()
            val bottom = size.height - PlotPadBottom.toPx()
            if (right <= left || bottom <= top) return@ComposeCanvas
            val geometry = PeqPlotGeometry(left, right, top, bottom, maxFrequency)
            val ctx = PeqDrawContext(
                geometry = geometry,
                paints = paints,
                glass = glass,
                model = model,
                density = density,
                systemValues = systemValues,
                peqState = peqState,
                activeBank = activeBank,
                selectedId = selectedBandId,
                channelDisplay = channelDisplay,
                showIndividualFilters = showIndividualFilters,
                sampleRate = sampleRate,
                maxFrequency = maxFrequency,
                mode = mode,
                showTiltHandles = showTiltHandles,
                showGainMeters = showGainMeters,
                nodeAlpha = dotAlpha,
                calloutBandId = callout?.band?.uuid,
                leftMeter = leftMeter,
                rightMeter = rightMeter,
            )
            drawIntoCanvas { canvas ->
                renderPeqGraph(
                    canvas.nativeCanvas, ctx,
                    canvasW = size.width.roundToInt(),
                    canvasH = size.height.roundToInt(),
                    drawSpectrum = showSpectrum,
                    spectrumFrame = spectrumFrame,
                )
            }
        }

        callout?.let { hit ->
            // Border colour-coded to the tapped band, same palette index as its node/overlay/fill
            // (perBandPalette[(number - 1) % size]) — 1:1 with ParametricEqSurface's infoCardStrokePaint.
            val calloutAccent = Color(
                paints.perBandPalette[(hit.number - 1).coerceAtLeast(0) % paints.perBandPalette.size],
            )
            PeqNodeCallout(
                hit = hit,
                accent = calloutAccent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(hit.anchor.x.roundToInt(), hit.anchor.y.roundToInt()) },
            )
        }

        graphOptions?.let { opts ->
            PeqGraphOptionsButton(
                mode = mode,
                channelDisplay = channelDisplay,
                showIndividualFilters = showIndividualFilters,
                options = opts,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun rememberPeqSurfacePaints(): PeqSurfacePaints {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    return remember(context, density) {
        PeqSurfacePaints(
            density = density,
            themeTextColor = themeColor(context, android.R.attr.textColorPrimary),
            themeAccentColor = themeColor(context, android.R.attr.colorAccent),
        ).apply {
            // ANALYZER_VISUAL_SPEC §4 grid hierarchy: recede the regular mesh so the brightened
            // 0 dB / octave lines actually read as emphasised rather than one-more-line.
            unifiedGridPaint.alpha = 90
            unifiedZeroPaint.color = AndroidColor.rgb(132, 136, 146)
            unifiedZeroPaint.alpha = 255
            // §7: push the live spectrum further into the background — greyer + fainter.
            unifiedSpectrumStrokePaint.color =
                ColorUtils.blendARGB(spectrumAccentColor, AndroidColor.rgb(150, 150, 150), 0.55f)
            unifiedSpectrumStrokePaint.alpha = 110
            dryStrokePaint.alpha = 120
        }
    }
}

@Composable
private fun rememberGlassPaints(): PeqGlassPaints {
    val density = LocalDensity.current.density
    return remember(density) { PeqGlassPaints(density) }
}

// --- render orchestration — mirrors ParametricEqSurface.drawUnifiedSystem ----------------------

/**
 * Draws a frame. The static layers (grid + shading in `bg`, curves + nodes + tilt + legend +
 * vignette in `fg`) are cached in bitmaps and rebuilt only when [staticKeyOf] changes; only the
 * live spectrum trace and the gain meters are painted fresh every [spectrumFrame].
 */
private fun renderPeqGraph(
    nc: Canvas,
    ctx: PeqDrawContext,
    canvasW: Int,
    canvasH: Int,
    drawSpectrum: Boolean,
    @Suppress("UNUSED_PARAMETER") spectrumFrame: Int,
) {
    val m = ctx.model
    val w = canvasW.coerceAtLeast(1)
    val h = canvasH.coerceAtLeast(1)

    val bg = ensureBitmap(m.bgBitmap, w, h)?.also { m.bgBitmap = it } ?: return
    val fg = ensureBitmap(m.fgBitmap, w, h)?.also { m.fgBitmap = it } ?: return
    val key = staticKeyOf(ctx, w, h)
    if (key != m.staticKey) {
        m.bgCanvas.setBitmap(bg)
        m.fgCanvas.setBitmap(fg)
        bg.eraseColor(AndroidColor.TRANSPARENT)
        fg.eraseColor(AndroidColor.TRANSPARENT)
        renderStaticLayers(m.bgCanvas, m.fgCanvas, ctx)
        m.bgCanvas.setBitmap(null)
        m.fgCanvas.setBitmap(null)
        m.staticKey = key
    }

    nc.drawBitmap(bg, 0f, 0f, null)
    if (drawSpectrum && ctx.mode == PeqGraphMode.MAGNITUDE) drawUnifiedSpectrum(nc, ctx)
    nc.drawBitmap(fg, 0f, 0f, null)
    if (ctx.showGainMeters && ctx.mode == PeqGraphMode.MAGNITUDE) drawGainMeters(nc, ctx)
}

private fun ensureBitmap(existing: android.graphics.Bitmap?, w: Int, h: Int): android.graphics.Bitmap? {
    if (existing != null && existing.width == w && existing.height == h) return existing
    existing?.recycle()
    return runCatching { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull()
}

/** Everything that changes only when a non-spectrum input changes (see [renderPeqGraph]). */
private fun staticKeyOf(ctx: PeqDrawContext, w: Int, h: Int): Int {
    var k = 17
    k = 31 * k + w
    k = 31 * k + h
    k = 31 * k + ctx.systemValues.contentHashCode()
    // Bands are not a data class — hashCode is identity — but the same instances are re-listed
    // each frame from the same peqState, so this stays stable between commits and flips on any
    // commit (applyCandidate always deep-copies).
    k = 31 * k + ctx.fullBands.hashCode()
    k = 31 * k + 31 * ctx.lowBands.hashCode()
    k = 31 * k + 961 * ctx.midBands.hashCode()
    k = 31 * k + ctx.mode.ordinal
    k = 31 * k + ctx.channelDisplay.ordinal
    k = 31 * k + if (ctx.showIndividualFilters) 1 else 0
    k = 31 * k + if (ctx.showTiltHandles) 1 else 0
    k = 31 * k + (ctx.selectedId?.hashCode() ?: 0)
    k = 31 * k + (ctx.calloutBandId?.hashCode() ?: 0)
    k = 31 * k + (ctx.nodeAlpha * 12f).roundToInt() // bucketed: ~12 rebuilds across the 400ms fade
    return k
}

private fun renderStaticLayers(bg: Canvas, fg: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    when (ctx.mode) {
        PeqGraphMode.MAGNITUDE -> {
            drawGrid(bg, g, ctx.paints, ctx.density, MagnitudeGridLines, { g.yForGain(it) }, ctx.glass.octaveGridPaint)
            drawCrossoverShading(bg, g, ctx.paints, ctx.systemValues, ctx.maxFrequency)
            drawMonoBassRegion(bg, g, ctx.paints, ctx.density, ctx.systemValues, ctx.maxFrequency)

            drawBranchCurves(fg, ctx)
            drawFilterOverlays(fg, ctx)
            drawPerBandFills(fg, ctx)
            drawSumAreaFill(fg, ctx)
            drawSumCurve(fg, ctx)
            if (ctx.showTiltHandles) drawTiltHandles(fg, ctx)
            drawMultiBankNodes(fg, ctx)
        }
        PeqGraphMode.PHASE -> {
            drawGrid(bg, g, ctx.paints, ctx.density, PhaseGridLines, { g.yForPhaseDeg(it) }, ctx.glass.octaveGridPaint)
            drawCrossoverShading(bg, g, ctx.paints, ctx.systemValues, ctx.maxFrequency)
            drawPhaseCurves(fg, ctx)
        }
    }
    drawLegend(fg, g, ctx.paints, ctx.density, ctx.systemValues, ctx.maxFrequency, ctx.mode)
    drawVignette(fg, ctx)
}

// --- frame helpers (raw Canvas, shared by PeqGraphFrame and renderPeqGraph) --------------------

// ANALYZER_VISUAL_SPEC §4: octave-boundary verticals get their own weight.
private val OctaveFreqs = setOf(100.0, 1_000.0, 10_000.0)

private fun drawGrid(
    nc: Canvas,
    g: PeqPlotGeometry,
    p: PeqSurfacePaints,
    density: Float,
    lines: FloatArray,
    toY: (Double) -> Float,
    octavePaint: Paint? = null,
) {
    lines.forEach { value ->
        val y = toY(value.toDouble())
        nc.drawLine(g.left, y, g.right, y, if (value == 0f) p.unifiedZeroPaint else p.unifiedGridPaint)
        nc.drawText(value.toInt().toString(), 4f * density, y + 3f * density, p.unifiedLabelPaint)
    }
    FreqScale.forEach { frequency ->
        val x = g.xForFrequency(frequency)
        val linePaint = if (octavePaint != null && frequency in OctaveFreqs) octavePaint else p.unifiedGridPaint
        nc.drawLine(x, g.top, x, g.bottom, linePaint)
        val label = frequency.prettyNumberFormat()
        nc.drawText(label, x - p.unifiedLabelPaint.measureText(label) / 2f, g.bottom + 15f * density, p.unifiedLabelPaint)
    }
}

private fun drawCrossoverShading(
    nc: Canvas,
    g: PeqPlotGeometry,
    p: PeqSurfacePaints,
    values: FloatArray,
    maxFrequency: Double,
) {
    if (values.size != BmwSignalChain.VALUE_COUNT) return
    // values[1] / values[2] = LPF-pass / HPF-pass bypass flags — no split active.
    if (values[NativeBmwDspValues.INDEX_LPF_PASS] >= .5f || values[NativeBmwDspValues.INDEX_HPF_PASS] >= .5f) return
    val lowFreq = values[
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
    ].toDouble().coerceIn(20.0, maxFrequency)
    val midFreq = values[
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
    ].toDouble().coerceIn(20.0, maxFrequency)
    if (midFreq <= lowFreq) return
    nc.drawRect(
        g.xForFrequency(lowFreq).coerceIn(g.left, g.right),
        g.top,
        g.xForFrequency(midFreq).coerceIn(g.left, g.right),
        g.bottom,
        p.crossoverShadePaint,
    )
}

private fun drawMonoBassRegion(
    nc: Canvas,
    g: PeqPlotGeometry,
    p: PeqSurfacePaints,
    density: Float,
    values: FloatArray,
    maxFrequency: Double,
) {
    if (values.size != BmwSignalChain.VALUE_COUNT || !MonoBassCue.isActive(values)) return
    val frequency = MonoBassCue.frequency(values, maxFrequency)
    val cornerX = g.xForFrequency(frequency).coerceIn(g.left, g.right)
    nc.drawRect(g.left, g.top, cornerX, g.bottom, p.crossoverShadePaint)
    nc.drawLine(cornerX, g.top, cornerX, g.bottom, p.unifiedGridPaint)
    nc.drawText(
        "MONO BASS ▸ ${frequency.roundToInt()} Hz",
        g.left + 6f * density,
        g.bottom - 6f * density,
        p.tiltLabelPaint,
    )
}

private fun drawLegend(
    nc: Canvas,
    g: PeqPlotGeometry,
    p: PeqSurfacePaints,
    density: Float,
    values: FloatArray,
    maxFrequency: Double,
    mode: PeqGraphMode,
) {
    val baseline = g.top - 6f * density
    fun tinted(color: Int) = Paint(p.unifiedLegendPaint).apply { this.color = color }
    val monoActive = values.size == BmwSignalChain.VALUE_COUNT && MonoBassCue.isActive(values)
    when (mode) {
        PeqGraphMode.PHASE -> {
            nc.drawText("LOW", g.left, baseline, tinted(p.bankColorLow))
            nc.drawText("MID", g.left + 38f * density, baseline, tinted(p.bankColorMid))
            nc.drawText(
                "FINAL SUM PHASE (L solid / R dashed) · compressor not shown (nonlinear)",
                g.left + 76f * density, baseline, p.unifiedLegendPaint,
            )
        }
        PeqGraphMode.MAGNITUDE -> {
            nc.drawText("FULL", g.left, baseline, tinted(p.bankColorFull))
            nc.drawText("LOW", g.left + 38f * density, baseline, tinted(p.bankColorLow))
            nc.drawText("MID", g.left + 74f * density, baseline, tinted(p.bankColorMid))
            val sumNote = if (monoActive) {
                "FINAL SUM (L solid / R dashed, mono below " +
                    "${MonoBassCue.frequency(values, maxFrequency).roundToInt()} Hz)"
            } else {
                "FINAL SUM (L solid / R dashed) · compressor not shown (nonlinear)"
            }
            nc.drawText(sumNote, g.left + 112f * density, baseline, p.unifiedLegendPaint)
        }
    }
}

// --- curve helpers — each mirrors the same-named ParametricEqSurface method --------------------

private fun strokeNeon(nc: Canvas, path: Path, paint: Paint, glow: Paint) {
    glow.color = paint.color
    glow.alpha = (AndroidColor.alpha(paint.color) * 0.16f).roundToInt()
    glow.strokeWidth = paint.strokeWidth * 3.4f
    glow.pathEffect = paint.pathEffect
    nc.drawPath(path, glow)
    nc.drawPath(path, paint)
}

/**
 * ANALYZER_VISUAL_SPEC §1: real Gaussian blur glow beneath a crisp core stroke — replaces
 * [strokeNeon]'s fake wide-stroke approximation for the primary (summed) curve only.
 */
private fun drawGlowStroke(nc: Canvas, glass: PeqGlassPaints, path: Path, paint: Paint) {
    val glow = glass.sumGlowPaint
    glow.color = paint.color
    glow.alpha = 170
    glow.strokeWidth = paint.strokeWidth * 1.6f
    glow.pathEffect = paint.pathEffect
    nc.drawPath(path, glow)
    nc.drawPath(path, paint)
}

private fun drawBranchCurves(nc: Canvas, ctx: PeqDrawContext) {
    drawBranchChannelPair(nc, ctx, ctx.curves.lowBranchDb, ctx.paints.lowBranchPaint, ctx.paints.lowBranchPaintDashed)
    drawBranchChannelPair(nc, ctx, ctx.curves.midBranchDb, ctx.paints.midBranchPaint, ctx.paints.midBranchPaintDashed)
}

private fun drawBranchChannelPair(
    nc: Canvas,
    ctx: PeqDrawContext,
    perChannel: Array<DoubleArray>,
    solid: Paint,
    dashed: Paint,
) {
    if (ctx.channelDisplay != PeqChannelDisplay.RIGHT) {
        drawCurveForChannel(nc, ctx, perChannel[BmwOutputChannel.LEFT.ordinal], solid) { ctx.geometry.yForGain(it) }
    }
    if (ctx.channelDisplay != PeqChannelDisplay.LEFT) {
        drawCurveForChannel(nc, ctx, perChannel[BmwOutputChannel.RIGHT.ordinal], dashed) { ctx.geometry.yForGain(it) }
    }
}

/** = ParametricEqSurface.drawSystemCurveForChannel. */
private fun drawCurveForChannel(
    nc: Canvas,
    ctx: PeqDrawContext,
    values: DoubleArray,
    paint: Paint,
    toY: (Double) -> Float,
) {
    if (values.isEmpty()) return
    val g = ctx.geometry
    val path = Path()
    for (i in values.indices) {
        val x = g.left + (i.toFloat() / (values.size - 1).coerceAtLeast(1)) * (g.right - g.left)
        val y = toY(values[i])
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    strokeNeon(nc, path, paint, ctx.paints.glowPaint)
}

/** = ParametricEqSurface.drawSystemCurve (arithmetic L/R mean, used for the phase branch lines). */
private fun drawCurveAverage(
    nc: Canvas,
    ctx: PeqDrawContext,
    perChannel: Array<DoubleArray>,
    paint: Paint,
    toY: (Double) -> Float,
) {
    val leftValues = perChannel[BmwOutputChannel.LEFT.ordinal]
    val rightValues = perChannel[BmwOutputChannel.RIGHT.ordinal]
    if (leftValues.isEmpty()) return
    val g = ctx.geometry
    val path = Path()
    for (i in leftValues.indices) {
        val avg = (leftValues[i] + rightValues[i]) * 0.5
        val x = g.left + (i.toFloat() / (leftValues.size - 1).coerceAtLeast(1)) * (g.right - g.left)
        val y = toY(avg)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    strokeNeon(nc, path, paint, ctx.paints.glowPaint)
}

private fun drawPhaseCurves(nc: Canvas, ctx: PeqDrawContext) {
    val toY: (Double) -> Float = { ctx.geometry.yForPhaseDeg(Math.toDegrees(it)) }
    drawCurveAverage(nc, ctx, ctx.curves.lowBranchPhase, ctx.paints.lowBranchPaint, toY)
    drawCurveAverage(nc, ctx, ctx.curves.midBranchPhase, ctx.paints.midBranchPaint, toY)
    if (ctx.channelDisplay != PeqChannelDisplay.RIGHT) {
        drawCurveForChannel(nc, ctx, ctx.curves.sumPhase[BmwOutputChannel.LEFT.ordinal], ctx.paints.sumPaintSolid, toY)
    }
    if (ctx.channelDisplay != PeqChannelDisplay.LEFT) {
        drawCurveForChannel(nc, ctx, ctx.curves.sumPhase[BmwOutputChannel.RIGHT.ordinal], ctx.paints.sumPaintDashed, toY)
    }
}

private fun drawSumCurve(nc: Canvas, ctx: PeqDrawContext) {
    val leftDb = ctx.curves.sumDb[BmwOutputChannel.LEFT.ordinal]
    val rightDb = ctx.curves.sumDb[BmwOutputChannel.RIGHT.ordinal]
    if (ctx.channelDisplay != PeqChannelDisplay.RIGHT) {
        drawSumChannelMonoAware(nc, ctx, leftDb, rightDb, ctx.paints.sumPaintSolid)
    }
    if (ctx.channelDisplay != PeqChannelDisplay.LEFT) {
        drawSumChannelMonoAware(nc, ctx, rightDb, leftDb, ctx.paints.sumPaintDashed)
    }
}

private fun drawSumChannelMonoAware(
    nc: Canvas,
    ctx: PeqDrawContext,
    self: DoubleArray,
    other: DoubleArray,
    paint: Paint,
) {
    if (self.isEmpty()) return
    val g = ctx.geometry
    val path = Path()
    for (i in self.indices) {
        val frequency = ctx.curves.frequencies.getOrElse(i) { ctx.maxFrequency }
        val blend = ctx.monoBassBlendAt(frequency)
        val value = if (blend <= 0f) self[i] else self[i] + (((self[i] + other[i]) * 0.5) - self[i]) * blend
        val x = g.left + (i.toFloat() / (self.size - 1).coerceAtLeast(1)) * (g.right - g.left)
        val y = g.yForGain(value)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawGlowStroke(nc, ctx.glass, path, paint)
}

/**
 * ANALYZER_VISUAL_SPEC §2: gradient area fill under the primary summed curve, fading toward the
 * 0 dB reference (PEQ's gain axis is symmetric around it). Drawn behind the stroke + glow.
 */
private fun drawSumAreaFill(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val primaryIsRight = ctx.channelDisplay == PeqChannelDisplay.RIGHT
    val self = ctx.curves.sumDb[if (primaryIsRight) BmwOutputChannel.RIGHT.ordinal else BmwOutputChannel.LEFT.ordinal]
    val other = ctx.curves.sumDb[if (primaryIsRight) BmwOutputChannel.LEFT.ordinal else BmwOutputChannel.RIGHT.ordinal]
    if (self.isEmpty()) return
    val zeroY = g.yForGain(0.0)
    val path = ctx.model.areaFillPath
    path.rewind()
    for (i in self.indices) {
        val frequency = ctx.curves.frequencies.getOrElse(i) { ctx.maxFrequency }
        val blend = ctx.monoBassBlendAt(frequency)
        val value = if (blend <= 0f) self[i] else self[i] + (((self[i] + other[i]) * 0.5) - self[i]) * blend
        val x = g.left + (i.toFloat() / (self.size - 1).coerceAtLeast(1)) * (g.right - g.left)
        val y = g.yForGain(value)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.lineTo(g.right, zeroY)
    path.lineTo(g.left, zeroY)
    path.close()

    val glass = ctx.glass
    val key = g.top.roundToInt() * 92821 + g.bottom.roundToInt()
    if (glass.areaFillKey != key) {
        val col = ctx.paints.sumColor
        val zeroFraction = PeqGraphMath.gainToFraction(0.0)
        glass.areaFillPaint.shader = LinearGradient(
            0f, g.top, 0f, g.bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(col, 72),
                ColorUtils.setAlphaComponent(col, 0),
                ColorUtils.setAlphaComponent(col, 72),
            ),
            floatArrayOf(0f, zeroFraction.coerceIn(0.02f, 0.98f), 1f),
            Shader.TileMode.CLAMP,
        )
        glass.areaFillKey = key
    }
    nc.drawPath(path, glass.areaFillPaint)
}

/** ANALYZER_VISUAL_SPEC §5: a subtle corner vignette so the plot ground doesn't read as flat. */
private fun drawVignette(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val glass = ctx.glass
    val key = g.left.roundToInt() * 92821 + g.bottom.roundToInt() * 131 + g.right.roundToInt()
    if (glass.vignetteKey != key) {
        val cx = (g.left + g.right) / 2f
        val cy = (g.top + g.bottom) / 2f
        val radius = hypot(g.right - g.left, g.bottom - g.top) / 2f
        if (radius <= 0f) return
        glass.vignettePaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT, AndroidColor.argb(34, 0, 0, 0)),
            floatArrayOf(0f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        glass.vignetteKey = key
    }
    nc.drawRect(g.left, g.top, g.right, g.bottom, glass.vignettePaint)
}

private fun drawFilterOverlays(nc: Canvas, ctx: PeqDrawContext) {
    if (!ctx.showIndividualFilters) return
    val g = ctx.geometry
    ctx.forEachVisibleBank { bank, bands ->
        bands.forEachIndexed { index, band ->
            val response = BiquadUtils.computeCombinedResponse(
                listOf(band), OVERLAY_POINT_COUNT, PeqGraphMath.MIN_FREQUENCY, ctx.maxFrequency, ctx.sampleRate, band.channel,
            )
            if (response.isEmpty()) return@forEachIndexed
            val path = Path()
            response.forEachIndexed { i, pair ->
                val x = g.left + (i.toFloat() / (response.size - 1).coerceAtLeast(1)) * (g.right - g.left)
                val y = g.yForGain(pair.second)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val palette = ctx.paints.perBandPalette
            ctx.paints.unifiedOverlayPaint.color = palette[(ctx.bankNumberOffset(bank) + index) % palette.size]
            ctx.paints.unifiedOverlayPaint.alpha =
                if (band.uuid == ctx.selectedId && bank == ctx.activeBank) 235 else 130
            ctx.paints.unifiedOverlayPaint.pathEffect =
                if (band.channel == ParametricEqChannel.RIGHT) ctx.paints.unifiedOverlayDashEffect else null
            nc.drawPath(path, ctx.paints.unifiedOverlayPaint)
        }
    }
}

/**
 * Per-band exact-subtraction shaded fills — 1:1 with ParametricEqSurface.drawPerBandFills. Each
 * band's fill hugs the real bank curve and its "curve minus this band's own dB response" twin;
 * see the View's long comment for why that subtraction is exact for an LTI cascade.
 */
private fun drawPerBandFills(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val m = ctx.model
    val path = Path()
    ctx.forEachVisibleBank { bank, bands ->
        if (bands.isEmpty()) return@forEachVisibleBank
        val referenceCurve = referenceCurveForBank(ctx, bank) ?: return@forEachVisibleBank
        bands.forEachIndexed { index, band ->
            m.bandCascade.clear()
            m.bandCascade.addPeqBand(band, ctx.sampleRate)
            path.rewind()
            for (i in 0 until SYSTEM_POINT_COUNT) {
                val fraction = i.toFloat() / (SYSTEM_POINT_COUNT - 1)
                val frequency = ctx.curves.frequencies[i]
                val w = 2.0 * PI * frequency / ctx.sampleRate
                val cosW = cos(w)
                val sinW = sin(w)
                val cos2W = 2.0 * cosW * cosW - 1.0
                val sin2W = 2.0 * sinW * cosW
                m.bandAcc.setUnity()
                m.bandCascade.accumulate(cosW, sinW, cos2W, sin2W, m.bandAcc)
                val withBandDb = referenceCurve[i]
                val withoutBandDb = withBandDb - m.bandAcc.magnitudeDb()
                m.fillX[i] = g.left + fraction * (g.right - g.left)
                m.fillTopY[i] = g.yForGain(withBandDb)
                m.fillBottomY[i] = g.yForGain(withoutBandDb)
            }
            for (i in 0 until SYSTEM_POINT_COUNT) {
                if (i == 0) path.moveTo(m.fillX[i], m.fillTopY[i]) else path.lineTo(m.fillX[i], m.fillTopY[i])
            }
            for (i in SYSTEM_POINT_COUNT - 1 downTo 0) path.lineTo(m.fillX[i], m.fillBottomY[i])
            path.close()
            val palette = ctx.paints.perBandPalette
            val color = palette[(ctx.bankNumberOffset(bank) + index) % palette.size]
            ctx.paints.bandFillPaint.color = color
            ctx.paints.bandFillPaint.alpha = BAND_FILL_ALPHA
            nc.drawPath(path, ctx.paints.bandFillPaint)
            ctx.paints.bandStrokePaint.color = color
            ctx.paints.bandStrokePaint.alpha = BAND_STROKE_ALPHA
            nc.drawPath(path, ctx.paints.bandStrokePaint)
        }
    }
}

private fun referenceCurveForBank(ctx: PeqDrawContext, bank: BmwPeqBank): DoubleArray? {
    val perChannel = when (bank) {
        BmwPeqBank.FULL -> ctx.curves.preSplitDb
        BmwPeqBank.LOW -> ctx.curves.lowBranchDb
        BmwPeqBank.MID -> ctx.curves.midBranchDb
    }
    val channelIndex =
        if (ctx.channelDisplay == PeqChannelDisplay.RIGHT) BmwOutputChannel.RIGHT.ordinal else BmwOutputChannel.LEFT.ordinal
    val values = perChannel[channelIndex]
    if (values.size != SYSTEM_POINT_COUNT) return null
    for (i in 0 until SYSTEM_POINT_COUNT) ctx.model.referenceCurveScratch[i] = values[i]
    return ctx.model.referenceCurveScratch
}

// --- live spectrum trace — 1:1 with drawUnifiedSpectrum / drawSpectrumDelta / fillDeltaSegment -

// §7: EMA weight toward the raw magnitude, and peak-hold decay, both per ~33 ms frame.
private const val SPECTRUM_EMA_ALPHA = 0.35f
private const val SPECTRUM_PEAK_DECAY_DB_PER_FRAME = 0.6f // ≈ 12 dB/s at the ~50 ms tick

private fun drawUnifiedSpectrum(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val p = ctx.paints
    val m = ctx.model
    m.spectrumStrokePath.rewind()
    m.spectrumFillPath.rewind()
    m.dryStrokePath.rewind()
    m.spectrumPeakPath.rewind()
    m.spectrumFillPath.moveTo(g.left, g.bottom)
    for (i in 0..SPECTRUM_STEPS) {
        val fraction = i / SPECTRUM_STEPS.toFloat()
        val freq = PeqGraphMath.fractionToFrequency(fraction, PeqGraphMath.MIN_FREQUENCY, ctx.maxFrequency)
        // §7: exponential moving average across frames removes the raw per-frame jitter.
        val rawWetDb = SpectrumEngine.magnitudeDbAt(freq)
        val wetDb = if (m.spectrumPrimed) {
            m.spectrumDbDisplayed[i] + SPECTRUM_EMA_ALPHA * (rawWetDb - m.spectrumDbDisplayed[i])
        } else {
            rawWetDb
        }
        m.spectrumDbDisplayed[i] = wetDb
        // §7: peak-hold — jump up instantly, decay slowly.
        m.spectrumDbPeak[i] = if (wetDb >= m.spectrumDbPeak[i]) {
            wetDb
        } else {
            maxOf(wetDb, m.spectrumDbPeak[i] - SPECTRUM_PEAK_DECAY_DB_PER_FRAME)
        }

        val wetGain = PeqGraphMath.spectrumDbToGraphGain(wetDb, SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB)
        val dryGain = PeqGraphMath.spectrumDbToGraphGain(
            SpectrumEngine.dryMagnitudeDbAt(freq), SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB,
        )
        val peakGain = PeqGraphMath.spectrumDbToGraphGain(m.spectrumDbPeak[i], SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB)
        val x = g.left + fraction * (g.right - g.left)
        val wetY = g.yForGain(wetGain)
        val dryY = g.yForGain(dryGain)
        val peakY = g.yForGain(peakGain)
        m.spectrumXs[i] = x
        m.spectrumWetYs[i] = wetY
        m.spectrumDryYs[i] = dryY
        if (i == 0) {
            m.spectrumStrokePath.moveTo(x, wetY)
            m.dryStrokePath.moveTo(x, dryY)
            m.spectrumPeakPath.moveTo(x, peakY)
        } else {
            m.spectrumStrokePath.lineTo(x, wetY)
            m.dryStrokePath.lineTo(x, dryY)
            m.spectrumPeakPath.lineTo(x, peakY)
        }
        m.spectrumFillPath.lineTo(x, wetY)
    }
    m.spectrumPrimed = true
    m.spectrumFillPath.lineTo(g.right, g.bottom)
    m.spectrumFillPath.close()
    drawSpectrumDelta(nc, ctx, SPECTRUM_STEPS + 1)
    if (g.top != p.spectrumFillShaderTop || g.bottom != p.spectrumFillShaderBottom) {
        // §7: fainter than before (was 150) so it stays ambient context, not a competing element.
        p.unifiedSpectrumFillPaint.shader = LinearGradient(
            0f, g.top, 0f, g.bottom,
            ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(p.spectrumAccentColor, AndroidColor.rgb(150, 150, 150), 0.55f), 80,
            ),
            ColorUtils.setAlphaComponent(p.spectrumAccentColor, 0),
            Shader.TileMode.CLAMP,
        )
        p.spectrumFillShaderTop = g.top
        p.spectrumFillShaderBottom = g.bottom
    }
    nc.drawPath(m.spectrumFillPath, p.unifiedSpectrumFillPaint)
    nc.drawPath(m.dryStrokePath, p.dryStrokePaint)
    nc.drawPath(m.spectrumStrokePath, p.unifiedSpectrumStrokePaint)
    nc.drawPath(m.spectrumPeakPath, ctx.glass.spectrumPeakPaint)
}

private fun drawSpectrumDelta(nc: Canvas, ctx: PeqDrawContext, pointCount: Int) {
    if (pointCount < 2) return
    val m = ctx.model
    var segmentStart = 0
    var segmentBoost = m.spectrumWetYs[0] <= m.spectrumDryYs[0]
    for (i in 1 until pointCount) {
        val isBoost = m.spectrumWetYs[i] <= m.spectrumDryYs[i]
        if (isBoost != segmentBoost) {
            fillDeltaSegment(nc, ctx, segmentStart, i, segmentBoost)
            segmentStart = i
            segmentBoost = isBoost
        }
    }
    fillDeltaSegment(nc, ctx, segmentStart, pointCount - 1, segmentBoost)
}

private fun fillDeltaSegment(nc: Canvas, ctx: PeqDrawContext, startIndex: Int, endIndex: Int, boost: Boolean) {
    if (endIndex <= startIndex) return
    val m = ctx.model
    m.deltaFillPath.rewind()
    m.deltaFillPath.moveTo(m.spectrumXs[startIndex], m.spectrumWetYs[startIndex])
    for (i in startIndex + 1..endIndex) m.deltaFillPath.lineTo(m.spectrumXs[i], m.spectrumWetYs[i])
    for (i in endIndex downTo startIndex) m.deltaFillPath.lineTo(m.spectrumXs[i], m.spectrumDryYs[i])
    m.deltaFillPath.close()
    nc.drawPath(m.deltaFillPath, if (boost) ctx.paints.spectrumBoostFillPaint else ctx.paints.spectrumCutFillPaint)
}

// --- nodes / tilt handles / gain meters — 1:1 with the same-named ParametricEqSurface methods --

private fun drawMultiBankNodes(nc: Canvas, ctx: PeqDrawContext) {
    ctx.forEachVisibleBank { bank, bands ->
        drawBankNodes(nc, ctx, bands, bank, emphasised = bank == ctx.activeBank)
    }
}

private fun drawBankNodes(
    nc: Canvas,
    ctx: PeqDrawContext,
    bands: List<ParametricEqBand>,
    bank: BmwPeqBank,
    emphasised: Boolean,
) {
    val g = ctx.geometry
    val p = ctx.paints
    val gl = ctx.glass
    val d = ctx.density
    val alpha = ctx.nodeAlpha.coerceIn(0f, 1f)
    if (alpha <= 0f) return
    fun withAlpha(a: Int) = (a * alpha).roundToInt().coerceIn(0, 255)
    val baseRadiusDp = if (emphasised) ACTIVE_NODE_RADIUS_DP else SECONDARY_NODE_RADIUS_DP
    val numberOffset = ctx.bankNumberOffset(bank)
    bands.forEachIndexed { index, band ->
        // §3 glass treatment: radial "lit from above" fill, real blurred glow when highlighted,
        // crisp ring + border, a top-left highlight arc — keeping the R-channel dark ring and the
        // luminance-contrasted number label exactly as before.
        val color = p.perBandPalette[(numberOffset + index) % p.perBandPalette.size]
        val x = g.xForFrequency(band.frequency)
        val y = g.yForGain(band.gain)
        val selected = emphasised && band.uuid == ctx.selectedId
        val highlighted = selected || band.uuid == ctx.calloutBandId
        val radius = (if (selected) baseRadiusDp + 1.5f else baseRadiusDp) * d

        if (highlighted) {
            // Soft colour-coded selection ring behind the fill — this band's own palette colour
            // at ~24% alpha, base radius + 7dp. 1:1 with ParametricEqSurface's flat nodeHaloPaint;
            // a tighter blurred glow then sits on top of it for the glass read.
            p.nodeHaloPaint.color = color
            p.nodeHaloPaint.alpha = withAlpha(60)
            nc.drawCircle(x, y, baseRadiusDp * d + 7f * d, p.nodeHaloPaint)
            gl.nodeGlowPaint.color = color
            gl.nodeGlowPaint.alpha = withAlpha(90)
            nc.drawCircle(x, y, radius + 3f * d, gl.nodeGlowPaint)
        }

        gl.nodeFillPaint.shader = RadialGradient(
            x, y - 0.16f * radius, radius.coerceAtLeast(1f),
            ColorUtils.blendARGB(color, AndroidColor.WHITE, 0.30f),
            color,
            Shader.TileMode.CLAMP,
        )
        gl.nodeFillPaint.alpha = withAlpha(255)
        nc.drawCircle(x, y, radius, gl.nodeFillPaint)
        gl.nodeFillPaint.shader = null

        gl.nodeRingPaint.color = ColorUtils.blendARGB(color, AndroidColor.WHITE, 0.45f)
        gl.nodeRingPaint.alpha = withAlpha(200)
        nc.drawCircle(x, y, radius, gl.nodeRingPaint)
        gl.nodeBorderPaint.color = ColorUtils.blendARGB(color, AndroidColor.BLACK, 0.35f)
        gl.nodeBorderPaint.alpha = withAlpha(220)
        nc.drawCircle(x, y, radius, gl.nodeBorderPaint)

        // Top-left glass highlight arc (200°, 70° sweep) — same geometry as GlassSwitchThumbDrawable.
        gl.nodeHighlightArcPaint.alpha = withAlpha(150)
        nodeArcRect.set(x - radius * 0.62f, y - radius * 0.72f, x + radius * 0.62f, y + radius * 0.44f)
        nc.drawArc(nodeArcRect, 200f, 70f, false, gl.nodeHighlightArcPaint)

        if (band.channel == ParametricEqChannel.RIGHT) {
            p.nodeRingPaint.alpha = withAlpha(255)
            nc.drawCircle(x, y, radius, p.nodeRingPaint)
        }

        p.nodeTextPaint.color =
            if (ColorUtils.calculateLuminance(color) > 0.5) AndroidColor.BLACK else AndroidColor.WHITE
        p.nodeTextPaint.alpha = withAlpha(255)
        val baseline = y - (p.nodeTextPaint.ascent() + p.nodeTextPaint.descent()) / 2
        nc.drawText((numberOffset + index + 1).toString(), x, baseline, p.nodeTextPaint)
    }
}

// Scratch rect for the node highlight arc (single-threaded draw; never escapes a frame).
private val nodeArcRect = android.graphics.RectF()

/** Read-only tilt markers — pivot diamond (frequency) + amount circle. 1:1 with the View. */
private fun drawTiltHandles(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val p = ctx.paints
    val d = ctx.density
    val values = ctx.systemValues
    if (values.size != BmwSignalChain.VALUE_COUNT) return
    val enabled = values[NativeBmwDspValues.INDEX_TILT_ENABLED] >= .5f
    val pivotX = g.xForFrequency(values[NativeBmwDspValues.INDEX_TILT_FREQ].toDouble())
    val pivotY = g.yForGain(0.0)
    val amountX = pivotX + 22f * d
    val amountY = g.yForGain(values[NativeBmwDspValues.INDEX_TILT_AMOUNT].toDouble())
    val paint = if (enabled) p.tiltHandlePaint else p.tiltHandleDimPaint

    nc.drawLine(pivotX, amountY, amountX, amountY, paint)
    nc.drawLine(pivotX, pivotY, pivotX, amountY, paint)

    val r = TILT_HANDLE_DRAW_RADIUS_DP * d
    val diamond = Path().apply {
        moveTo(pivotX, pivotY - r)
        lineTo(pivotX + r, pivotY)
        lineTo(pivotX, pivotY + r)
        lineTo(pivotX - r, pivotY)
        close()
    }
    nc.drawPath(diamond, paint)
    nc.drawCircle(amountX, amountY, r, paint)

    if (!enabled) {
        nc.drawText("TILT BYPASSED", pivotX + r + 6f * d, pivotY - 6f * d, p.tiltLabelPaint)
    }
}

private fun drawGainMeters(nc: Canvas, ctx: PeqDrawContext) {
    val left = ctx.leftMeter ?: return
    val right = ctx.rightMeter ?: return
    val g = ctx.geometry
    val p = ctx.paints
    val d = ctx.density
    val barWidth = 8f * d
    val gap = 4f * d
    val leftBarX = g.right + 5f * d
    val rightBarX = leftBarX + barWidth + gap
    drawMeterBar(nc, p, d, leftBarX, g.top, g.bottom, barWidth, left)
    drawMeterBar(nc, p, d, rightBarX, g.top, g.bottom, barWidth, right)
    nc.drawText("L", leftBarX + barWidth / 2f, g.bottom + 15f * d, p.meterLabelPaint)
    nc.drawText("R", rightBarX + barWidth / 2f, g.bottom + 15f * d, p.meterLabelPaint)
}

private fun drawMeterBar(
    nc: Canvas,
    p: PeqSurfacePaints,
    d: Float,
    x: Float,
    top: Float,
    bottom: Float,
    width: Float,
    meter: PeakHoldMeter,
) {
    nc.drawRect(x, top, x + width, bottom, p.meterTrackPaint)
    val rmsFraction = PeakHoldMeter.fractionFor(meter.rmsDb, METER_FLOOR_DB, METER_CEILING_DB)
    val rmsY = bottom - rmsFraction * (bottom - top)
    nc.drawRect(x, rmsY, x + width, bottom, p.meterRmsPaint)
    val peakFraction = PeakHoldMeter.fractionFor(meter.peakDb, METER_FLOOR_DB, METER_CEILING_DB)
    val peakY = bottom - peakFraction * (bottom - top)
    nc.drawLine(x, peakY, x + width, peakY, p.meterPeakPaint)
    val holdFraction = PeakHoldMeter.fractionFor(meter.holdDb, METER_FLOOR_DB, METER_CEILING_DB)
    val holdY = (bottom - holdFraction * (bottom - top)).coerceIn(top, bottom - 1.5f * d)
    nc.drawRect(x, holdY - 1.5f * d, x + width, holdY + 1.5f * d, p.meterHoldPaint)
}

// --- node hit-testing + detail callout + options menu ----------------------------------------

/** A tapped node: its band, which bank, its global 1-based number, and where it sits in px. */
private class NodeHit(
    val band: ParametricEqBand,
    val bank: BmwPeqBank,
    val number: Int,
    val anchor: Offset,
)

/**
 * Nearest node within [NODE_TOUCH_RADIUS_DP] across all three banks — 1:1 with
 * `ParametricEqSurface.hitTestAnyBank`. Numbers are global 1-based (Full, then Low, then Mid).
 */
private fun hitTestAnyBank(
    tap: Offset,
    geometry: PeqPlotGeometry,
    peqState: BmwPeqState,
    density: Float,
): NodeHit? {
    val full = peqState.fullRangeBands.toList()
    val low = peqState.lowBandBands.toList()
    val mid = peqState.midBandBands.toList()
    val radius = NODE_TOUCH_RADIUS_DP * density
    var best: NodeHit? = null
    var bestDistance = Float.MAX_VALUE
    fun consider(bands: List<ParametricEqBand>, bank: BmwPeqBank, numberOffset: Int) {
        bands.forEachIndexed { index, band ->
            val x = geometry.xForFrequency(band.frequency)
            val y = geometry.yForGain(band.gain)
            val distance = hypot(tap.x - x, tap.y - y)
            if (distance <= radius && distance < bestDistance) {
                bestDistance = distance
                best = NodeHit(band, bank, numberOffset + index + 1, Offset(x, y))
            }
        }
    }
    consider(full, BmwPeqBank.FULL, 0)
    consider(low, BmwPeqBank.LOW, full.size)
    consider(mid, BmwPeqBank.MID, full.size + low.size)
    return best
}

private fun bankLabel(bank: BmwPeqBank): String = when (bank) {
    BmwPeqBank.FULL -> "Pre EQ"
    BmwPeqBank.LOW -> "Low Band"
    BmwPeqBank.MID -> "Mid Band"
}

/** The tapped-node detail card — the [drawInfoCard] content, as a small Compose surface. */
@Composable
private fun PeqNodeCallout(hit: NodeHit, accent: Color, modifier: Modifier = Modifier) {
    val band = hit.band
    Column(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF121316))
            .border(1.dp, accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            "#${hit.number} · ${band.filterType.displayLabel} · ${band.channel.displayLabel}",
            color = Color(0xFFE8EAF0),
            fontSize = 11.sp,
        )
        Text(
            "${band.frequency.roundToInt()} Hz · ${"%+.1f".format(band.gain)} dB · Q ${"%.2f".format(band.q)}",
            color = Color(0xFFE8EAF0),
            fontSize = 11.sp,
        )
        Text("${bankLabel(hit.bank)} band", color = Color(0xFF9AA0AA), fontSize = 11.sp)
    }
}

/** The ⋮ graph-options menu — the Compose replacement for the fragment's `showGraphOptionsPopup`. */
@Composable
private fun PeqGraphOptionsButton(
    mode: PeqGraphMode,
    channelDisplay: PeqChannelDisplay,
    showIndividualFilters: Boolean,
    options: PeqGraphOptions,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            // material-icons isn't on the classpath here (see BmwSlider/PeqBandList) — glyph it.
            Text("⋮", fontSize = 20.sp, color = Color(0xFFB0B2BA))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Show individual filters" + if (showIndividualFilters) "  ✓" else "") },
                onClick = { options.onShowIndividualFiltersChange(!showIndividualFilters); expanded = false },
            )
            MenuSectionLabel("Display channel")
            PeqChannelDisplay.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() } + if (option == channelDisplay) "  ✓" else "") },
                    onClick = { options.onChannelDisplayChange(option); expanded = false },
                )
            }
            MenuSectionLabel("Response mode")
            PeqGraphMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() } + if (option == mode) "  ✓" else "") },
                    onClick = { options.onModeChange(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        fontSize = 11.sp,
        fontFamily = FontFamily.SansSerif,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- draw-call context + reusable scratch -----------------------------------------------------

/** Everything [renderPeqGraph]'s helpers read — the Compose equivalent of the View's fields. */
private class PeqDrawContext(
    val geometry: PeqPlotGeometry,
    val paints: PeqSurfacePaints,
    val glass: PeqGlassPaints,
    val model: PeqResponseModel,
    val density: Float,
    val systemValues: FloatArray,
    peqState: BmwPeqState,
    val activeBank: BmwPeqBank,
    val selectedId: UUID?,
    val channelDisplay: PeqChannelDisplay,
    val showIndividualFilters: Boolean,
    val sampleRate: Double,
    val maxFrequency: Double,
    val mode: PeqGraphMode,
    val showTiltHandles: Boolean = false,
    val showGainMeters: Boolean = false,
    val nodeAlpha: Float = 1f,
    val calloutBandId: UUID? = null,
    val leftMeter: PeakHoldMeter? = null,
    val rightMeter: PeakHoldMeter? = null,
) {
    val curves: BmwResponseCurves get() = model.curves

    val fullBands: List<ParametricEqBand> = peqState.fullRangeBands.toList()
    val lowBands: List<ParametricEqBand> = peqState.lowBandBands.toList()
    val midBands: List<ParametricEqBand> = peqState.midBandBands.toList()

    private val hasSystemConfig = systemValues.size == BmwSignalChain.VALUE_COUNT

    fun monoBassBlendAt(frequency: Double): Float =
        if (hasSystemConfig) MonoBassCue.blendAt(systemValues, frequency, maxFrequency) else 0f

    /** Global 1-based filter numbering: Full, then Low, then Mid — = ParametricEqSurface.bankNumberOffset. */
    fun bankNumberOffset(bank: BmwPeqBank): Int = when (bank) {
        BmwPeqBank.FULL -> 0
        BmwPeqBank.LOW -> fullBands.size
        BmwPeqBank.MID -> fullBands.size + lowBands.size
    }

    inline fun forEachVisibleBank(action: (BmwPeqBank, List<ParametricEqBand>) -> Unit) {
        action(BmwPeqBank.FULL, fullBands)
        action(BmwPeqBank.LOW, lowBands)
        action(BmwPeqBank.MID, midBands)
    }
}

/**
 * The calculator + curves + all the reused per-frame scratch, held across recompositions by a
 * `remember`. Mirrors the equivalent private fields on `ParametricEqSurface` (nothing here is
 * reallocated after construction).
 */
private class PeqResponseModel {
    private val calculator = BmwResponseCalculator(SYSTEM_POINT_COUNT)
    val curves = BmwResponseCurves(SYSTEM_POINT_COUNT)

    val bandCascade = BiquadCascade(1)
    val bandAcc = ComplexAcc()
    val fillX = FloatArray(SYSTEM_POINT_COUNT)
    val fillTopY = FloatArray(SYSTEM_POINT_COUNT)
    val fillBottomY = FloatArray(SYSTEM_POINT_COUNT)
    val referenceCurveScratch = DoubleArray(SYSTEM_POINT_COUNT)

    val spectrumXs = FloatArray(SPECTRUM_STEPS + 1)
    val spectrumDryYs = FloatArray(SPECTRUM_STEPS + 1)
    val spectrumWetYs = FloatArray(SPECTRUM_STEPS + 1)
    val spectrumStrokePath = Path()
    val spectrumFillPath = Path()
    val dryStrokePath = Path()
    val deltaFillPath = Path()
    val areaFillPath = Path()

    // §7: cross-frame smoothing state for the spectrum overlay. dbDisplayed EMA-tracks the raw
    // magnitude; dbPeak holds the max and decays slowly. `spectrumPrimed` guards the first frame
    // so the EMA doesn't ramp up from the floor.
    val spectrumDbDisplayed = FloatArray(SPECTRUM_STEPS + 1) { SpectrumEngine.FLOOR_DB }
    val spectrumDbPeak = FloatArray(SPECTRUM_STEPS + 1) { SpectrumEngine.FLOOR_DB }
    val spectrumPeakPath = Path()
    var spectrumPrimed = false

    // Cached static layers so the per-frame spectrum tick doesn't re-run the grid, the curves,
    // the real-blur sum glow and the glass nodes (the blur passes are what pegs a software-GL
    // head unit). `bg` = grid + shading (behind the spectrum), `fg` = curves + nodes + tilt +
    // legend + vignette (in front). Both rebuilt only when a static input changes; the spectrum
    // and the live gain meters draw straight onto the frame between / on top of them.
    var bgBitmap: android.graphics.Bitmap? = null
    var fgBitmap: android.graphics.Bitmap? = null
    val bgCanvas = Canvas()
    val fgCanvas = Canvas()
    var staticKey = Int.MIN_VALUE

    fun recycleBitmaps() {
        bgBitmap?.recycle(); bgBitmap = null
        fgBitmap?.recycle(); fgBitmap = null
        staticKey = Int.MIN_VALUE
    }

    fun recompute(values: FloatArray, peq: BmwPeqState, sampleRate: Double) {
        if (values.size != BmwSignalChain.VALUE_COUNT) return
        val maxFreq = min(20_000.0, sampleRate * 0.5 * 0.999)
        calculator.configureAxis(sampleRate, 20.0, maxFreq)
        calculator.invalidateAll()
        calculator.compute(values, peq, curves)
    }
}

/**
 * Extra paints for the 10c-ii visual pass (`ANALYZER_VISUAL_SPEC.md` §1–5, §7) — constructed
 * once per composition, reused every frame. Real Gaussian blur comes from [BlurMaskFilter] (the
 * same mechanism `GlassSwitchThumbDrawable` / `BmwSwitch` use); no `RenderEffect` layer juggling.
 */
private class PeqGlassPaints(density: Float) {
    // §1: real blur glow beneath the summed curve.
    val sumGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter((8f * density).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }
    // §2: gradient area fill under the summed curve; shader rebuilt when the plot rect changes.
    val areaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    var areaFillKey = Int.MIN_VALUE

    // §4: octave-boundary verticals (100 / 1k / 10k) — brighter than the mesh, dimmer than 0 dB.
    val octaveGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = AndroidColor.rgb(92, 96, 106)
    }

    // §3: glass node treatment.
    val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter((6f * density).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }
    val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val nodeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    val nodeHighlightArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
        color = AndroidColor.argb(150, 255, 255, 255)
    }

    // §7: peak-hold marker line above the smoothed spectrum trace.
    val spectrumPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = AndroidColor.argb(140, 170, 176, 186)
    }

    // §5: corner vignette; shader rebuilt when the plot rect changes.
    val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    var vignetteKey = Int.MIN_VALUE
}

/** Compose equivalent of `ParametricEqSurface.themeColor` — resolves a `?android:attr` colour. */
private fun themeColor(context: Context, attribute: Int): Int {
    var color = AndroidColor.BLACK
    context.withStyledAttributes(TypedValue().data, intArrayOf(attribute)) {
        color = getColor(0, AndroidColor.BLACK)
    }
    return color
}

@Preview(widthDp = 760, heightDp = 300, backgroundColor = 0xFF0B0B0B, showBackground = true)
@Composable
private fun PeqGraphFramePreview() {
    val values = remember {
        FloatArray(BmwSignalChain.VALUE_COUNT).apply {
            this[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 120f
            this[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 640f
            this[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED] = 1f
            this[NativeBmwDspValues.INDEX_MONO_BASS_FREQ] = 80f
            this[NativeBmwDspValues.INDEX_MONO_BASS_BLEND] = 100f
        }
    }
    BmwDspTheme {
        Box(Modifier.fillMaxSize()) {
            PeqGraphFrame(systemValues = values, modifier = Modifier.size(760.dp, 300.dp))
        }
    }
}
