package app.siphondsp.compose.screens

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.TypedValue
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.pow

/**
 * Compose port of [app.siphondsp.fragment.NativeBmwDspResponseView] -- the read-only "unified
 * live BMW DSP response" preview (Full-Range PEQ/preamp + headroom + low/mid crossover branches +
 * branch PEQ/gain/delay/polarity + complex summation + tilt + post gain, with a live spectrum
 * behind it). No nodes, no tap interaction.
 *
 * Drawing is kept 1:1 with `NativeBmwDspResponseView.onDraw` / `drawGrid` / `drawLegend` /
 * `drawSpectrum` / `drawResponse`. Two Compose-side differences, both deliberate:
 *  - the response curves are computed in a `remember` keyed on the DSP values, not re-run on
 *    every spectrum frame the way the View's `drawResponse` does (curves don't depend on the
 *    spectrum);
 *  - a dashed crossover-Hz marker is added at the Lowpass / Highpass corner frequencies -- the
 *    View has none, but this graph now lives on the Crossovers page where it earns its place
 *    (`CrossoverHandoffSurface` drew the same markers).
 *
 * Colours: the View resolves `lowPaint`/`midPaint`/`sumPaint` from the Android theme attrs
 * `colorAccent` / `textColorLink` / `textColorPrimary`. Here we use the already-resolved
 * appearance directly -- [BmwTheme.colors] `sliderLowBand` / `midBandYellow` / white -- and keep
 * the theme-attr lookup only for the grid/label/spectrum/marker greys (`textColorSecondary`) and
 * the legend text (`textColorPrimary`).
 *
 * `docs/ANALYZER_VISUAL_SPEC.md` polish applied on top of the 1:1 port:
 *  - §1 real blur glow under the summed curve only (BlurMaskFilter, the same mechanism the PEQ
 *    port uses -- no RenderEffect layer juggling; the low/mid branch curves stay plain);
 *  - §2 gradient area fill under the summed magnitude curve, fading to nothing at the 0 dB line
 *    (MAGNITUDE / MAGNITUDE_PHASE only -- a fill under a wrapped phase or a group-delay curve
 *    is meaningless);
 *  - §4 grid hierarchy: the 0 line is brightest, the 100 Hz / 1 k / 10 k octave verticals are
 *    mid, every other gridline recedes.
 * §7 (spectrum EMA / peak-hold) is deliberately skipped -- this graph's spectrum is already a
 * subtle 28/105-alpha wash, drawn behind the curves in only two of the four modes, so it never
 * reads as a competing element the way PEQ's foreground spectrum did.
 */
enum class CrossoverGraphMode { MAGNITUDE, PHASE, MAGNITUDE_PHASE, GROUP_DELAY }

// --- geometry / constants, 1:1 with NativeBmwDspResponseView ---------------------------------
private const val PAD_LEFT_DP = 42f
private const val PAD_RIGHT_DP = 12f
private const val PAD_TOP_DP = 24f
private const val PAD_BOTTOM_DP = 28f
private const val LABEL_TEXT_SP = 10f
private const val LEGEND_TEXT_SP = 11f
private const val MARKER_TEXT_SP = 9f
private const val POINT_COUNT = 192
private const val SAMPLE_RATE = 48_000.0
private const val SPECTRUM_POINTS = 180
private const val SPECTRUM_TICK_MS = 33L

private val DbGridLines = floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f, -24f)
private val PhaseGridLines = floatArrayOf(180f, 90f, 0f, -90f, -180f)
private val GroupDelayGridLines = floatArrayOf(10f, 6f, 2f, 0f, -2f)
private val FreqGridLines =
    floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)

// ANALYZER_VISUAL_SPEC.md polish
private const val SUM_GLOW_BLUR_DP = 7f
private const val SUM_GLOW_ALPHA = 90
private const val AREA_FILL_ALPHA = 82   // §2: curve colour at the curve edges, -> 0 at 0 dB
private const val GRID_BASE_ALPHA = 42   // §4: everything that isn't a reference line recedes
private const val GRID_OCTAVE_ALPHA = 78 // §4: 100 Hz / 1 k / 10 k slightly brighter
private const val GRID_ZERO_ALPHA = 122  // §4: the 0 line is the brightest
private val OctaveFreqs = setOf(100f, 1000f, 10000f)

