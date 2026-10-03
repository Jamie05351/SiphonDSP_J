package app.siphondsp.compose.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * One workspace sidebar tile, made as a small square version of the front page's signal-chain
 * card ([HomeChainCard]) so the two read as one set: dark glass with a tint of the module's
 * colour from the top-left corner, the coloured border running from [accent] to [accent2], the
 * module's [graphic] across the top and its short [label] under it.
 *
 * [selected] (the screen you are on) lights it: full colour, a stronger tint and a glow round the
 * card. The other tiles keep their colours, dimmed, so each page is still recognisable. Sizes are
 * fractions of the tile's width, as laid out in Figma "SiphonDSP Front Panel (from code)", page
 * "Sidebar".
 */
@Composable
fun RailChainCard(
    label: String,
    graphic: ImageVector,
    accent: Color,
    accent2: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    // What accessibility services announce in place of the short visible [label].
    a11yLabel: String? = null,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(80), label = "press")
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "lit")

    BoxWithConstraints(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind { drawRailCard(accent, accent2, lit) }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = a11yLabel ?: label,
                role = Role.Button,
                onClick = onClick,
            )
            .then(if (a11yLabel != null) Modifier.semantics { contentDescription = a11yLabel } else Modifier),
    ) {
        val w = maxWidth
        Image(
            imageVector = graphic,
            contentDescription = null,
            modifier = Modifier
                .offset(w * 0.08f, w * 0.09f)
                .size(w * 0.84f, w * 0.52f)
                .alpha(0.6f + 0.4f * lit),
        )

        // 13 % of the tile, shrunk (not below LabelMinSp) if the label would be wider than 90 % of it.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labelSize = remember(label, w, density) {
            with(density) {
                val base = (w * 0.13f).toSp().value
                val width = measurer.measure(label, TextStyle(fontSize = base.sp, fontWeight = FontWeight.Medium)).size.width
                val room = (w * 0.90f).toPx()
                (if (width > room) maxOf(base * room / width, LabelMinSp) else base).sp
            }
        }
        Text(
            text = label,
            color = lerp(DspColors.Label, Color.White, lit),
            fontSize = labelSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .offset(y = w * 0.69f)
                .fillMaxWidth()
                .wrapContentWidth()
                // With an a11yLabel the tile announces that instead, not both.
                .then(if (a11yLabel != null) Modifier.clearAndSetSemantics { } else Modifier),
        )
    }
}

private const val LabelMinSp = 9f

private fun DrawScope.drawRailCard(accent: Color, accent2: Color, lit: Float) {
    val w = size.width
    val radius = CornerRadius(w * 0.12f)

    // Selected glow: layered strokes outside the card, in the module's colour.
    if (lit > 0.01f) {
        for (i in 5 downTo 1) {
            val g = w * 0.018f * i
            drawRoundRect(
                color = accent.copy(alpha = 0.09f * lit * (6 - i)),
                topLeft = Offset(-g, -g),
                size = Size(w + g * 2, w + g * 2),
                cornerRadius = CornerRadius(w * 0.12f + g),
                style = Stroke(w * 0.03f),
            )
        }
    }

    // Dark glass, a tint of the module's colour from the top-left, less of the second colour from
    // the bottom right, and a soft sheen across the top.
    drawRoundRect(GlassBase, cornerRadius = radius)
    drawRoundRect(
        Brush.radialGradient(
            0f to accent.copy(alpha = 0.10f + 0.14f * lit),
            0.6f to accent.copy(alpha = 0.02f + 0.04f * lit),
            1f to Color.Transparent,
            center = Offset(w * 0.12f, w * 0.08f),
            radius = w * 1.4f,
        ),
        cornerRadius = radius,
    )
    drawRoundRect(
        Brush.radialGradient(
            0f to accent2.copy(alpha = 0.04f + 0.05f * lit),
            1f to Color.Transparent,
            center = Offset(w * 0.96f, w * 0.96f),
            radius = w,
        ),
        cornerRadius = radius,
    )
    drawRoundRect(
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.3f to Color.White.copy(alpha = 0.015f), 1f to Color.Transparent),
        cornerRadius = radius,
    )

    // The coloured border, dimmed while not selected.
    val border = w * 0.028f
    drawRoundRect(
        Brush.linearGradient(
            listOf(accent.copy(alpha = 0.42f + 0.58f * lit), accent2.copy(alpha = 0.38f + 0.52f * lit)),
            start = Offset.Zero,
            end = Offset(w, w),
        ),
        topLeft = Offset(border / 2f, border / 2f),
        size = Size(w - border, w - border),
        cornerRadius = CornerRadius(w * 0.12f - border / 2f),
        style = Stroke(border),
    )
}

private val GlassBase = Color(0xFF0B0B0E)
