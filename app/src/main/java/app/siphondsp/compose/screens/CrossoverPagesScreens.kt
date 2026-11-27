package app.siphondsp.compose.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.DspColors
import app.siphondsp.compose.controls.ArtLabelSize
import app.siphondsp.compose.controls.ArtRow
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwDropdown
import app.siphondsp.compose.controls.BmwSwitch
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
 * master 3-way switch, Mid lowpass, High highpass + slope) -- then [CrossoverTiltPage]. The graph mode is
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
    onSplit: ((Int) -> Unit)? = null,
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
    // Mid's highpass slope only; its lowpass and High's highpass have their own on the Mid/High
    // page.
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
        // The band split itself, like every other stage's section switch. Off removes it entirely
        // (INDEX_CROSSOVER_BYPASS): one full-range path, no filters, all-pass, band delays, gains
        // or polarity, and no added latency -- for measuring. Covers the 3-way Mid/High split too.
        // The settings below stay adjustable while it is off, and come back exactly as set.
        ArtGroupHeader(
            title = "Crossover",
            accent = DspColors.Xover,
            modifier = row(80, 40),
            checked = !dsp.isOn(NativeBmwDspValues.INDEX_CROSSOVER_BYPASS),
            onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_CROSSOVER_BYPASS, if (on) 0f else 1f) },
        )
        DspArtSlider(
            dsp, LowLowpassLabel, NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f..320f, 1f, "Hz", lowSlider,
            row(124, 48), mirrors = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
        )
        SlopeRow(lowCrossoverType, onLowType, row(176, 40))
        DspArtSlider(
            dsp, MidHighpassLabel, NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 60f..320f, 1f, "Hz", midSlider,
            row(220, 48), mirrors = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
        )
        SlopeRow(midCrossoverType, onMidType, row(272, 40))
        DspArtSlider(
            dsp, subsonicLabel, NativeBmwDspValues.INDEX_SUBSONIC_FREQ, 20f..60f, 1f, "Hz", DefaultAccent,
            row(324, 48), mirrors = subsonicFreqMirror,
        )
        // On/off and the slope in one row: LR4 (24 dB/oct, the original) or BW2 (12 dB/oct, half
        // the phase turn through the bass, a little less excursion protection).
        ArtRow("Subsonic", row(380, 36), labelWidth = ControlLabelWidth) {
            BmwSwitch(
                checked = dsp.isOn(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED),
                onCheckedChange = { on ->
                    dsp.commit(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED, if (on) 1f else 0f, subsonicEnMirror)
                },
                // The 72dp pill leaves room for the selector in the 333dp column beside the 150dp
                // label: 150 + 72 + 8 + 100 = 330.
                contentDescription = "Subsonic",
            )
            Spacer(Modifier.width(8.dp))
            BmwSegmentedControl(
                options = listOf("LR4", "BW2"),
                selectedIndex = if (dsp.isOn(NativeBmwDspValues.INDEX_SUBSONIC_SLOPE)) 1 else 0,
                onSelect = { i ->
                    dsp.commit(
                        NativeBmwDspValues.INDEX_SUBSONIC_SLOPE,
                        if (i == 1) NativeBmwDspValues.SUBSONIC_SLOPE_BW2 else NativeBmwDspValues.SUBSONIC_SLOPE_LR4,
                    )
                },
                modifier = Modifier.width(100.dp),
            )
        }
    }

    CrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls, split = 0, onSplit = onSplit)
}

/** See [CrossoverLowMidPage]. The 3-way switch is the master 3-way on/off: off is bit-identical
 *  to the old 2-way crossover (see [ThreeWayCrossover]). */
