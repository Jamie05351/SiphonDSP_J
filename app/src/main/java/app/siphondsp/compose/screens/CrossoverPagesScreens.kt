package app.siphondsp.compose.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabelSize
import app.siphondsp.compose.controls.ArtRow
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwDropdown
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ThreeWayCrossover
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay

private val DefaultAccent = Color(BmwDashboardSkin.SLIDER_DEFAULT_COLOR)
private val CrossoverTypeOptions = listOf("BW2", "BW3", "LR4", "BW1 (6 dB/oct)", "BW4")

/**
 * Where one row of a page's controls goes: [y] down the head unit's 1280x480 editor space (its
 * position there) and [h] tall in dp. The head unit places the row at that spot in its controls
 * column; the phone stacks the rows in order and uses only [h].
 */
private typealias ControlRow = (y: Int, h: Int) -> Modifier

/**
 * Phase 5 of the 3-way crossover (docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md): the Crossovers
 * controls split into one swipe page per split point, each beside the same full response graph --
 * [CrossoverLowMidPage] (Low lowpass, Mid highpass, Subsonic) and [CrossoverMidHighPage] (the
 * master 3-way switch, Mid lowpass, High highpass + slope, the linked Mid all-pass alignment
 * and, on the phone, a deep link to the full per-output All-pass screen) -- then [CrossoverTiltPage]. The graph mode is
 * hoisted by the pager so all three pages keep the same MAG / PHASE / BOTH / DELAY choice.
 *
 * Both screens lay a page out the same way (Figma "SiphonDSP Front Panel (from code)", Steppers
 * page): the graph and its mode picker on the left, a [ControlColumnWidth] column of label +
 * value box + −/+ rows on the right, no scrolling. Each page lists its controls once, against a
 * [ControlRow] that the head unit ([HeadUnitCrossoverPage]) or the phone ([PhoneCrossoverPage])
 * turns into a position.
 */
@Composable
fun CrossoverLowMidPage(
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dsp = rememberBmwDspState()
    val subsonicLabel = stringResource(R.string.bmw_dsp_subsonic_freq)
    val lowSlider = Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR)
    val midSlider = Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR)

    val lowCrossoverType = dsp.get(
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
    ).toInt().coerceIn(0, 4)
    val midCrossoverType = dsp.get(
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
    ).toInt().coerceIn(0, 4)
    val onLowType: (Int) -> Unit = {
        dsp.commit(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
            it.toFloat(),
            lowPair(NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
        )
    }
    // Mid's type also shapes its upper (Mid/High) lowpass natively; High's highpass has its own
    // slope on the Mid/High page.
    val onMidType: (Int) -> Unit = {
        dsp.commit(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
            it.toFloat(),
            midPair(NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
        )
    }
    val subsonicFreqMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_FREQ)
    val subsonicEnMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)

    val controls: @Composable (ControlRow) -> Unit = { row ->
        DspArtSlider(
            dsp, LowLowpassLabel, NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f..320f, 1f, "Hz", lowSlider,
            row(80, 48), mirrors = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
        )
        SlopeRow(lowCrossoverType, onLowType, row(136, 40))
        DspArtSlider(
            dsp, MidHighpassLabel, NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 80f..320f, 1f, "Hz", midSlider,
            row(196, 48), mirrors = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
        )
        SlopeRow(midCrossoverType, onMidType, row(252, 40))
        DspArtSlider(
            dsp, subsonicLabel, NativeBmwDspValues.INDEX_SUBSONIC_FREQ, 20f..60f, 1f, "Hz", DefaultAccent,
            row(312, 48), mirrors = subsonicFreqMirror,
        )
        ArtSwitchRow(
            label = "Subsonic",
            checked = dsp.isOn(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED),
            onCheckedChange = { on ->
                dsp.commit(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED, if (on) 1f else 0f, subsonicEnMirror)
            },
            modifier = row(372, 36),
            labelWidth = ControlLabelWidth,
        )
    }

    CrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls)
}

/** See [CrossoverLowMidPage]. The 3-way switch is the master 3-way on/off: off is bit-identical
 *  to the old 2-way crossover (see [ThreeWayCrossover]). */
