package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.isHeadUnitDisplay

/**
 * Measurement signal generator controls (native sweep/pink noise, PRs #344/#345) -- a standalone
 * screen, deliberately separate from `MeasurementCaptureActivity` (that one captures/exports raw
 * input+output WAVs; this one drives what's playing in the first place). Reachable from the main
 * overflow menu, same as Measurement Capture.
 *
 * Laid out the same way on both screens (Figma "SiphonDSP Front Panel (from code)", Steppers
 * page): a top bar that is always visible -- three tabs (Signal / Timing ref / Isolation &
 * routing) and the OFF/SWEEP/PINK generator control -- then the chosen tab's two columns, so
 * nothing scrolls.
 *
 * The OFF/SWEEP/PINK segmented control doubles as both the signal-type selector and the
 * start/stop control: `NativeBmwDspProcessor` treats `measGenType == 0` as fully inactive and any
 * other value as "generating right now" (see `processFrame()`), so there's no separate
 * "armed but stopped" state to model in the UI -- picking a type starts it immediately, and OFF
 * stops it. Keeping it in the top bar means the signal can be stopped from any tab.
 *
 * Timing reference (only meaningful while SWEEP is selected) wraps the sweep in a REW Acoustic
 * Timing Reference cycle -- reverse-engineered from real REW-exported measurement files, not
 * REW's own published spec, see `NativeBmwMeasurementGenerator::configureTimingRef()`. Like the
 * generator type itself, its enable toggle uses `preview()` not `commit()` for the same
 * don't-persist-a-surprise-on-restart reason.
 *
 * Band isolation reuses `INDEX_MEASUREMENT_MUTE` directly. The old "Measurements / routing" card
 * on the main DSP dashboard has been retired and its rows merged in here (Band isolation and
 * Routing); no second place in the app controls these values.
 *
 * Neutral accent colour throughout ([BmwTheme.colors.sliderDefault]) rather than the band
 * colours -- this screen isn't band-specific in that sense.
 */
@Composable
fun SignalGeneratorScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val accent = BmwTheme.colors.sliderDefault
    val headUnit = LocalContext.current.isHeadUnitDisplay()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val type = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_TYPE).toInt().coerceIn(0, 2)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (headUnit) 40.dp else 20.dp)
                .padding(top = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BmwSegmentedControl(
                options = listOf("SIGNAL", "TIMING REF", "ISOLATION & ROUTING"),
                selectedIndex = tab,
                onSelect = { tab = it },
                optionAccents = List(3) { accent },
                modifier = Modifier.weight(1f, fill = false).width(TabsWidth),
                segmentHeight = 36.dp,
                segmentGap = 4.dp,
            )
            Spacer(Modifier.weight(1f))
            if (headUnit) ArtLabel("Generator", Modifier.padding(end = 16.dp))
            BmwSegmentedControl(
                options = listOf("OFF", "SWEEP", "PINK"),
                selectedIndex = type,
                // preview(), not commit(): the active generator type must stay transient runtime
                // state, never written to disk. Otherwise engine init on the next service/device
                // restart would reload a nonzero type and silently replace normal playback with
                // the test signal with no new user action. Sweep/pink parameters still use
                // onCommit as normal -- only "which generator is currently running" is transient.
                onSelect = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_TYPE, it.toFloat()) },
                optionAccents = List(3) { accent },
                modifier = Modifier.width(GeneratorWidth),
                segmentHeight = 36.dp,
                segmentGap = 4.dp,
            )
        }
        val left: @Composable () -> Unit
        val right: @Composable () -> Unit
        when (tab) {
            0 -> {
                left = { SweepColumn(dsp, accent, enabled = type == 1) }
                right = { PinkColumn(dsp, accent, enabled = type == 2) }
            }
            1 -> {
                left = { TimingSwitchesColumn(dsp, accent) }
                right = { TimingBandsColumn(dsp, accent, enabled = type == 1 && dsp.isOn(NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_ENABLED)) }
            }
            else -> {
                left = { IsolationColumn(dsp, accent) }
                right = { RoutingColumn(dsp, accent) }
            }
        }
        TwoColumns(if (headUnit) 94.dp else 61.dp, left, right, Modifier.weight(1f))
    }
}

/** The tab's two columns side by side and centred; a screen too narrow for both stacks them and
 *  scrolls. */
@Composable
private fun TwoColumns(gap: Dp, left: @Composable () -> Unit, right: @Composable () -> Unit, modifier: Modifier) {
    val column: @Composable (@Composable () -> Unit) -> Unit = { content ->
        Column(Modifier.width(ColumnWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= ColumnWidth * 2 + gap + 24.dp
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp, bottom = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    column(left)
                    column(right)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    column(left)
                    column(right)
                }
            }
        }
    }
}

