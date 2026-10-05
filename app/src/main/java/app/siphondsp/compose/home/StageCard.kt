package app.siphondsp.compose.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import app.siphondsp.R

/**
 * One signal-chain tile: a menu button that opens its DSP screen. The tile itself always shows in
 * its [accent], whatever the stage or the DSP power is doing; only the badge across its top carries
 * state. It is [CardWidth] wide and as tall as the chain panel allows.
 *
 * [stageOn] is the stage's own on/off, or null for a stage the engine can't bypass (no badge). The
 * badge reads ON, solid in [accent], or BYPASSED in grey; its colour is lit by the stage's own
 * animated fraction times [globalActive], so while the whole DSP is bypassed an ON stage keeps its
 * label but goes grey (globally held).
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
    stageOn: Boolean?,
    globalActive: Float,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(16f))
    val glow = animateActive(selected, "tile glow $title")
    val stateText = stageOn?.let { stringResource(if (it) R.string.home_stage_on else R.string.home_stage_bypassed) }
    Column(
        modifier
            .width(s.dp(CardWidth))
            .fillMaxHeight()
            .drawBehind {
                // A soft halo just outside the tile, stronger while it is opening.
                val spread = s.dp(14f).toPx()
                val radius = s.dp(16f).toPx()
                for (step in 1..3) {
                    val g = spread * step / 3f
                    drawRoundRect(
                        accent.copy(alpha = (0.10f + 0.25f * glow) / step),
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(radius + g),
                    )
                }
            }
            .clip(shape)
            .background(HomePalette.TileBase)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f + 0.15f * glow), Color.Transparent)))
            .border(s.dp(2.5f), accent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (stateText != null) stateDescription = stateText }
            .padding(s.dp(12f)),
    ) {
        Box(Modifier.fillMaxWidth().height(s.dp(BadgeHeight))) {
            if (stageOn != null && stateText != null) {
                StateBadge(stateText, accent, animateActive(stageOn, "badge $title") * globalActive)
            }
        }
        Spacer(Modifier.weight(0.4f))
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

/** The badge across the tile's top: solid [accent] with white text at [t] = 1, grey at 0. */
@Composable
private fun StateBadge(text: String, accent: Color, t: Float) {
    val s = LocalHomeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(s.dp(BadgeHeight))
            .background(lerp(HomePalette.BadgeIdle, accent, t), RoundedCornerShape(s.dp(10f))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = lerp(HomePalette.CellLabel, Color.White, t),
            fontSize = s.sp(18f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A tile's width in design units. */
internal const val CardWidth = 170f
private const val BadgeHeight = 34f