@Composable
fun CrossoverMidHighPage(
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dsp = rememberBmwDspState()
    val context = LocalContext.current
    val midSlider = Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR)
    val highSlider = Color(BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR)

    // Section-1 Mid all-pass block for each Mid output: the frequency handle aligns the Mid
    // branch's phase through the handoff; L drives, R mirrors.
    val midLeftBase = NativeBmwDspValues.INDEX_ALL_PASS +
        (NativeBmwDspValues.OUTPUT_MID_LEFT * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) *
        NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
    val midRightBase = NativeBmwDspValues.INDEX_ALL_PASS +
        (NativeBmwDspValues.OUTPUT_MID_RIGHT * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) *
        NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
    val midAlignFreqMirror = intArrayOf(midRightBase + 2)
    val midAlignEnMirror = intArrayOf(midRightBase)
    val highCrossoverType = dsp.get(ThreeWayCrossover.highTypeIndex).toInt().coerceIn(0, 4)
    val onHighType: (Int) -> Unit = {
        dsp.commit(ThreeWayCrossover.highTypeIndex, it.toFloat(), ThreeWayCrossover.highTypeMirrors)
    }

    val controls: @Composable (ControlRow) -> Unit = { row ->
        // Each switch sits directly over the values it enables: 3-way over the Mid lowpass and
        // High highpass, Mid align over its frequency. Mid's lowpass uses Mid's slope (Low/Mid
        // page); High's highpass has its own.
        ArtSwitchRow(
            label = "3-way",
            checked = ThreeWayCrossover.isEnabled(dsp.values),
            onCheckedChange = { on -> dsp.commitAll(ThreeWayCrossover.updates(dsp.values, on)) },
            modifier = row(80, 36),
            labelWidth = ControlLabelWidth,
        )
        DspArtSlider(
            dsp, MidLowpassLabel, ThreeWayCrossover.midLowpassIndex, 300f..8000f, 10f, "Hz", midSlider,
            row(124, 48), mirrors = ThreeWayCrossover.midLowpassMirrors,
        )
        DspArtSlider(
            dsp, HighHighpassLabel, ThreeWayCrossover.highHighpassIndex, 1000f..8000f, 10f, "Hz", highSlider,
            row(180, 48), mirrors = ThreeWayCrossover.highHighpassMirrors,
        )
        SlopeRow(highCrossoverType, onHighType, row(236, 40))
        ArtSwitchRow(
            label = "Mid align",
            checked = dsp.isOn(midLeftBase),
            onCheckedChange = { on -> dsp.commit(midLeftBase, if (on) 1f else 0f, midAlignEnMirror) },
            modifier = row(288, 36),
            labelWidth = ControlLabelWidth,
        )
        // "Mid align (all-pass)" is too long for the label column, so it sits over the stepper.
        DspArtSlider(
            dsp, MidAlignLabel, midLeftBase + 2, 20f..1000f, 1f, "Hz", midSlider,
            row(328, 76), labelAbove = true, mirrors = midAlignFreqMirror,
        )
    }

    CrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls) {
        // Phone only: the head unit reaches the full All-pass screen from its sidebar.
        Text(
            text = "Open full All-pass ›",
            color = Color(BmwDashboardSkin.LIGHT_BLUE),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable {
                    context.startActivity(
                        Intent(context, CrossoverTiltActivity::class.java).putExtra(
                            CrossoverTiltActivity.EXTRA_WORKSPACE_MODE,
                            CrossoverTiltActivity.MODE_ALLPASS,
                        ),
                    )
                },
        )
    }
}

/**
 * The third Crossovers page: Tonality Tilt, beside the same response graph as the other two (it
 * already includes the tilt, so Amount and Pivot show on it as they change). The section switch
 * sits in the column's header; Amount and Pivot stay adjustable while it is off, so they can be
 * set before it is switched on.
 */
@Composable
fun CrossoverTiltPage(
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dsp = rememberBmwDspState()
    val tiltColor = Color(BmwDashboardSkin.SLIDER_TILT_COLOR)
    val title = stringResource(R.string.bmw_dsp_tilt_section)
    val amount = stringResource(R.string.bmw_dsp_tilt_amount)
    val pivot = stringResource(R.string.bmw_dsp_tilt_pivot)

    val controls: @Composable (ControlRow) -> Unit = { row ->
        ArtGroupHeader(
            title = title,
            accent = tiltColor,
            modifier = row(80, 40),
            checked = dsp.isOn(NativeBmwDspValues.INDEX_TILT_ENABLED),
            onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_TILT_ENABLED, if (on) 1f else 0f) },
        )
        DspArtSlider(dsp, amount, NativeBmwDspValues.INDEX_TILT_AMOUNT, -6f..6f, 0.1f, "dB", tiltColor, row(132, 48))
        DspArtSlider(dsp, pivot, NativeBmwDspValues.INDEX_TILT_FREQ, 200f..2000f, 1f, "Hz", tiltColor, row(192, 48))
    }

    CrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls)
}

