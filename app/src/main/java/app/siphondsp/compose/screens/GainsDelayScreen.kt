package app.siphondsp.compose.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.controls.ArcGainSlider
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
 * between them, plus an [ArcGainSlider] wrapped around each of the four speakers drawn in that
 * image (gain used to be a row inside the card box; a rotary control on the actual speaker it
 * controls reads more directly). Delay is normally 4 independent values; when the Left Low
 * card's LINK L/R switch is on, each delay edit also writes the band's other side (via
 * [BmwDspState]'s `mirrors`), and the sibling card just recomposes off the shared snapshot.
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
                        title = "Left Mid", accent = yellow, stroke = yellow,
                        delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_L,
                        delaySibling = NativeBmwDspValues.INDEX_MID_DELAY_R,
                        output = NativeBmwDspValues.OUTPUT_MID_LEFT,
                    )
                    Spacer(Modifier.height(6.dp))
                    GainsChannelCard(
                        dsp, linked,
                        title = "Left Low", accent = blue, stroke = mBlue,
                        delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_L,
                        delaySibling = NativeBmwDspValues.INDEX_LOW_DELAY_R,
                        output = NativeBmwDspValues.OUTPUT_LOW_LEFT,
                        withLinkToggle = true,
                    )
                }

                // The car image ships at a fixed 2340x878 pixel layout (bmw_gains_delay_car); the
                // 4 speaker positions/sizes below are measured directly off that bitmap as
                // fractions of its width/height, so BoxWithConstraints's measured width can size
                // and place every ArcGainSlider without the image ever being distorted or cropped
                // (aspectRatio locked to the same 2340:878 the art was authored at).
                BoxWithConstraints(modifier = Modifier.weight(CarImageWeight).aspectRatio(CarImageAspectRatio)) {
                    Image(
                        painter = painterResource(R.drawable.bmw_gains_delay_car),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    SpeakerGain(dsp, NativeBmwDspValues.INDEX_MID_GAIN_L, yellow, maxWidth, maxHeight, MidLeftX, MidLeftY, MidDiameterFraction)
                    SpeakerGain(dsp, NativeBmwDspValues.INDEX_MID_GAIN_R, yellow, maxWidth, maxHeight, MidRightX, MidRightY, MidDiameterFraction)
                    SpeakerGain(dsp, NativeBmwDspValues.INDEX_LOW_GAIN_L, blue, maxWidth, maxHeight, LowLeftX, LowLeftY, LowDiameterFraction)
                    SpeakerGain(dsp, NativeBmwDspValues.INDEX_LOW_GAIN_R, blue, maxWidth, maxHeight, LowRightX, LowRightY, LowDiameterFraction)
                }

                Column(modifier = Modifier.weight(CardColumnWeight).padding(start = 4.dp)) {
                    GainsChannelCard(
                        dsp, linked,
                        title = "Right Mid", accent = yellow, stroke = yellow,
                        delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_R,
                        delaySibling = NativeBmwDspValues.INDEX_MID_DELAY_L,
                        output = NativeBmwDspValues.OUTPUT_MID_RIGHT,
                    )
                    Spacer(Modifier.height(6.dp))
                    GainsChannelCard(
                        dsp, linked,
                        title = "Right Low", accent = blue, stroke = mBlue,
                        delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_R,
                        delaySibling = NativeBmwDspValues.INDEX_LOW_DELAY_L,
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
    delayIndex: Int,
    delaySibling: Int,
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
        modifier = Modifier.fillMaxWidth(),
        linkChecked = if (withLinkToggle) linked else null,
        onLinkChange = if (withLinkToggle) {
            { on: Boolean -> dsp.commit(NativeBmwDspValues.INDEX_DELAY_LINKED, if (on) 1f else 0f) }
        } else {
            null
        },
    )
}

/** One [ArcGainSlider], sized/positioned as fractions of the car image's own measured box so it
 *  stays locked onto its speaker at any screen size. [xFraction]/[yFraction] are the speaker's
 *  centre, [diameterFraction] the arc's own outer size -- all fractions of [boxWidth] (a circle
 *  scales safely off either axis once the box's aspect ratio is locked to the source art, but
 *  every measurement below was taken as a fraction of the image's pixel width, so width is what
 *  they're applied to here). */
@Composable
private fun SpeakerGain(
    dsp: BmwDspState,
    gainIndex: Int,
    accent: Color,
    boxWidth: Dp,
    boxHeight: Dp,
    xFraction: Float,
    yFraction: Float,
    diameterFraction: Float,
) {
    val diameter = boxWidth * diameterFraction
    ArcGainSlider(
        value = dsp.get(gainIndex),
        range = GainRange,
        step = GainStep,
        accent = accent,
        onPreview = { dsp.preview(gainIndex, it) },
        onCommit = { dsp.commit(gainIndex, it) },
        modifier = Modifier
            .size(diameter)
            .offset(x = boxWidth * xFraction - diameter / 2, y = boxHeight * yFraction - diameter / 2),
    )
}

// Weights 1:1 with CrossoverDashboardBuilder's CHANNEL_CARD_WIDTH_DP (264) / CAR_DIAGRAM_WIDTH_DP (612).
private const val CardColumnWeight = 264f
private const val CarImageWeight = 612f
private val DelayRange = 0f..2.8f
private val GainRange = -3f..3f
private const val GainStep = 0.5f

// bmw_gains_delay_car.png is a fixed 2340x878 source image; every constant below is that bitmap's
// pixel geometry expressed as a fraction of its own width/height, measured directly off the art
// (speaker centres + full outer-rim diameter). The arc itself is drawn larger than the raw
// speaker so it wraps with a visible gap, same as the reference mock -- see ARC_WRAP_SCALE.
private const val CarImageAspectRatio = 2340f / 878f
private const val ARC_WRAP_SCALE = 1.35f
private const val MidLeftX = 811f / 2340f
private const val MidLeftY = 276f / 878f
private const val MidRightX = 1825f / 2340f
private const val MidRightY = 290f / 878f
private const val LowLeftX = 972f / 2340f
private const val LowLeftY = 746f / 878f
private const val LowRightX = 1657f / 2340f
private const val LowRightY = 751f / 878f
private const val MidDiameterFraction = (190f / 2340f) * ARC_WRAP_SCALE
private const val LowDiameterFraction = (195f / 2340f) * ARC_WRAP_SCALE