@Composable
fun CrossoverMidHighPage(
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier = Modifier,
    onSplit: ((Int) -> Unit)? = null,
) {
    val dsp = rememberBmwDspState()
    val midSlider = Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR)
    val highSlider = Color(BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR)

    val midLowpassType = dsp.get(ThreeWayCrossover.midLowpassTypeIndex).toInt().coerceIn(0, 4)
    val onMidLowpassType: (Int) -> Unit = {
        dsp.commit(ThreeWayCrossover.midLowpassTypeIndex, it.toFloat(), ThreeWayCrossover.midLowpassTypeMirrors)
    }
    val highCrossoverType = dsp.get(ThreeWayCrossover.highTypeIndex).toInt().coerceIn(0, 4)
    val onHighType: (Int) -> Unit = {
        dsp.commit(ThreeWayCrossover.highTypeIndex, it.toFloat(), ThreeWayCrossover.highTypeMirrors)
    }

    val controls: @Composable (ControlRow) -> Unit = { row ->
        // The 3-way switch sits directly over the values it enables: the Mid lowpass and High
        // highpass, each with its own slope. Mid's all-pass alignment is on the All-pass page,
        // beside the same graph.
        ArtSwitchRow(
            label = "3-way",
            checked = ThreeWayCrossover.isEnabled(dsp.values),
            onCheckedChange = { on -> dsp.commitAll(ThreeWayCrossover.updates(dsp.values, on)) },
            modifier = row(80, 36),
            labelWidth = ControlLabelWidth,
        )
        DspArtSlider(
            dsp, MidLowpassLabel, ThreeWayCrossover.midLowpassIndex, 300f..8000f, 10f, "Hz", midSlider,
            row(120, 48), mirrors = ThreeWayCrossover.midLowpassMirrors,
        )
        SlopeRow(midLowpassType, onMidLowpassType, row(172, 40))
        DspArtSlider(
            dsp, HighHighpassLabel, ThreeWayCrossover.highHighpassIndex, 1000f..8000f, 10f, "Hz", highSlider,
            row(220, 48), mirrors = ThreeWayCrossover.highHighpassMirrors,
        )
        SlopeRow(highCrossoverType, onHighType, row(272, 40))
    }

    CrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls, split = 1, onSplit = onSplit)
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
        // Shown as the real gain at each end (see TILT_END_GAIN_PER_AMOUNT): +x is x dB more bass
        // and x dB less treble. Stored range -6..6 -> shown -9..9.
        val endGain = NativeBmwDspValues.TILT_END_GAIN_PER_AMOUNT
        DspArtSlider(
            dsp, amount, NativeBmwDspValues.INDEX_TILT_AMOUNT, -6f * endGain..6f * endGain, 0.1f, "dB",
            tiltColor, row(132, 48), displayScale = endGain,
        )
        DspArtSlider(dsp, pivot, NativeBmwDspValues.INDEX_TILT_FREQ, 200f..2000f, 1f, "Hz", tiltColor, row(192, 48))
    }

    CrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls)
}

/** A Crossovers page on either screen. */
@Composable
private fun CrossoverPage(
    dsp: BmwDspState,
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier,
    controls: @Composable (ControlRow) -> Unit,
    split: Int = -1,
    onSplit: ((Int) -> Unit)? = null,
) {
    if (LocalContext.current.isHeadUnitDisplay()) {
        HeadUnitCrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls, split, onSplit)
    } else {
        PhoneCrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls, split, onSplit)
    }
}

/**
 * The Crossover page: one graph, one split at a time. [split] 0 shows the Low / Mid controls
 * ([CrossoverLowMidPage]), 1 the Mid / High ones ([CrossoverMidHighPage]); the selector beside the
 * graph-mode picker switches them, and the graph picks the chosen split's markers out.
 */
@Composable
fun CrossoverSplitsPage(
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var split by rememberSaveable { mutableIntStateOf(0) }
    if (split == 0) CrossoverLowMidPage(graphMode, onGraphModeChange, modifier, onSplit = { split = it })
    else CrossoverMidHighPage(graphMode, onGraphModeChange, modifier, onSplit = { split = it })
}

/**
 * What the Crossovers graph draws besides the DSP model: the car baseline and house curve (as the
 * PEQ graph shows them, with the same Graph menu switches) and the car's measured Low + Mid sum
 * from Band Align's imports, re-timed for today's delays.
 */
