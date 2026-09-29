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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.siphondsp.R
import app.siphondsp.view.DspDestination

/**
 * Values for the rail's glyphs. Every field is a lambda so it is read in the draw phase, so they
 * can later be wired to live DSP state without recomposing.
 *
 * Nothing supplies these yet: like the front-page tiles, the rail glyphs are ICONS drawn from
 * these fixed sample values (4 ms, -6 dB, 3-way ...), not a readout of the current settings.
 * Don't read settings off them; the workspace controls are the truth.
 */
class SidebarLive(
    val peqBands: () -> List<PeqBand> = { SampleBands },
    val delayMs: () -> Float = { 4f },
    val gainDb: () -> Float = { -6f },
    val xoverWays: () -> Int = { 3 },
    val compThreshold: () -> Float = { 0.55f },
    val compGrDb: () -> Float = { 6f },
    val allpassPhaseDeg: () -> Float = { 90f },
)

private val SampleBands = listOf(
    PeqBand(60f, 5f, 1.2f, DspColors.BandMagenta),
    PeqBand(250f, 9f, 1.4f, DspColors.BandBlue),
    PeqBand(1000f, -9f, 1.6f, DspColors.BandAmber),
    PeqBand(6000f, 5f, 1.5f, DspColors.BandGreen),
)

private val RailCorner = 14.dp
private val RailBezel = 3.dp

/**
 * The submenu sidebar drawn entirely in Compose: a raised glass housing with its own slim bezel
 * (same metal as the screen and tile bezels) and the five nav tiles from the home screen
 * ([DspTile] + live glyphs). It sits on top of [DspWorkspaceBackdrop].
 *
 * Size it from the caller: the calibrated head unit rail is about 106dp wide inset 9dp from the
 * screen edge (`Modifier.padding(9.dp).width(106.dp)`), which gives ~78dp tiles at 480dp height.
 * The current destination is lit and not clickable; hardware D-pad focus keeps [bmwFocusRing].
 */
@Composable
fun DspSidebarRail(
    destinations: List<DspDestination>,
    current: DspDestination,
    canNavigate: () -> Boolean,
    onNavigate: (DspDestination) -> Unit,
    modifier: Modifier = Modifier,
    live: SidebarLive = SidebarLive(),
) {
    BoxWithConstraints(
        modifier.fillMaxSize().drawBehind { drawRailHousing(RailCorner.toPx(), RailBezel.toPx()) },
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
                DspTile(
                    label = stringResource(dest.tileLabelRes()),
                    // The caption is short ("PEQ", "Xovers"); announce the full screen name.
                    a11yLabel = stringResource(dest.labelRes),
                    accent = dest.accent(),
                    selected = selected,
                    enabled = !selected,
                    onClick = { if (canNavigate()) onNavigate(dest) },
                    interactionSource = source,
                    modifier = Modifier.size(tile).bmwFocusRing(source, cornerRadius = tile * 0.15f),
                ) {
                    when (dest) {
                        DspDestination.PARAMETRIC_EQ -> PeqGlyph(live.peqBands)
                        DspDestination.GAINS_DELAY -> DelayGainGlyph(live.delayMs, live.gainDb)
                        DspDestination.CROSSOVER_TILT -> XoverGlyph(live.xoverWays)
                        DspDestination.COMPRESSOR -> CompressorGlyph(live.compThreshold, live.compGrDb)
                        DspDestination.ALLPASS -> AllpassGlyph(live.allpassPhaseDeg)
                    }
                }
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

private fun DspDestination.accent(): Color = when (this) {
    DspDestination.PARAMETRIC_EQ -> DspColors.Peq
    DspDestination.GAINS_DELAY -> DspColors.Delay
    DspDestination.CROSSOVER_TILT -> DspColors.Xover
    DspDestination.COMPRESSOR -> DspColors.Comp
    DspDestination.ALLPASS -> DspColors.Allpass
}

/** Raised glass panel: dark gradient body, soft sheen, then the shared slim metal bezel. */
private fun DrawScope.drawRailHousing(corner: Float, bezel: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF232527), Color(0xFF0B0C0D))),
        cornerRadius = CornerRadius(corner),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.08f), 0.35f to Color.Transparent,
            start = Offset.Zero, end = Offset(w * 1.2f, h * 0.45f),
        ),
        cornerRadius = CornerRadius(corner),
    )
    drawInsetShadow(corner, depth = bezel * 2.5f)
    drawBezelRing(0f, 0f, w, h, corner, bezel)
}
