package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import app.siphondsp.R
import app.siphondsp.view.LevelReadout

/**
 * The output meter panel: the L and R output levels on a −48..0 dBFS scale, each a bar to its RMS
 * level with a tick at its held peak and the held peak in dB at the right, the meters' ceiling as
 * a red line across both, and the headroom from the louder held peak up to that ceiling.
 *
 * [ceilingDb] is the limiter threshold while the limiter is on, else 0 dBFS. [globalActive] blends
 * the bars from the green-to-red level gradient to flat grey while the DSP is bypassed, when the
 * headroom reads "— bypassed". Purely visual: the numbers change several times a second, too
 * often to announce.
 */
@Composable
fun OutputMeter(
    readout: LevelReadout,
    ceilingDb: Float,
    globalActive: Float,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    val g = globalActive
    val powered = g >= 0.5f
    val measurer = rememberTextMeasurer()
    val ceilingLabel = stringResource(R.string.home_ceiling, formatDb(ceilingDb))
    Column(
        modifier
            .clearAndSetSemantics { }
            .padding(start = s.dp(26f), end = s.dp(24f), top = s.dp(14f), bottom = s.dp(12f)),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(if (powered) R.string.home_output else R.string.home_output_bypassed),
                color = HomePalette.Label,
                fontSize = s.sp(13f),
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(letterSpacing = 0.14.em),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Headroom(readout.headroomDb(ceilingDb), powered)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            drawMeters(measurer, readout, ceilingDb, ceilingLabel, g, s)
        }
    }
}

/** "HEADROOM 6.2 dB to ceiling", or "HEADROOM — bypassed" while the DSP is off. */
@Composable
private fun Headroom(headroomDb: Float?, powered: Boolean) {
    val s = LocalHomeScale.current
    Row(horizontalArrangement = Arrangement.spacedBy(s.dp(10f)), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.home_headroom),
            color = HomePalette.Label,
            fontSize = s.sp(13f),
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(letterSpacing = 0.14.em),
            maxLines = 1,
        )
        Text(
            text = when {
                !powered -> "—"
                headroomDb == null -> "--"
                else -> "${formatDb(headroomDb)} dB"
            },
            color = if (powered) Color.White else HomePalette.Dim,
            fontSize = s.sp(26f),
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        Text(
            text = stringResource(if (powered) R.string.home_to_ceiling else R.string.home_headroom_bypassed),
            color = HomePalette.Label,
            fontSize = s.sp(14f),
            maxLines = 1,
        )
    }
}

/**
 * Both bars, the ceiling line and the scale. Laid out in design units from the Figma panel: the
 * channel letter in a 42-unit gutter, the bars from there to 100 units short of the right edge,
 * where the held-peak readouts sit.
 */
private fun DrawScope.drawMeters(
    measurer: TextMeasurer,
    readout: LevelReadout,
    ceilingDb: Float,
    ceilingLabel: String,
    g: Float,
    s: HomeScale,
) {
    fun u(v: Float) = v * s.k * density
    val left = u(42f)
    val right = size.width - u(100f)
    val span = right - left
    fun x(db: Float) = left + span * ((db - FloorDb) / -FloorDb).coerceIn(0f, 1f)
    val barH = u(18f)
    val rows = listOf(
        Triple("L", readout.leftRmsDb, readout.leftPeakDb) to u(40f),
        Triple("R", readout.rightRmsDb, readout.rightPeakDb) to u(84f),
    )
    val level = Brush.horizontalGradient(
        0f to HomePalette.MeterGreen,
        0.78f to HomePalette.MeterAmber,
        1f to HomePalette.MeterRed,
        startX = left,
        endX = right,
    )
    for ((channel, top) in rows) {
        val (name, rmsDb, peakDb) = channel
        drawLabel(measurer, name, Offset(u(6f), top + barH / 2), s.sp(15f), HomePalette.Caption, FontWeight.SemiBold, centreY = true)
        val corner = CornerRadius(barH / 2)
        drawRoundRect(HomePalette.MeterTrack, Offset(left, top), Size(span, barH), corner)
        val fill = x(rmsDb) - left
        if (!readout.silent && fill > 0f) {
            drawRoundRect(HomePalette.Caption.copy(alpha = 0.45f * (1 - g)), Offset(left, top), Size(fill, barH), corner)
            drawRoundRect(level, Offset(left, top), Size(fill, barH), corner, alpha = g)
            // The held peak.
            val px = x(peakDb)
            drawRect(Color.White.copy(alpha = 0.6f + 0.4f * g), Offset(px - u(1.5f), top - u(4f)), Size(u(3f), barH + u(8f)))
        }
        val db = if (readout.silent) "--" else "${formatDb(peakDb)} dB"
        drawLabel(
            measurer, db, Offset(right + u(16f), top + barH / 2), s.sp(14f),
            lerp(HomePalette.Label, Color.White, g), FontWeight.Medium, centreY = true,
        )
    }
    // The ceiling, across both bars, with its label over the top one.
    val ceiling = HomePalette.Ceiling.copy(alpha = 0.4f + 0.6f * g)
    val cx = x(ceilingDb)
    drawRect(ceiling, Offset(cx - u(1f), u(26f)), Size(u(2f), u(96f)))
    val labelLayout = measurer.measure(ceilingLabel, TextStyle(fontSize = s.sp(11f), fontWeight = FontWeight.SemiBold))
    drawText(
        labelLayout,
        color = HomePalette.Ceiling.copy(alpha = 0.5f + 0.5f * g),
        topLeft = Offset((cx - labelLayout.size.width).coerceAtLeast(left), u(10f)),
    )
    // The scale under the bars.
    for (mark in ScaleMarks) {
        val text = if (mark == 0f) "0" else formatDb(mark).removeSuffix(".0")
        val layout = measurer.measure(text, TextStyle(fontSize = s.sp(11f)))
        drawText(layout, HomePalette.Dim, Offset(x(mark) - layout.size.width / 2f, u(130f)))
    }
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    size: TextUnit,
    color: Color,
    weight: FontWeight,
    centreY: Boolean,
) {
    val layout = measurer.measure(text, TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum"))
    drawText(layout, color, if (centreY) at - Offset(0f, layout.size.height / 2f) else at)
}

/** The meter's floor and its scale marks, in dBFS; the scale is linear, as in the Figma panel. */
private const val FloorDb = -48f
private val ScaleMarks = listOf(-48f, -36f, -24f, -12f, -6f, -3f, 0f)
