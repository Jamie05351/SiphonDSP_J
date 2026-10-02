package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
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
 * Settings and More sit in the top screen as bare icons with a label under them, no tile shell.
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
            HomeTileKind.SETTINGS -> HomeIconButton(stringResource(R.string.title_activity_settings)) { GearGlyph() }
            HomeTileKind.MORE -> HomeIconButton(stringResource(R.string.home_tile_more)) { MoreGlyph(dotRadius = 0.10f) }
        }
    }
}

/**
 * A top-screen icon (Settings, More): the glyph over its label, with no tile shell. Like [HomeTile]
 * it is purely visual; the host ComposeView owns the click, ripple and accessibility label. The
 * label is 18sp white (the top screen's size), shrunk only if it would be wider than the box, as
 * on a small phone.
 */
@Composable
private fun HomeIconButton(label: String, glyph: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val icon = min(maxWidth, maxHeight * 0.58f)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val boxWidth = maxWidth
        val labelSize = remember(label, boxWidth, density) {
            val width = measurer.measure(label, TextStyle(fontSize = IconLabelSp.sp)).size.width
            val room = with(density) { boxWidth.toPx() }
            (if (width > room) maxOf(IconLabelSp * room / width, IconLabelMinSp) else IconLabelSp).sp
        }
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(icon)) { glyph() }
            Text(
                text = label,
                color = Color.White,
                fontSize = labelSize,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private const val IconLabelSp = 18f
private const val IconLabelMinSp = 11f