private fun CrossoverGraphMode.showsSpectrum() =
    this == CrossoverGraphMode.MAGNITUDE || this == CrossoverGraphMode.MAGNITUDE_PHASE

@Composable
fun CrossoverResponseGraph(
    mode: CrossoverGraphMode,
    systemValues: FloatArray,
    peqState: BmwPeqState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lowArgb = BmwTheme.colors.sliderLowBand.toArgb()
    val midArgb = BmwTheme.colors.midBandYellow.toArgb()
    val sumArgb = android.graphics.Color.WHITE
    val gridArgb = remember(context) { themeColor(context, android.R.attr.textColorSecondary) }
    val legendArgb = remember(context) { themeColor(context, android.R.attr.textColorPrimary) }

    // Paints built in the same apply-order the View uses (alpha set, then colour). setColor()
    // overwrites the alpha channel, so this reproduces the View's final Paint state exactly
    // whatever alpha the resolved theme colour carries.
    // §4 grid hierarchy: three weights off the same grey. Base is dimmer than the View's so the
    // brightened reference lines actually read as emphasised. `alpha` is set AFTER `color` here
    // (unlike the 1:1 paints) precisely so it is not overwritten.
    val gridPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = gridArgb
            alpha = GRID_BASE_ALPHA
        }
    }
    val gridOctavePaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = gridArgb
            alpha = GRID_OCTAVE_ALPHA
        }
    }
    val gridZeroPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            color = gridArgb
            alpha = GRID_ZERO_ALPHA
        }
    }
    val labelPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = LABEL_TEXT_SP * density
            color = gridArgb
        }
    }
    val legendPaint = remember(density, legendArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = LEGEND_TEXT_SP * density
            color = legendArgb
        }
    }
    // The View draws the "LOW" / "MID" legend words with the same STROKE paints it draws the
    // curves with (2.1dp stroke, alpha 180) -- reproduced here for 1:1.
    val lowPaint = remember(density, lowArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.1f * density
            alpha = 180
            color = lowArgb
        }
    }
    val midPaint = remember(density, midArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.1f * density
            alpha = 180
            color = midArgb
        }
    }
    val sumPaint = remember(density, sumArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.1f * density
            color = sumArgb
        }
    }
    // §1: real Gaussian blur glow beneath the summed curve (BlurMaskFilter, same as the PEQ port).
    val sumGlowPaint = remember(density, sumArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.1f * density * 2.2f
            maskFilter = BlurMaskFilter(SUM_GLOW_BLUR_DP * density, BlurMaskFilter.Blur.NORMAL)
            color = sumArgb
            alpha = SUM_GLOW_ALPHA
        }
    }
    // §2: gradient area fill under the summed magnitude curve; shader rebuilt per draw (only the
    // two magnitude modes reach it).
    val areaFillPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val phasePaint = remember(density, sumArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
            color = sumArgb
        }
    }
    val spectrumFillPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 28
            color = gridArgb
        }
    }
    val spectrumPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
            alpha = 105
            color = gridArgb
        }
    }
    val markerPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
            alpha = 120
            color = gridArgb
        }
    }
    val markerLabelPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = MARKER_TEXT_SP * density
            alpha = 150
            color = gridArgb
        }
    }

    // Response curves -- same shared engine the View and the full PEQ visualiser use. Recomputed
    // only when the DSP values / PEQ snapshot change (not per spectrum frame).
    val calculator = remember { BmwResponseCalculator(pointCount = POINT_COUNT) }
    val curves = remember { BmwResponseCurves(POINT_COUNT) }
    val valuesHash = systemValues.contentHashCode()
    remember(valuesHash, peqState) {
        if (systemValues.size == BmwSignalChain.VALUE_COUNT) {
            calculator.configureAxis(SAMPLE_RATE, 20.0, 20_000.0)
            calculator.invalidateAll()
            calculator.compute(systemValues, peqState, curves)
        }
    }

    // Live spectrum poll, scoped to composition and to the modes that draw it. Acquire/release
    // bracket SpectrumEngine; the tick is read only in the draw phase so it never recomposes.
    val spectrumActive = mode.showsSpectrum()
    val spectrumTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(spectrumActive) {
        if (!spectrumActive) return@LaunchedEffect
        SpectrumEngine.acquire()
        try {
            while (true) {
                spectrumTick.intValue++
                delay(SPECTRUM_TICK_MS)
            }
        } finally {
            SpectrumEngine.release()
        }
    }

    ComposeCanvas(modifier) {
        val left = PAD_LEFT_DP * density
        val right = size.width - PAD_RIGHT_DP * density
        val top = PAD_TOP_DP * density
        val bottom = size.height - PAD_BOTTOM_DP * density
        if (right <= left || bottom <= top) return@ComposeCanvas
        val frame = spectrumTick.intValue // read in the draw phase -> redraws each spectrum tick
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            drawLegend(nc, mode, left, top - 10f * density, density, lowPaint, midPaint, legendPaint)
            val toY: (Float) -> Float = when (mode) {
                CrossoverGraphMode.MAGNITUDE, CrossoverGraphMode.MAGNITUDE_PHASE ->
                    { v -> dbToY(v, top, bottom) }
                CrossoverGraphMode.PHASE -> { v -> valueToY(v, -180f, 180f, top, bottom) }
                CrossoverGraphMode.GROUP_DELAY -> { v -> valueToY(v, -2f, 10f, top, bottom) }
            }
            val gridLines = when (mode) {
                CrossoverGraphMode.MAGNITUDE, CrossoverGraphMode.MAGNITUDE_PHASE -> DbGridLines
                CrossoverGraphMode.PHASE -> PhaseGridLines
                CrossoverGraphMode.GROUP_DELAY -> GroupDelayGridLines
            }
            drawGrid(
                nc, left, right, top, bottom, density, gridLines,
                gridPaint, gridOctavePaint, gridZeroPaint, labelPaint, toY,
            )
            drawCrossoverMarkers(nc, systemValues, left, right, top, bottom, density, markerPaint, markerLabelPaint)
            if (mode.showsSpectrum()) {
                drawSpectrum(nc, left, right, top, bottom, frame, spectrumFillPaint, spectrumPaint)
            }
            if (mode == CrossoverGraphMode.MAGNITUDE || mode == CrossoverGraphMode.MAGNITUDE_PHASE) {
                drawSumAreaFill(nc, curves, left, right, top, bottom, areaFillPaint)
            }
            drawResponse(nc, mode, curves, left, right, top, bottom, lowPaint, midPaint, sumPaint, sumGlowPaint, phasePaint)
        }
    }
}

