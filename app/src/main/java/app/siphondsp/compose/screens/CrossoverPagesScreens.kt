package app.siphondsp.compose.screens

import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.nav.Routes
import app.siphondsp.view.DspDestination
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
import kotlin.math.roundToInt
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtGroupHeader
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
import app.siphondsp.dsp.CrossoverPairing
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
    // Mid's highpass slope only; its lowpass and High's highpass have their own on the Mid/High
    // page.
    val onMidType: (Int) -> Unit = {
        dsp.commit(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
            it.toFloat(),
            midPair(NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
        )
    }
    val (pairL, pairR) = pairings(dsp.values)
    val subsonicFreqMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_FREQ)
    val subsonicEnMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)

    val controls: @Composable (ControlRow) -> Unit = { row ->
        DspArtSlider(
            dsp, LowLowpassLabel, NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f..320f, 1f, "Hz", lowSlider,
            row(80, 48), mirrors = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
        )
        SlopeRow(lowCrossoverType, onLowType, row(136, 40), slopeLabel(pairL.lowMidCorrected || pairR.lowMidCorrected))
        DspArtSlider(
            dsp, MidHighpassLabel, NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 60f..320f, 1f, "Hz", midSlider,
            row(196, 48), mirrors = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
        )
        SlopeRow(
            midCrossoverType, onMidType, row(252, 40),
            slopeLabel(
                pairL.lowMidCorrected || pairR.lowMidCorrected,
                inverted = pairL.midInverted || pairR.midInverted,
            ),
        )
        DspArtSlider(
            dsp, subsonicLabel, NativeBmwDspValues.INDEX_SUBSONIC_FREQ, 20f..60f, 1f, "Hz", DefaultAccent,
            row(312, 48), mirrors = subsonicFreqMirror,
        )
        // On/off and the slope in one row: LR4 (24 dB/oct, the original) or BW2 (12 dB/oct, half
        // the phase turn through the bass, a little less excursion protection).
        ArtRow("Subsonic", row(372, 36), labelWidth = ControlLabelWidth) {
            BmwSwitch(
                checked = dsp.isOn(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED),
                onCheckedChange = { on ->
                    dsp.commit(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED, if (on) 1f else 0f, subsonicEnMirror)
                },
                contentDescription = "Subsonic",
                // Narrower than the usual 100dp so the selector fits the 333dp column beside the
                // 150dp label: 150 + 72 + 8 + 100 = 330.
                width = 72.dp,
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
    val midLowpassType = dsp.get(ThreeWayCrossover.midLowpassTypeIndex).toInt().coerceIn(0, 4)
    val onMidLowpassType: (Int) -> Unit = {
        dsp.commit(ThreeWayCrossover.midLowpassTypeIndex, it.toFloat(), ThreeWayCrossover.midLowpassTypeMirrors)
    }
    val highCrossoverType = dsp.get(ThreeWayCrossover.highTypeIndex).toInt().coerceIn(0, 4)
    val (pairL, pairR) = pairings(dsp.values)
    val onHighType: (Int) -> Unit = {
        dsp.commit(ThreeWayCrossover.highTypeIndex, it.toFloat(), ThreeWayCrossover.highTypeMirrors)
    }

    val controls: @Composable (ControlRow) -> Unit = { row ->
        // Each switch sits directly over the values it enables: 3-way over the Mid lowpass and
        // High highpass (each with its own slope), Mid align over its frequency.
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
        SlopeRow(midLowpassType, onMidLowpassType, row(172, 40), slopeLabel(pairL.midHighCorrected || pairR.midHighCorrected))
        DspArtSlider(
            dsp, HighHighpassLabel, ThreeWayCrossover.highHighpassIndex, 1000f..8000f, 10f, "Hz", highSlider,
            row(220, 48), mirrors = ThreeWayCrossover.highHighpassMirrors,
        )
        SlopeRow(
            highCrossoverType, onHighType, row(272, 40),
            slopeLabel(
                pairL.midHighCorrected || pairR.midHighCorrected,
                inverted = pairL.highInverted || pairR.highInverted,
            ),
        )
        ArtSwitchRow(
            label = "Mid align",
            checked = dsp.isOn(midLeftBase),
            onCheckedChange = { on -> dsp.commit(midLeftBase, if (on) 1f else 0f, midAlignEnMirror) },
            modifier = row(320, 36),
            labelWidth = ControlLabelWidth,
        )
        // "Mid align (all-pass)" is too long for the label column, so it sits over the stepper.
        DspArtSlider(
            dsp, MidAlignLabel, midLeftBase + 2, 20f..1000f, 1f, "Hz", midSlider,
            row(360, 76), labelAbove = true, mirrors = midAlignFreqMirror,
        )
    }

    val navigator = LocalAppNavigator.current
    CrossoverPage(dsp, graphMode, onGraphModeChange, modifier, controls) {
        // Phone only: the All-pass page (Alignment's ALLPASS) is a sidebar tap away on the head unit.
        Text(
            text = "Open full All-pass ›",
            color = Color(BmwDashboardSkin.LIGHT_BLUE),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable { navigator.openWorkspace(DspDestination.ALIGNMENT, Routes.ALIGNMENT_PAGE_ALLPASS) },
        )
    }
}

/**
 * The third Crossovers page: Tonality Tilt, beside the same response graph as the other two (it
 * already includes the tilt, so Amount and Pivot show on it as they change). The section switch
 * sits in the column's header; Amount and Pivot stay adjustable while it is off, so they can be
 * set before it is switched on. Virtual Bass, which shared this page, has its own: see
 * [CrossoverVirtualBassPage].
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

/**
 * The fourth Crossovers page: **Virtual Bass**, beside the same response graph. Notes below the
 * Woofer reach -- the lowest note the woofers really play, e.g. where the subsonic HPF starts --
 * are rebuilt as harmonics they can play (the ear hears the missing fundamental); notes above it
 * are left alone.
 * - Amount: the harmonics' level.
 * - Range: the corner of the harmonics' band filter, x the reach. The line under the controls
 *   gives where they mostly land (up to about 3.4x the reach, the 4th harmonic of the highest note
 *   rebuilt) and that corner ("cap"); below 3.4x Range trims them, above it only their tail.
 * - Warm ↔ Punch: their mix (even harmonics for a round, warm bass; more of the 3rd for punch).
 * - Response: how quickly they follow each note's decay -- fast is tighter and punchier, slow is
 *   smoother and fuller.
 * - Remove sub: gently thins what lies below the reach (never the woofers' own band), sparing
 *   excursion. That shows on the graph; the harmonics (not a filter) don't.
 * Its settings stay adjustable while it is off.
 */
@Composable
fun CrossoverVirtualBassPage(
    graphMode: CrossoverGraphMode,
    onGraphModeChange: (CrossoverGraphMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dsp = rememberBmwDspState()
    val bassColor = Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR)

    val controls: @Composable (ControlRow) -> Unit = { row ->
        ArtGroupHeader(
            title = "Virtual Bass",
            accent = bassColor,
            modifier = row(80, 40),
            checked = dsp.isOn(NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED),
            onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED, if (on) 1f else 0f) },
        )
        DspArtSlider(
            dsp, "Woofer reach", NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ,
            NativeBmwDspValues.VIRTUAL_BASS_MIN_HZ..NativeBmwDspValues.VIRTUAL_BASS_MAX_HZ,
            NativeBmwDspValues.VIRTUAL_BASS_STEP_HZ, "Hz", bassColor, row(126, 48),
        )
        DspArtSlider(
            dsp, "Amount", NativeBmwDspValues.INDEX_VIRTUAL_BASS_AMOUNT, 0f..100f, 5f, "%", bassColor, row(178, 48),
        )
        DspArtSlider(
            dsp, "Range", NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE,
            NativeBmwDspValues.VIRTUAL_BASS_MIN_RANGE..NativeBmwDspValues.VIRTUAL_BASS_MAX_RANGE,
            NativeBmwDspValues.VIRTUAL_BASS_STEP_RANGE, "×", bassColor, row(230, 48),
        )
        // 0 = Warm (even harmonics), 100 = Punchy (more of the 3rd).
        DspArtSlider(
            dsp, "Warm ↔ Punch", NativeBmwDspValues.INDEX_VIRTUAL_BASS_CHARACTER, 0f..100f, 5f, "%", bassColor,
            row(282, 48),
        )
        DspArtSlider(
            dsp, "Response", NativeBmwDspValues.INDEX_VIRTUAL_BASS_RESPONSE,
            NativeBmwDspValues.VIRTUAL_BASS_MIN_RESPONSE_MS..NativeBmwDspValues.VIRTUAL_BASS_MAX_RESPONSE_MS,
            NativeBmwDspValues.VIRTUAL_BASS_STEP_RESPONSE_MS, "ms", bassColor, row(334, 48),
        )
        ArtSwitchRow(
            label = "Remove sub",
            checked = dsp.isOn(NativeBmwDspValues.INDEX_VIRTUAL_BASS_REMOVE_SUB),
            onCheckedChange = { on ->
                dsp.commit(NativeBmwDspValues.INDEX_VIRTUAL_BASS_REMOVE_SUB, if (on) 1f else 0f)
            },
            modifier = row(386, 36),
            labelWidth = ControlLabelWidth,
        )
        // Where the harmonics mostly land, and the corner of Range's band filter above them, from the
        // reach and range as the engine clamps them; shown whether or not it's on, as the controls are.
        val shown = dsp.values.copyOf().also { it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 1f }
        val fromHz = NativeBmwDspValues.virtualBassCutHz(shown).roundToInt()
        val toHz = NativeBmwDspValues.virtualBassHarmonicsTopHz(shown).roundToInt()
        val capHz = NativeBmwDspValues.virtualBassTopHz(shown).roundToInt()
        Box(row(430, 36), contentAlignment = Alignment.CenterStart) {
            Text(
                text = "Harmonics $fromHz–$toHz Hz · cap $capHz",
                color = bassColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
        options = listOf("MAG", "PHASE", "BOTH", "DELAY", "ALIGN"),
        selectedIndex = graphMode.ordinal,
        onSelect = { onGraphModeChange(CrossoverGraphMode.entries[it]) },
        modifier = Modifier.fillMaxWidth(),
        segmentHeight = 34.dp,
        segmentGap = 4.dp,
    )
}

/**
 * "Slope" beside the crossover type dropdown, its label in the controls' label column. [label]
 * says when the engine is auto-correcting this slope's BW2/BW4 pair (see [slopeLabel]).
 */
@Composable
private fun SlopeRow(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier, label: String = "Slope") {
    ArtRow(label, modifier, labelWidth = ControlLabelWidth) {
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

/**
 * A slope row's label: "Slope (auto)" while its boundary's BW2/BW4 pair is auto-corrected (the
 * corners spread around the set frequency), "Auto, inverted" on the band whose polarity the
 * correction also inverts. Short enough for the 150dp label column. Shown when either side is
 * corrected (these pages link L and R, but a side can differ if set elsewhere).
 */
private fun slopeLabel(corrected: Boolean, inverted: Boolean = false) = when {
    !corrected -> "Slope"
    inverted -> "Auto, inverted"
    else -> "Slope (auto)"
}

/** Both sides' pairings, so a label covers a Right side that differs from Left. */
private fun pairings(values: FloatArray) =
    CrossoverPairing.of(values, left = true) to CrossoverPairing.of(values, left = false)

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
