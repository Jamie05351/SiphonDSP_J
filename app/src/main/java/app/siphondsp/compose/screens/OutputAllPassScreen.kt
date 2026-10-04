package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.abs

private val OrderOptions = listOf("First order" to 1f, "Second order" to 2f)

/**
 * Where one row of a section goes: [y] dp down the section (its head-unit position) and [h] tall.
 * The head unit places the row there; the phone stacks the rows and uses only [h].
 */
private typealias SectionRow = (y: Int, h: Int) -> Modifier

/**
 * Phase 9 of COMPOSE_MIGRATION_ROADMAP.md -- one Output all-pass page (per physical output).
 * Each section reads/writes its own `base..base+3` slots (enabled / order / freq / Q); the
 * shared [rememberBmwDspState] snapshot keeps recomposition scoped per section.
 *
 * Laid out the same way on both screens (Figma "SiphonDSP Front Panel (from code)", Steppers
 * page): the output's name across the top, then its two sections side by side -- each a header
 * with its on/off switch, the order, and Frequency and Q steppers -- everything in the output's
 * band colour ([colorArgb]). A Compose `HorizontalPager` (see `OutputAllPassFragment`) hosts six of
 * these, one per output (Low / Mid / High x L/R).
 */
@Composable
fun OutputAllPassScreen(
    output: Int,
    title: String,
    colorArgb: Int,
    modifier: Modifier = Modifier,
) {
    val dsp = rememberBmwDspState()
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

    val section: @Composable (Int, SectionRow) -> Unit = { i, row ->
        AllPassSection(dsp, i, sectionBase(i), freqRange(i), freqStep, color, row)
    }
    val heading: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = Alignment.CenterStart) { ArtLabel(title.uppercase(), color = color, size = 20.sp) }
    }

    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                heading(Modifier.artRect(artDp(SectionLeftX, PageTop, SectionRightX + SectionWidth - SectionLeftX, 30)))
                repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { i ->
                    val x = if (i == 0) SectionLeftX else SectionRightX
                    section(i) { y, h -> Modifier.artRect(artDp(x, PageTop + 44 + y, SectionWidth, h)) }
                }
            }
        } else {
            PhoneAllPassPage(heading, section, modifier)
        }
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

/** Phone: the heading, then the two sections side by side, centred. A phone too narrow for both
 *  stacks them, and the page scrolls only if it doesn't fit. */
@Composable
private fun PhoneAllPassPage(
    heading: @Composable (Modifier) -> Unit,
    section: @Composable (Int, SectionRow) -> Unit,
    modifier: Modifier,
) {
    val stack: SectionRow = { _, h -> Modifier.fillMaxWidth().height(h.dp) }
    val column: @Composable (Int) -> Unit = { i ->
        Column(Modifier.width(SectionWidth.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { section(i, stack) }
    }
    val pageWidth = (SectionRightX + SectionWidth - SectionLeftX).dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= pageWidth + 24.dp
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(if (sideBySide) pageWidth else SectionWidth.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                heading(Modifier.fillMaxWidth().height(30.dp))
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy((SectionRightX - SectionLeftX - SectionWidth).dp)) {
                        column(0)
                        column(1)
                    }
                } else {
                    column(0)
                    column(1)
                }
            }
        }
    }
}

// Head-unit layout, in the 1280x480 editor's dp: the same two 333dp columns as the Output and
// Compressor pages (x 317 and 770), under the output's name. The phone uses the same widths.
private const val SectionLeftX = 317
private const val SectionRightX = 770
private const val SectionWidth = 333
private const val PageTop = 112
private val SectionLabelWidth = 150.dp
