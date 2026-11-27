package app.siphondsp.compose.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtLabelSize
import app.siphondsp.compose.controls.ArtRow
import app.siphondsp.compose.controls.BmwDropdown
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.abs

private val OrderOptions = listOf("First order" to 1f, "Second order" to 2f)

/**
 * The Alignment workspace's ALLPASS page: one output at a time, picked with LEFT / RIGHT and the
 * Delay page's High / Mid / Low tabs, beside the same response graph as the Crossovers pages
 * (opening on PHASE), so each step of a section's Frequency or Q shows on the band sum as it's
 * made. The output's two sections share one controls column, one at a time on Section 1 / 2 tabs.
 */
@Composable
fun AllPassPage(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    var right by rememberSaveable { mutableStateOf(false) }
    var bandIndex by rememberSaveable { mutableIntStateOf(GainsBand.LOW.ordinal) }
    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var graphMode by rememberSaveable { mutableStateOf(CrossoverGraphMode.PHASE) }
    val band = GainsBand.entries[bandIndex.coerceIn(0, GainsBand.entries.size - 1)]
    val output = if (right) band.rightOutput else band.leftOutput
    val title = "${if (right) "Right" else "Left"} ${band.title}"
    val dsp = rememberBmwDspState()
    val context = LocalContext.current
    val peqState = remember { BmwPeqState.load(context) }

    val sides: @Composable (Modifier) -> Unit = { m ->
        BmwSegmentedControl(
            options = listOf("LEFT", "RIGHT"),
            selectedIndex = if (right) 1 else 0,
            onSelect = { right = it == 1 },
            modifier = m,
            segmentHeight = 36.dp,
            segmentGap = 6.dp,
        )
    }
    val bands: @Composable (Modifier) -> Unit = { m -> BandTabs(band, { bandIndex = it.ordinal }, m) }
    val graph: @Composable (Modifier) -> Unit = { m ->
        CrossoverResponseGraph(mode = graphMode, systemValues = dsp.values, peqState = peqState, modifier = m)
    }
    val picker: @Composable () -> Unit = { GraphModePicker(graphMode) { graphMode = it } }
    val controls: @Composable (SectionRow) -> Unit = { row ->
        // Keyed so each output's controls start fresh rather than reusing the last one's state.
        key(output) { OutputAllPassControls(dsp, output, title, band.slider, sectionIndex, { sectionIndex = it }, row) }
    }

    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                sides(Modifier.artRect(artDp(GraphX, SelectorTop, 170, 44)))
                bands(Modifier.artRect(artDp(GraphX + 186, SelectorTop, 420, 44)))
                graph(Modifier.artRect(artDp(GraphX, GraphTop, GraphWidth, GraphHeight)))
                Box(Modifier.artRect(artDp(GraphX, GraphTop + GraphHeight + 6, GraphWidth, 42)), contentAlignment = Alignment.Center) {
                    picker()
                }
                controls { y, h -> Modifier.artRect(artDp(ControlX, y, ControlColumnWidth, h)) }
                // Opened from the Phase page: back to Band Align, in the free space right of the tabs.
                if (onBack != null) {
                    Box(Modifier.artRect(artDp(GraphX + 618, SelectorTop, GraphWidth - 618, 44)), contentAlignment = Alignment.CenterEnd) {
                        app.siphondsp.compose.controls.GlassChip("‹ Back", onClick = onBack, arrow = false)
                    }
                }
            }
        } else {
            Column(modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    sides(Modifier.width(150.dp).height(44.dp))
                    bands(Modifier.weight(1f).height(44.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        graph(Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(8.dp))
                        picker()
                    }
                    Column(
                        Modifier.width(ControlColumnWidth.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        controls { _, h -> Modifier.fillMaxWidth().height(h.dp) }
                    }
                }
            }
        }
    }
}

/**
 * Where one row of the controls column goes: [y] down the head unit's 1280x480 editor space and
 * [h] tall. The head unit places the row there; the phone stacks the rows and uses only [h].
 */
private typealias SectionRow = (y: Int, h: Int) -> Modifier

/**
 * One output's all-pass controls (Phase 9 of COMPOSE_MIGRATION_ROADMAP.md): its name, the
 * Section 1 / 2 tabs, and the picked section -- a header with its on/off switch, the order, and
 * Frequency and Q steppers -- in the output's band colour ([colorArgb]). Each section reads and
 * writes its own `base..base+3` slots (enabled / order / freq / Q).
 */
