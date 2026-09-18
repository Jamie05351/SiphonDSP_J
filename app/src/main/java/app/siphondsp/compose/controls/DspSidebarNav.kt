package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspDestination

private val TileGlowColor = Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
private val TileGlowStrokeWidth = 2.dp
private val TileGlowWidth = 5.dp
private val TileGlowCornerRadius = 6.dp

// Icon sits inside the glow ring, both centered and inset from the tile's own bounds -- the
// backdrop's baked-in tile square is slightly larger than either, so neither touches its edges.
private const val TileGlowFraction = 0.84f
private const val TileIconFraction = 0.68f

/**
 * The sidebar rail's 5 nav tiles, drawn live over a background-only backdrop image: this owns the
 * real touch target bounds directly, rather than a click overlay guessing at a static image's
 * baked-in tile geometry.
 *
 * [weights] is `DspCrossNavBar`'s cumulative-boundary table: alternating gap/tile weights (margin,
 * tile, gap, tile, gap, ..., tile, margin), sized against the 1280x480 head-unit backdrop's own
 * rail geometry. Boundaries are computed from the actual measured pixel height (not
 * `Modifier.weight()`, which independently rounds each child's share and can drift the further
 * down the column a tile sits) so the split stays exact regardless of the container's real height.
 */
@Composable
fun DspSidebarNav(
    destinations: List<DspDestination>,
    current: DspDestination,
    weights: IntArray,
    canNavigate: () -> Boolean,
    onNavigate: (DspDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val totalHeightPx = with(density) { maxHeight.toPx() }
        val totalWeight = weights.sum()
        val heightsPx = remember(totalHeightPx, weights) {
            var cumulative = 0
            var previousBoundary = 0
            IntArray(weights.size) { i ->
                cumulative += weights[i]
                val boundary = (totalHeightPx.toLong() * cumulative / totalWeight).toInt()
                (boundary - previousBoundary).also { previousBoundary = boundary }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            heightsPx.forEachIndexed { i, heightPx ->
                val heightDp = with(density) { heightPx.toDp() }
                if (i % 2 == 0) {
                    Spacer(Modifier.height(heightDp))
                } else {
                    val destination = destinations[(i - 1) / 2]
                    DspSidebarTile(
                        destination = destination,
                        selected = destination == current,
                        onClick = { if (canNavigate()) onNavigate(destination) },
                        modifier = Modifier.fillMaxWidth().height(heightDp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DspSidebarTile(
    destination: DspDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(destination.labelRes)
    Box(
        modifier = modifier.then(
            // The selected tile shows its own permanent glow instead -- excluded from click/focus
            // entirely, matching the old View rows' isClickable=false/isFocusable=false for the
            // current destination (its own screen, tapping it again is a no-op).
            if (selected) Modifier
            else Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            TileGlow(Modifier.fillMaxSize(TileGlowFraction))
        }
        Image(
            painter = painterResource(if (selected) destination.iconOn else destination.iconOff),
            // Decorative: the clickable tile above already carries the label via onClickLabel.
            contentDescription = null,
            modifier = Modifier.fillMaxSize(TileIconFraction),
        )
    }
}

/** Same cyan glow + stroke rounded-rect as [bmwFocusRing] (wide low-alpha stroke under a crisp
 *  one, not a real blur -- the app's established "lit tile" technique, see
 *  BmwSkinDrawables.TileFocusRingDrawable), gated on selection instead of D-pad focus. */
@Composable
private fun TileGlow(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawBmwTileGlow(TileGlowCornerRadius)
    }
}

private fun DrawScope.drawBmwTileGlow(cornerRadius: Dp) {
    val strokePx = TileGlowStrokeWidth.toPx()
    val inset = strokePx / 2f
    val corner = CornerRadius(cornerRadius.toPx())
    val topLeft = Offset(inset, inset)
    val ringSize = Size(size.width - strokePx, size.height - strokePx)
    drawRoundRect(
        color = TileGlowColor,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(TileGlowWidth.toPx()),
        alpha = 0.35f,
    )
    drawRoundRect(
        color = TileGlowColor,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(strokePx),
    )
}
