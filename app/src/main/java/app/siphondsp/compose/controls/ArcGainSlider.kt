package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

private val Fmt = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

// Canvas.drawArc convention: 0deg = 3 o'clock, positive sweep = clockwise. The track covers 2/3
// of the circle (240deg) with a 120deg gap centred at the bottom (90deg) for the value readout --
// start = 90 + 60, running clockwise through the top back down to 90 - 60.
private const val ArcStartDeg = 150f
private const val ArcSweepDeg = 240f
private const val ArcCenterDeg = ArcStartDeg + ArcSweepDeg / 2f // 270deg -- straight up

/**
 * Gain control drawn as a partial ring wrapped around a speaker in the Gains & Delay car diagram
 * -- replaces the linear GAIN row that used to live inside [BmwChannelCard]'s box. No label: the
 * speaker it wraps already says which channel it is.
 *
 * [range] is expected to be symmetric around 0 (e.g. -3..3 dB): 0 sits at the arc's exact
 * midpoint (straight up), with the bright fill growing from there toward whichever side the drag
 * goes, dim background track showing the rest. The current value is plain text centred in the
 * gap at the bottom, tap it to type an exact number. Drag anywhere over the control (not just the
 * ring itself) to set the angle; the value follows the touch continuously and commits on release,
 * same preview/commit split every other slider in this app uses.
 */
@Composable
fun ArcGainSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    accent: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var drag by remember(value) { mutableFloatStateOf(value) }
    val shown = drag.coerceIn(range.start, range.endInclusive)
    val trackColor = accent.copy(alpha = 0.22f)

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(range, step) {
                    fun updateFromTouch(offset: Offset) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val angle = angleOf(offset, center)
                        val fraction = fractionForAngle(angle)
                        val raw = range.start + fraction * (range.endInclusive - range.start)
                        drag = snapToStep(raw, range, step)
                        onPreview(drag)
                    }
                    detectDragGestures(
                        onDragStart = { updateFromTouch(it) },
                        onDrag = { change, _ -> updateFromTouch(change.position) },
                        onDragEnd = { onCommit(drag) },
                        onDragCancel = { onCommit(drag) },
                    )
                },
        ) {
            val strokePx = ArcStrokeDp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = ArcStartDeg,
                sweepAngle = ArcSweepDeg,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            val valueAngle = ArcStartDeg + fractionForValue(shown, range) * ArcSweepDeg
            val fillStart = minOf(ArcCenterDeg, valueAngle)
            val fillSweep = abs(valueAngle - ArcCenterDeg)
            if (fillSweep > 0f) {
                drawArc(
                    color = accent,
                    startAngle = fillStart,
                    sweepAngle = fillSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }

        Text(
            text = formatDb(shown),
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(bottom = ValueLabelBottomPadding)
                .clickable {
                    context.showBmwNumberInput("GAIN", range.start, range.endInclusive, drag, step, "dB") {
                        drag = it
                        onCommit(it)
                    }
                },
        )
    }
}

private fun angleOf(touch: Offset, center: Offset): Float {
    val degrees = Math.toDegrees(atan2((touch.y - center.y).toDouble(), (touch.x - center.x).toDouble())).toFloat()
    return (degrees + 360f) % 360f
}

/** Touches inside the bottom gap snap to whichever end of the arc they're angularly closer to. */
private fun fractionForAngle(angleDeg: Float): Float {
    val rel = ((angleDeg - ArcStartDeg) % 360f + 360f) % 360f
    return when {
        rel <= ArcSweepDeg -> rel / ArcSweepDeg
        rel <= ArcSweepDeg + (360f - ArcSweepDeg) / 2f -> 1f
        else -> 0f
    }
}

private fun fractionForValue(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

private fun snapToStep(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
    val snapped = range.start + ((raw - range.start) / step).roundToInt() * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

private fun formatDb(value: Float): String {
    val text = Fmt.format(value)
    return if (value > 0f) "+$text" else text
}

private val ArcStrokeDp = 5.dp
private val ValueLabelBottomPadding = 2.dp
