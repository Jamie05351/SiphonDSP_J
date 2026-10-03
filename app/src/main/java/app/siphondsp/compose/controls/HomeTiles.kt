package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
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
 * The five DSP modules are signal-chain cards ([HomeChainCard]) in the bottom screen; Settings and
 * More sit in the top screen as bare icons with a label under them, no tile shell.
 *
 * [selected] lights the card's glow; the front page sets it for a moment when a card is tapped,
 * just before its screen zooms open.
 */
@Composable
fun HomeTile(kind: HomeTileKind, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (kind) {
            HomeTileKind.PEQ -> HomeChainCard(
                stringResource(R.string.home_card_peq), stringResource(R.string.home_card_peq_sub),
                DspColors.Peq, DspColors.BandMagenta, selected,
            ) { PeqGlyph({ SampleBands }) }
            HomeTileKind.GAINS -> HomeChainCard(
                stringResource(R.string.home_card_gains), stringResource(R.string.home_card_gains_sub),
                DspColors.Delay, ChainTeal, selected,
            ) { DelayGainGlyph(delayMs = { 8f }, gainDb = { -6f }) }
            HomeTileKind.XOVERS -> HomeChainCard(
                stringResource(R.string.home_card_xovers), stringResource(R.string.home_card_xovers_sub),
                DspColors.Xover, DspColors.XoverLow, selected,
            ) { XoverGlyph({ 3 }) }
            HomeTileKind.COMPRESSOR -> HomeChainCard(
                stringResource(R.string.home_card_compressor), stringResource(R.string.home_card_compressor_sub),
                DspColors.Comp, ChainRose, selected,
            ) { CompressorGlyph(thresholdNorm = { 0.55f }, grDb = { 7f }) }
            HomeTileKind.ALLPASS -> HomeChainCard(
                stringResource(R.string.home_card_allpass), stringResource(R.string.home_card_allpass_sub),
                DspColors.Allpass, ChainIndigo, selected,
            ) { AllpassGlyph({ 90f }) }
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
        // Measured in the exact style the Text draws with (the theme's font, weight and letter
        // spacing), or the measurement comes up short and the label clips at the box's edge.
        val baseStyle = LocalTextStyle.current.merge(
            TextStyle(fontSize = IconLabelSp.sp, fontWeight = FontWeight.Normal),
        )
        val labelSize = remember(label, boxWidth, density, baseStyle) {
            val width = measurer.measure(label, baseStyle).size.width
            // A little short of the box: letter spacing doesn't shrink exactly with the size.
            val room = with(density) { boxWidth.toPx() } * 0.94f
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
                style = baseStyle.copy(fontSize = labelSize),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// Each card's second border colour, from the Figma cards (the others reuse DspColors).
private val ChainTeal = Color(0xFF14B8A6)
private val ChainRose = Color(0xFFFF4D8D)
private val ChainIndigo = Color(0xFF5B5BFF)

private const val IconLabelSp = 18f
private const val IconLabelMinSp = 11f
