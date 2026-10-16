package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/**
 * One global stage on the top screen: a status dot and the stage's name over its setting in large
 * type ("● TILT / +1.0 dB"), in [accent], the colour of the menu the stage lives in. Lit (the stage
 * on and the DSP powered), the cell is lit like the screens: an [accent] edge with a glow fading
 * inwards from it over a faint [accent] tint, and the dot lit with its halo. Off, it is a plain dark
 * cell with a grey edge and a hollow grey dot. Text is white while lit and grey while not; it all
 * blends by [globalActive] times the cell's own animated on fraction. Tapping it opens the screen
 * that owns the stage.
 */
@Composable
fun GlobalChip(
    label: String,
    value: String,
    accent: Color,
    on: Boolean,
    globalActive: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    val t = animateActive(on, "cell $label") * globalActive
    val shape = RoundedCornerShape(s.dp(CellCorner))
    Column(
        modifier
            .clip(shape)
            .background(HomePalette.Cell)
            .drawBehind { drawCellGlow(accent, t, s.dp(CellCorner).toPx(), s.dp(1.2f).toPx()) }
            .border(s.dp(1.5f), lerp(HomePalette.IdleEdge, accent, t), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = s.dp(14f), vertical = s.dp(8f)),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(10f))) {
            StatusDot(accent, t, s.dp(14f))
            Text(
                text = label,
                color = lerp(HomePalette.Muted, HomePalette.CellLabel, t),
                fontSize = s.sp(16f),
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(lineHeight = s.sp(20f)),
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = value,
            color = lerp(HomePalette.Muted, Color.White, t),
            fontSize = s.sp(28f),
            fontWeight = FontWeight.Bold,
            // Tight line height, so the value fits the cell at every scale.
            style = TextStyle(fontFeatureSettings = "tnum", lineHeight = s.sp(32f)),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The lit cell's glow, as the chain screen's ring does it: a faint [accent] tint, then thin bands
 * fading to nothing inwards from the edge, all scaled by [t]. [radius] is the cell's corner and
 * [band] the width of one band.
 */
private fun DrawScope.drawCellGlow(accent: Color, t: Float, radius: Float, band: Float) {
    if (t <= 0f) return
    drawRoundRect(accent.copy(alpha = 0.08f * t), cornerRadius = CornerRadius(radius))
    for (step in 0 until CellGlowBands) {
        val inset = band * (step + 0.5f)
        val fade = 1 - (step + 1f) / CellGlowBands
        drawRoundRect(
            accent.copy(alpha = 0.32f * t * fade * fade),
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2 * inset, size.height - 2 * inset),
            cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
            style = Stroke(band),
        )
    }
}

private const val CellCorner = 10f
private const val CellGlowBands = 10

/**
 * The at-a-glance state light: at [t] = 1 a solid [colour] dot with a soft halo, at 0 a hollow
 * grey ring, cross-fading in between.
 */
@Composable
internal fun StatusDot(colour: Color, t: Float, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        // Halo, drawn past the dot's own bounds.
        for (step in 1..3) drawCircle(colour.copy(alpha = 0.18f * t / step), r * (1f + 0.35f * step))
        drawCircle(colour.copy(alpha = t), r)
        val ring = r * 0.28f
        drawCircle(HomePalette.Hollow.copy(alpha = 1 - t), r - ring / 2, style = Stroke(ring))
    }
}
