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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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

// Saturated neon blue for the lit tile, deliberately brighter than the skin's pale
// LIGHT_BLUE_BRIGHT so the selected tile pops against the dark rail. Fallback only: the head-unit
// art lights a different-coloured strip per tile, so the ring follows TILE_GLOW_COLORS instead.
private val TileGlowColor = Color(0xFF19B5FF)

// Each head-unit backdrop lights the selected tile's bottom strip in that tile's own colour; the
// selection ring is drawn in the same colour so ring and strip read as one lit control. Sampled
// from the brightest strip pixels of the five authored backdrops (not eyeballed).
private val TILE_GLOW_COLORS = mapOf(
    DspDestination.PARAMETRIC_EQ to Color(0xFF06FCFD),
    DspDestination.GAINS_DELAY to Color(0xFF00FAC3),
    DspDestination.CROSSOVER_TILT to Color(0xFFFDFB02),
    DspDestination.COMPRESSOR to Color(0xFFAF00FC),
    DspDestination.ALLPASS to Color(0xFF05FCFC),
)
private val TileGlowStrokeWidth = 2.dp
private val TileGlowWidth = 5.dp
private val TileGlowHaloWidth = 10.dp
// Head-unit art is already crisp line art, so its ring drops the bloom: the wide, faint layers
// read as blur against it. A tight 3dp underlay at low alpha just keeps the line from looking thin.
private val CrispRingWidth = 2.5.dp
private val CrispRingUnderlayWidth = 3.5.dp
private val TileGlowCornerRadius = 6.dp

// Icon sits inside the glow ring, both centered and inset from the tile's own bounds -- the
// backdrop's baked-in tile square is slightly larger than either, so neither touches its edges.
// All of these (and the insets/offsets below) were measured against the real backdrop art with
// the HTML sidebar calibrator, not eyeballed -- see the tile-position PR for the tool.
private const val TileGlowFraction = 1.000f
private const val TileIconFraction = 0.620f

// The rail's visible tile squares don't span the full 140dp sidebar column -- they sit inset
// from the column's own glass-facia edges. Fractions of the column's own width, not the screen.
// Defaults are for the empty-slot phone art; the head-unit art has its own (wider) tiles.
const val TILE_LEFT_INSET_FRACTION = 0.1330f
const val TILE_RIGHT_INSET_FRACTION = 0.1560f

// A couple of the source icon PNGs bake in far more transparent padding than the others (e.g.
// Gains & Delay's is a 1080x1080 canvas vs. the others' tightly-cropped glyphs), so the same
// TileIconFraction renders them visibly smaller. Scales the icon up per-destination to compensate
// without re-exporting the art.
private val ICON_SCALE = mapOf(
    DspDestination.PARAMETRIC_EQ to 1.31f,
    DspDestination.GAINS_DELAY to 1.96f,
    DspDestination.CROSSOVER_TILT to 1.38f,
    DspDestination.COMPRESSOR to 1.45f,
    DspDestination.ALLPASS to 1.29f,
)

// Small nudges off dead-center, as a fraction of the tile's own size (+x = right, +y = down).
private const val TileIconOffsetXFraction = -0.0050f
private const val TileIconOffsetYFraction = 0.0250f
private const val TileGlowOffsetXFraction = 0.0000f
private const val TileGlowOffsetYFraction = 0.0000f

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
    leftInsetFraction: Float = TILE_LEFT_INSET_FRACTION,
    rightInsetFraction: Float = TILE_RIGHT_INSET_FRACTION,
    // True for the head-unit art, which already has each tile's icon, label and lit/dim strip baked
    // in: a tile is then just the touch target plus a selection ring in that page's strip colour,
    // with no live icon drawn over the art's own.
    bakedInArt: Boolean = false,
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
        val leftInset = maxWidth * leftInsetFraction
        val rightInset = maxWidth * rightInsetFraction

        Column(Modifier.fillMaxWidth().padding(start = leftInset, end = rightInset)) {
            heightsPx.forEachIndexed { i, heightPx ->
                val heightDp = with(density) { heightPx.toDp() }
                if (i % 2 == 0) {
                    Spacer(Modifier.height(heightDp))
                } else {
                    val destination = destinations[(i - 1) / 2]
                    DspSidebarTile(
                        destination = destination,
                        selected = destination == current,
                        bakedInArt = bakedInArt,
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
    bakedInArt: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(destination.labelRes)
    BoxWithConstraints(
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
            TileGlow(
                crisp = bakedInArt,
                color = if (bakedInArt) TILE_GLOW_COLORS[destination] ?: TileGlowColor else TileGlowColor,
                modifier = Modifier
                    .fillMaxSize(TileGlowFraction)
                    .offset(x = maxWidth * TileGlowOffsetXFraction, y = maxHeight * TileGlowOffsetYFraction)
            )
        }
        // A couple of the source icon PNGs bake in far more padding than the others -- see
        // ICON_SCALE -- so the per-destination multiplier keeps them visually consistent without
        // re-exporting the art. requiredSize, not fillMaxSize(fraction): fillMaxSize coerces the
        // fraction to the incoming max constraints, so a scaled fraction above 1.0 (Gains &
        // Delay: 0.62 * 1.96) would silently stop at the tile size. The oversized box is mostly
        // transparent padding, so it never draws outside the tile.
        if (!bakedInArt) {
            val iconFraction = TileIconFraction * (ICON_SCALE[destination] ?: 1f)
            Image(
                painter = painterResource(if (selected) destination.iconOn else destination.iconOff),
                // Decorative: the clickable tile above already carries the label via onClickLabel.
                contentDescription = null,
                modifier = Modifier
                    .requiredSize(maxWidth * iconFraction, maxHeight * iconFraction)
                    .offset(x = maxWidth * TileIconOffsetXFraction, y = maxHeight * TileIconOffsetYFraction),
            )
        }
    }
}

/** Same cyan glow + stroke rounded-rect as [bmwFocusRing] (wide low-alpha stroke under a crisp
 *  one, not a real blur -- the app's established "lit tile" technique, see
 *  BmwSkinDrawables.TileFocusRingDrawable), gated on selection instead of D-pad focus. */
@Composable
private fun TileGlow(color: Color, crisp: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (crisp) drawCrispTileRing(color, TileGlowCornerRadius) else drawBmwTileGlow(color, TileGlowCornerRadius)
    }
}

private fun DrawScope.drawCrispTileRing(color: Color, cornerRadius: Dp) {
    val strokePx = CrispRingWidth.toPx()
    val inset = strokePx / 2f
    val corner = CornerRadius(cornerRadius.toPx())
    val topLeft = Offset(inset, inset)
    val ringSize = Size(size.width - strokePx, size.height - strokePx)
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(CrispRingUnderlayWidth.toPx()),
        alpha = 0.3f,
    )
    drawRoundRect(color = color, topLeft = topLeft, size = ringSize, cornerRadius = corner, style = Stroke(strokePx))
}

private fun DrawScope.drawBmwTileGlow(color: Color, cornerRadius: Dp) {
    val strokePx = TileGlowStrokeWidth.toPx()
    val inset = strokePx / 2f
    val corner = CornerRadius(cornerRadius.toPx())
    val topLeft = Offset(inset, inset)
    val ringSize = Size(size.width - strokePx, size.height - strokePx)
    // Outermost, faintest layer: fakes a soft bloom without a real blur.
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(TileGlowHaloWidth.toPx()),
        alpha = 0.16f,
    )
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(TileGlowWidth.toPx()),
        alpha = 0.45f,
    )
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(strokePx),
    )
}
