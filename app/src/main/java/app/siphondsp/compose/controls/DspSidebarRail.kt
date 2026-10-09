package app.siphondsp.compose.controls

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import app.siphondsp.R
import app.siphondsp.compose.assets.AllpassGraphic
import app.siphondsp.compose.assets.CompressorGraphic
import app.siphondsp.compose.assets.GainsDelayGraphic
import app.siphondsp.compose.assets.PeqGraphic
import app.siphondsp.compose.assets.XoversGraphic
import app.siphondsp.view.DspDestination
import kotlin.math.roundToInt

/**
 * The submenu sidebar: the front page's chain screen turned on its side (see SidebarChrome.kt).
 * Fill the whole sidebar column with it; it draws the black glass screen [SidebarFacia] in from
 * the column's edges, over the facia that [DspWorkspaceBackdrop] draws behind it, and lays the
 * five nav tiles ([RailChainCard]) down the screen on the purple signal line, placed by
 * [SidebarLayout] so the front page's morph lands on exactly the same spots.
 *
 * The current destination is lit and not clickable; hardware D-pad focus keeps [bmwFocusRing].
 */
@Composable
fun DspSidebarRail(
    destinations: List<DspDestination>,
    current: DspDestination,
    canNavigate: () -> Boolean,
    onNavigate: (DspDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val signal = SidebarSignal
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val count = destinations.size.coerceAtLeast(1)
        val layout = with(density) { SidebarLayout(maxWidth.toPx(), maxHeight.toPx(), SidebarFacia.toPx(), count) }
        Spacer(Modifier.fillMaxSize().drawBehind { drawSidebarScreen(layout, count, signal) })
        val tile = with(density) { layout.tile.toDp() }
        destinations.forEachIndexed { i, dest ->
            val selected = dest == current
            val source = remember { MutableInteractionSource() }
            val (accent, accent2, graphic) = dest.cardStyle()
            val at = layout.tileRect(i)
            RailChainCard(
                label = stringResource(dest.tileLabelRes()),
                graphic = graphic,
                accent = accent,
                accent2 = accent2,
                // The caption is short ("PEQ", "Xover"); announce the full screen name.
                a11yLabel = stringResource(dest.labelRes),
                selected = selected,
                enabled = !selected,
                onClick = { if (canNavigate()) onNavigate(dest) },
                interactionSource = source,
                modifier = Modifier
                    .offset { IntOffset(at.left.roundToInt(), at.top.roundToInt()) }
                    .size(tile)
                    .bmwFocusRing(source, cornerRadius = tile * 0.12f),
            )
        }
    }
}

/** Each page's short tile caption ("PEQ", "Xover"): the same as its tile on the front page. */
internal fun DspDestination.tileLabelRes(): Int = when (this) {
    DspDestination.PARAMETRIC_EQ -> R.string.home_tile_peq
    DspDestination.GAINS_DELAY -> R.string.home_tile_gains
    DspDestination.CROSSOVER_TILT -> R.string.home_tile_xovers
    DspDestination.COMPRESSOR -> R.string.home_tile_compressor
    DspDestination.ALLPASS -> R.string.home_tile_allpass
}

/** Each page's card colours and artwork: the same as its card on the front page. */
internal fun DspDestination.cardStyle(): Triple<Color, Color, ImageVector> = when (this) {
    DspDestination.PARAMETRIC_EQ -> Triple(DspColors.Peq, DspColors.BandMagenta, PeqGraphic)
    DspDestination.GAINS_DELAY -> Triple(DspColors.Delay, ChainTeal, GainsDelayGraphic)
    DspDestination.CROSSOVER_TILT -> Triple(DspColors.Xover, DspColors.XoverLow, XoversGraphic)
    DspDestination.COMPRESSOR -> Triple(DspColors.Comp, ChainRose, CompressorGraphic)
    DspDestination.ALLPASS -> Triple(DspColors.Allpass, ChainIndigo, AllpassGraphic)
}

// Each card's second border colour, from the Figma cards (the others reuse DspColors).
private val ChainTeal = Color(0xFF14B8A6)
private val ChainRose = Color(0xFFFF4D8D)
private val ChainIndigo = Color(0xFF5B5BFF)