@Composable
private fun OutputAllPassControls(
    dsp: BmwDspState,
    output: Int,
    title: String,
    colorArgb: Int,
    sectionIndex: Int,
    onSection: (Int) -> Unit,
    row: SectionRow,
) {
    val color = Color(colorArgb)
    val isHigh = output == NativeBmwDspValues.OUTPUT_HIGH_LEFT || output == NativeBmwDspValues.OUTPUT_HIGH_RIGHT
    val isMid = output == NativeBmwDspValues.OUTPUT_MID_LEFT || output == NativeBmwDspValues.OUTPUT_MID_RIGHT
    // Per-section frequency range. UI-only: native accepts any frequency below Nyquist.
    // - High only plays above the Mid/High corner (1 kHz+), so 20..1000 Hz couldn't reach its band.
    // - Mid section 2 reaches the Mid/High corner (up to 8 kHz, as on the Crossovers page) for
    //   phase work at the mid/tweeter crossover; section 1 keeps the finer 20..1000 Hz range.
    fun freqRange(section: Int) = when {
        isHigh -> 1000f..16000f
        isMid && section == 1 -> 20f..8000f
        else -> 20f..1000f
    }
    val freqStep = if (isHigh) 10f else 1f

    // High's all-pass block lives in the schema tail, not the legacy 4-output block.
    fun sectionBase(section: Int) = if (isHigh) {
        NativeBmwDspValues.highAllPassIndex(output, section, 0)
    } else {
        NativeBmwDspValues.INDEX_ALL_PASS +
            (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) *
            NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
    }
    val section = sectionIndex.coerceIn(0, NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT - 1)

    Box(row(TitleTop, 36), contentAlignment = Alignment.CenterStart) {
        ArtLabel(title.uppercase(), color = color, size = 20.sp)
    }
    BmwSegmentedControl(
        options = List(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { "SECTION ${it + 1}" },
        selectedIndex = section,
        onSelect = onSection,
        optionAccents = List(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { color },
        modifier = row(GraphTop, 40),
        segmentHeight = 40.dp,
        segmentGap = 6.dp,
    )
    AllPassSection(dsp, section, sectionBase(section), freqRange(section), freqStep, color) { y, h ->
        row(GraphTop + 54 + y, h)
    }
}

/** One section: its header and switch, then order, Frequency and Q. */
@Composable
private fun AllPassSection(
    dsp: BmwDspState,
    section: Int,
    base: Int,
    freqRange: ClosedFloatingPointRange<Float>,
    freqStep: Float,
    color: Color,
    row: SectionRow,
) {
    ArtGroupHeader(
        title = "Section ${section + 1}",
        accent = color,
        modifier = row(0, 40),
        checked = dsp.isOn(base),
        onCheckedChange = { on -> setEnabled(dsp, base, freqRange, on) },
    )
    ArtRow("Order", row(52, 40), labelWidth = SectionLabelWidth) {
        val order = dsp.get(base + 1)
        BmwDropdown(
            options = OrderOptions.map { it.first },
            selectedIndex = OrderOptions.indices.minByOrNull { abs(OrderOptions[it].second - order) } ?: 0,
            onSelect = { dsp.commit(base + 1, OrderOptions[it].second) },
            modifier = Modifier.weight(1f),
            textSize = ArtLabelSize,
            minHeight = 40.dp,
        )
    }
    DspArtSlider(dsp, "Frequency", base + 2, freqRange, freqStep, "Hz", color, row(104, 48))
    DspArtSlider(dsp, "Q", base + 3, 0.1f..30f, 0.01f, "", color, row(164, 48))
}

/**
 * High's sections are stored at the shared 150 Hz default, below its range, so the stepper shows
 * a coerced value native isn't using. Turning a section on commits that shown value with the
 * enable, so what plays matches what's shown.
 */
private fun setEnabled(dsp: BmwDspState, base: Int, freqRange: ClosedFloatingPointRange<Float>, on: Boolean) {
    val freq = dsp.get(base + 2)
    val shown = freq.coerceIn(freqRange.start, freqRange.endInclusive)
    if (on && shown != freq) {
        dsp.commitAll(mapOf(base to 1f, base + 2 to shown))
    } else {
        dsp.commit(base, if (on) 1f else 0f)
    }
}

// Head-unit layout, in the 1280x480 editor's dp, matching the Crossovers pages: the graph from
// x 168 (under the output selectors), then the 333dp controls column ending at x 1250.
private const val GraphX = 168
private const val GraphWidth = 719
private const val GraphTop = 120
private const val GraphHeight = 260
private const val ControlX = 917
private const val ControlColumnWidth = 333
private const val SelectorTop = 68
private const val TitleTop = 72
private val SectionLabelWidth = 150.dp
