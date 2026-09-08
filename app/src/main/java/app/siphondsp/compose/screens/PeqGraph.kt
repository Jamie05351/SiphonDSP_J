package app.siphondsp.compose.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.TypedValue
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import app.siphondsp.view.PeqGraphMath
import app.siphondsp.view.PeqPlotGeometry
import app.siphondsp.view.PeqSurfacePaints
import kotlinx.coroutines.delay
import android.graphics.Color as AndroidColor
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
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

/**
 * The full response graph: [PeqGraphFrame]'s frame plus the modelled branch / per-band / sum
 * curves, the individual-filter overlays, and — when [showSpectrum] — the live dry/wet spectrum
 * trace with its boost/cut delta fill. Still no nodes and no interaction (10c-i-c).
 *
 * Stateless: the caller passes the current native config, PEQ state, active bank and selection.
 * The modelled response is recomputed synchronously whenever those inputs change; the spectrum
 * trace redraws at ~30 fps off its own effect loop without recomposing the rest.
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
    sampleRate: Double = 48_000.0,
) {
    val paints = rememberPeqSurfacePaints()
    val model = remember { PeqResponseModel() }
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

    // ~30 fps spectrum poll, scoped to composition. Acquire/release bracket SpectrumEngine; the
    // tick counter is read only in the draw phase below so it never triggers recomposition.
    val spectrumTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(showSpectrum) {
        if (!showSpectrum) return@LaunchedEffect
        SpectrumEngine.acquire()
        try {
            while (true) {
                spectrumTick.intValue++
                delay(33L)
            }
        } finally {
            SpectrumEngine.release()
        }
    }

    ComposeCanvas(modifier.fillMaxSize()) {
        // Read the tick here (draw phase), not in composition — see the effect above.
        val spectrumFrame = spectrumTick.intValue
        val left = PlotPadLeft.toPx()
        val top = PlotPadTop.toPx()
        val right = size.width - PlotPadRight.toPx()
        val bottom = size.height - PlotPadBottom.toPx()
        if (right <= left || bottom <= top) return@ComposeCanvas
        val geometry = PeqPlotGeometry(left, right, top, bottom, maxFrequency)
        val ctx = PeqDrawContext(
            geometry = geometry,
            paints = paints,
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
        )
        drawIntoCanvas { canvas ->
            renderPeqGraph(canvas.nativeCanvas, ctx, drawSpectrum = showSpectrum, spectrumFrame = spectrumFrame)
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
        )
    }
}

// --- render orchestration — mirrors ParametricEqSurface.drawUnifiedSystem ----------------------

/**
 * @param spectrumFrame unused by the drawing itself — reading it in the caller's draw phase is
 *        what re-runs this render on every spectrum tick.
 */
private fun renderPeqGraph(
    nc: Canvas,
    ctx: PeqDrawContext,
    drawSpectrum: Boolean,
    @Suppress("UNUSED_PARAMETER") spectrumFrame: Int,
) {
    val g = ctx.geometry
    when (ctx.mode) {
        PeqGraphMode.MAGNITUDE -> {
            drawGrid(nc, g, ctx.paints, ctx.density, MagnitudeGridLines) { g.yForGain(it) }
            drawCrossoverShading(nc, g, ctx.paints, ctx.systemValues, ctx.maxFrequency)
            drawMonoBassRegion(nc, g, ctx.paints, ctx.density, ctx.systemValues, ctx.maxFrequency)
            if (drawSpectrum) drawUnifiedSpectrum(nc, ctx)
            drawBranchCurves(nc, ctx)
            drawFilterOverlays(nc, ctx)
            drawPerBandFills(nc, ctx)
            drawSumCurve(nc, ctx)
        }
        PeqGraphMode.PHASE -> {
            drawGrid(nc, g, ctx.paints, ctx.density, PhaseGridLines) { g.yForPhaseDeg(it) }
            drawCrossoverShading(nc, g, ctx.paints, ctx.systemValues, ctx.maxFrequency)
            drawPhaseCurves(nc, ctx)
        }
    }
    drawLegend(nc, g, ctx.paints, ctx.density, ctx.systemValues, ctx.maxFrequency, ctx.mode)
}

// --- frame helpers (raw Canvas, shared by PeqGraphFrame and renderPeqGraph) --------------------

