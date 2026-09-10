package app.siphondsp.compose.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.extensions.prettyNumberFormat
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CompressorSurfaceMath

/**
 * Compose port of [app.siphondsp.view.CompressorSurface] -- the pinned, display-only visualiser
 * for the pre-crossover 4-band compressor: a log-frequency X-axis, a single dBFS Y-axis, the four
 * shaded crossover-band regions + split lines, each band's threshold line, the per-band GR
 * readout, and (Step B) the live dry/wet spectrum + the applied gain-reduction curve. Nothing
 * here is interactive.
 *
 * **Step A (this PR): static frame** -- [drawBandRegions], [drawGrid], [drawThresholdLines],
 * [drawBandReadouts]. The spectrum ([drawSpectrum] / [drawSpectrumDelta]) and the live gain
 * curve ([drawGainCurve]) land in Step B. Geometry, grid sets, colours, alpha values and label
 * positions are kept 1:1 with `CompressorSurface.onDraw`.
 *
 * `CompressorSurfaceMath` is a pure object -- splits, band ranges, the freq/dB fraction mapping
 * and the GR->dB conversion are shared verbatim with the View.
 */

// --- geometry / constants, 1:1 with CompressorSurface ----------------------------------------
private const val PAD_LEFT_DP = 28f
private const val PAD_RIGHT_DP = 10f
private const val PAD_TOP_DP = 12f
private const val PAD_BOTTOM_DP = 20f
private const val BAND_FILL_ALPHA = 34

private val FreqScale = doubleArrayOf(
    25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
    1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
)
private val BandTints = intArrayOf(
    BmwDashboardSkin.M_BLUE, 0xFF6E7BFF.toInt(), 0xFFF2B33D.toInt(), 0xFFE86A4A.toInt(),
)

@Composable
fun CompressorGraph(
    systemValues: FloatArray,
    mbcMeter: FloatArray?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val textPrimaryArgb = remember(context) { themeColor(context, android.R.attr.textColorPrimary) }

    // Paints, 1:1 with CompressorSurface's fields.
    val gridPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(58, 60, 66); style = Paint.Style.STROKE; strokeWidth = density
        }
    }
    val zeroPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 122, 130); style = Paint.Style.STROKE; strokeWidth = 1.3f * density
        }
    }
    val labelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(176, 178, 186); textSize = 9.5f * density
        }
    }
    val splitLinePaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 152, 160); style = Paint.Style.STROKE; strokeWidth = 1.2f * density
        }
    }
    val bandFillPaints = remember {
        BandTints.map { tint ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(tint, BAND_FILL_ALPHA); style = Paint.Style.FILL
            }
        }
    }
    val thresholdPaint = remember(density, textPrimaryArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimaryArgb; style = Paint.Style.STROKE; strokeWidth = 1.4f * density
        }
    }
    val readoutPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 202, 210); textSize = 10f * density; textAlign = Paint.Align.CENTER
        }
    }

    ComposeCanvas(modifier) {
        if (systemValues.size < NativeBmwDspValues.SIZE) return@ComposeCanvas
        val left = PAD_LEFT_DP * density
        val right = size.width - PAD_RIGHT_DP * density
        val top = PAD_TOP_DP * density
        val bottom = size.height - PAD_BOTTOM_DP * density
        if (right <= left || bottom <= top) return@ComposeCanvas
        val splits = CompressorSurfaceMath.splitFrequencies(systemValues)
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            drawBandRegions(nc, left, right, top, bottom, splits, bandFillPaints, splitLinePaint)
            drawGrid(nc, left, right, top, bottom, density, gridPaint, zeroPaint, labelPaint)
            drawThresholdLines(nc, systemValues, left, right, top, bottom, splits, thresholdPaint)
            drawBandReadouts(nc, systemValues, mbcMeter, left, right, top, density, splits, readoutPaint)
        }
    }
}

// --- drawing, 1:1 with CompressorSurface ---------------------------------------------------

