package app.siphondsp.compose.controls

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Compose port of `CrossoverDashboardBuilder.buildGlassSegmentGroup` -- a 2+ way glass segment
 * toggle: a dark rounded capsule shell ([GlassSegmentTrackDrawable]) with the selected segment
 * drawn as a glowing gradient pill ([GlassSegmentDrawable]). All-caps bold labels, white when
 * selected, muted grey otherwise. Values 1:1 with `BmwDashboardSkin.GLASS_SEGMENT_*`.
 *
 * [optionAccents], when given, tints each segment's selected fill/glow/border to its own colour
 * (the Polarity toggle uses green NORMAL / red INVERT); default is the neutral cyan.
 */
@Composable
fun BmwSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    optionAccents: List<Color>? = null,
    segmentHeight: Dp = 24.dp,
) {
    Row(
        modifier = modifier
            .drawBehind { drawSegmentTrack() }
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val accent = optionAccents?.getOrNull(i) ?: DefaultSelectedAccent
            Segment(
                label = label,
                selected = selected,
                accent = accent,
                height = segmentHeight,
                onClick = { if (!selected) onSelect(i) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowScope.Segment(
    label: String,
    selected: Boolean,
    accent: Color,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(height)
            .clickable(onClick = onClick)
            .drawBehind { if (selected) drawSelectedPill(accent) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = if (selected) Color.White else SegmentIdleText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 5.dp),
        )
    }
}

private fun DrawScope.drawSegmentTrack() {
    val borderPx = SegmentBorderWidth.toPx()
    val rect = inset(Rect(0f, 0f, size.width, size.height), borderPx / 2f)
    if (rect.width <= 0f || rect.height <= 0f) return
    val corner = CornerRadius(rect.height / 2f)
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(SegmentTrackFillNear, SegmentTrackFillFar),
            start = Offset(rect.left, rect.top),
            end = Offset(rect.right, rect.bottom),
        ),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = SegmentTrackRim,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = corner,
        style = Stroke(borderPx * 0.6f),
    )
    drawRoundRect(
        color = SegmentTrackBorder,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = corner,
        style = Stroke(borderPx),
    )
}

private fun DrawScope.drawSelectedPill(accent: Color) {
    val borderPx = SegmentBorderWidth.toPx()
    val glowPx = SegmentGlowWidth.toPx()
    val rect = inset(Rect(0f, 0f, size.width, size.height), borderPx / 2f)
    if (rect.width <= 0f || rect.height <= 0f) return
    val corner = CornerRadius(rect.height / 2f)
    val w = rect.width

    val fillNear = lerp(accent, Color.White, 0.25f)
    val fillFar = lerp(accent, Color.Black, 0.25f)
    val glow = accent.copy(alpha = SegmentGlowAlpha)

    // Blurred glow ring -- nativeCanvas (DrawScope can't blur a stroke).
    drawIntoCanvas { canvas ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glowPx
            maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
            color = glow.toArgb()
        }
        canvas.nativeCanvas.drawRoundRect(
            rect.left, rect.top, rect.right, rect.bottom, corner.x, corner.y, p,
        )
    }
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to fillNear, 0.18f to accent, 1f to fillFar,
            start = Offset(rect.left, rect.top),
            end = Offset(rect.right, rect.bottom),
        ),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = corner,
    )
    val clip = Path().apply { addRoundRect(RoundRect(rect, corner)) }
    clipPath(clip) {
        val sheen = Path().apply {
            moveTo(rect.left + w * 0.12f, rect.top)
            lineTo(rect.left + w * 0.42f, rect.top)
            lineTo(rect.left + w * 0.30f, rect.bottom)
            lineTo(rect.left, rect.bottom)
            close()
        }
        drawPath(
            sheen,
            brush = Brush.linearGradient(
                listOf(SegmentSheenNear, SegmentSheenFar),
                start = Offset(rect.left + w * 0.12f, rect.top),
                end = Offset(rect.left + w * 0.30f, rect.bottom),
            ),
        )
    }
    drawRoundRect(
        color = accent,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = corner,
        style = Stroke(borderPx),
    )
}

private fun inset(r: Rect, d: Float) = Rect(r.left + d, r.top + d, r.right - d, r.bottom - d)

private val SegmentBorderWidth = BmwDashboardSkin.GLASS_SEGMENT_BORDER_WIDTH_DP.dp
private val SegmentGlowWidth = BmwDashboardSkin.GLASS_SEGMENT_GLOW_WIDTH_DP.dp
private val SegmentGlowAlpha = android.graphics.Color.alpha(BmwDashboardSkin.GLASS_SEGMENT_GLOW_COLOR) / 255f
private val SegmentIdleText = Color(0xFFB4BCC5) // rgb(180,188,197)
private val DefaultSelectedAccent = Color(BmwDashboardSkin.GLASS_SEGMENT_BORDER_COLOR)
private val SegmentTrackFillNear = Color(BmwDashboardSkin.GLASS_SEGMENT_TRACK_FILL_NEAR)
private val SegmentTrackFillFar = Color(BmwDashboardSkin.GLASS_SEGMENT_TRACK_FILL_FAR)
private val SegmentTrackRim = Color(BmwDashboardSkin.GLASS_SEGMENT_TRACK_RIM_COLOR)
private val SegmentTrackBorder = Color(BmwDashboardSkin.GLASS_SEGMENT_TRACK_BORDER_COLOR)
private val SegmentSheenNear = Color(BmwDashboardSkin.GLASS_SEGMENT_SHEEN_NEAR)
private val SegmentSheenFar = Color(BmwDashboardSkin.GLASS_SEGMENT_SHEEN_FAR)
