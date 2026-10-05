package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import app.siphondsp.R
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.view.LevelReadout
import kotlin.math.floor

/**
 * The top screen's output section: the headroom from the louder held peak up to the ceiling, in
 * large type with a status dot (green with 3 dB or more to spare, amber under 3, red under 1),
 * then the L and R output levels as thin segmented LED rows on a −48..0 dBFS scale.
 *
 * Each LED row lights in the signal purple up to the channel's RMS level, magenta in the top
 * 6 dB, with one near-white segment at the held peak and the held peak in dB at the right; unlit
 * segments stay faintly visible so the scale always reads. [ceilingDb] (the limiter threshold
 * while the limiter is on, else 0 dBFS) is a red line across both rows.
 *
 * [globalActive] blends everything to grey while the DSP is bypassed, when the headroom reads
 * "—". Purely visual: the numbers change several times a second, too often to announce.
 */
@Composable
fun OutputMeter(
    readout: LevelReadout,
    ceilingDb: Float,
    globalActive: Float,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val measurer = rememberTextMeasurer()
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Headroom(readout.headroomDb(ceilingDb), ceilingDb, globalActive, Modifier.width(s.dp(HeadroomWidth)))
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            drawMeters(measurer, readout, ceilingDb, globalActive, signal, s)
        }
    }
}