// --- drawing, 1:1 with NativeBmwDspResponseView ---------------------------------------------

private fun drawGrid(
    nc: Canvas,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    density: Float,
    lines: FloatArray,
    gridPaint: Paint,
    octavePaint: Paint,
    zeroPaint: Paint,
    labelPaint: Paint,
    valueToY: (Float) -> Float,
) {
    lines.forEach { value ->
        val y = valueToY(value)
        nc.drawLine(left, y, right, y, if (value == 0f) zeroPaint else gridPaint)
        nc.drawText(value.toInt().toString(), 5f * density, y + 4f * density, labelPaint)
    }
    FreqGridLines.forEach { frequency ->
        val x = frequencyToX(frequency, left, right)
        nc.drawLine(x, top, x, bottom, if (frequency in OctaveFreqs) octavePaint else gridPaint)
        val label = if (frequency >= 1000f) "${(frequency / 1000f).toInt()}k" else frequency.toInt().toString()
        nc.drawText(label, x - labelPaint.measureText(label) / 2f, bottom + 18f * density, labelPaint)
    }
}

private fun drawLegend(
    nc: Canvas,
    mode: CrossoverGraphMode,
    left: Float,
    baseline: Float,
    density: Float,
    lowPaint: Paint,
    midPaint: Paint,
    legendPaint: Paint,
) {
    if (mode == CrossoverGraphMode.GROUP_DELAY) {
        nc.drawText("GROUP DELAY · FINAL SUM", left, baseline, legendPaint)
        return
    }
    nc.drawText("LOW", left, baseline, lowPaint)
    nc.drawText("MID", left + 42f * density, baseline, midPaint)
    val label = when (mode) {
        CrossoverGraphMode.PHASE -> "PHASE · FINAL SUM · PEQ · TILT · GAINS"
        CrossoverGraphMode.MAGNITUDE_PHASE -> "SOLID = MAGNITUDE, DASHED = PHASE"
        else -> "FINAL SUM · PEQ · TILT · GAINS"
    }
    nc.drawText(label, left + 88f * density, baseline, legendPaint)
}

