package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import app.siphondsp.view.LevelHistory
import app.siphondsp.view.LevelReadout
import app.siphondsp.view.PeakHoldMeter
import java.util.Locale

/**
 * The front page's live output, at the end of the signal chain (Figma "SiphonDSP Faceplate v3",
 * component "Output Scope"): the chain's line splits into an L rail and an R rail, then a scope of
 * the last few seconds of post-DSP output, then each channel's held peak.
 *
 * In the scope each frame of [history] is one column, newest at the right edge (the bright "now"
 * line) and fading as it scrolls left: L RMS grows up from the centre line and R RMS grows down,
 * with a light cap at that frame's peak (red at full scale). The readouts are [readout]'s held
 * peaks: "--" in silence, red with a CLIP chip at or above [ClipDb].
 *
 * [tick] changes on every pushed frame; it is read only while drawing, so a new frame redraws the
 * scope without recomposing anything. Purely visual: the numbers change too often to announce.
 */
@Composable
fun HomeOutputScope(
    history: LevelHistory,
    tick: () -> Int,
    readout: LevelReadout,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        tick()
        // Laid out in the Figma component's 400x330 units, fitted to the box, flush left (the
        // chain's line comes in at the left edge, level with the centre) and centred vertically.
        val k = minOf(size.width / DesignW, size.height / DesignH)
        val oy = (size.height - DesignH * k) / 2f
        fun p(x: Float, y: Float) = Offset(x * k, oy + y * k)

        drawRails(measurer, k, ::p)
        drawHistory(history, k, ::p)
        drawReadout(measurer, "L", readout.leftPeakDb, readout.silent, ReadoutTopY, k, ::p)
        drawReadout(measurer, "R", readout.rightPeakDb, readout.silent, ReadoutBottomY, k, ::p)
    }
}

/** The split from the chain's line to the L and R rails, and the L / R badges. */
private fun DrawScope.drawRails(measurer: TextMeasurer, k: Float, p: (Float, Float) -> Offset) {
    val rails = Path().apply {
        for (sign in listOf(-1f, 1f)) {
            val end = CentreY + sign * RailSpread
            moveTo(p(0f, CentreY).x, p(0f, CentreY).y)
            val c1 = p(20f, CentreY)
            val c2 = p(20f, end)
            val to = p(40f, end)
            cubicTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y)
            val tail = p(60f, end)
            lineTo(tail.x, tail.y)
        }
    }
    drawPath(rails, ChainPurple.copy(alpha = 0.25f), style = Stroke(9f * k, cap = StrokeCap.Round))
    drawPath(rails, ChainPurple, style = Stroke(3f * k, cap = StrokeCap.Round))

    for ((name, sign) in listOf("L" to -1f, "R" to 1f)) {
        val c = p(BadgeX, CentreY + sign * RailSpread)
        drawCircle(BadgeFill, 17f * k, c)
        drawCircle(Color.White.copy(alpha = 0.25f), 17f * k, c, style = Stroke(1.5f * k))
        val letter = measurer.measure(
            name,
            TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = (16f * k).toSp(), fontWeight = FontWeight.Medium),
        )
        drawText(letter, topLeft = Offset(c.x - letter.size.width / 2f, c.y - letter.size.height / 2f))
    }
}

private fun DrawScope.drawHistory(history: LevelHistory, k: Float, p: (Float, Float) -> Offset) {
    val centre = p(ScopeX, ScopeY + ScopeH / 2f)
    drawLine(Color.White.copy(alpha = 0.15f), centre, Offset(p(NowX, 0f).x, centre.y), k)

    val columns = history.capacity
    val pitch = (NowX - ScopeX - 4f) / columns
    val barW = pitch * 0.52f
    val corner = CornerRadius(barW / 2f * k)
    for (age in 0 until columns) {
        val i = columns - 1 - age
        val x = p(ScopeX + i * pitch, 0f).x
        val t = i / columns.toFloat()
        val fade = 0.25f + 0.75f * t
        val colour = lerp(ScopeOld, ScopeNew, t).copy(alpha = fade)
        lane(history.leftRmsDb(age), history.leftPeakDb(age), x, centre.y, -1f, barW * k, k, colour, fade, corner)
        lane(history.rightRmsDb(age), history.rightPeakDb(age), x, centre.y, 1f, barW * k, k, colour, fade, corner)
    }

    // The "now" line, where each new frame comes in.
    val top = p(NowX, ScopeY)
    val bottom = p(NowX, ScopeY + ScopeH)
    drawLine(ScopeNew.copy(alpha = 0.35f), top, bottom, 8f * k, StrokeCap.Round)
    drawLine(PeakCap, top, bottom, 2f * k, StrokeCap.Round)
}

