package app.siphondsp.compose.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * One workspace sidebar tile: a small square version of the front page's signal-chain tile
 * ([app.siphondsp.compose.home.StageCard]), drawn by [drawRailTile] so the front page's
 * tile-to-sidebar morph can draw exactly the same thing: dark glass washed with the module's
 * colour, the border running from [accent] to [accent2], the module's [graphic] across the top and
 * its short [label] under it.
 *
 * [selected] (the screen you are on) lights it: a stronger wash, a white label and a halo round
 * the card.
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
    val art = rememberVectorPainter(graphic)
    val measurer = rememberTextMeasurer()

    Spacer(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawWithCache {
                val text = measureTileLabel(measurer, label, size.width, this)
                onDrawBehind { drawRailTile(Rect(0f, 0f, size.width, size.height), accent, accent2, lit, art, text) }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = a11yLabel ?: label,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = a11yLabel ?: label },
    )
}
