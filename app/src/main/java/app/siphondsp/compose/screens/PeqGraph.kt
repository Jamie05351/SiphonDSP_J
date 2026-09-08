package app.siphondsp.compose.screens

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.withStyledAttributes
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.extensions.prettyNumberFormat
import app.siphondsp.view.MonoBassCue
import app.siphondsp.view.PeqGraphMath
import app.siphondsp.view.PeqPlotGeometry
import app.siphondsp.view.PeqSurfacePaints
import android.graphics.Color as AndroidColor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Compose-drawn Parametric EQ response graph (roadmap Phase 10c-i) — the replacement for the
 * `AndroidView(ParametricEqSurface)` wrapper. Built in stacked sub-PRs:
 *
 *  - **10c-i-a (this file so far):** the static plot frame — log-frequency / dB (or degree) grid,
 *    axis labels, crossover + mono-bass shading, and the bank legend. No holder state, no curves,
 *    no nodes, no interaction yet; not wired into any fragment (same "land the piece, wire it in
 *    10e" approach as `PeqStateHolder` / `PeqBandList`).
 *  - 10c-i-b: branch / per-band / sum curves + the live spectrum trace.
 *  - 10c-i-c: numbered nodes, tap hit-testing, the auto-fading detail callout, tilt handles, gain
 *    meters, and the graph-options menu.
 *
 * Drawing is kept 1:1 with `ParametricEqSurface`: geometry routes through the same
 * [PeqPlotGeometry] / [PeqGraphMath], and paints are the very same [PeqSurfacePaints] the View
 * draws with, so "the Compose graph" and "the old View graph" render identically until 10c-ii
 * deliberately changes the paint treatment.
 */

/**
 * Display modes the Compose graph supports. Deliberately just two (2026-09-09 direction):
 * `ParametricEqSurface.DisplayMode` still also carries `MAGNITUDE_PHASE` and `GROUP_DELAY` for the
 * not-yet-retired View fragment, but the port drops both — the combined overlay and the
 * group-delay curve/grid/math are not carried over.
 */
enum class PeqGraphMode { MAGNITUDE, PHASE }

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

/**
 * The static plot frame — grid + axis labels + crossover / mono-bass shading + legend.
 *
 * @param systemValues the 192-float native BMW DSP config array ([BmwSignalChain.VALUE_COUNT]);
 *        only the crossover-frequency and mono-bass fields are read here. A wrong-sized array is
 *        tolerated — the shading passes just no-op, exactly as the View guards.
 * @param sampleRate live recorder sample rate; caps the x-axis at `min(20 kHz, fs/2 · 0.999)`,
 *        same as `ParametricEqSurface.recomputeSystemResponse`.
 */
@Composable
fun PeqGraphFrame(
    systemValues: FloatArray,
    modifier: Modifier = Modifier,
    mode: PeqGraphMode = PeqGraphMode.MAGNITUDE,
    sampleRate: Double = 48_000.0,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val paints = remember(context, density) {
        PeqSurfacePaints(
            density = density,
            themeTextColor = themeColor(context, android.R.attr.textColorPrimary),
            themeAccentColor = themeColor(context, android.R.attr.colorAccent),
        )
    }
    val maxFrequency = remember(sampleRate) {
        min(PeqGraphMath.MAX_FREQUENCY, sampleRate * 0.5 * 0.999)
    }

    Canvas(modifier) {
        val left = PlotPadLeft.toPx()
        val top = PlotPadTop.toPx()
        val right = size.width - PlotPadRight.toPx()
        val bottom = size.height - PlotPadBottom.toPx()
        if (right <= left || bottom <= top) return@Canvas

        val geometry = PeqPlotGeometry(left, right, top, bottom, maxFrequency)
        val gridLines = if (mode == PeqGraphMode.PHASE) PhaseGridLines else MagnitudeGridLines
        val toY: (Double) -> Float =
            if (mode == PeqGraphMode.PHASE) geometry::yForPhaseDeg else geometry::yForGain

        drawGrid(geometry, paints, gridLines, toY)
        // The View draws crossover shading in every mode; the mono-bass region only in MAGNITUDE.
        drawCrossoverShading(geometry, paints, systemValues, maxFrequency)
        if (mode == PeqGraphMode.MAGNITUDE) {
            drawMonoBassRegion(geometry, paints, systemValues, maxFrequency)
        }
        drawLegend(geometry, paints, systemValues, maxFrequency, mode)
    }
}

// --- draw helpers — each mirrors the same-named ParametricEqSurface method -------------------

private fun DrawScope.drawGrid(
    g: PeqPlotGeometry,
    paints: PeqSurfacePaints,
    gainLines: FloatArray,
    toY: (Double) -> Float,
) {
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        gainLines.forEach { value ->
            val y = toY(value.toDouble())
            nc.drawLine(
                g.left, y, g.right, y,
                if (value == 0f) paints.unifiedZeroPaint else paints.unifiedGridPaint,
            )
            nc.drawText(value.toInt().toString(), 4f * density, y + 3f * density, paints.unifiedLabelPaint)
        }
        FreqScale.forEach { frequency ->
            val x = g.xForFrequency(frequency)
            nc.drawLine(x, g.top, x, g.bottom, paints.unifiedGridPaint)
            val label = frequency.prettyNumberFormat()
            nc.drawText(
                label,
                x - paints.unifiedLabelPaint.measureText(label) / 2f,
                g.bottom + 15f * density,
                paints.unifiedLabelPaint,
            )
        }
    }
}