/** One channel's column: RMS bar from the centre line ([dir] -1 up, +1 down) and a peak cap. */
private fun DrawScope.lane(
    rmsDb: Float,
    peakDb: Float,
    x: Float,
    centreY: Float,
    dir: Float,
    barW: Float,
    k: Float,
    colour: Color,
    fade: Float,
    corner: CornerRadius,
) {
    val rms = PeakHoldMeter.fractionFor(rmsDb, LevelReadout.FLOOR_DB, 0f)
    val peak = PeakHoldMeter.fractionFor(peakDb, LevelReadout.FLOOR_DB, 0f)
    val h = maxOf(2f, rms * RmsReach) * k
    val top = if (dir < 0f) centreY - h else centreY + k
    drawRoundRect(colour, Offset(x, top), Size(barW, h), corner)
    if (peak > 0f) {
        val reach = maxOf(rms * RmsReach + 2f, peak * PeakReach) * k
        val capY = if (dir < 0f) centreY - reach - k else centreY + reach - k
        val cap = if (peakDb >= ClipDb) ClipRed else PeakCap
        drawRect(cap.copy(alpha = fade), Offset(x, capY), Size(barW, 2f * k))
    }
}

/** A channel's held peak: "−15.0 dB" with a PEAK chip under the unit, or red with CLIP. */
private fun DrawScope.drawReadout(
    measurer: TextMeasurer,
    channel: String,
    peakDb: Float,
    silent: Boolean,
    y: Float,
    k: Float,
    p: (Float, Float) -> Offset,
) {
    val clip = !silent && peakDb >= ClipDb
    val text = if (silent || peakDb <= LevelReadout.FLOOR_DB) {
        "--"
    } else {
        String.format(Locale.ROOT, "%.1f", peakDb.coerceAtMost(0f)).replace('-', '−').let { if (it == "−0.0") "0.0" else it }
    }
    val valueColour = when {
        clip -> ClipText
        silent -> Color.White.copy(alpha = 0.45f)
        else -> Color.White
    }
    val value = measurer.measure(
        text,
        TextStyle(color = valueColour, fontSize = (40f * k).toSp(), fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
    )
    val at = p(ReadoutX, y)
    drawText(value, topLeft = at)
    val unit = measurer.measure("dB", TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = (20f * k).toSp()))
    val unitX = at.x + value.size.width + 6f * k
    drawText(unit, topLeft = Offset(unitX, at.y + 16f * k))

    val label = measurer.measure(
        (if (clip) "CLIP " else "PEAK ") + channel,
        TextStyle(
            color = if (clip) Color.White else ChipText,
            fontSize = (12f * k).toSp(),
            fontWeight = FontWeight.Bold,
        ),
    )
    val padX = 8f * k
    val padY = 2f * k
    val chipAt = Offset(unitX, at.y + 44f * k)
    val chipSize = Size(label.size.width + padX * 2f, label.size.height + padY * 2f)
    val chipFill = when {
        clip -> DspColors.Comp
        silent -> Color.White.copy(alpha = 0.3f)
        else -> Color.White.copy(alpha = 0.9f)
    }
    drawRoundRect(chipFill, chipAt, chipSize, CornerRadius(chipSize.height / 2f))
    drawText(label, topLeft = Offset(chipAt.x + padX, chipAt.y + padY))
}

// The Figma component's frame and the positions in it.
private const val DesignW = 400f
private const val DesignH = 330f
private const val CentreY = 165f
private const val RailSpread = 101f
private const val ScopeX = 92f
private const val ScopeY = 85f
private const val ScopeH = 160f
private const val NowX = 372f
private const val BadgeX = 61f
private const val RmsReach = 72f
private const val PeakReach = 76f
private const val ReadoutX = 222f
private const val ReadoutTopY = 5f
private const val ReadoutBottomY = 260f

/** A held peak at or above this reads as clipping. */
private const val ClipDb = -0.1f

private val ChainPurple = Color(0xFFB14DFF)
private val ScopeOld = Color(0xFFB04DFF)
private val ScopeNew = Color(0xFFF040CC)
private val PeakCap = Color(0xFFEDD8FF)
private val ClipRed = Color(0xFFEA1A26)
private val ClipText = Color(0xFFFF5A64)
private val ChipText = Color(0xFF111114)
private val BadgeFill = Color(0xFF141418)