private fun drawGrid(
    nc: Canvas,
    g: PeqPlotGeometry,
    p: PeqSurfacePaints,
    density: Float,
    lines: FloatArray,
    toY: (Double) -> Float,
) {
    lines.forEach { value ->
        val y = toY(value.toDouble())
        nc.drawLine(g.left, y, g.right, y, if (value == 0f) p.unifiedZeroPaint else p.unifiedGridPaint)
        nc.drawText(value.toInt().toString(), 4f * density, y + 3f * density, p.unifiedLabelPaint)
    }
    FreqScale.forEach { frequency ->
        val x = g.xForFrequency(frequency)
        nc.drawLine(x, g.top, x, g.bottom, p.unifiedGridPaint)
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
    strokeNeon(nc, path, paint, ctx.paints.glowPaint)
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

private fun drawUnifiedSpectrum(nc: Canvas, ctx: PeqDrawContext) {
    val g = ctx.geometry
    val p = ctx.paints
    val m = ctx.model
    m.spectrumStrokePath.rewind()
    m.spectrumFillPath.rewind()
    m.dryStrokePath.rewind()
    m.spectrumFillPath.moveTo(g.left, g.bottom)
    for (i in 0..SPECTRUM_STEPS) {
        val fraction = i / SPECTRUM_STEPS.toFloat()
        val freq = PeqGraphMath.fractionToFrequency(fraction, PeqGraphMath.MIN_FREQUENCY, ctx.maxFrequency)
        val wetGain = PeqGraphMath.spectrumDbToGraphGain(
            SpectrumEngine.magnitudeDbAt(freq), SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB,
        )
        val dryGain = PeqGraphMath.spectrumDbToGraphGain(
            SpectrumEngine.dryMagnitudeDbAt(freq), SpectrumEngine.FLOOR_DB, SpectrumEngine.CEILING_DB,
        )
        val x = g.left + fraction * (g.right - g.left)
        val wetY = g.yForGain(wetGain)
        val dryY = g.yForGain(dryGain)
        m.spectrumXs[i] = x
        m.spectrumWetYs[i] = wetY
        m.spectrumDryYs[i] = dryY
        if (i == 0) {
            m.spectrumStrokePath.moveTo(x, wetY)
            m.dryStrokePath.moveTo(x, dryY)
        } else {
            m.spectrumStrokePath.lineTo(x, wetY)
            m.dryStrokePath.lineTo(x, dryY)
        }
        m.spectrumFillPath.lineTo(x, wetY)
    }
    m.spectrumFillPath.lineTo(g.right, g.bottom)
    m.spectrumFillPath.close()
    drawSpectrumDelta(nc, ctx, SPECTRUM_STEPS + 1)
    if (g.top != p.spectrumFillShaderTop || g.bottom != p.spectrumFillShaderBottom) {
        p.unifiedSpectrumFillPaint.shader = LinearGradient(
            0f, g.top, 0f, g.bottom,
            ColorUtils.setAlphaComponent(p.spectrumAccentColor, 150),
            ColorUtils.setAlphaComponent(p.spectrumAccentColor, 0),
            Shader.TileMode.CLAMP,
        )
        p.spectrumFillShaderTop = g.top
        p.spectrumFillShaderBottom = g.bottom
    }
    nc.drawPath(m.spectrumFillPath, p.unifiedSpectrumFillPaint)
    nc.drawPath(m.dryStrokePath, p.dryStrokePaint)
    strokeNeon(nc, m.spectrumStrokePath, p.unifiedSpectrumStrokePaint, p.glowPaint)
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

// --- draw-call context + reusable scratch -----------------------------------------------------

/** Everything [renderPeqGraph]'s helpers read — the Compose equivalent of the View's fields. */
private class PeqDrawContext(
    val geometry: PeqPlotGeometry,
    val paints: PeqSurfacePaints,
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

    fun recompute(values: FloatArray, peq: BmwPeqState, sampleRate: Double) {
        if (values.size != BmwSignalChain.VALUE_COUNT) return
        val maxFreq = min(20_000.0, sampleRate * 0.5 * 0.999)
        calculator.configureAxis(sampleRate, 20.0, maxFreq)
        calculator.invalidateAll()
        calculator.compute(values, peq, curves)
    }
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
