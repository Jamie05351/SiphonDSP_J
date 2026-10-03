package app.siphondsp.compose.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One front-page signal-chain card (Figma "SiphonDSP Faceplate v3", component "Chain Card"): dark
 * glass with a faint tint of the module's colour from the top-left corner, the tiles' coloured
 * border running from [accent] to [accent2], a settings mark, the module's glyph large across the
 * middle, then [title] and [subtitle].
 *
 * Purely visual, like [HomeTile]: the host ComposeView owns the click, the ripple and the
 * accessibility label. [selected] lights the outer glow for the moment between a tap and its
 * screen opening. Everything is a fraction of the card's size, from the Figma card's 270x330.
 */
@Composable
fun HomeChainCard(
    title: String,
    subtitle: String,
    accent: Color,
    accent2: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    glyph: @Composable () -> Unit,
) {
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "glow")
    BoxWithConstraints(modifier.fillMaxSize().drawBehind { drawCard(accent, accent2, glow) }) {
        val w = maxWidth
        val h = maxHeight
        Box(
            Modifier
                .offset(w * (GlyphX / CardW), h * (GlyphY / CardH))
                .size(w * (GlyphW / CardW), h * (GlyphH / CardH)),
        ) { glyph() }
        CardText(title, TitleSize, TitleMinSp, Color.White, FontWeight.Medium, w, h, TitleY)
        CardText(subtitle, SubtitleSize, SubtitleMinSp, SubtitleColour, FontWeight.Normal, w, h, SubtitleY)
    }
}

/**
 * One line of card text at its Figma size ([designSize] in the 330-tall card, at [designY]),
 * shrunk to fit between the card's text margins if it would be wider (a long translation, a small
 * phone), never below [minSp]; past that it ends in an ellipsis rather than being cut off.
 */
@Composable
private fun CardText(
    text: String,
    designSize: Float,
    minSp: Float,
    colour: Color,
    weight: FontWeight,
    w: Dp,
    h: Dp,
    designY: Float,
) {
    val margin = w * (TextX / CardW)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Measured in the exact style the Text draws with, or the measurement comes up short.
    val baseStyle = LocalTextStyle.current.merge(TextStyle(fontWeight = weight))
    val size = remember(text, w, h, density, baseStyle) {
        val designSp = with(density) { (h * (designSize / CardH)).toSp().value }
        val room = with(density) { (w - margin * 2).toPx() } * 0.97f
        val width = measurer.measure(text, baseStyle.copy(fontSize = designSp.sp)).size.width
        val fitted = if (width > room) designSp * room / width else designSp
        maxOf(fitted, minSp).sp
    }
    Text(
        text = text,
        color = colour,
        style = baseStyle.copy(fontSize = size),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.offset(y = h * (designY / CardH)).padding(start = margin, end = margin),
    )
}

private fun DrawScope.drawCard(accent: Color, accent2: Color, glow: Float) {
    val w = size.width
    val h = size.height
    val k = w / CardW
    val radius = CornerRadius(24f * k)

    // Selected glow: layered strokes outside the card, as on the tiles.
    if (glow > 0.01f) {
        for (i in 4 downTo 1) {
            val g = w * 0.012f * i
            drawRoundRect(
                color = accent.copy(alpha = 0.07f * glow * (5 - i)),
                topLeft = Offset(-g, -g),
                size = Size(w + g * 2, h + g * 2),
                cornerRadius = CornerRadius(24f * k + g),
                style = Stroke(w * 0.025f),
            )
        }
    }

    // Dark glass, a faint tint from the top-left and less of the second colour from the bottom
    // right, and a soft sheen across the top.
    drawRoundRect(GlassBase, cornerRadius = radius)
    drawRoundRect(
        Brush.radialGradient(
            0f to accent.copy(alpha = 0.20f), 0.6f to accent.copy(alpha = 0.05f), 1f to Color.Transparent,
            center = Offset(30f * k, 20f * k), radius = 380f * k,
        ),
        cornerRadius = radius,
    )
    drawRoundRect(
        Brush.radialGradient(
            0f to accent2.copy(alpha = 0.08f), 1f to Color.Transparent,
            center = Offset(260f * k, h - 10f * k), radius = 260f * k,
        ),
        cornerRadius = radius,
    )
    drawRoundRect(
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.3f to Color.White.copy(alpha = 0.015f), 1f to Color.Transparent),
        cornerRadius = radius,
    )

    // The tiles' coloured border, from the first colour to the second across the card.
    val border = 3f * k
    drawRoundRect(
        Brush.linearGradient(listOf(accent, accent2.copy(alpha = 0.9f)), start = Offset.Zero, end = Offset(w, h)),
        topLeft = Offset(border / 2f, border / 2f),
        size = Size(w - border, h - border),
        cornerRadius = CornerRadius(24f * k - border / 2f),
        style = Stroke(border),
    )

    drawSettingsMark(Offset(w - 40f * k, 38f * k), k)
}

/** The settings mark top right: a glass disc with a hexagon nut in it. */
private fun DrawScope.drawSettingsMark(c: Offset, k: Float) {
    drawCircle(Color.White.copy(alpha = 0.16f), 19f * k, c)
    drawCircle(Color.White.copy(alpha = 0.25f), 18.5f * k, c, style = Stroke(k))
    val r = 8.5f * k
    val hex = Path().apply {
        for (i in 0..5) {
            val a = (-90f + 60f * i) * PI.toFloat() / 180f
            val x = c.x + r * cos(a)
            val y = c.y + r * sin(a)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(hex, Color.White, style = Stroke(1.8f * k, join = StrokeJoin.Round))
    drawCircle(Color.White, 3.4f * k, c, style = Stroke(1.8f * k))
}

// The Figma card's frame and the positions in it.
private const val CardW = 270f
private const val CardH = 330f
private const val GlyphX = 24f
private const val GlyphY = 62f
private const val GlyphW = 222f
private const val GlyphH = 148f
private const val TextX = 22f
private const val TitleY = 226f
private const val SubtitleY = 270f
private const val TitleSize = 32f
private const val SubtitleSize = 18f
private const val TitleMinSp = 12f
private const val SubtitleMinSp = 10f

private val GlassBase = Color(0xFF0B0B0E)
private val SubtitleColour = Color.White.copy(alpha = 0.62f)
