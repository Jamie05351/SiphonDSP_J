package app.siphondsp.compose.screens

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.compose.controls.bmwFocusRing
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
import app.siphondsp.utils.extensions.prettyNumberFormat
import app.siphondsp.view.PeakHoldMeter
import app.siphondsp.view.PeqGraphMath
import app.siphondsp.view.PeqPlotGeometry
import app.siphondsp.view.PeqSurfacePaints
import kotlinx.coroutines.delay
import android.graphics.Color as AndroidColor
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
 *
 * The magnitude view no longer matches the View (2026-10-01 redesign, Figma "PEQ graph —
 * redesign"): band areas instead of branch lines and crossover shading, one bank in focus at a
 * time with its filters as translucent per-filter shapes and its nodes on its own curve, a
 * 10 s idle that hands focus back to the full curve, a cool-white sum, per-filter colours clear of
 * the bank colours, and a gain axis fitted to the curves. The phase view is unchanged.
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
private const val SPECTRUM_STEPS = 240
// Band focus (2026-10-01 redesign): tapping the graph focuses the selected bank -- its own curve,
// its filters' shapes and its nodes -- with the full EQ'd curve dropped back to a faint line. After
// FOCUS_IDLE_MS untouched that fades and the full curve takes focus again, whichever bank is
// selected; the next tap brings the bank back. The band areas stay throughout.
private const val FOCUS_IDLE_MS = 10_000L
private const val FOCUS_FADE_MS = 400

// Per-filter colours (node, shape and callout), by global filter number. Chosen to stay clear of
// the bank colours (Pre EQ white, Low cyan, Mid yellow, High pink) so a filter never reads as a
// band, and different enough from each other that overlapping shapes stay tellable apart.
private val FilterPalette = intArrayOf(
    AndroidColor.rgb(0x2D, 0xE1, 0xC2), // teal
    AndroidColor.rgb(0xA0, 0x6B, 0xFF), // violet
    AndroidColor.rgb(0xFF, 0x7A, 0x59), // coral
    AndroidColor.rgb(0xB5, 0xE6, 0x55), // lime
    AndroidColor.rgb(0xFF, 0xA2, 0x4C), // orange
    AndroidColor.rgb(0xD9, 0x8C, 0xFF), // orchid
    AndroidColor.rgb(0x7D, 0xFF, 0xB2), // mint
    AndroidColor.rgb(0xFF, 0x4D, 0x4D), // red
    AndroidColor.rgb(0x7B, 0x8C, 0xFF), // periwinkle
    AndroidColor.rgb(0xFF, 0xC7, 0xA0), // peach
)

private fun filterColor(globalIndex: Int): Int = FilterPalette[globalIndex.mod(FilterPalette.size)]

// The full EQ'd (summed) curve: a cool white, distinct from Pre EQ's pure white and every band.
private val SumCurveColor = AndroidColor.rgb(0xEA, 0xF2, 0xFF)

private const val ACTIVE_NODE_RADIUS_DP = 8f
private const val NODE_TOUCH_RADIUS_DP = 22f
private const val TILT_HANDLE_DRAW_RADIUS_DP = 8f
private const val METER_FLOOR_DB = -50f
private const val METER_CEILING_DB = 0f

// Plot insets — 1:1 with ParametricEqSurface.padLeft/padTop/padRight/padBottom.
private val PlotPadLeft = 44.dp
private val PlotPadTop = 24.dp
private val PlotPadRight = 52.dp
private val PlotPadBottom = 30.dp

// Horizontal gridline values per mode — 1:1 with drawUnifiedGrid / drawPhaseGrid.
private val MagnitudeGridLines = floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f, -24f)
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
            drawLegend(nc, geometry, paints, density, mode)
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
 * the numbered nodes with tap-to-detail, the read-only tilt handles, the L/R gain meters, and the
 * ⋮ graph-options menu.
 *
 * Magnitude view (2026-10-01 redesign): the band areas (Low / Mid / High) are always there. A tap
 * focuses [activeBank]: its own curve, each of its filters as a shape in that filter's colour, and
 * its numbered nodes on that curve, with the full EQ'd curve faint behind. After 10 s untouched the
 * filters fade and the full curve takes focus again, whichever bank is selected.
 *
 * Stateless: the caller passes the current native config, PEQ state, active bank and selection,
 * plus [onNodeTapped] (select the band + scroll its list row). Focus is owned here — any tap or any
 * change to [peqState] / [activeBank] brings the bank back into focus and restarts the timer.
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

    // Band focus: 1 = the selected bank in focus, 0 = the full EQ'd curve. Any tap, or any change to
    // peqState / activeBank, snaps it to 1 and restarts the idle timer; FOCUS_IDLE_MS later it
    // fades back to the full curve.
    val focus = remember { Animatable(1f) }
    var interactionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(interactionTick, peqState, activeBank) {
        focus.snapTo(1f)
        delay(FOCUS_IDLE_MS)
        focus.animateTo(0f, tween(FOCUS_FADE_MS))
    }

    var callout by remember { mutableStateOf<NodeHit?>(null) }
    LaunchedEffect(callout) {
        if (callout != null) {
            delay(5_000L)
            callout = null
        }
    }
    // The last tapped node stays lit until you tap somewhere else (independent of the callout,
    // which auto-hides). Cleared when peqState / bank changes so a stale uuid never lingers.
    var tappedNodeId by remember(peqState, activeBank) { mutableStateOf<UUID?>(null) }

    Box(modifier) {
        ComposeCanvas(
            Modifier
                .fillMaxSize()
                .pointerInput(peqState, activeBank, channelDisplay, mode, maxFrequency) {
                    detectTapGestures { offset ->
                        // While the full curve has focus the nodes aren't showing, so a tap only
                        // brings the bank back into focus; it doesn't pick a node it can't see.
                        val wasIdle = focus.value < 0.5f
                        interactionTick++
                        if (mode != PeqGraphMode.MAGNITUDE || wasIdle) {
                            callout = null
                            return@detectTapGestures
                        }
                        val left = PlotPadLeft.toPx()
                        val top = PlotPadTop.toPx()
                        val right = size.width - PlotPadRight.toPx()
                        val bottom = size.height - PlotPadBottom.toPx()
                        if (right <= left || bottom <= top) return@detectTapGestures
                        val geometry = model.geometry(left, right, top, bottom, maxFrequency)
                        val hit = hitTestFocusedBank(offset, geometry, model.curves, peqState, activeBank, channelDisplay, maxFrequency, density)
                        tappedNodeId = hit?.band?.uuid
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
            // Read the tick + focus here (draw phase), not in composition.
            val spectrumFrame = spectrumTick.intValue
            // A silent selected bank (High with 3-way off) has no curve, filters or nodes to focus,
            // so the full curve keeps focus instead of dimming to a near-blank graph.
            val selectedSilent = activeBank == BmwPeqBank.HIGH && !model.curves.highBranchActive
            val bandFocus = if (selectedSilent) 0f else focus.value
            val left = PlotPadLeft.toPx()
            val top = PlotPadTop.toPx()
            val right = size.width - PlotPadRight.toPx()
            val bottom = size.height - PlotPadBottom.toPx()
            if (right <= left || bottom <= top) return@ComposeCanvas
            val geometry = model.geometry(left, right, top, bottom, maxFrequency)
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
                focus = bandFocus,
                calloutBandId = callout?.band?.uuid,
                highlightId = tappedNodeId,
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
            // Border in the tapped filter's own colour, the same one as its node and shape.
            val calloutAccent = Color(filterColor((hit.number - 1).coerceAtLeast(0)))
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

            // The summed-response curve: a cool white (the old warm yellow read as the Mid band),
            // a little heavier so the bloom underneath reads. Compose-only override —
            // PeqSurfacePaints.sumColor stays white for the legacy view and the gain meters.
            sumPaintSolid.color = SumCurveColor
            sumPaintSolid.strokeWidth = 2.2f * density
            sumPaintDashed.color = SumCurveColor
            sumPaintDashed.strokeWidth = 2.2f * density
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
    k = 31 * k + (ctx.highlightId?.hashCode() ?: 0)
    k = 31 * k + ctx.activeBank.ordinal
    k = 31 * k + ctx.geometry.maxGain.hashCode()
    k = 31 * k + ctx.geometry.minGain.hashCode()
    k = 31 * k + (ctx.focus * 12f).roundToInt() // bucketed: ~12 rebuilds across the 400ms fade
    return k
}

private fun renderStaticLayers(bg: Canvas, fg: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    when (ctx.mode) {
        PeqGraphMode.MAGNITUDE -> {
            drawGrid(bg, g, ctx.paints, ctx.density, gainGridLines(g), { g.yForGain(it) }, ctx.glass.octaveGridPaint)

            // Back to front: band areas (context), the focused bank's filter shapes, the full
            // curve (bright when it has focus, faint behind a focused bank), the focused bank's own
            // curve, then its nodes on top.
            drawBandAreas(fg, ctx)
            if (ctx.showIndividualFilters) drawFocusedFilterShapes(fg, ctx)
            drawSumCurve(fg, ctx)
            drawFocusedBankCurve(fg, ctx)
            if (ctx.showTiltHandles) drawTiltHandles(fg, ctx)
            drawFocusedNodes(fg, ctx)
        }
        PeqGraphMode.PHASE -> {
            drawGrid(bg, g, ctx.paints, ctx.density, PhaseGridLines, { g.yForPhaseDeg(it) }, ctx.glass.octaveGridPaint)
            drawCrossoverShading(bg, g, ctx.paints, ctx.systemValues, ctx.maxFrequency)
            drawPhaseCurves(fg, ctx)
        }
    }
    drawLegend(fg, g, ctx.paints, ctx.density, ctx.mode, focusedBank = ctx.activeBank.takeIf { ctx.focus >= 0.5f })
    drawVignette(fg, ctx)
}

// --- frame helpers (raw Canvas, shared by PeqGraphFrame and renderPeqGraph) --------------------

/** Horizontal gridlines every 6 dB across the geometry's (fitted) gain window, top to bottom. */
private fun gainGridLines(g: PeqPlotGeometry): FloatArray {
    val lines = ArrayList<Float>()
    var db = g.maxGain
    while (db >= g.minGain - 1e-6) {
        lines.add(db.toFloat())
        db -= 6.0
    }
    return lines.toFloatArray()
}

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
        nc.drawText(value.toInt().toString(), 5f * density, y + 5f * density, p.unifiedLabelPaint)
    }
    // Narrow plots (a phone, the home-screen graphs) shrink the frequency labels to fit rather than
    // dropping any; only if they still overlap at the smallest size is a label skipped.
    val labelPaint = p.unifiedLabelPaint
    val baseTextSize = labelPaint.textSize
    val xs = FloatArray(FreqScale.size) { g.xForFrequency(FreqScale[it]) }
    val labels = Array(FreqScale.size) { FreqScale[it].prettyNumberFormat() }
    val widest = labels.maxOf { labelPaint.measureText(it) }
    labelPaint.textSize = baseTextSize *
        graphLabelFitScale(minNeighbourSpacing(xs), widest, gapPx = 3f * density)
    var lastLabelEnd = Float.NEGATIVE_INFINITY
    FreqScale.forEachIndexed { i, frequency ->
        val x = xs[i]
        val linePaint = if (octavePaint != null && frequency in OctaveFreqs) octavePaint else p.unifiedGridPaint
        nc.drawLine(x, g.top, x, g.bottom, linePaint)
        val labelWidth = labelPaint.measureText(labels[i])
        val labelStart = x - labelWidth / 2f
        if (labelStart >= lastLabelEnd + 2f * density) {
            nc.drawText(labels[i], labelStart, g.bottom + 21f * density, labelPaint)
            lastLabelEnd = labelStart + labelWidth
        }
    }
    labelPaint.textSize = baseTextSize
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

private fun drawLegend(
    nc: Canvas,
    g: PeqPlotGeometry,
    p: PeqSurfacePaints,
    density: Float,
    mode: PeqGraphMode,
    focusedBank: BmwPeqBank? = null,
) {
    val baseline = g.top - 8f * density
    fun tinted(color: Int) = Paint(p.unifiedLegendPaint).apply { this.color = color }
    when (mode) {
        PeqGraphMode.PHASE -> {
            nc.drawText("LOW", g.left, baseline, tinted(p.bankColorLow))
            nc.drawText("MID", g.left + 48f * density, baseline, tinted(p.bankColorMid))
            nc.drawText("HIGH", g.left + 92f * density, baseline, tinted(p.bankColorHigh))
            nc.drawText(
                "FINAL SUM PHASE (L solid / R dashed) · compressor not shown (nonlinear)",
                g.left + 146f * density, baseline, p.unifiedLegendPaint,
            )
        }
        PeqGraphMode.MAGNITUDE -> {
            // Left: the bank colour key. Right: what has focus, and its L solid / R dashed key.
            var x = g.left
            for ((label, color) in listOf(
                "PRE EQ" to p.bankColorFull, "LOW" to p.bankColorLow,
                "MID" to p.bankColorMid, "HIGH" to p.bankColorHigh,
            )) {
                val paint = tinted(color)
                nc.drawText(label, x, baseline, paint)
                x += paint.measureText(label) + 14f * density
            }
            val focusLabel = focusedBank?.let { bankShortLabel(it) } ?: "SUM"
            val focusColor = focusedBank?.let { bankColor(p, it) } ?: SumCurveColor
            val sample = 20f * density
            val gap = 6f * density
            val labelPaint = tinted(focusColor)
            val keyPaint = tinted(AndroidColor.argb(190, 255, 255, 255))
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
                color = focusColor
            }
            val midY = baseline - p.unifiedLegendPaint.textSize * 0.32f
            var right = g.right
            right -= keyPaint.measureText("R"); nc.drawText("R", right, baseline, keyPaint)
            right -= gap + sample
            line.pathEffect = DashPathEffect(floatArrayOf(5f * density, 3f * density), 0f)
            nc.drawLine(right, midY, right + sample, midY, line)
            right -= 14f * density + keyPaint.measureText("L"); nc.drawText("L", right, baseline, keyPaint)
            right -= gap + sample
            line.pathEffect = null
            nc.drawLine(right, midY, right + sample, midY, line)
            right -= 12f * density + labelPaint.measureText(focusLabel)
            nc.drawText(focusLabel, right, baseline, labelPaint)
        }
    }
}

private fun bankShortLabel(bank: BmwPeqBank): String = when (bank) {
    BmwPeqBank.FULL -> "PRE EQ"
    BmwPeqBank.LOW -> "LOW"
    BmwPeqBank.MID -> "MID"
    BmwPeqBank.HIGH -> "HIGH"
}

private fun bankColor(p: PeqSurfacePaints, bank: BmwPeqBank): Int = when (bank) {
    BmwPeqBank.FULL -> p.bankColorFull
    BmwPeqBank.LOW -> p.bankColorLow
    BmwPeqBank.MID -> p.bankColorMid
    BmwPeqBank.HIGH -> p.bankColorHigh
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
private fun drawGlowStroke(nc: Canvas, glass: PeqGlassPaints, path: Path, paint: Paint, glowAlpha: Int = 205) {
    val glow = glass.sumGlowPaint
    glow.color = paint.color
    glow.alpha = glowAlpha
    glow.strokeWidth = paint.strokeWidth * 1.9f
    glow.pathEffect = paint.pathEffect
    nc.drawPath(path, glow)
    nc.drawPath(path, paint)
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
    if (ctx.curves.highBranchActive) drawCurveAverage(nc, ctx, ctx.curves.highBranchPhase, ctx.paints.highBranchPaint, toY)
    if (ctx.channelDisplay != PeqChannelDisplay.RIGHT) {
        drawCurveForChannel(nc, ctx, ctx.curves.sumPhase[BmwOutputChannel.LEFT.ordinal], ctx.paints.sumPaintSolid, toY)
    }
    if (ctx.channelDisplay != PeqChannelDisplay.LEFT) {
        drawCurveForChannel(nc, ctx, ctx.curves.sumPhase[BmwOutputChannel.RIGHT.ordinal], ctx.paints.sumPaintDashed, toY)
    }
}

/** Index of the channel whose curve is drawn as "the" curve (L, unless showing R only). */
private fun primaryChannel(ctx: PeqDrawContext): Int =
    if (ctx.channelDisplay == PeqChannelDisplay.RIGHT) BmwOutputChannel.RIGHT.ordinal else BmwOutputChannel.LEFT.ordinal

/**
 * Which channel's curve [band] sits on: its own for an L-only or R-only filter, the primary one for
 * L+R. Null when the display hides the only channel it applies to (an L-only filter while showing
 * R only, or the reverse), so it isn't drawn on a curve it doesn't touch.
 */
private fun channelFor(band: ParametricEqBand, display: PeqChannelDisplay): Int? = when (band.channel) {
    ParametricEqChannel.LEFT -> if (display == PeqChannelDisplay.RIGHT) null else BmwOutputChannel.LEFT.ordinal
    ParametricEqChannel.RIGHT -> if (display == PeqChannelDisplay.LEFT) null else BmwOutputChannel.RIGHT.ordinal
    ParametricEqChannel.LEFT_RIGHT ->
        if (display == PeqChannelDisplay.RIGHT) BmwOutputChannel.RIGHT.ordinal else BmwOutputChannel.LEFT.ordinal
}

/** [values] (evenly spaced across the plot's log-frequency axis) as a path across the plot. */
private fun curvePath(g: PeqPlotGeometry, values: DoubleArray, into: Path = Path()): Path {
    into.rewind()
    for (i in values.indices) {
        val x = g.left + (i.toFloat() / (values.size - 1).coerceAtLeast(1)) * (g.right - g.left)
        val y = g.yForGain(values[i])
        if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
    }
    return into
}

/**
 * The full EQ'd (summed) curve. With the full curve in focus (focus 0) it's bright with a glow, L
 * solid and R dashed; as a bank takes focus it drops back to one faint thin line behind it.
 */
private fun drawSumCurve(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val p = ctx.paints
    val bright = 1f - ctx.focus
    if (bright > 0f) {
        if (ctx.channelDisplay != PeqChannelDisplay.RIGHT) {
            val values = ctx.curves.sumDb[BmwOutputChannel.LEFT.ordinal]
            if (values.isNotEmpty()) {
                p.sumPaintSolid.alpha = scaleAlpha(255, bright)
                drawGlowStroke(nc, ctx.glass, curvePath(g, values), p.sumPaintSolid, scaleAlpha(150, bright))
            }
        }
        if (ctx.channelDisplay != PeqChannelDisplay.LEFT) {
            val values = ctx.curves.sumDb[BmwOutputChannel.RIGHT.ordinal]
            if (values.isNotEmpty()) {
                p.sumPaintDashed.alpha = scaleAlpha(180, bright)
                nc.drawPath(curvePath(g, values), p.sumPaintDashed)
            }
        }
    }
    if (ctx.focus > 0f) {
        val values = ctx.curves.sumDb[primaryChannel(ctx)]
        if (values.isNotEmpty()) {
            val faint = ctx.glass.faintSumPaint
            faint.color = SumCurveColor
            faint.alpha = scaleAlpha(72, ctx.focus)
            nc.drawPath(curvePath(g, values), faint)
        }
    }
}

/** The focused bank's own modelled curve: Pre EQ's pre-split response, or a band's branch. */
private fun bankCurves(curves: BmwResponseCurves, bank: BmwPeqBank): Array<DoubleArray> = when (bank) {
    BmwPeqBank.FULL -> curves.preSplitDb
    BmwPeqBank.LOW -> curves.lowBranchDb
    BmwPeqBank.MID -> curves.midBranchDb
    BmwPeqBank.HIGH -> curves.highBranchDb
}

/** [values] (evenly spaced across the plot's log-frequency axis) read at [frequency]. */
private fun curveDbAt(values: DoubleArray, frequency: Double, maxFrequency: Double): Double {
    if (values.isEmpty()) return 0.0
    if (values.size == 1) return values[0]
    val position = PeqGraphMath.frequencyToFraction(frequency, PeqGraphMath.MIN_FREQUENCY, maxFrequency) * (values.size - 1)
    val i = floor(position).toInt().coerceIn(0, values.size - 2)
    val t = (position - i).coerceIn(0f, 1f)
    return values[i] + (values[i + 1] - values[i]) * t
}

/** Whether [bank] makes any sound (High only with the Crossovers page's 3-way switch on). */
private fun bankAudible(ctx: PeqDrawContext, bank: BmwPeqBank): Boolean =
    bank != BmwPeqBank.HIGH || ctx.curves.highBranchActive

/** The focused bank's own curve, bright in its bank colour (L solid, R dashed), faded by focus. */
private fun drawFocusedBankCurve(nc: Canvas, ctx: PeqDrawContext) {
    if (ctx.focus <= 0f || !bankAudible(ctx, ctx.activeBank)) return
    val g = ctx.geometry
    val perChannel = bankCurves(ctx.curves, ctx.activeBank)
    val color = bankColor(ctx.paints, ctx.activeBank)
    val solid = ctx.glass.bankCurvePaint
    val dashed = ctx.glass.bankCurveDashedPaint
    if (ctx.channelDisplay != PeqChannelDisplay.RIGHT) {
        val values = perChannel[BmwOutputChannel.LEFT.ordinal]
        if (values.isNotEmpty()) {
            solid.color = color
            solid.alpha = scaleAlpha(255, ctx.focus)
            drawGlowStroke(nc, ctx.glass, curvePath(g, values), solid, scaleAlpha(130, ctx.focus))
        }
    }
    if (ctx.channelDisplay != PeqChannelDisplay.LEFT) {
        val values = perChannel[BmwOutputChannel.RIGHT.ordinal]
        if (values.isNotEmpty()) {
            dashed.color = color
            dashed.alpha = scaleAlpha(190, ctx.focus)
            nc.drawPath(curvePath(g, values), dashed)
        }
    }
}

/**
 * The crossover bands as tinted areas under their own branch curves (Low / Mid / High, High only
 * with 3-way on): the context behind whatever has focus, always drawn. While a band has focus the
 * other bands recede and the focused one keeps its wash without the outline (its bright curve is
 * the outline).
 */
private fun drawBandAreas(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val p = ctx.paints
    val areaPaint = ctx.glass.bandAreaPaint
    val edgePaint = ctx.glass.bandAreaEdgePaint
    val path = ctx.model.scratchPath
    for (bank in listOf(BmwPeqBank.LOW, BmwPeqBank.MID, BmwPeqBank.HIGH)) {
        if (!bankAudible(ctx, bank)) continue
        val values = bankCurves(ctx.curves, bank)[primaryChannel(ctx)]
        if (values.isEmpty()) continue
        val color = bankColor(p, bank)
        val focused = bank == ctx.activeBank
        val recede = if (focused) 1f else 1f - 0.7f * ctx.focus
        val washAlpha = if (focused) 0.16f - 0.04f * ctx.focus else 0.16f * recede
        val edgeAlpha = if (focused) 0.35f * (1f - ctx.focus) else 0.35f * recede
        curvePath(g, values, path)
        path.lineTo(g.right, g.bottom)
        path.lineTo(g.left, g.bottom)
        path.close()
        areaPaint.shader = LinearGradient(
            0f, g.top, 0f, g.bottom,
            ColorUtils.setAlphaComponent(color, (washAlpha * 255f).roundToInt().coerceIn(0, 255)),
            ColorUtils.setAlphaComponent(color, 0),
            Shader.TileMode.CLAMP,
        )
        nc.drawPath(path, areaPaint)
        if (edgeAlpha > 0f) {
            edgePaint.color = color
            edgePaint.alpha = (edgeAlpha * 255f).roundToInt().coerceIn(0, 255)
            nc.drawPath(curvePath(g, values, path), edgePaint)
        }
    }
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

/**
 * Each of the focused bank's filters as its own shape in its own colour: the region between the
 * bank's curve and "the bank's curve without this filter" (exact for an LTI cascade: subtract the
 * filter's own dB response). Translucent, so where two filters overlap their colours mix. No
 * outline: dashed "without" edges crowded the curve wherever filters sat side by side. Fades with
 * focus, so idle shows no stacked shapes at all.
 */
private fun drawFocusedFilterShapes(nc: Canvas, ctx: PeqDrawContext) {
    if (ctx.focus <= 0f || !bankAudible(ctx, ctx.activeBank)) return
    val bands = ctx.bandsOf(ctx.activeBank)
    if (bands.isEmpty()) return
    val g = ctx.geometry
    val m = ctx.model
    val perChannel = bankCurves(ctx.curves, ctx.activeBank)
    val fill = ctx.glass.filterShapePaint
    val shape = Path()
    val offset = ctx.bankNumberOffset(ctx.activeBank)
    bands.forEachIndexed { index, band ->
        val channel = channelFor(band, ctx.channelDisplay) ?: return@forEachIndexed
        val reference = perChannel[channel]
        if (reference.size != SYSTEM_POINT_COUNT) return@forEachIndexed
        m.bandCascade.clear()
        m.bandCascade.addPeqBand(band, ctx.sampleRate)
        for (i in 0 until SYSTEM_POINT_COUNT) {
            val frequency = ctx.curves.frequencies[i]
            val w = 2.0 * PI * frequency / ctx.sampleRate
            val cosW = cos(w)
            val sinW = sin(w)
            val cos2W = 2.0 * cosW * cosW - 1.0
            val sin2W = 2.0 * sinW * cosW
            m.bandAcc.setUnity()
            m.bandCascade.accumulate(cosW, sinW, cos2W, sin2W, m.bandAcc)
            m.fillX[i] = g.left + (i.toFloat() / (SYSTEM_POINT_COUNT - 1)) * (g.right - g.left)
            m.fillTopY[i] = g.yForGain(reference[i])
            m.fillBottomY[i] = g.yForGain(reference[i] - m.bandAcc.magnitudeDb())
        }
        shape.rewind()
        for (i in 0 until SYSTEM_POINT_COUNT) {
            if (i == 0) shape.moveTo(m.fillX[i], m.fillTopY[i]) else shape.lineTo(m.fillX[i], m.fillTopY[i])
        }
        for (i in SYSTEM_POINT_COUNT - 1 downTo 0) shape.lineTo(m.fillX[i], m.fillBottomY[i])
        shape.close()
        val color = filterColor(offset + index)
        fill.color = color
        fill.alpha = scaleAlpha(97, ctx.focus)
        nc.drawPath(shape, fill)
    }
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
    // Whether the dry (pre-DSP) reference trace carries real data this frame. When it doesn't
    // (no feed wired, or the analyzer hasn't primed yet) every dry sample sits on the floor, so
    // the whole band reads as one "boost" segment and drawSpectrumDelta floods the graph with the
    // green boost fill. Gate the delta shading on this so a missing dry feed stays invisible
    // instead of painting a solid green mass behind the curves.
    var dryHasSignal = false
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

        val wetGain = PeqGraphMath.spectrumDbToGraphGain(wetDb, SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB, g.minGain, g.maxGain)
        val dryDb = SpectrumEngine.dryMagnitudeDbAt(freq)
        if (dryDb > SpectrumEngine.FLOOR_DB + 1f) dryHasSignal = true
        val dryGain = PeqGraphMath.spectrumDbToGraphGain(
            dryDb, SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB, g.minGain, g.maxGain,
        )
        val peakGain = PeqGraphMath.spectrumDbToGraphGain(m.spectrumDbPeak[i], SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB, g.minGain, g.maxGain)
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
    if (dryHasSignal) drawSpectrumDelta(nc, ctx, SPECTRUM_STEPS + 1)
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

// --- nodes (focused bank) / tilt handles / gain meters --------------------------------------------

/** Scale a 0–255 paint alpha by a 0–1 fade factor, clamped to a legal channel value. */
private fun scaleAlpha(base: Int, factor: Float) = (base * factor).roundToInt().coerceIn(0, 255)

/**
 * The focused bank's numbered nodes, each on the bank's own curve at its frequency and in its
 * filter's colour (the same colour as its shape and callout). They fade with focus: while the full
 * curve has focus there are none.
 */
private fun drawFocusedNodes(nc: Canvas, ctx: PeqDrawContext) {
    if (ctx.focus <= 0f || !bankAudible(ctx, ctx.activeBank)) return
    val bands = ctx.bandsOf(ctx.activeBank)
    if (bands.isEmpty()) return
    val g = ctx.geometry
    val p = ctx.paints
    val gl = ctx.glass
    val d = ctx.density
    val perChannel = bankCurves(ctx.curves, ctx.activeBank)
    val numberOffset = ctx.bankNumberOffset(ctx.activeBank)
    val dotAlpha = ctx.focus.coerceIn(0f, 1f)
    fun withAlpha(a: Int) = scaleAlpha(a, dotAlpha)
    bands.forEachIndexed { index, band ->
        // §3 glass treatment: radial "lit from above" fill, real blurred glow when highlighted,
        // crisp ring + border, a top-left highlight arc, the R-channel dark ring and a
        // luminance-contrasted number.
        val channel = channelFor(band, ctx.channelDisplay) ?: return@forEachIndexed
        val color = filterColor(numberOffset + index)
        val x = g.xForFrequency(band.frequency)
        val y = g.yForGain(curveDbAt(perChannel[channel], band.frequency, ctx.maxFrequency))
        val highlighted = band.uuid == ctx.selectedId ||
            band.uuid == ctx.calloutBandId ||
            band.uuid == ctx.highlightId
        val radius = (if (highlighted) ACTIVE_NODE_RADIUS_DP + 3f else ACTIVE_NODE_RADIUS_DP) * d

        if (highlighted) {
            p.nodeHaloPaint.color = color
            p.nodeHaloPaint.alpha = withAlpha(160)
            nc.drawCircle(x, y, ACTIVE_NODE_RADIUS_DP * d + 11f * d, p.nodeHaloPaint)
            gl.nodeGlowPaint.color = color
            gl.nodeGlowPaint.alpha = withAlpha(180)
            nc.drawCircle(x, y, radius + 5f * d, gl.nodeGlowPaint)
            gl.nodeRingPaint.color = ColorUtils.blendARGB(color, AndroidColor.WHITE, 0.8f)
            gl.nodeRingPaint.alpha = withAlpha(255)
            nc.drawCircle(x, y, radius + 2.5f * d, gl.nodeRingPaint)
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
    nc.drawText("L", leftBarX + barWidth / 2f, g.bottom + 21f * d, p.meterLabelPaint)
    nc.drawText("R", rightBarX + barWidth / 2f, g.bottom + 21f * d, p.meterLabelPaint)
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
 * Nearest of [bank]'s nodes within [NODE_TOUCH_RADIUS_DP] of [tap], at the same place
 * [drawFocusedNodes] draws them (on the bank's own curve). Only the focused bank's nodes are on
 * screen, so only they can be hit. Numbers are global 1-based (Pre EQ, then Low, Mid, High).
 */
private fun hitTestFocusedBank(
    tap: Offset,
    geometry: PeqPlotGeometry,
    curves: BmwResponseCurves,
    peqState: BmwPeqState,
    bank: BmwPeqBank,
    channelDisplay: PeqChannelDisplay,
    maxFrequency: Double,
    density: Float,
): NodeHit? {
    val full = peqState.fullRangeBands.toList()
    val low = peqState.lowBandBands.toList()
    val mid = peqState.midBandBands.toList()
    val high = peqState.highBandBands.toList()
    val (bands, numberOffset) = when (bank) {
        BmwPeqBank.FULL -> full to 0
        BmwPeqBank.LOW -> low to full.size
        BmwPeqBank.MID -> mid to full.size + low.size
        BmwPeqBank.HIGH -> high to full.size + low.size + mid.size
    }
    // Mirrors drawFocusedNodes: a silent High band (3-way off) draws no nodes, so none can be hit.
    if (bank == BmwPeqBank.HIGH && !curves.highBranchActive) return null
    val perChannel = bankCurves(curves, bank)
    val radius = NODE_TOUCH_RADIUS_DP * density
    var best: NodeHit? = null
    var bestDistance = Float.MAX_VALUE
    bands.forEachIndexed { index, band ->
        val channel = channelFor(band, channelDisplay) ?: return@forEachIndexed
        val x = geometry.xForFrequency(band.frequency)
        val y = geometry.yForGain(curveDbAt(perChannel[channel], band.frequency, maxFrequency))
        val distance = hypot(tap.x - x, tap.y - y)
        if (distance <= radius && distance < bestDistance) {
            bestDistance = distance
            best = NodeHit(band, bank, numberOffset + index + 1, Offset(x, y))
        }
    }
    return best
}

private fun bankLabel(bank: BmwPeqBank): String = when (bank) {
    BmwPeqBank.FULL -> "Pre EQ"
    BmwPeqBank.LOW -> "Low Band"
    BmwPeqBank.MID -> "Mid Band"
    BmwPeqBank.HIGH -> "High Band"
}

/** The tapped-node detail card — the [drawInfoCard] content, as a small Compose surface. */
@Composable
private fun PeqNodeCallout(hit: NodeHit, accent: Color, modifier: Modifier = Modifier) {
    val band = hit.band
    // A thin, bright "neon" edge in the node's own colour — accent lifted toward white so it
    // reads as a lit outline around the text body rather than a heavy frame.
    val neon = lerp(accent, Color.White, 0.3f)
    Column(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF121316))
            .border(1.dp, neon, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            "#${hit.number} · ${band.filterType.displayLabel} · ${band.channel.displayLabel}",
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Text(
            "${band.frequency.roundToInt()} Hz · ${"%+.1f".format(band.gain)} dB · Q ${"%.2f".format(band.q)}",
            color = Color(0xFFE8EAF0),
            fontSize = 14.sp,
        )
        Text("${bankLabel(hit.bank)} band", color = Color(0xFF9AA0AA), fontSize = 14.sp)
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
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            interactionSource = interactionSource,
            modifier = Modifier.bmwFocusRing(interactionSource),
        ) {
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
        fontSize = 14.sp,
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
    /** 1 = [activeBank] in focus (its curve, filter shapes, nodes); 0 = the full curve. */
    val focus: Float = 1f,
    val calloutBandId: UUID? = null,
    val highlightId: UUID? = null,
    val leftMeter: PeakHoldMeter? = null,
    val rightMeter: PeakHoldMeter? = null,
) {
    val curves: BmwResponseCurves get() = model.curves

    val fullBands: List<ParametricEqBand> = peqState.fullRangeBands.toList()
    val lowBands: List<ParametricEqBand> = peqState.lowBandBands.toList()
    val midBands: List<ParametricEqBand> = peqState.midBandBands.toList()
    val highBands: List<ParametricEqBand> = peqState.highBandBands.toList()

    /** Global 1-based filter numbering: Full, then Low, then Mid, then High — = ParametricEqSurface.bankNumberOffset. */
    fun bankNumberOffset(bank: BmwPeqBank): Int = when (bank) {
        BmwPeqBank.FULL -> 0
        BmwPeqBank.LOW -> fullBands.size
        BmwPeqBank.MID -> fullBands.size + lowBands.size
        BmwPeqBank.HIGH -> fullBands.size + lowBands.size + midBands.size
    }

    fun bandsOf(bank: BmwPeqBank): List<ParametricEqBand> = when (bank) {
        BmwPeqBank.FULL -> fullBands
        BmwPeqBank.LOW -> lowBands
        BmwPeqBank.MID -> midBands
        BmwPeqBank.HIGH -> highBands
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
    val scratchPath = Path()

    val spectrumXs = FloatArray(SPECTRUM_STEPS + 1)
    val spectrumDryYs = FloatArray(SPECTRUM_STEPS + 1)
    val spectrumWetYs = FloatArray(SPECTRUM_STEPS + 1)
    val spectrumStrokePath = Path()
    val spectrumFillPath = Path()
    val dryStrokePath = Path()
    val deltaFillPath = Path()

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

    // The gain window, fitted to the curves in fitAxis(). Starts as the old fixed -24..+12.
    var axisTop = PeqGraphMath.MAX_GAIN
    var axisBottom = PeqGraphMath.MIN_GAIN

    /** Plot geometry on the fitted gain window; draw and hit-test both build theirs here. */
    fun geometry(left: Float, right: Float, top: Float, bottom: Float, maxFrequency: Double) =
        PeqPlotGeometry(left, right, top, bottom, maxFrequency, axisBottom, axisTop)

    fun recompute(values: FloatArray, peq: BmwPeqState, sampleRate: Double) {
        if (values.size != BmwSignalChain.VALUE_COUNT) return
        val maxFreq = min(20_000.0, sampleRate * 0.5 * 0.999)
        calculator.configureAxis(sampleRate, 20.0, maxFreq)
        calculator.invalidateAll()
        calculator.compute(values, peq, curves)
        fitAxis()
    }

    /**
     * Fits the gain window to what's drawn, so the curves use the plot's height instead of sitting
     * in the bottom of a fixed -24..+12: the top is the first 6 dB line at least 3 dB above the
     * highest point of the full curve or any bank's curve (uncapped, so a big stacked boost still
     * shows its real height); the window is 24 dB tall, or 36 dB when
     * the full curve dips further than that (ignoring the rolled-off ends below 40 Hz / above
     * 16 kHz).
     */
    private fun fitAxis() {
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        val freqs = curves.frequencies
        for (ch in 0..1) {
            for (arr in arrayOf(curves.sumDb[ch], curves.preSplitDb[ch], curves.lowBranchDb[ch], curves.midBranchDb[ch])) {
                for (v in arr) if (v.isFinite() && v > high) high = v
            }
            if (curves.highBranchActive) for (v in curves.highBranchDb[ch]) if (v.isFinite() && v > high) high = v
            val sum = curves.sumDb[ch]
            for (i in sum.indices) {
                if (i < freqs.size && freqs[i] in 40.0..16_000.0 && sum[i].isFinite() && sum[i] < low) low = sum[i]
            }
        }
        if (!high.isFinite() || !low.isFinite()) return
        val top = ceil((high + 3.0) / 6.0) * 6.0
        val span = if (top - low <= 22.0) 24.0 else 36.0
        axisTop = top
        axisBottom = top - span
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
    // The full curve drawn faint behind a focused bank.
    val faintSumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeJoin = Paint.Join.ROUND
    }

    // The focused bank's own curve (L solid, R dashed).
    val bankCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeJoin = Paint.Join.ROUND
    }
    val bankCurveDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }

    // Band areas: a wash under each band's curve (shader set per band) and its thin outline.
    val bandAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val bandAreaEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // A focused bank's filters: one translucent shape each.
    val filterShapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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
        }
    }
    BmwDspTheme {
        Box(Modifier.fillMaxSize()) {
            PeqGraphFrame(systemValues = values, modifier = Modifier.size(760.dp, 300.dp))
        }
    }
}
