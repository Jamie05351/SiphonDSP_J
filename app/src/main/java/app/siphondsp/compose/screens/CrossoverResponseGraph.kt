package app.siphondsp.compose.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.model.BmwPeqState
import kotlin.math.log10

/**
 * Compose port of [app.siphondsp.fragment.NativeBmwDspResponseView] -- the read-only "unified
 * live BMW DSP response" preview (Full-Range PEQ/preamp + headroom + low/mid crossover branches +
 * branch PEQ/gain/delay/polarity + complex summation + tilt + post gain, with a live spectrum
 * behind it). No nodes, no tap interaction.
 *
 * **Step A (this PR): static frame only** -- the grid ([drawGrid]) and the per-mode legend
 * ([drawLegend]). Curves ([drawResponse]), the spectrum ([drawSpectrum]) and the crossover-Hz
 * marker land in Step B. Geometry, grid lines, label positions and legend strings are kept 1:1
 * with `NativeBmwDspResponseView.onDraw` / `drawGrid` / `drawLegend`.
 *
 * Colours: the View resolves `lowPaint`/`midPaint`/`sumPaint` from the Android theme attrs
 * `colorAccent` / `textColorLink` / `textColorPrimary`. Here we use the already-resolved
 * appearance directly -- [BmwTheme.colors] `sliderLowBand` / `midBandYellow` / white -- and keep
 * the theme-attr lookup only for the grid/label/spectrum greys (`textColorSecondary`) and the
 * legend text (`textColorPrimary`), which the prompt did not override.
 */
enum class CrossoverGraphMode { MAGNITUDE, PHASE, MAGNITUDE_PHASE, GROUP_DELAY }

// --- geometry, 1:1 with NativeBmwDspResponseView.onDraw ---------------------------------------
private const val PAD_LEFT_DP = 42f
private const val PAD_RIGHT_DP = 12f
private const val PAD_TOP_DP = 24f
private const val PAD_BOTTOM_DP = 28f
private const val LABEL_TEXT_SP = 10f
private const val LEGEND_TEXT_SP = 11f

private val DbGridLines = floatArrayOf(12f, 6f, 0f, -6f, -12f, -18f, -24f)
private val PhaseGridLines = floatArrayOf(180f, 90f, 0f, -90f, -180f)
private val GroupDelayGridLines = floatArrayOf(10f, 6f, 2f, 0f, -2f)
private val FreqGridLines =
    floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)

@Composable
fun CrossoverResponseGraph(
    mode: CrossoverGraphMode,
    @Suppress("UNUSED_PARAMETER") systemValues: FloatArray,
    @Suppress("UNUSED_PARAMETER") peqState: BmwPeqState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lowArgb = BmwTheme.colors.sliderLowBand.toArgb()
    val midArgb = BmwTheme.colors.midBandYellow.toArgb()
    val gridArgb = remember(context) { themeColor(context, android.R.attr.textColorSecondary) }
    val legendArgb = remember(context) { themeColor(context, android.R.attr.textColorPrimary) }

    // Paints built in the same apply-order the View uses (alpha set, then colour). setColor()
    // overwrites the alpha channel, so this reproduces the View's final Paint state exactly
    // whatever alpha the resolved theme colour carries.
    val gridPaint = remember(density, gridArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            alpha = 62
            color = gridArgb
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

    ComposeCanvas(modifier) {
        val left = PAD_LEFT_DP * density
        val right = size.width - PAD_RIGHT_DP * density
        val top = PAD_TOP_DP * density
        val bottom = size.height - PAD_BOTTOM_DP * density
        if (right <= left || bottom <= top) return@ComposeCanvas
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            drawLegend(nc, mode, left, top - 10f * density, density, lowPaint, midPaint, legendPaint)
            when (mode) {
                CrossoverGraphMode.MAGNITUDE, CrossoverGraphMode.MAGNITUDE_PHASE ->
                    drawGrid(nc, left, right, top, bottom, density, DbGridLines, gridPaint, labelPaint) {
                        dbToY(it, top, bottom)
                    }
                CrossoverGraphMode.PHASE ->
                    drawGrid(nc, left, right, top, bottom, density, PhaseGridLines, gridPaint, labelPaint) {
                        valueToY(it, -180f, 180f, top, bottom)
                    }
                CrossoverGraphMode.GROUP_DELAY ->
                    drawGrid(nc, left, right, top, bottom, density, GroupDelayGridLines, gridPaint, labelPaint) {
                        valueToY(it, -2f, 10f, top, bottom)
                    }
            }
        }
    }
}

// --- drawing, 1:1 with NativeBmwDspResponseView.drawGrid / drawLegend -------------------------

private fun drawGrid(
    nc: Canvas,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    density: Float,
    lines: FloatArray,
    gridPaint: Paint,
    labelPaint: Paint,
    valueToY: (Float) -> Float,
) {
    lines.forEach { value ->
        val y = valueToY(value)
        nc.drawLine(left, y, right, y, gridPaint)
        nc.drawText(value.toInt().toString(), 5f * density, y + 4f * density, labelPaint)
    }
    FreqGridLines.forEach { frequency ->
        val x = frequencyToX(frequency, left, right)
        nc.drawLine(x, top, x, bottom, gridPaint)
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

// --- axis mapping, 1:1 with the View's private helpers --------------------------------------

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
