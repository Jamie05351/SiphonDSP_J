package app.siphondsp.compose.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.audio.MeasurementSpectrumAnalyzer
import app.siphondsp.compose.controls.CarUi
import app.siphondsp.compose.settings.AppScreen
import app.siphondsp.compose.settings.InfoCard
import app.siphondsp.compose.settings.MStripe
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.view.PeqGraphMath
import kotlin.math.max
import kotlin.math.min

/** What the measurement screen shows; the activity owns the capture. */
data class MeasurementUi(
    val status: String,
    val readout: String? = null,
    val spectrum: MeasurementSpectrumAnalyzer.Result? = null,
    val captureLabel: String,
    val captureEnabled: Boolean = true,
    val exportEnabled: Boolean = false,
)

@Composable
fun MeasurementCaptureScreen(ui: MeasurementUi, onCapture: () -> Unit, onExport: () -> Unit, onBack: () -> Unit) {
    AppScreen(title = stringResource(R.string.title_activity_measurement_capture), onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.measurement_explainer),
                    color = Color(0xFFC6C5CE),
                    fontSize = CarUi.MinDenseText,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                InfoCard(title = ui.status, body = ui.readout)
                ui.spectrum?.let {
                    MeasurementResponseChart(
                        it,
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .padding(horizontal = 16.dp),
                    )
                }
            }
            MStripe()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onCapture, enabled = ui.captureEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(ui.captureLabel)
                }
                OutlinedButton(onClick = onExport, enabled = ui.exportEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.measurement_export))
                }
            }
        }
    }
}

/**
 * Raw input vs output, dB over log frequency, for a finished capture. The dB axis fits the data
 * with 6 dB of margin either side.
 */
@Composable
fun MeasurementResponseChart(result: MeasurementSpectrumAnalyzer.Result, modifier: Modifier = Modifier) {
    val outColour = BmwTheme.colors.lightBlue
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        var minDb = Float.MAX_VALUE
        var maxDb = -Float.MAX_VALUE
        for (series in arrayOf(result.rawInDb, result.outDb)) {
            for (v in series) if (v > FLOOR_DB) {
                minDb = min(minDb, v)
                maxDb = max(maxDb, v)
            }
        }
        if (minDb > maxDb) {
            minDb = FLOOR_DB
            maxDb = 0f
        }
        minDb -= 6f
        maxDb += 6f
        val span = (maxDb - minDb).coerceAtLeast(1f)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.White.copy(alpha = 0.63f).toArgb()
            textSize = 10.dp.toPx()
        }
        for (freq in FREQ_TICKS) {
            val x = PeqGraphMath.frequencyToFraction(freq.toDouble()) * w
            drawLine(Color.White.copy(alpha = 0.24f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
            val label = if (freq >= 1000) "${freq / 1000}k" else "$freq"
            drawContext.canvas.nativeCanvas.drawText(label, x + 2.dp.toPx(), h - 2.dp.toPx(), labelPaint)
        }

        fun path(db: FloatArray) = Path().apply {
            for (bin in db.indices) {
                val freq = bin.toDouble() * result.sampleRate / result.fftSize
                val x = PeqGraphMath.frequencyToFraction(freq) * w
                val y = h - ((db[bin] - minDb) / span).coerceIn(0f, 1f) * h
                if (bin == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val stroke = Stroke(width = 1.5.dp.toPx())
        drawPath(path(result.rawInDb), Color(0xDCAAB2BA), style = stroke)
        drawPath(path(result.outDb), outColour, style = stroke)
    }
}

private const val FLOOR_DB = -150f
private val FREQ_TICKS = intArrayOf(20, 100, 1000, 10000, 20000)