@Composable
private fun SweepColumn(dsp: BmwDspState, accent: Color, enabled: Boolean) {
    ArtGroupHeader("Sweep", accent, Modifier.fillMaxWidth().height(40.dp))
    GenStepper(dsp, "Start", NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_START_HZ, 10f..24000f, 10f, "Hz", accent, enabled, WideBox)
    GenStepper(dsp, "End", NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_END_HZ, 10f..24000f, 10f, "Hz", accent, enabled, WideBox)
    GenStepper(dsp, "Duration", NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_DURATION_S, 0.5f..60f, 0.1f, "s", accent, enabled)
    GenStepper(dsp, "Level", NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_LEVEL_DB, -60f..0f, 1f, "dB", accent, enabled)
}

@Composable
private fun PinkColumn(dsp: BmwDspState, accent: Color, enabled: Boolean) {
    ArtGroupHeader("Pink noise", accent, Modifier.fillMaxWidth().height(40.dp))
    GenStepper(dsp, "Period", NativeBmwDspValues.INDEX_MEAS_GEN_PINK_PERIOD_S, 0.1f..10f, 0.1f, "s", accent, enabled)
    GenStepper(dsp, "Level", NativeBmwDspValues.INDEX_MEAS_GEN_PINK_LEVEL_DB, -60f..0f, 1f, "dB", accent, enabled)
    Note(stringResource(R.string.signal_generator_explainer))
}

@Composable
private fun TimingSwitchesColumn(dsp: BmwDspState, accent: Color) {
    ArtGroupHeader(stringResource(R.string.signal_generator_section_timing_ref), accent, Modifier.fillMaxWidth().height(40.dp))
    ArtSwitchRow(
        label = "Timing sequence",
        checked = dsp.isOn(NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_ENABLED),
        // preview(), not commit(): same reasoning as the OFF/SWEEP/PINK control -- this must
        // never persist as a "resume where I left off" surprise on restart.
        onCheckedChange = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_ENABLED, if (it) 1f else 0f) },
        labelWidth = SwitchLabelWidth,
        modifier = Modifier.fillMaxWidth().height(40.dp),
    )
    ArtSwitchRow(
        label = "Split channels",
        checked = dsp.isOn(NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_SPLIT_CHANNELS),
        onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_SPLIT_CHANNELS, if (it) 1f else 0f) },
        labelWidth = SwitchLabelWidth,
        modifier = Modifier.fillMaxWidth().height(40.dp),
    )
    Note(stringResource(R.string.signal_generator_timing_ref_explainer))
}

@Composable
private fun TimingBandsColumn(dsp: BmwDspState, accent: Color, enabled: Boolean) {
    ArtGroupHeader("Chirp bands", accent, Modifier.fillMaxWidth().height(40.dp))
    GenStepper(dsp, "Mid start", NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_MID_START_HZ, 10f..24000f, 10f, "Hz", accent, enabled, WideBox)
    GenStepper(dsp, "Mid end", NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_MID_END_HZ, 10f..24000f, 10f, "Hz", accent, enabled, WideBox)
    GenStepper(dsp, "Low start", NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_LOW_START_HZ, 10f..24000f, 10f, "Hz", accent, enabled, WideBox)
    GenStepper(dsp, "Low end", NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_LOW_END_HZ, 10f..24000f, 10f, "Hz", accent, enabled, WideBox)
}

@Composable
private fun IsolationColumn(dsp: BmwDspState, accent: Color) {
    // INDEX_MEASUREMENT_MUTE's native values are 0=off, 1=isolate Mid (historically "mute Low"),
    // 2=isolate Low ("mute Mid"), 3=isolate High -- see NativeBmwDspProcessor::rebuildMeasBus().
    // The control is ordered OFF/LOW/MID/HIGH, so 1 and 2 are swapped at this boundary in both
    // directions via swapLowMid(), which is its own inverse.
    val band = swapLowMid(dsp.get(NativeBmwDspValues.INDEX_MEASUREMENT_MUTE).toInt().coerceIn(0, 3))
    ArtGroupHeader(stringResource(R.string.signal_generator_band_isolation), accent, Modifier.fillMaxWidth().height(40.dp))
    BmwSegmentedControl(
        options = listOf("OFF", "LOW", "MID", "HIGH"),
        selectedIndex = band,
        onSelect = { dsp.commit(NativeBmwDspValues.INDEX_MEASUREMENT_MUTE, swapLowMid(it).toFloat()) },
        optionAccents = List(4) { accent },
        modifier = Modifier.fillMaxWidth(),
        segmentHeight = 36.dp,
        segmentGap = 4.dp,
    )
    GenStepper(dsp, "Stopband", NativeBmwDspValues.INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES, 0f..4f, 0.1f, "oct", accent, band != 0)
    // HIGH only isolates anything audible while the Crossovers page's 3-way switch is on --
    // otherwise High is silent and the bus carries nothing.
    Note("HIGH only isolates anything while the 3-way crossover is on.")
}

