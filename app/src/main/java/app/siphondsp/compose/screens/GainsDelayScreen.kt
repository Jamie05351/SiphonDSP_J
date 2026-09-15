package app.siphondsp.compose.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwChannelCard
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.controls.BoxedValue
import app.siphondsp.compose.controls.showBmwNumberInput
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin

/**
 * Phase 6 of COMPOSE_MIGRATION_ROADMAP.md -- ports `GainLimiterFragment`'s car-diagram page:
 * four [BmwChannelCard]s (Mid/Low x L/R) in symmetrical columns around the
 * `bmw_gains_delay_car` image. The global delay link sits above the bonnet, outside the channel
 * cards. The fixed-height arrangement is designed for the 1280x480 head unit and never scrolls.
 *
 * The Output page ([HeadroomOutputScreen]) and the bus-limiter page ([CompressorDriverPage],
 * moved here from the compressor pager) are the other two pages of this workspace; a Compose
 * `HorizontalPager` (see `GainLimiterFragment`) hosts all three.
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
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.bmw_gains_delay_car),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(CarAspectRatio),
                contentScale = ContentScale.Fit,
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = ControlTopInset,
                    bottom = 5.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.width(SideColumnWidth).fillMaxHeight(),
                ) {
                    GainsChannelCard(
                        dsp, linked,
                        title = "Left Mid", accent = yellow, stroke = yellow, sliderAccent = midSlider,
                        delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_L,
                        delaySibling = NativeBmwDspValues.INDEX_MID_DELAY_R,
                        gainIndex = NativeBmwDspValues.INDEX_MID_GAIN_L,
                        output = NativeBmwDspValues.OUTPUT_MID_LEFT,
                    )
                    StageDelayControl(
                        dsp = dsp,
                        label = "STAGE ALIGNMENT",
                        index = NativeBmwDspValues.INDEX_STAGE_DELAY_L,
                        accent = stageAccent,
                        mirrored = false,
                    )
                    GainsChannelCard(
                        dsp, linked,
                        title = "Left Low", accent = blue, stroke = mBlue, sliderAccent = lowSlider,
                        delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_L,
                        delaySibling = NativeBmwDspValues.INDEX_LOW_DELAY_R,
                        gainIndex = NativeBmwDspValues.INDEX_LOW_GAIN_L,
                        output = NativeBmwDspValues.OUTPUT_LOW_LEFT,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(
                    modifier = Modifier.width(SideColumnWidth).fillMaxHeight(),
                ) {
                    GainsChannelCard(
                        dsp, linked,
                        title = "Right Mid", accent = yellow, stroke = yellow, sliderAccent = midSlider,
                        delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_R,
                        delaySibling = NativeBmwDspValues.INDEX_MID_DELAY_L,
                        gainIndex = NativeBmwDspValues.INDEX_MID_GAIN_R,
                        output = NativeBmwDspValues.OUTPUT_MID_RIGHT,
                        mirrored = true,
                    )
                    StageDelayControl(
                        dsp = dsp,
                        label = "STAGE ALIGNMENT",
                        index = NativeBmwDspValues.INDEX_STAGE_DELAY_R,
                        accent = stageAccent,
                        mirrored = true,
                    )
                    GainsChannelCard(
                        dsp, linked,
                        title = "Right Low", accent = blue, stroke = mBlue, sliderAccent = lowSlider,
                        delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_R,
                        delaySibling = NativeBmwDspValues.INDEX_LOW_DELAY_L,
                        gainIndex = NativeBmwDspValues.INDEX_LOW_GAIN_R,
                        output = NativeBmwDspValues.OUTPUT_LOW_RIGHT,
                        mirrored = true,
                    )
                }
            }
            Row(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "STEREO LINK",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp),
                )
                BmwSwitch(
                    checked = linked,
                    onCheckedChange = {
                        dsp.commit(NativeBmwDspValues.INDEX_DELAY_LINKED, if (it) 1f else 0f)
                    },
                    contentDescription = "Link left and right speaker delay",
                )
            }
        }
    }
}

@Composable
private fun StageDelayControl(
    dsp: BmwDspState,
    label: String,
    index: Int,
    accent: Color,
    mirrored: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(StageTimingHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title: @Composable () -> Unit = {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val value: @Composable () -> Unit = { StageDelayValue(dsp, index, accent) }
        if (mirrored) {
            value()
            Spacer(Modifier.weight(1f))
            title()
        } else {
            title()
            Spacer(Modifier.weight(1f))
            value()
        }
    }
}

@Composable
private fun StageDelayValue(dsp: BmwDspState, index: Int, accent: Color) {
    val context = LocalContext.current
    BoxedValue(
        text = DelayFormat.format(dsp.get(index)),
        unit = "ms",
        accentColor = accent,
        modifier = Modifier
            .width(StageValueWidth)
            .height(32.dp)
            .clickable {
                context.showBmwNumberInput(
                    label = "STAGE ALIGNMENT",
                    min = 0f,
                    max = NativeBmwDspValues.STAGE_DELAY_MAX_MS,
                    current = dsp.get(index),
                    step = 0.05f,
                    suffix = "ms",
                ) { dsp.commit(index, it) }
            },
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
    mirrored: Boolean = false,
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
        mirrored = mirrored,
    )
}

private val SideColumnWidth = 300.dp
private const val CarAspectRatio = 1080f / 404f
private val ControlTopInset = 28.dp
private val StageTimingHeight = 42.dp
private val StageValueWidth = 82.dp
private val DelayFormat = java.text.DecimalFormat(
    "0.##",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
private val DelayRange = 0f..2.8f
private val GainRange = -6f..0f
private const val GainStep = 0.5f