private fun DrawScope.drawCrossoverShading(
    g: PeqPlotGeometry,
    paints: PeqSurfacePaints,
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
    drawIntoCanvas {
        it.nativeCanvas.drawRect(
            g.xForFrequency(lowFreq).coerceIn(g.left, g.right),
            g.top,
            g.xForFrequency(midFreq).coerceIn(g.left, g.right),
            g.bottom,
            paints.crossoverShadePaint,
        )
    }
}

private fun DrawScope.drawMonoBassRegion(
    g: PeqPlotGeometry,
    paints: PeqSurfacePaints,
    values: FloatArray,
    maxFrequency: Double,
) {
    if (values.size != BmwSignalChain.VALUE_COUNT || !MonoBassCue.isActive(values)) return
    val frequency = MonoBassCue.frequency(values, maxFrequency)
    val cornerX = g.xForFrequency(frequency).coerceIn(g.left, g.right)
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        nc.drawRect(g.left, g.top, cornerX, g.bottom, paints.crossoverShadePaint)
        nc.drawLine(cornerX, g.top, cornerX, g.bottom, paints.unifiedGridPaint)
        nc.drawText(
            "MONO BASS ▸ ${frequency.roundToInt()} Hz",
            g.left + 6f * density,
            g.bottom - 6f * density,
            paints.tiltLabelPaint,
        )
    }
}

private fun DrawScope.drawLegend(
    g: PeqPlotGeometry,
    paints: PeqSurfacePaints,
    values: FloatArray,
    maxFrequency: Double,
    mode: PeqGraphMode,
) {
    val baseline = g.top - 6f * density
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        fun tinted(color: Int) = Paint(paints.unifiedLegendPaint).apply { this.color = color }
        when (mode) {
            PeqGraphMode.PHASE -> {
                nc.drawText("LOW", g.left, baseline, tinted(paints.bankColorLow))
                nc.drawText("MID", g.left + 38f * density, baseline, tinted(paints.bankColorMid))
                nc.drawText(
                    "FINAL SUM PHASE (L solid / R dashed) · compressor not shown (nonlinear)",
                    g.left + 76f * density, baseline, paints.unifiedLegendPaint,
                )
            }
            PeqGraphMode.MAGNITUDE -> {
                nc.drawText("FULL", g.left, baseline, tinted(paints.bankColorFull))
                nc.drawText("LOW", g.left + 38f * density, baseline, tinted(paints.bankColorLow))
                nc.drawText("MID", g.left + 74f * density, baseline, tinted(paints.bankColorMid))
                val sumNote = if (MonoBassCue.isActive(values)) {
                    "FINAL SUM (L solid / R dashed, mono below " +
                        "${MonoBassCue.frequency(values, maxFrequency).roundToInt()} Hz)"
                } else {
                    "FINAL SUM (L solid / R dashed) · compressor not shown (nonlinear)"
                }
                nc.drawText(sumNote, g.left + 112f * density, baseline, paints.unifiedLegendPaint)
            }
        }
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