/** A Crossovers page on either screen; [phoneExtra] is shown under the phone's controls. */
@Composable
private fun CrossoverPage(
    dsp: BmwDspState,
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier,
    controls: @Composable (ControlRow) -> Unit,
    phoneExtra: @Composable () -> Unit = {},
) {
    if (LocalContext.current.isHeadUnitDisplay()) {
        HeadUnitCrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls)
    } else {
        PhoneCrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls, phoneExtra)
    }
}

/** Head unit: the graph and its mode picker on the left, [controls] placed at their editor
 *  positions in the controls column on the right. The steppers are narrower than the sliders
 *  they replaced, so the graph takes the room they freed. */
@Composable
private fun HeadUnitCrossoverPage(
    dsp: BmwDspState,
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier,
    controls: @Composable (ControlRow) -> Unit,
) {
    val context = LocalContext.current
    val peqState = remember { BmwPeqState.load(context) }

    BmwDspTheme {
        WorkspaceArtBox(modifier.fillMaxSize()) {
            CrossoverResponseGraph(
                mode = graphMode,
                systemValues = dsp.values,
                peqState = peqState,
                modifier = Modifier.artRect(artDp(GraphX, 76, GraphWidth, 305)),
            )
            Box(Modifier.artRect(artDp(GraphX, 388, GraphWidth, 42)), contentAlignment = Alignment.Center) {
                GraphModePicker(graphMode, onGraphModeChange)
            }
            controls { y, h -> Modifier.artRect(artDp(ControlX, y, ControlColumnWidth, h)) }
        }
    }
}

/** Phone: the same arrangement in the phone's workspace -- the graph filling the left, its mode
 *  picker under it, and [controls] stacked in the controls column on the right (which scrolls
 *  only if a small phone is too short for them). */
@Composable
private fun PhoneCrossoverPage(
    dsp: BmwDspState,
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier,
    controls: @Composable (ControlRow) -> Unit,
    extra: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val peqState = remember { BmwPeqState.load(context) }

    BmwDspTheme {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                CrossoverResponseGraph(
                    mode = graphMode,
                    systemValues = dsp.values,
                    peqState = peqState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Spacer(Modifier.height(8.dp))
                GraphModePicker(graphMode, onGraphModeChange)
            }
            Column(
                modifier = Modifier
                    .width(ControlColumnWidth.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                controls { _, h -> Modifier.fillMaxWidth().height(h.dp) }
                extra()
            }
        }
    }
}

@Composable
private fun GraphModePicker(graphMode: CrossoverGraphMode, onGraphModeChange: (CrossoverGraphMode) -> Unit) {
    BmwSegmentedControl(
        options = listOf("MAG", "PHASE", "BOTH", "DELAY"),
        selectedIndex = graphMode.ordinal,
        onSelect = { onGraphModeChange(CrossoverGraphMode.entries[it]) },
        modifier = Modifier.fillMaxWidth(),
        segmentHeight = 34.dp,
        segmentGap = 4.dp,
    )
}

/** "Slope" beside the crossover type dropdown, its label in the controls' label column. */
@Composable
private fun SlopeRow(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    ArtRow("Slope", modifier, labelWidth = ControlLabelWidth) {
        BmwDropdown(
            CrossoverTypeOptions, selected, onSelect,
            Modifier.weight(1f), textSize = ArtLabelSize, minHeight = 40.dp,
        )
    }
}

// Head-unit layout, in the 1280x480 editor's dp: the graph from x 168, then the controls column
// ending at x 1250 -- a label column, then a stepper (96dp value box, 6dp gap, 81dp -/+). The
// phone uses the same column width.
private const val GraphX = 168
private const val GraphWidth = 719
private const val ControlX = 917
private const val ControlColumnWidth = 333
private val ControlLabelWidth = 150.dp

private fun lowPair(field: Int) = intArrayOf(
    NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, field),
    NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, field),
)

private fun midPair(field: Int) = intArrayOf(
    NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, field),
    NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, field),
)

private const val LowLowpassLabel = "Low lowpass"
private const val MidHighpassLabel = "Mid highpass"
private const val MidLowpassLabel = "Mid lowpass"
private const val HighHighpassLabel = "High highpass"
private const val MidAlignLabel = "Mid align (all-pass)"
