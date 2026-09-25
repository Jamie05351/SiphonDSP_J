package app.siphondsp.compose.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import app.siphondsp.compose.controls.ArtLabelSize
import app.siphondsp.compose.controls.ArtRow
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwDropdown
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.controls.BmwTitleDropdown
import app.siphondsp.compose.controls.WorkspaceArt
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.WorkspaceArtScope
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
private val NoMirror = IntArray(0)
private val CrossoverTypeOptions = listOf("BW2", "BW3", "LR4", "BW1 (6 dB/oct)", "BW4")

/**
 * Phase 5 of the 3-way crossover (docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md): the Crossovers
 * controls split into one swipe page per split point, each over the same full response graph --
 * [CrossoverLowMidPage] (Low lowpass, Mid highpass, Subsonic) and [CrossoverMidHighPage] (the
 * master 3-way switch in its header, the Mid/High corner, the linked Mid all-pass alignment and a
 * deep link to the full per-output All-pass screen). The graph mode is hoisted by the pager so
 * both pages keep the same MAG / PHASE / BOTH / DELAY choice.
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
    // Mid's type also shapes its upper (Mid/High) lowpass natively, so High's highpass follows
    // it -- see ThreeWayCrossover.typeMirrors.
    val onMidType: (Int) -> Unit = {
        dsp.commit(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
            it.toFloat(),
            midPair(NativeBmwDspValues.FIELD_CROSSOVER_TYPE) + ThreeWayCrossover.typeMirrors,
        )
    }
    val subsonicFreqMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_FREQ)
    val subsonicEnMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)

    if (LocalContext.current.isHeadUnitDisplay()) {
        HeadUnitCrossoverPage(dsp, graphMode, onGraphModeChange, artDp(188, 392, 600, 52), modifier) {
            DspArtSlider(
                dsp, LowLowpassLabel, NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f..320f, 1f, "Hz", lowSlider,
                Modifier.artRect(artDp(835, 72, 415, 65)), labelAbove = true,
                mirrors = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ), valueWidth = 84.dp,
            )
            SlopeRow(lowCrossoverType, onLowType, Modifier.artRect(artDp(835, 140, 185, 40)))
            DspArtSlider(
                dsp, MidHighpassLabel, NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 80f..320f, 1f, "Hz", midSlider,
                Modifier.artRect(artDp(835, 188, 415, 65)), labelAbove = true,
                mirrors = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ), valueWidth = 84.dp,
            )
            SlopeRow(midCrossoverType, onMidType, Modifier.artRect(artDp(835, 268, 185, 40)))
            DspArtSlider(
                dsp, subsonicLabel, NativeBmwDspValues.INDEX_SUBSONIC_FREQ, 20f..60f, 1f, "Hz", DefaultAccent,
                Modifier.artRect(artDp(835, 316, 415, 64)), labelAbove = true,
                mirrors = subsonicFreqMirror, valueWidth = 84.dp,
            )
            ArtSwitchRow(
                label = "Subsonic",
                checked = dsp.isOn(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED),
                onCheckedChange = { on ->
                    dsp.commit(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED, if (on) 1f else 0f, subsonicEnMirror)
                },
                modifier = Modifier.artRect(artDp(835, 392, 250, 36)),
            )
        }
        return
    }

    CrossoverPage(
        title = "Low / Mid",
        dsp = dsp,
        graphMode = graphMode,
        onGraphModeChange = onGraphModeChange,
        sliderLabels = listOf(LowLowpassLabel, MidHighpassLabel, subsonicLabel),
        modifier = modifier,
    ) {
        // The slope picker lives in the row's title box (a dropdown) rather than a segmented
        // control on its own line, so it costs no extra height.
        DspSliderRow(
            LowLowpassLabel, NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f..320f, 1f, "Hz",
            dsp, lowSlider, mirrors = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
            titleDropdown = BmwTitleDropdown(CrossoverTypeOptions, lowCrossoverType, onLowType),
        )
        DspSliderRow(
            MidHighpassLabel, NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 80f..320f, 1f, "Hz",
            dsp, midSlider, mirrors = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
            titleDropdown = BmwTitleDropdown(CrossoverTypeOptions, midCrossoverType, onMidType),
        )

        BmwSliderRow(
            label = subsonicLabel,
            value = dsp.get(NativeBmwDspValues.INDEX_SUBSONIC_FREQ),
            valueRange = 20f..60f, step = 1f, unit = "Hz",
            accentColor = DefaultAccent,
            onPreview = { dsp.preview(NativeBmwDspValues.INDEX_SUBSONIC_FREQ, it, subsonicFreqMirror) },
            onCommit = { dsp.commit(NativeBmwDspValues.INDEX_SUBSONIC_FREQ, it, subsonicFreqMirror) },
            onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_SUBSONIC_FREQ, it, subsonicFreqMirror) },
            toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED),
            onToggleChange = { on ->
                dsp.commit(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED, if (on) 1f else 0f, subsonicEnMirror)
            },
        )
    }
}

/** See [CrossoverLowMidPage]. The header switch is the master 3-way on/off: off is
 *  bit-identical to the old 2-way crossover (see [ThreeWayCrossover]). */
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

    if (LocalContext.current.isHeadUnitDisplay()) {
        HeadUnitCrossoverPage(dsp, graphMode, onGraphModeChange, artDp(190, 400, 600, 40), modifier) {
            ArtSwitchRow(
                label = "3-way",
                checked = ThreeWayCrossover.isEnabled(dsp.values),
                onCheckedChange = { on -> dsp.commitAll(ThreeWayCrossover.updates(dsp.values, on)) },
                modifier = Modifier.artRect(artDp(835, 105, 250, 36)),
            )
            DspArtSlider(
                dsp, MidHighLabel, ThreeWayCrossover.cornerIndex, 1000f..8000f, 10f, "Hz", highSlider,
                Modifier.artRect(artDp(835, 165, 415, 65)), labelAbove = true,
                mirrors = ThreeWayCrossover.cornerMirrors, valueWidth = 90.dp,
            )
            ArtSwitchRow(
                label = "Mid align",
                checked = dsp.isOn(midLeftBase),
                onCheckedChange = { on -> dsp.commit(midLeftBase, if (on) 1f else 0f, midAlignEnMirror) },
                modifier = Modifier.artRect(artDp(835, 265, 250, 36)),
            )
            DspArtSlider(
                dsp, MidAlignLabel, midLeftBase + 2, 20f..1000f, 1f, "Hz", midSlider,
                Modifier.artRect(artDp(835, 325, 415, 65)), labelAbove = true,
                mirrors = midAlignFreqMirror, valueWidth = 90.dp,
            )
        }
        return
    }

    CrossoverPage(
        title = "3-way Mid / High",
        dsp = dsp,
        graphMode = graphMode,
        onGraphModeChange = onGraphModeChange,
        sliderLabels = listOf(MidHighLabel, MidAlignLabel),
        toggleChecked = ThreeWayCrossover.isEnabled(dsp.values),
        onToggleChange = { on -> dsp.commitAll(ThreeWayCrossover.updates(dsp.values, on)) },
        modifier = modifier,
    ) {
        BmwSliderRow(
            label = MidHighLabel,
            value = dsp.get(ThreeWayCrossover.cornerIndex),
            valueRange = 1000f..8000f, step = 10f, unit = "Hz",
            accentColor = highSlider,
            onPreview = { dsp.preview(ThreeWayCrossover.cornerIndex, it, ThreeWayCrossover.cornerMirrors) },
            onCommit = { dsp.commit(ThreeWayCrossover.cornerIndex, it, ThreeWayCrossover.cornerMirrors) },
            onValueEntered = { dsp.commit(ThreeWayCrossover.cornerIndex, it, ThreeWayCrossover.cornerMirrors) },
        )

        BmwSliderRow(
            label = MidAlignLabel,
            value = dsp.get(midLeftBase + 2),
            valueRange = 20f..1000f, step = 1f, unit = "Hz",
            accentColor = midSlider,
            onPreview = { dsp.preview(midLeftBase + 2, it, midAlignFreqMirror) },
            onCommit = { dsp.commit(midLeftBase + 2, it, midAlignFreqMirror) },
            onValueEntered = { dsp.commit(midLeftBase + 2, it, midAlignFreqMirror) },
            toggleChecked = dsp.isOn(midLeftBase),
            onToggleChange = { on -> dsp.commit(midLeftBase, if (on) 1f else 0f, midAlignEnMirror) },
        )

        Text(
            text = "Open full All-pass \u203a",
            color = Color(BmwDashboardSkin.LIGHT_BLUE),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
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

/** A Crossovers page: a titled [BmwPanel] (optionally with a header switch) holding the graph
 *  mode picker and the full response graph, then that page's [rows]. Scrolls if a screen is too
 *  short for everything. */
@Composable
private fun CrossoverPage(
    title: String,
    dsp: BmwDspState,
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    sliderLabels: List<String>,
    modifier: Modifier,
    toggleChecked: Boolean? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
    rows: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val peqState = remember { BmwPeqState.load(context) }

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            BmwPanel(
                title = title,
                modifier = Modifier.fillMaxWidth(),
                toggleChecked = toggleChecked,
                onToggleChange = onToggleChange,
                leanStart = 20.dp,
                leanEnd = 20.dp,
                topContentGap = 4.dp,
                sliderLabels = sliderLabels,
            ) {
                BmwSegmentedControl(
                    options = listOf("MAG", "PHASE", "BOTH", "DELAY"),
                    selectedIndex = graphMode.ordinal,
                    onSelect = { onGraphModeChange(CrossoverGraphMode.entries[it]) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    segmentGap = 4.dp,
                )
                CrossoverResponseGraph(
                    mode = graphMode,
                    systemValues = dsp.values,
                    peqState = peqState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(164.dp)
                        .padding(bottom = 4.dp),
                )
                rows()
            }
        }
    }
}

/** Head unit: the graph on the left with its mode picker under it, the page's [controls] placed
 *  in a column on the right (REW/_UI/submenu_layout_editor.html) -- no scrolling. */
@Composable
private fun HeadUnitCrossoverPage(
    dsp: BmwDspState,
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modeRect: WorkspaceArt.Frac,
    modifier: Modifier,
    controls: @Composable WorkspaceArtScope.() -> Unit,
) {
    val context = LocalContext.current
    val peqState = remember { BmwPeqState.load(context) }

    BmwDspTheme {
        WorkspaceArtBox(modifier.fillMaxSize()) {
            CrossoverResponseGraph(
                mode = graphMode,
                systemValues = dsp.values,
                peqState = peqState,
                modifier = Modifier.artRect(artDp(168, 76, 635, 305)),
            )
            Box(Modifier.artRect(modeRect), contentAlignment = Alignment.Center) {
                BmwSegmentedControl(
                    options = listOf("MAG", "PHASE", "BOTH", "DELAY"),
                    selectedIndex = graphMode.ordinal,
                    onSelect = { onGraphModeChange(CrossoverGraphMode.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                    segmentHeight = 34.dp,
                    segmentGap = 4.dp,
                )
            }
            controls()
        }
    }
}

/** "Slope" beside the crossover type dropdown. */
@Composable
private fun SlopeRow(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    ArtRow("Slope", modifier, labelWidth = 70.dp) {
        BmwDropdown(
            CrossoverTypeOptions, selected, onSelect,
            Modifier.weight(1f), textSize = ArtLabelSize, minHeight = 40.dp,
        )
    }
}

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
private const val MidHighLabel = "Mid/High corner"
private const val MidAlignLabel = "Mid align (all-pass)"

@Composable
private fun DspSliderRow(
    label: String,
    index: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    dsp: BmwDspState,
    accent: Color,
    mirrors: IntArray = NoMirror,
    titleDropdown: BmwTitleDropdown? = null,
) {
    BmwSliderRow(
        label = label,
        value = dsp.get(index),
        valueRange = range,
        step = step,
        unit = unit,
        accentColor = accent,
        onPreview = { dsp.preview(index, it, mirrors) },
        onCommit = { dsp.commit(index, it, mirrors) },
        onValueEntered = { dsp.commit(index, it, mirrors) },
        titleDropdown = titleDropdown,
    )
}