private fun drawBandRegions(
    nc: Canvas,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    splits: DoubleArray,
    bandFillPaints: List<Paint>,
    splitLinePaint: Paint,
) {
    for (band in 0 until CompressorSurfaceMath.BAND_COUNT) {
        val (lowHz, highHz) = CompressorSurfaceMath.bandRange(band, splits)
        val x0 = xForFrequency(lowHz, left, right)
        val x1 = xForFrequency(highHz, left, right)
        nc.drawRect(x0, top, x1, bottom, bandFillPaints[band])
    }
    for (hz in splits) {
        val x = xForFrequency(hz, left, right)
        nc.drawLine(x, top, x, bottom, splitLinePaint)
    }
}

private fun drawGrid(
    nc: Canvas,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    density: Float,
    gridPaint: Paint,
    zeroPaint: Paint,
    labelPaint: Paint,
) {
    for (db in CompressorSurfaceMath.GRID_DB) {
        val y = yForDb(db, top, bottom)
        nc.drawLine(left, y, right, y, if (db == 0.0) zeroPaint else gridPaint)
        nc.drawText("${db.toInt()}", 3f * density, y + 3.2f * density, labelPaint)
    }
    for (hz in FreqScale) {
        val x = xForFrequency(hz, left, right)
        nc.drawLine(x, top, x, bottom, gridPaint)
        val label = hz.prettyNumberFormat()
        nc.drawText(label, x - labelPaint.measureText(label) / 2f, bottom + 14f * density, labelPaint)
    }
}

private fun drawThresholdLines(
    nc: Canvas,
    values: FloatArray,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    splits: DoubleArray,
    thresholdPaint: Paint,
) {
    for (band in 0 until CompressorSurfaceMath.BAND_COUNT) {
        val enabled = values[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_ENABLED)] >= .5f
        val thr = values[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_THRESHOLD)].toDouble()
        val (lowHz, highHz) = CompressorSurfaceMath.bandRange(band, splits)
        val x0 = xForFrequency(lowHz, left, right).coerceAtLeast(left)
        val x1 = xForFrequency(highHz, left, right).coerceAtMost(right)
        val y = yForDb(thr, top, bottom)
        thresholdPaint.alpha = if (enabled) 210 else 70
        nc.drawLine(x0, y, x1, y, thresholdPaint)
    }
}

private fun drawBandReadouts(
    nc: Canvas,
    values: FloatArray,
    mbcMeter: FloatArray?,
    left: Float,
    right: Float,
    top: Float,
    density: Float,
    splits: DoubleArray,
    readoutPaint: Paint,
) {
    val mbcOn = values[NativeBmwDspValues.INDEX_MBC_ENABLED] >= .5f
    for (band in 0 until CompressorSurfaceMath.BAND_COUNT) {
        val (lowHz, highHz) = CompressorSurfaceMath.bandRange(band, splits)
        val cx = (xForFrequency(lowHz, left, right) + xForFrequency(highHz, left, right)) / 2f
        val enabled = mbcOn &&
            values[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_ENABLED)] >= .5f
        val gr = mbcMeter?.getOrNull(band * 3 + 2) ?: 0f
        val text = if (enabled) "GR ${"%.1f".format(gr)} dB" else "—"
        nc.drawText(text, cx, top + 12f * density, readoutPaint)
    }
}

// --- coordinate mapping, 1:1 with CompressorSurface.xForFrequency / yForDb -----------------

private fun xForFrequency(hz: Double, left: Float, right: Float): Float =
    left + CompressorSurfaceMath.frequencyToFraction(hz) * (right - left)

private fun yForDb(db: Double, top: Float, bottom: Float): Float =
    top + CompressorSurfaceMath.dbToFraction(db) * (bottom - top)

/** = `CompressorSurface.themeColor` (resolves a `?android:attr` colour). */
private fun themeColor(context: Context, attribute: Int): Int {
    val typedValue = TypedValue()
    context.theme.resolveAttribute(attribute, typedValue, true)
    return if (typedValue.resourceId != 0) {
        ContextCompat.getColor(context, typedValue.resourceId)
    } else {
        typedValue.data
    }
}
