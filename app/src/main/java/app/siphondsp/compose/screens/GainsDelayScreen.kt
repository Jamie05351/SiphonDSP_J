package app.siphondsp.compose.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwChannelCard
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin

/**
 * Phase 6 of COMPOSE_MIGRATION_ROADMAP.md -- ports `GainLimiterFragment`'s car-diagram page:
 * four [BmwChannelCard]s (Mid/Low x L/R) in two columns with the `bmw_gains_delay_car` image
 * between them. Delay is normally 4 independent values; when the Left Low card's LINK L/R switch
 * is on, each delay edit also writes the band's other side (via [BmwDspState]'s `mirrors`), and
 * the sibling card just recomposes off the shared snapshot.
 *
 * The Output page ([HeadroomOutputScreen]) and the bus-limiter page ([CompressorDriverPage],
 * moved here from the compressor pager) are the other two pages of this workspace; `DspPager`
 * hosts all three.
 */
@Composable
fun GainsDelayScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val linked = dsp.isOn(NativeBmwDspValues.INDEX_DELAY_LINKED)

    val yellow = Color(BmwDashboardSkin.MID_BAND_YELLOW)
    val blue = Color(BmwDashboardSkin.LIGHT_BLUE)
    val mBlue = Color(BmwDashboardSkin.M_BLUE)
    val midSlider = Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR)
    val lowSlider = Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR)
    val stageAccent = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 42.dp, end = 24.dp, top = 10.dp, bottom = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(CardColumnWeight).padding(end = 4.dp)) {
                    GainsChannelCard(
                        dsp, linked,
                        title = "Left Mid", accent = yellow, stroke = yellow, sliderAccent = midSlider,
                        delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_L,
                        delaySibling = NativeBmwDspValues.INDEX_MID_DELAY_R,
                        gainIndex = NativeBmwDspValues.INDEX_MID_GAIN_L,
                        output = NativeBmwDspValues.OUTPUT_MID_LEFT,
                    )
                    Spacer(Modifier.height(6.dp))
                    GainsChannelCard(
                        dsp, linked,
                        title = "Left Low", accent = blue, stroke = mBlue, sliderAccent = lowSlider,
                        delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_L,
                        delaySibling = NativeBmwDspValues.INDEX_LOW_DELAY_R,
                        gainIndex = NativeBmwDspValues.INDEX_LOW_GAIN_L,
                        output = NativeBmwDspValues.OUTPUT_LOW_LEFT,
                        withLinkToggle = true,
                    )
                }
                Image(
                    painter = painterResource(R.drawable.bmw_gains_delay_car),
                    contentDescription = null,
                    modifier = Modifier.weight(CarImageWeight),
                    contentScale = ContentScale.FillWidth,
                )
                Column(modifier = Modifier.weight(CardColumnWeight).padding(start = 4.dp)) {
                    GainsChannelCard(
                        dsp, linked,
                        title = "Right Mid", accent = yellow, stroke = yellow, sliderAccent = midSlider,
                        delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_R,
                        delaySibling = NativeBmwDspValues.INDEX_MID_DELAY_L,
                        gainIndex = NativeBmwDspValues.INDEX_MID_GAIN_R,
                        output = NativeBmwDspValues.OUTPUT_MID_RIGHT,
                    )
                    Spacer(Modifier.height(6.dp))
                    GainsChannelCard(
                        dsp, linked,
                        title = "Right Low", accent = blue, stroke = mBlue, sliderAccent = lowSlider,
                        delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_R,
                        delaySibling = NativeBmwDspValues.INDEX_LOW_DELAY_L,
                        gainIndex = NativeBmwDspValues.INDEX_LOW_GAIN_R,
                        output = NativeBmwDspValues.OUTPUT_LOW_RIGHT,
                    )
                }
            }

            // Stage timing -- a separate L/R stage-centring correction layer, downstream of the
            // per-driver crossover delays in the cards above (native applies it to the summed
            // output, after the master limiter). Left and Right are independent.
            Spacer(Modifier.height(10.dp))
            BmwPanel(
                title = "Stage timing",
                subtitle = "L/R alignment delay for stage centring. Independent of the per-driver " +
                    "delays above; applied to the summed output.",
                modifier = Modifier.fillMaxWidth(),
                leanStart = 84.dp,
                sliderLabels = listOf("Left", "Right"),
            ) {
                StageDelayRow(dsp, "Left", NativeBmwDspValues.INDEX_STAGE_DELAY_L, stageAccent)
                StageDelayRow(dsp, "Right", NativeBmwDspValues.INDEX_STAGE_DELAY_R, stageAccent)
            }
        }
    }
}

@Composable
private fun StageDelayRow(dsp: BmwDspState, label: String, index: Int, accent: Color) {
    BmwSliderRow(
        label = label,
        value = dsp.get(index),
        valueRange = 0f..NativeBmwDspValues.STAGE_DELAY_MAX_MS,
        step = 0.05f,
        unit = "ms",
        accentColor = accent,
        // Commit on release / on typed entry only -- a delay value jumping every drag frame
        // steps the fractional delay line and clicks. Matches the per-driver delay controls.
        onPreview = {},
        onCommit = { dsp.commit(index, it) },
        onValueEntered = { dsp.commit(index, it) },
    )
}

@Composable
private fun GainsChannelCard(
    dsp: BmwDspState,
    linked: Boolean,
    title: String,
    accent: Color,
    stroke: Color,
    sliderAccent: Color,
    delayIndex: Int,
    delaySibling: Int,
    gainIndex: Int,
    output: Int,
    withLinkToggle: Boolean = false,
) {
    val polarityIndex = NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)
    val delayMirror = if (linked) intArrayOf(delaySibling) else IntArray(0)

    BmwChannelCard(
        title = title,
        accentColor = accent,
        strokeColor = stroke,
        delayValue = dsp.get(delayIndex),
        delayRange = DelayRange,
        onDelayCommit = { dsp.commit(delayIndex, it, delayMirror) },
        polarityInverted = dsp.isOn(polarityIndex),
        onPolarityChange = { dsp.commit(polarityIndex, if (it) 1f else 0f) },
        gainValue = dsp.get(gainIndex),
        gainRange = GainRange,
        gainStep = GainStep,
        gainSliderAccent = sliderAccent,
        onGainPreview = { dsp.preview(gainIndex, it) },
        onGainCommit = { dsp.commit(gainIndex, it) },
        modifier = Modifier.fillMaxWidth(),
        linkChecked = if (withLinkToggle) linked else null,
        onLinkChange = if (withLinkToggle) {
            { on: Boolean -> dsp.commit(NativeBmwDspValues.INDEX_DELAY_LINKED, if (on) 1f else 0f) }
        } else {
            null
        },
    )
}

// Weights 1:1 with CrossoverDashboardBuilder's CHANNEL_CARD_WIDTH_DP (264) / CAR_DIAGRAM_WIDTH_DP (612).
private const val CardColumnWeight = 264f
private const val CarImageWeight = 612f
private val DelayRange = 0f..2.8f
private val GainRange = -6f..0f
private const val GainStep = 0.5f
