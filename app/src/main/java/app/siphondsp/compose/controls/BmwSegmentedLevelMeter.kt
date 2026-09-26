package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin
import kotlin.math.ceil

/** Reusable black-glass level meter with individually recessed purple LED segments. */
@Composable
fun BmwSegmentedLevelMeter(
    levelDb: Float,
    modifier: Modifier = Modifier,
    peakDb: Float? = null,
    valueRange: ClosedFloatingPointRange<Float> = -60f..0f,
    accentColor: Color = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR),
    segments: Int = 28,
    accessibilityLabel: String? = null,
) {
    val safeSegments = segments.coerceAtLeast(1)
    val levelFraction = meterFractionFor(levelDb, valueRange)
    val peakFraction = peakDb?.let { meterFractionFor(it, valueRange) }?.takeIf { it > 0f }
    val activeSegments = activeMeterSegmentCount(levelFraction, safeSegments)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .semantics(mergeDescendants = true) {
                accessibilityLabel?.let { contentDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = levelDb.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange,
                )
            },
    ) {
        val outer = Rect(0f, 0f, size.width, size.height)
        val outerRadius = size.height * 0.30f
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.70f),
            topLeft = Offset(0f, size.height * 0.10f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.90f),
            cornerRadius = CornerRadius(outerRadius),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF4A4D57), Color(0xFF111319), Color(0xFF050609)),
            ),
            topLeft = outer.topLeft,
            size = outer.size,
            cornerRadius = CornerRadius(outerRadius),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = outer.topLeft,
            size = outer.size,
            cornerRadius = CornerRadius(outerRadius),
            style = Stroke(width = 1.dp.toPx()),
        )

        val inset = 3.dp.toPx()
        val gap = 1.7.dp.toPx()
        val available = (size.width - inset * 2f).coerceAtLeast(1f)
        val segmentWidth = ((available - gap * (safeSegments - 1)) / safeSegments).coerceAtLeast(1f)
        val top = inset
        val bottom = size.height - inset
        val segmentRadius = 1.2.dp.toPx()
        repeat(safeSegments) { index ->
            val left = inset + index * (segmentWidth + gap)
            val rect = Rect(left, top, left + segmentWidth, bottom)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF292B33), Color(0xFF08090D))),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(segmentRadius),
            )
            if (index < activeSegments) {
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.20f),
                    topLeft = Offset(rect.left - gap * 0.45f, rect.top - gap * 0.55f),
                    size = androidx.compose.ui.geometry.Size(rect.width + gap * 0.9f, rect.height + gap * 1.1f),
                    cornerRadius = CornerRadius(segmentRadius + gap),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            androidx.compose.ui.graphics.lerp(accentColor, Color.White, 0.72f),
                            accentColor,
                            androidx.compose.ui.graphics.lerp(accentColor, Color.Black, 0.40f),
                        ),
                    ),
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(segmentRadius),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.52f),
                    start = Offset(rect.left + segmentWidth * 0.18f, rect.top + 1.dp.toPx()),
                    end = Offset(rect.right - segmentWidth * 0.18f, rect.top + 1.dp.toPx()),
                    strokeWidth = 0.7.dp.toPx(),
                )
            }
        }

        peakFraction?.let { fraction ->
            val peakIndex = (ceil(fraction * safeSegments).toInt() - 1).coerceIn(0, safeSegments - 1)
            val left = inset + peakIndex * (segmentWidth + gap)
            val rect = Rect(left, top, left + segmentWidth, bottom)
            drawRoundRect(
                color = androidx.compose.ui.graphics.lerp(accentColor, Color.White, 0.82f),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(segmentRadius),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }
}

internal fun meterFractionFor(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
): Float {
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f) return 0f
    return ((value - valueRange.start) / span).coerceIn(0f, 1f)
}

internal fun activeMeterSegmentCount(fraction: Float, segments: Int): Int {
    if (fraction <= 0f || segments <= 0) return 0
    return ceil(fraction.coerceIn(0f, 1f) * segments).toInt().coerceAtMost(segments)
}
