package app.siphondsp.compose.controls

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.siphondsp.R
import app.siphondsp.compose.assets.AllpassGraphic
import app.siphondsp.compose.assets.CompressorGraphic
import app.siphondsp.compose.assets.GainsDelayGraphic
import app.siphondsp.compose.assets.PeqGraphic
import app.siphondsp.compose.assets.XoversGraphic
import app.siphondsp.view.DspDestination

/**
 * The submenu sidebar drawn entirely in Compose: a housing made of the front page's faceplate (the
 * same grained plate and metal bezel, see FaceplatePlate.kt) holding the five nav tiles from the
 * front page's signal chain as small square cards ([RailChainCard], with the same artwork and
 * colours as the front page's signal-chain tiles). It sits on top of [DspWorkspaceBackdrop].
 *
 * Size it from the caller: the calibrated head unit rail is about 106dp wide inset 9dp from the
 * screen edge (`Modifier.padding(9.dp).width(106.dp)`, inside the 124dp column), which gives ~78dp tiles at 480dp height.
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
    val grain = remember { plateGrainBrush() }
    BoxWithConstraints(
        modifier.fillMaxSize().drawBehind {
            val corner = FaceplateCorner.toPx()
            drawPlate(grain, corner)
            drawBezelRing(0f, 0f, size.width, size.height, corner, FaceplateBezel.toPx())
        },
    ) {
        val n = destinations.size.coerceAtLeast(1)
        // Layout ratios from the rail art: tile 1.0, gap 0.12, end padding 0.20 (of tile size).
        val byHeight = maxHeight / (n + 0.12f * (n - 1) + 0.40f)
        val tile = min(byHeight, maxWidth * 0.74f)

        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = tile * 0.20f),
            verticalArrangement = Arrangement.spacedBy(tile * 0.12f, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            destinations.forEach { dest ->
                val selected = dest == current
                val source = remember { MutableInteractionSource() }
                val (accent, accent2, graphic) = dest.cardStyle()
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
                    modifier = Modifier.size(tile).bmwFocusRing(source, cornerRadius = tile * 0.12f),
                )
            }
        }
    }
}

private fun DspDestination.tileLabelRes(): Int = when (this) {
    DspDestination.PARAMETRIC_EQ -> R.string.home_tile_peq
    DspDestination.GAINS_DELAY -> R.string.home_tile_gains
    DspDestination.CROSSOVER_TILT -> R.string.home_tile_xovers
    DspDestination.COMPRESSOR -> R.string.home_tile_compressor
    DspDestination.ALLPASS -> R.string.home_tile_allpass
}

/** Each page's card colours and artwork: the same as its card on the front page. */
private fun DspDestination.cardStyle(): Triple<Color, Color, ImageVector> = when (this) {
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
