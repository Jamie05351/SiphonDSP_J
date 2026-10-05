package app.siphondsp.compose.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import app.siphondsp.compose.theme.BmwTheme
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight

/**
 * One signal-chain tile: a menu button that opens its DSP screen. The tile's fill and artwork
 * always show in its [accent], whatever the stage or the DSP power is doing. It is [CardWidth]
 * wide and as tall as the chain panel allows.
 *
 * Its border is the signal passing through: grey while dark, lit in [accent] by [borderLit]
 * (0..1, from the power-on sweep, see [ChainSweep]) starting where the chain's line comes in at
 * the left-middle edge, splitting both ways round the tile, and meeting at the right-middle edge
 * where the line carries on, with a spark in the power button's purple at the front of each side
 * while [spark] (powering on, not off). Read while drawing, so the sweep redraws without
 * recomposing.
 *
 * [selected] brightens the glow; the front page sets it for a moment when the tile is tapped, just
 * before its screen zooms open.
 */
@Composable
fun StageCard(
    title: String,
    subtitle: String,
    accent: Color,
    art: ImageVector,
    borderLit: () -> Float,
    spark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(16f))
    val glow = animateActive(selected, "tile glow $title")
    val signal = BmwTheme.colors.sliderHeadroom
    val px = s.k * LocalDensity.current.density
    Column(
        modifier
            .width(s.dp(CardWidth))
            .fillMaxHeight()
            .drawBehind {
                // A soft halo just outside the tile once its border is lit, stronger while it is
                // opening.
                val spread = s.dp(14f).toPx()
                val radius = s.dp(16f).toPx()
                val halo = 0.10f * borderLit() + 0.25f * glow
                for (step in 1..3) {
                    val g = spread * step / 3f
                    drawRoundRect(
                        accent.copy(alpha = halo / step),
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(radius + g),
                    )
                }
            }
            // Outside the clip, so the spark's glow isn't cut off at the tile's edge.
            .sweptBorder(s.dp(2.5f), s.dp(16f), accent, borderLit, if (spark) signal else null, px)
            .clip(shape)
            .background(HomePalette.TileBase)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f + 0.15f * glow), Color.Transparent)))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(s.dp(12f)),
    ) {
        Spacer(Modifier.weight(1f))
        Image(
            imageVector = art,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(s.dp(80f)),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = title,
            color = Color.White,
            fontSize = s.sp(22f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = s.dp(4f)),
        )
        Text(
            text = subtitle,
            color = HomePalette.Subtitle,
            fontSize = s.sp(15f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = s.dp(4f), top = s.dp(8f), bottom = s.dp(6f)),
        )
    }
}

/**
 * A rounded border in [HomePalette.Idle], lit in [colour] along two paths that both start at the
 * left-middle edge, one round the top and one round the bottom, each [lit] (0..1) of the way to
 * the right-middle edge. With a [spark] colour, a spark ([drawSpark], [scale] px per design unit)
 * rides the front of each path until they meet.
 */
private fun Modifier.sweptBorder(
    width: Dp,
    radius: Dp,
    colour: Color,
    lit: () -> Float,
    spark: Color?,
    scale: Float,
) = drawWithCache {
    val w = width.toPx()
    val h = w / 2
    val r = radius.toPx() - h
    val left = h
    val top = h
    val right = size.width - h
    val bottom = size.height - h
    val cy = size.height / 2
    val upper = Path().apply {
        moveTo(left, cy)
        lineTo(left, top + r)
        arcTo(Rect(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
        lineTo(right - r, top)
        arcTo(Rect(right - 2 * r, top, right, top + 2 * r), 270f, 90f, false)
        lineTo(right, cy)
    }
    val lower = Path().apply {
        moveTo(left, cy)
        lineTo(left, bottom - r)
        arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 180f, -90f, false)
        lineTo(right - r, bottom)
        arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 90f, -90f, false)
        lineTo(right, cy)
    }
    val measure = PathMeasure()
    measure.setPath(upper, false)
    val half = measure.length
    val stroke = Stroke(w, cap = StrokeCap.Round)
    val litUpper = Path()
    val litLower = Path()
    onDrawWithContent {
        drawContent()
        drawPath(upper, HomePalette.Idle, style = Stroke(w))
        drawPath(lower, HomePalette.Idle, style = Stroke(w))
        val t = lit()
        if (t > 0f) {
            litUpper.reset()
            litLower.reset()
            measure.setPath(upper, false)
            measure.getSegment(0f, half * t, litUpper, true)
            measure.setPath(lower, false)
            measure.getSegment(0f, half * t, litLower, true)
            drawPath(litUpper, colour, style = stroke)
            drawPath(litLower, colour, style = stroke)
            if (spark != null && t < 1f) {
                measure.setPath(upper, false)
                drawSpark(measure.getPosition(half * t), spark, scale)
                measure.setPath(lower, false)
                drawSpark(measure.getPosition(half * t), spark, scale)
            }
        }
    }
}

/** A tile's width in design units. */
internal const val CardWidth = 170f
