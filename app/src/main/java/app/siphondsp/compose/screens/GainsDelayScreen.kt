package app.siphondsp.compose.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import app.siphondsp.model.ThreeWayCrossover
import app.siphondsp.view.BmwDashboardSkin

/**
 * One Gains & Delay page per crossover band (Phase 5 of the 3-way crossover,
 * docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md): a Left and a Right [BmwChannelCard] for that band,
 * the shared STAGE ALIGNMENT value under each, and the global STEREO LINK above the car.
 *
 * The car itself is no longer drawn here: each band has its own full-screen workspace backdrop
 * ([band]'s `backdrop`, swapped in by `GainLimiterFragment` as the pager moves) with the car and
 * that band's speakers + leader lines baked in. The cards sit at the height of those leader
 * lines, measured off the 2340x878 head-unit art at its 1280x480 render scale (~0.547 dp/px)
 * minus the 69 dp toolbar. Fixed-height, designed for the head unit, never scrolls.
 *
 * High only makes sound with the Crossovers page's 3-way switch on; with it off the High page
 * stays in the pager (so the page count never changes) but greyed out and inert, with a pointer
 * to where 3-way is turned on.
 */
enum class GainsBand(
    val title: String,
    val leftOutput: Int,
    val rightOutput: Int,
    val gainL: Int,
    val gainR: Int,
    val delayL: Int,
    val delayR: Int,
    val accent: Int,
    val stroke: Int,
    val slider: Int,
    val backdrop: Int,
    val backdropPhone: Int,
    /** Card top, content-relative, lined up with the art's leader lines. */
    val cardTop: Dp,
) {
    HIGH(
        "High", NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.OUTPUT_HIGH_RIGHT,
        NativeBmwDspValues.INDEX_HIGH_GAIN_L, NativeBmwDspValues.INDEX_HIGH_GAIN_R,
        NativeBmwDspValues.INDEX_HIGH_DELAY_L, NativeBmwDspValues.INDEX_HIGH_DELAY_R,
        BmwDashboardSkin.HIGH_BAND_PINK, BmwDashboardSkin.HIGH_BAND_PINK, BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR,
        R.drawable.dsp_workspace_backdrop_gains_tweeter, R.drawable.dsp_workspace_backdrop_gains_tweeter_phone,
        cardTop = 36.dp,
    ),
    MID(
        "Mid", NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT,
        NativeBmwDspValues.INDEX_MID_GAIN_L, NativeBmwDspValues.INDEX_MID_GAIN_R,
        NativeBmwDspValues.INDEX_MID_DELAY_L, NativeBmwDspValues.INDEX_MID_DELAY_R,
        BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
        R.drawable.dsp_workspace_backdrop_gains_mid, R.drawable.dsp_workspace_backdrop_gains_mid_phone,
        cardTop = 102.dp,
    ),
    LOW(
        "Low", NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.OUTPUT_LOW_RIGHT,
        NativeBmwDspValues.INDEX_LOW_GAIN_L, NativeBmwDspValues.INDEX_LOW_GAIN_R,
        NativeBmwDspValues.INDEX_LOW_DELAY_L, NativeBmwDspValues.INDEX_LOW_DELAY_R,
        BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.M_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR,
        R.drawable.dsp_workspace_backdrop_gains_woofer, R.drawable.dsp_workspace_backdrop_gains_woofer_phone,
        cardTop = 192.dp,
    );

    /** High's per-output config lives in the schema tail, not the legacy 4-output block. */
    fun polarityIndex(output: Int): Int =
        if (this == HIGH) NativeBmwDspValues.highOutputIndex(output, NativeBmwDspValues.FIELD_INVERT)
        else NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)
}

