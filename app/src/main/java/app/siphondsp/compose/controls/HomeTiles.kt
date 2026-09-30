package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.siphondsp.R

/** The seven front-page tiles, in the order of [app.siphondsp.view.HomeArt.TILE_KEYS]. */
enum class HomeTileKind { PEQ, GAINS, XOVERS, COMPRESSOR, ALLPASS, SETTINGS, MORE }

private val SampleBands = listOf(
    PeqBand(60f, 5f, 1.2f, DspColors.BandMagenta),
    PeqBand(250f, 9f, 1.4f, DspColors.BandBlue),
    PeqBand(1000f, -9f, 1.6f, DspColors.BandAmber),
    PeqBand(6000f, 5f, 1.5f, DspColors.BandGreen),
)

/**
 * One front-page tile, drawn to fit its host view's rect. It is purely visual: the host ComposeView
 * owns the click, the ripple and the accessibility label (see fragment_dsp_page_shortcuts.xml), so
 * the tile and its touch area are always the same box. The glyphs are illustrative, not live.
 * Settings and More have slightly smaller rects than the other five, which they keep.
 *
 * [selected] lights the tile's glow; the front page sets it for a moment when a tile is tapped, just
 * before its screen zooms open.
 */
@Composable
fun HomeTile(kind: HomeTileKind, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (kind) {
            HomeTileKind.PEQ -> DspTile(stringResource(R.string.home_tile_peq), DspColors.Peq, selected, null) {
                PeqGlyph({ SampleBands })
            }
            HomeTileKind.GAINS -> DspTile(stringResource(R.string.home_tile_gains), DspColors.Delay, selected, null) {
                DelayGainGlyph(delayMs = { 8f }, gainDb = { -6f })
            }
            HomeTileKind.XOVERS -> DspTile(stringResource(R.string.home_tile_xovers), DspColors.Xover, selected, null) {
                XoverGlyph({ 3 })
            }
            HomeTileKind.COMPRESSOR -> DspTile(stringResource(R.string.home_tile_compressor), DspColors.Comp, selected, null) {
                CompressorGlyph(thresholdNorm = { 0.55f }, grDb = { 7f })
            }
            HomeTileKind.ALLPASS -> DspTile(stringResource(R.string.home_tile_allpass), DspColors.Allpass, selected, null) {
                AllpassGlyph({ 90f })
            }
            HomeTileKind.SETTINGS -> DspTile(stringResource(R.string.title_activity_settings), DspColors.Neutral, selected, null) {
                GearGlyph()
            }
            HomeTileKind.MORE -> DspTile(stringResource(R.string.home_tile_more), DspColors.Neutral, selected, null) {
                MoreGlyph()
            }
        }
    }
}