/** = `NativeBmwDspResponseView.drawSpectrum`. `tick` only forces the snapshot read to count. */
private fun drawSpectrum(
    nc: Canvas,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    @Suppress("UNUSED_PARAMETER") tick: Int,
    fillPaint: Paint,
    strokePaint: Paint,
) {
    val line = Path()
    val fill = Path().apply { moveTo(left, bottom) }
    for (i in 0 until SPECTRUM_POINTS) {
        val fraction = i.toDouble() / (SPECTRUM_POINTS - 1)
        val frequency = 20.0 * 1000.0.pow(fraction)
        val spectrumDb = SpectrumEngine.magnitudeDbAt(frequency)
        val displayDb = ((spectrumDb - SpectrumEngine.FLOOR_DB) /
            (SpectrumEngine.CEILING_DB - SpectrumEngine.FLOOR_DB) * 36f - 24f)
        val x = left + fraction.toFloat() * (right - left)
        val y = dbToY(displayDb, top, bottom)
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        fill.lineTo(x, y)
    }
    fill.lineTo(right, bottom)
    fill.close()
    nc.drawPath(fill, fillPaint)
    nc.drawPath(line, strokePaint)
}

/**
 * = `NativeBmwDspResponseView.drawResponse` (Low / Mid / Sum, per display mode), plus
 * ANALYZER_VISUAL_SPEC §1: the summed curve is stroked twice -- a blurred [sumGlowPaint] pass
 * then the crisp [sumPaint] -- while the low/mid branch curves stay plain.
 */