/** "HEADROOM / 6.2 dB / ● to −1.0 dB ceiling", or "— / ○ DSP bypassed" while the DSP is off. */
@Composable
private fun Headroom(headroomDb: Float?, ceilingDb: Float, g: Float, modifier: Modifier) {
    val s = LocalHomeScale.current
    val powered = g >= 0.5f
    Column(modifier) {
        Text(
            text = stringResource(R.string.home_headroom),
            color = HomePalette.Caption,
            fontSize = s.sp(14f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = when {
                    !powered -> "—"
                    headroomDb == null -> "--"
                    else -> formatDb(headroomDb)
                },
                color = lerp(HomePalette.Dim, Color.White, g),
                fontSize = s.sp(64f),
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            if (powered && headroomDb != null) {
                Text(
                    text = "dB",
                    color = Color.White,
                    fontSize = s.sp(24f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline().padding(start = s.dp(6f)),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(8f))) {
            StatusDot(headroomColour(headroomDb), if (headroomDb == null) 0f else g, s.dp(12f))
            Text(
                text = if (powered) {
                    stringResource(R.string.home_to_ceiling, formatDb(ceilingDb))
                } else {
                    stringResource(R.string.home_dsp_bypassed)
                },
                color = HomePalette.Caption,
                fontSize = s.sp(15f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Green with 3 dB or more to spare, amber under 3, red under 1. */
private fun headroomColour(db: Float?): Color = when {
    db == null || db >= 3f -> HomePalette.HeadroomGood
    db >= 1f -> HomePalette.HeadroomLow
    else -> HomePalette.HeadroomCritical
}

/**
 * Both LED rows, the ceiling line and the scale, in design units: the channel letter in a 24-unit
 * gutter, the rows from there to [ReadoutWidth] short of the right edge, where the held-peak
 * readouts sit. The block is centred vertically in the canvas.
 */
private fun DrawScope.drawMeters(
    measurer: TextMeasurer,
    readout: LevelReadout,
    ceilingDb: Float,
    g: Float,
    signal: Color,
    s: HomeScale,
) {
    fun u(v: Float) = v * s.k * density
    val oy = (size.height - u(BlockHeight)) / 2f
    val left = u(24f)
    val right = size.width - u(ReadoutWidth)
    val span = right - left
    fun x(db: Float) = left + span * ((db - FloorDb) / -FloorDb).coerceIn(0f, 1f)

    val segW = u(4f)
    val pitch = u(6.5f)
    val count = floor((span + pitch - segW) / pitch).toInt().coerceAtLeast(1)
    val segH = u(10f)
    val corner = CornerRadius(u(1.5f))
    val idle = HomePalette.Caption
    val lit = lerp(idle.copy(alpha = 0.55f), signal, g)
    val hot = lerp(idle.copy(alpha = 0.55f), HomePalette.MeterHot, g)
    val unlit = lerp(idle.copy(alpha = 0.10f), signal.copy(alpha = 0.14f), g)
    val peakColour = lerp(Color.White.copy(alpha = 0.6f), HomePalette.MeterPeak, g)
    // A segment's fraction of the scale; the top 6 dB are "hot".
    fun segFraction(i: Int) = (i + 1f) / count
    val hotStart = 1f - 6f / -FloorDb

    for ((index, row) in listOf(
        Triple("L", readout.leftRmsDb, readout.leftPeakDb),
        Triple("R", readout.rightRmsDb, readout.rightPeakDb),
    ).withIndex()) {
        val (name, rmsDb, peakDb) = row
        val top = oy + u(if (index == 0) RowL else RowR)
        val mid = top + segH / 2
        drawLabel(measurer, name, Offset(0f, mid), s.sp(20f), HomePalette.Caption, FontWeight.Bold)
        val litTo = if (readout.silent) 0f else (rmsDb - FloorDb) / -FloorDb
        val peakAt = if (readout.silent) -1 else ((peakDb - FloorDb) / -FloorDb * count).toInt().coerceIn(0, count - 1)
        // A soft glow under the lit run while powered.
        val litCount = (litTo * count).toInt().coerceIn(0, count)
        if (litCount > 0 && g > 0f) {
            for (step in 1..2) {
                val grow = u(2.5f) * step
                drawRoundRect(
                    signal.copy(alpha = 0.12f * g / step),
                    Offset(left - grow, top - grow),
                    Size(pitch * (litCount - 1) + segW + 2 * grow, segH + 2 * grow),
                    CornerRadius(u(4f) + grow),
                )
            }
        }
        for (i in 0 until count) {
            val colour = when {
                i < litCount -> if (segFraction(i) > hotStart) hot else lit
                i == peakAt -> peakColour
                else -> unlit
            }
            drawRoundRect(colour, Offset(left + i * pitch, top), Size(segW, segH), corner)
        }
        drawLabel(
            measurer,
            if (readout.silent) "--" else formatDb(peakDb),
            Offset(right + u(16f), mid),
            s.sp(22f),
            lerp(HomePalette.Label, Color.White, g),
            FontWeight.Bold,
        )
    }
    // The ceiling, across both rows.
    val cx = x(ceilingDb)
    drawRect(HomePalette.Ceiling.copy(alpha = 0.4f + 0.6f * g), Offset(cx - u(1.5f), oy), Size(u(3f), u(CeilingHeight)))
    // The scale under the rows.
    for (mark in ScaleMarks) {
        val text = if (mark == 0f) "0" else formatDb(mark).removeSuffix(".0")
        val layout = measurer.measure(text, TextStyle(fontSize = s.sp(13f)))
        drawText(layout, HomePalette.Muted, Offset(x(mark) - layout.size.width / 2f, oy + u(ScaleTop)))
    }
}

/** Text vertically centred on [at]'s y. */
private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    size: TextUnit,
    color: Color,
    weight: FontWeight,
) {
    val layout = measurer.measure(text, TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum"))
    drawText(layout, color, at - Offset(0f, layout.size.height / 2f))
}

// The meter block in design units (Figma "v3"): the ceiling line spans the block's top to the
// scale; the L and R rows sit inside it, the scale labels under them.
private const val FloorDb = -48f
private val ScaleMarks = listOf(-48f, -24f, -12f, -6f, 0f)
private const val HeadroomWidth = 204f
private const val ReadoutWidth = 90f
private const val RowL = 16f
private const val RowR = 62f
private const val CeilingHeight = 92f
private const val ScaleTop = 96f
private const val BlockHeight = 112f