@Composable
private fun RoutingColumn(dsp: BmwDspState, accent: Color) {
    ArtGroupHeader(stringResource(R.string.signal_generator_section_routing), accent, Modifier.fillMaxWidth().height(40.dp))
    RoutingSwitch(stringResource(R.string.signal_generator_lpf_passthrough), dsp.isOn(NativeBmwDspValues.INDEX_LPF_PASS)) {
        dsp.commit(NativeBmwDspValues.INDEX_LPF_PASS, if (it) 1f else 0f)
    }
    RoutingSwitch(stringResource(R.string.signal_generator_hpf_passthrough), dsp.isOn(NativeBmwDspValues.INDEX_HPF_PASS)) {
        dsp.commit(NativeBmwDspValues.INDEX_HPF_PASS, if (it) 1f else 0f)
    }
    RoutingSwitch(stringResource(R.string.signal_generator_mute_low_band), dsp.isOn(NativeBmwDspValues.INDEX_LOW_MUTE)) {
        dsp.commit(
            NativeBmwDspValues.INDEX_LOW_MUTE, if (it) 1f else 0f,
            mirrors = intArrayOf(
                NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_MUTE),
                NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_MUTE),
            ),
        )
    }
    RoutingSwitch(stringResource(R.string.signal_generator_mute_mid_band), dsp.isOn(NativeBmwDspValues.INDEX_MID_MUTE)) {
        dsp.commit(
            NativeBmwDspValues.INDEX_MID_MUTE, if (it) 1f else 0f,
            mirrors = intArrayOf(
                NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_MUTE),
                NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_MUTE),
            ),
        )
    }
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtLabel("Channels", Modifier.width(110.dp))
        BmwSegmentedControl(
            options = listOf("BOTH", "MUTE L", "MUTE R"),
            selectedIndex = dsp.get(NativeBmwDspValues.INDEX_CHANNEL_MUTE).toInt().coerceIn(0, 2),
            onSelect = { dsp.commit(NativeBmwDspValues.INDEX_CHANNEL_MUTE, it.toFloat()) },
            optionAccents = List(3) { accent },
            modifier = Modifier.weight(1f),
            segmentHeight = 36.dp,
            segmentGap = 4.dp,
        )
    }
}

@Composable
private fun RoutingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = ArtSwitchRow(
    label = label,
    checked = checked,
    onCheckedChange = onChange,
    labelWidth = SwitchLabelWidth,
    modifier = Modifier.fillMaxWidth().height(36.dp),
)

/** A label + value box + −/+ row; [boxWidth] widens the box for values up to 24000 Hz, taking
 *  the room from the label column so the row stays [ColumnWidth] wide. */
@Composable
private fun GenStepper(
    dsp: BmwDspState,
    label: String,
    index: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accent: Color,
    enabled: Boolean,
    boxWidth: Dp = 96.dp,
) = DspArtSlider(
    dsp, label, index, range, step, unit, accent,
    Modifier.fillMaxWidth().height(48.dp),
    valueWidth = boxWidth,
    labelWidth = ColumnWidth - boxWidth - StepperControlsWidth,
    enabled = enabled,
)

@Composable
private fun Note(text: String) = Text(
    text = text,
    color = Color.White.copy(alpha = 0.5f),
    fontSize = 13.sp,
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
)

/** Swaps 1<->2, leaves 0 (off) and 3 (High) alone. Its own inverse -- see [IsolationColumn] for
 *  why this boundary needs it in both directions. */
private fun swapLowMid(value: Int): Int = when (value) {
    1 -> 2
    2 -> 1
    3 -> 3
    else -> 0
}

private val ColumnWidth = 333.dp
private val TabsWidth = 520.dp
private val GeneratorWidth = 300.dp
private val WideBox = 110.dp
/** The −/+ pill and the gap before it. */
private val StepperControlsWidth = 87.dp
/** A switch row's label column, which puts the switch at the column's end. */
private val SwitchLabelWidth = 271.dp