private fun drawResponse(
    nc: Canvas,
    mode: CrossoverGraphMode,
    curves: BmwResponseCurves,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    lowPaint: Paint,
    midPaint: Paint,
    sumPaint: Paint,
    sumGlowPaint: Paint,
    phasePaint: Paint,
) {
    val lastIndex = POINT_COUNT - 1
    fun pathFor(sample: (Int) -> Float): Path = Path().apply {
        for (i in 0 until POINT_COUNT) {
            val x = left + (i.toFloat() / lastIndex) * (right - left)
            val y = sample(i)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    fun strokeSum(path: Path) {
        nc.drawPath(path, sumGlowPaint)
        nc.drawPath(path, sumPaint)
    }
    val l = BmwOutputChannel.LEFT
    val r = BmwOutputChannel.RIGHT
    when (mode) {
        CrossoverGraphMode.MAGNITUDE -> {
            nc.drawPath(pathFor { i -> dbToY(averageDb(curves.lowBranchDbFor(l)[i], curves.lowBranchDbFor(r)[i]), top, bottom) }, lowPaint)
            nc.drawPath(pathFor { i -> dbToY(averageDb(curves.midBranchDbFor(l)[i], curves.midBranchDbFor(r)[i]), top, bottom) }, midPaint)
            strokeSum(pathFor { i -> dbToY(averageDb(curves.sumDbFor(l)[i], curves.sumDbFor(r)[i]), top, bottom) })
        }
        CrossoverGraphMode.PHASE -> {
            nc.drawPath(pathFor { i -> valueToY(averageDeg(curves.lowBranchPhaseFor(l)[i], curves.lowBranchPhaseFor(r)[i]), -180f, 180f, top, bottom) }, lowPaint)
            nc.drawPath(pathFor { i -> valueToY(averageDeg(curves.midBranchPhaseFor(l)[i], curves.midBranchPhaseFor(r)[i]), -180f, 180f, top, bottom) }, midPaint)
            strokeSum(pathFor { i -> valueToY(averageDeg(curves.sumPhaseFor(l)[i], curves.sumPhaseFor(r)[i]), -180f, 180f, top, bottom) })
        }
        CrossoverGraphMode.MAGNITUDE_PHASE -> {
            nc.drawPath(pathFor { i -> dbToY(averageDb(curves.lowBranchDbFor(l)[i], curves.lowBranchDbFor(r)[i]), top, bottom) }, lowPaint)
            nc.drawPath(pathFor { i -> dbToY(averageDb(curves.midBranchDbFor(l)[i], curves.midBranchDbFor(r)[i]), top, bottom) }, midPaint)
            strokeSum(pathFor { i -> dbToY(averageDb(curves.sumDbFor(l)[i], curves.sumDbFor(r)[i]), top, bottom) })
            nc.drawPath(pathFor { i -> valueToY(averageDeg(curves.sumPhaseFor(l)[i], curves.sumPhaseFor(r)[i]), -180f, 180f, top, bottom) }, phasePaint)
        }
        CrossoverGraphMode.GROUP_DELAY -> {
            val leftDelay = curves.groupDelayMsFor(l)
            val rightDelay = curves.groupDelayMsFor(r)
            strokeSum(
                pathFor { i -> valueToY((((leftDelay[i] + rightDelay[i]) * 0.5).toFloat()).coerceIn(-2f, 10f), -2f, 10f, top, bottom) },
            )
        }
    }
}

/**
 * ANALYZER_VISUAL_SPEC §2: gradient area fill under the summed magnitude curve. The summed
 * magnitude sits between the curve and the 0 dB reference; the fill is the curve colour (white)
 * at its edges fading to nothing at 0 dB, so a boost pool sits above the line and the crossover
 * notch pools below it. Drawn behind the curve stroke + glow. Magnitude modes only.
 */
private fun drawSumAreaFill(
    nc: Canvas,
    curves: BmwResponseCurves,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    fillPaint: Paint,
) {
    val lastIndex = POINT_COUNT - 1
    val zeroY = dbToY(0f, top, bottom)
    val path = Path()
    for (i in 0 until POINT_COUNT) {
        val x = left + (i.toFloat() / lastIndex) * (right - left)
        val y = dbToY(
            averageDb(curves.sumDbFor(BmwOutputChannel.LEFT)[i], curves.sumDbFor(BmwOutputChannel.RIGHT)[i]),
            top, bottom,
        )
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.lineTo(right, zeroY)
    path.lineTo(left, zeroY)
    path.close()
    val zeroFraction = ((12f - 0f) / (12f - (-24f))).coerceIn(0.02f, 0.98f)
    fillPaint.shader = LinearGradient(
        0f, top, 0f, bottom,
        intArrayOf(
            android.graphics.Color.argb(AREA_FILL_ALPHA, 255, 255, 255),
            android.graphics.Color.argb(0, 255, 255, 255),
            android.graphics.Color.argb(AREA_FILL_ALPHA, 255, 255, 255),
        ),
        floatArrayOf(0f, zeroFraction, 1f),
        Shader.TileMode.CLAMP,
    )
    nc.drawPath(path, fillPaint)
}

/**
 * Dashed vertical marker + Hz label at the Lowpass / Highpass corner frequencies. Not in
 * `NativeBmwDspResponseView`; matches what `CrossoverHandoffSurface.drawCornerMarker` showed on
 * this page.
 */
private fun drawCrossoverMarkers(
    nc: Canvas,
    values: FloatArray,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    density: Float,
    markerPaint: Paint,
    labelPaint: Paint,
) {
    if (values.size <= NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ) return
    intArrayOf(NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ)
        .forEach { index ->
            val freq = values[index]
            if (freq < 20f || freq > 20_000f) return@forEach
            val x = frequencyToX(freq, left, right).coerceIn(left, right)
            nc.drawLine(x, top, x, bottom, markerPaint)
            nc.drawText("${freq.toInt()}", x + 2f * density, top + 10f * density, labelPaint)
        }
}

// --- axis mapping, 1:1 with the View's private helpers -------------------------------------

private fun averageDb(left: Double, right: Double): Float =
    ((left + right) * 0.5).toFloat().coerceIn(-24f, 12f)

private fun averageDeg(left: Double, right: Double): Float =
    Math.toDegrees((left + right) * 0.5).toFloat().coerceIn(-180f, 180f)

private fun dbToY(db: Float, top: Float, bottom: Float): Float = valueToY(db, -24f, 12f, top, bottom)

private fun valueToY(value: Float, min: Float, max: Float, top: Float, bottom: Float): Float {
    val clamped = value.coerceIn(min, max)
    return top + (max - clamped) / (max - min) * (bottom - top)
}

private fun frequencyToX(frequency: Float, left: Float, right: Float): Float {
    val fraction = log10(frequency / 20f) / log10(1000f)
    return left + fraction * (right - left)
}

/** = `NativeBmwDspResponseView.resolveColor`. */
private fun themeColor(context: Context, attribute: Int): Int {
    val typedValue = TypedValue()
    context.theme.resolveAttribute(attribute, typedValue, true)
    return if (typedValue.resourceId != 0) {
        ContextCompat.getColor(context, typedValue.resourceId)
    } else {
        typedValue.data
    }
}