@Composable
fun GainsDelayScreen(band: GainsBand, modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val linked = dsp.isOn(NativeBmwDspValues.INDEX_DELAY_LINKED)
    val inactive = band == GainsBand.HIGH && !ThreeWayCrossover.isEnabled(dsp.values)
    val stageAccent = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)

    BmwDspTheme {
        Box(modifier = modifier.fillMaxSize()) {
            BandColumn(
                inactive,
                Modifier.align(Alignment.TopStart).padding(start = SideInset, top = band.cardTop),
            ) {
                GainsChannelCard(
                    dsp, linked, band,
                    title = "Left ${band.title}", output = band.leftOutput,
                    delayIndex = band.delayL, delaySibling = band.delayR, gainIndex = band.gainL,
                )
                Spacer(Modifier.height(CardStageGap))
                StageDelayControl(dsp, NativeBmwDspValues.INDEX_STAGE_DELAY_L, stageAccent, mirrored = false)
            }
            BandColumn(
                inactive,
                Modifier.align(Alignment.TopEnd).padding(end = SideInset, top = band.cardTop),
            ) {
                GainsChannelCard(
                    dsp, linked, band,
                    title = "Right ${band.title}", output = band.rightOutput,
                    delayIndex = band.delayR, delaySibling = band.delayL, gainIndex = band.gainR,
                    mirrored = true,
                )
                Spacer(Modifier.height(CardStageGap))
                StageDelayControl(dsp, NativeBmwDspValues.INDEX_STAGE_DELAY_R, stageAccent, mirrored = true)
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
            if (inactive) {
                Text(
                    text = "3-way is off: turn it on in Crossovers",
                    color = Color(band.accent),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
                )
            }
        }
    }
}

/** A side column of the band page. When [inactive] it's dimmed, swallows taps (a swipe still
 *  reaches the pager, since a click detector doesn't consume drags) and can't take D-pad/rotary
 *  focus. */
@Composable
private fun BandColumn(
    inactive: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.width(ColumnWidth)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (inactive) Modifier.alpha(InactiveAlpha) else Modifier)
                .focusProperties { onEnter = { if (inactive) cancelFocusChange() } }
                .focusGroup(),
        ) { content() }
        if (inactive) {
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            )
        }
    }
}

@Composable
private fun StageDelayControl(
    dsp: BmwDspState,
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
                text = StageLabel,
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
                    label = StageLabel,
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
    band: GainsBand,
    title: String,
    output: Int,
    delayIndex: Int,
    delaySibling: Int,
    gainIndex: Int,
    mirrored: Boolean = false,
) {
    val polarityIndex = band.polarityIndex(output)
    val delayMirror = if (linked) intArrayOf(delaySibling) else IntArray(0)

    BmwChannelCard(
        title = title,
        accentColor = Color(band.accent),
        strokeColor = Color(band.stroke),
        delayValue = dsp.get(delayIndex),
        delayRange = DelayRange,
        onDelayCommit = { dsp.commit(delayIndex, it, delayMirror) },
        polarityInverted = dsp.isOn(polarityIndex),
        onPolarityChange = { dsp.commit(polarityIndex, if (it) 1f else 0f) },
        gainValue = dsp.get(gainIndex),
        gainRange = GainRange,
        gainStep = GainStep,
        gainSliderAccent = Color(band.slider),
        onGainPreview = { dsp.preview(gainIndex, it) },
        onGainCommit = { dsp.commit(gainIndex, it) },
        modifier = Modifier.fillMaxWidth(),
        mirrored = mirrored,
    )
}

private const val StageLabel = "STAGE ALIGNMENT"
private const val InactiveAlpha = 0.35f
// BmwChannelCard's narrowest usable width (padding + DELAY label + value box + POL switch). The
// Mid art's right leader line reaches ~47 dp under the Right card at this width; the free space
// right of the car there is only ~245 dp -- needs a head-unit look.
private val ColumnWidth = 292.dp
private val SideInset = 16.dp
private val CardStageGap = 8.dp
private val StageTimingHeight = 42.dp
private val StageValueWidth = 82.dp
private val DelayFormat = java.text.DecimalFormat(
    "0.##",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
private val DelayRange = 0f..2.8f
private val GainRange = -6f..6f
private const val GainStep = 0.5f