private class SystemContext(
    val peqState: BmwPeqState,
    val carBaseline: app.siphondsp.dsp.CarBaseline?,
    val houseCurve: app.siphondsp.dsp.HouseCurve?,
    val measured: (DoubleArray, app.siphondsp.dsp.BmwOutputChannel) -> app.siphondsp.dsp.bandalign.MeasuredLowMid.Result?,
)

@Composable
private fun rememberSystemContext(dsp: BmwDspState): SystemContext {
    val context = LocalContext.current
    val holder = remember(context) { app.siphondsp.compose.state.PeqStateHolder(context.applicationContext) }
    androidx.compose.runtime.LaunchedEffect(holder) { holder.loadCarBaseline(); holder.loadHouseCurve() }
    val bandAlign = rememberBandAlignModel()
    return SystemContext(
        peqState = holder.peqState,
        carBaseline = holder.graphBaseline,
        houseCurve = holder.houseCurve?.takeIf { holder.showHouseCurve },
        // Driver seat when it has files, else passenger; the legend names which.
        measured = { f, side ->
            bandAlign.measured(BandAlignViewModel.Seat.DRIVER, side, dsp.values, f)
                ?: bandAlign.measured(BandAlignViewModel.Seat.PASSENGER, side, dsp.values, f)
        },
    )
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
    split: Int,
    onSplit: ((Int) -> Unit)?,
) {
    val system = rememberSystemContext(dsp)

    BmwDspTheme {
        WorkspaceArtBox(modifier.fillMaxSize()) {
            CrossoverResponseGraph(
                mode = graphMode,
                systemValues = dsp.values,
                peqState = system.peqState,
                modifier = Modifier.artRect(artDp(GraphX, 76, GraphWidth, 305)),
                carBaseline = system.carBaseline,
                houseCurve = system.houseCurve,
                measured = system.measured,
                highlightSplit = split,
            )
            if (onSplit != null) {
                // The split selector, in the pair's band colours, beside the graph modes.
                BmwSegmentedControl(
                    options = listOf("LOW / MID", "MID / HIGH"),
                    selectedIndex = split,
                    onSelect = onSplit,
                    optionAccents = listOf(Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR), Color(BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR)),
                    modifier = Modifier.artRect(artDp(GraphX, 388, SplitWidth, 42)),
                    segmentHeight = 34.dp,
                    segmentGap = 4.dp,
                )
                Box(Modifier.artRect(artDp(GraphX + SplitWidth + 12, 388, GraphWidth - SplitWidth - 12, 42)), contentAlignment = Alignment.Center) {
                    GraphModePicker(graphMode, onGraphModeChange)
                }
            } else {
                Box(Modifier.artRect(artDp(GraphX, 388, GraphWidth, 42)), contentAlignment = Alignment.Center) {
                    GraphModePicker(graphMode, onGraphModeChange)
                }
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
    split: Int = -1,
    onSplit: ((Int) -> Unit)? = null,
) {
    val system = rememberSystemContext(dsp)

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
                    peqState = system.peqState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    carBaseline = system.carBaseline,
                    houseCurve = system.houseCurve,
                    measured = system.measured,
                    highlightSplit = split,
                )
                Spacer(Modifier.height(8.dp))
                // Phones in landscape: the split selector above the graph modes (one page holds both splits).
                if (onSplit != null) {
                    BmwSegmentedControl(
                        options = listOf("LOW / MID", "MID / HIGH"),
                        selectedIndex = split,
                        onSelect = onSplit,
                        optionAccents = listOf(Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR), Color(BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR)),
                        modifier = Modifier.fillMaxWidth(),
                        segmentHeight = 34.dp,
                        segmentGap = 4.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                }
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
            }
        }
    }
}

@Composable
internal fun GraphModePicker(graphMode: CrossoverGraphMode, onGraphModeChange: (CrossoverGraphMode) -> Unit) {
    BmwSegmentedControl(
        options = listOf("MAG", "PHASE", "BOTH", "DELAY", "ALIGN"),
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
private const val SplitWidth = 260

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
