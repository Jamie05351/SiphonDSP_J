package app.siphondsp.compose.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import app.siphondsp.R

/**
 * One signal-chain tile: a menu button that opens its DSP screen. The tile itself always shows in
 * its [accent], whatever the stage or the DSP power is doing; only the corner pill carries state.
 *
 * [stageOn] is the stage's own on/off, or null for a stage the engine can't bypass (no pill). The
 * pill reads ON or BYPASS and is lit by its own animated fraction times [globalActive], so while
 * the whole DSP is bypassed an "ON" stage keeps its label but goes grey (globally held).
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
    val shape = RoundedCornerShape(s.dp(20f))
    val glow = animateActive(selected, "tile glow $title")
    val stateText = stageOn?.let { stringResource(if (it) R.string.home_stage_on else R.string.home_stage_bypass) }
    Column(
        modifier
            .size(s.dp(CardWidth), s.dp(CardHeight))
            .drawBehind {
                // A soft halo just outside the tile, stronger while it is opening.
                val spread = s.dp(12f).toPx()
                val radius = s.dp(20f).toPx()
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
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.3f + 0.15f * glow), HomePalette.TileBase)))
            .border(s.dp(2f), accent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (stateText != null) stateDescription = stateText }
            .padding(horizontal = s.dp(20f), vertical = s.dp(14f)),
    ) {
        Box(Modifier.height(s.dp(PillHeight))) {
            if (stageOn != null && stateText != null) StagePill(stateText, accent, animateActive(stageOn, "pill $title") * globalActive)
        }
        Spacer(Modifier.weight(1f))
        Image(
            imageVector = art,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(s.dp(90f)),
        )
        Spacer(Modifier.weight(1.4f))
        Text(
            text = title,
            color = Color.White,
            fontSize = s.sp(26f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
        Row(Modifier.fillMaxWidth().padding(top = s.dp(4f)), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = subtitle,
                color = HomePalette.Subtitle,
                fontSize = s.sp(16f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            Text(text = "›", color = Color.White.copy(alpha = 0.5f), fontSize = s.sp(28f), maxLines = 1)
        }
    }
}

/** The corner pill: a dot and ON / BYPASS, lit in [accent] at [t] = 1, idle grey at 0. */
@Composable
private fun StagePill(text: String, accent: Color, t: Float) {
    val s = LocalHomeScale.current
    val shape = CircleShape
    Row(
        Modifier
            .background(lerp(HomePalette.IdleFill, accent.copy(alpha = 0.16f), t), shape)
            .border(s.dp(1.5f), lerp(HomePalette.IdleEdge, accent.copy(alpha = 0.6f), t), shape)
            .padding(start = s.dp(10f), end = s.dp(12f), top = s.dp(6f), bottom = s.dp(6f)),
        horizontalArrangement = Arrangement.spacedBy(s.dp(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(s.dp(9f)).background(lerp(HomePalette.Idle, accent, t), CircleShape))
        Text(
            text = text,
            color = lerp(HomePalette.Label, accent, t),
            fontSize = s.sp(12f),
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(letterSpacing = 0.08.em),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A tile's size in design units. */
internal const val CardWidth = 250f
internal const val CardHeight = 330f
private const val PillHeight = 30f
