package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.siphondsp.compose.controls.ArtLabelSize
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.ArtTitle
import app.siphondsp.compose.controls.BmwDropdown
import app.siphondsp.compose.controls.BmwDropdownRow
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.abs

private val OrderOptions = listOf("First order" to 1f, "Second order" to 2f)

/**
 * Phase 9 of COMPOSE_MIGRATION_ROADMAP.md -- one Output all-pass page (per physical output).
 * The `repeat(ALL_PASS_SECTIONS_PER_OUTPUT)` loop over generated `INDEX_ALL_PASS` blocks is the
 * roadmap's "generated/repeated Compose rows" test: each section reads/writes its own
 * `base..base+3` slots (enabled / order / freq / Q) with per-`base` `onCommit` closures, and the
 * shared [rememberBmwDspState] snapshot means recomposition stays scoped per section.
 *
 * A Compose `HorizontalPager` (see `OutputAllPassFragment`) hosts six of these, one per output
 * (Low / Mid / High x L/R).
 */
@Composable
fun OutputAllPassScreen(
    output: Int,
    title: String,
    bandColorArgb: Int,
    sliderColorArgb: Int,
    modifier: Modifier = Modifier,
) {
    val dsp = rememberBmwDspState()
    val bandColor = Color(bandColorArgb)
    val sliderColor = Color(sliderColorArgb)
    val isHigh = output == NativeBmwDspValues.OUTPUT_HIGH_LEFT || output == NativeBmwDspValues.OUTPUT_HIGH_RIGHT
    // High only plays above the Mid/High corner (1 kHz+), so the Low/Mid 20..1000 Hz range would
    // leave its all-pass unable to reach the band it acts on. UI-only: native accepts any
    // frequency below Nyquist.
    val freqRange = if (isHigh) 1000f..16000f else 20f..1000f
    val freqStep = if (isHigh) 10f else 1f

    // High's all-pass block lives in the schema tail, not the legacy 4-output block.
    fun sectionBase(section: Int) = if (isHigh) {
        NativeBmwDspValues.highAllPassIndex(output, section, 0)
    } else {
        NativeBmwDspValues.INDEX_ALL_PASS +
            (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) *
            NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
    }
    fun selectedOrder(base: Int): Int {
        val order = dsp.get(base + 1)
        return OrderOptions.indices.minByOrNull { abs(OrderOptions[it].second - order) } ?: 0
    }
    fun setEnabled(base: Int, on: Boolean) {
        // High's sections are stored at the shared 150 Hz default, below its range, so the slider
        // shows a coerced value native isn't using. Commit that shown value with the enable so
        // what plays matches what's shown.
        val freq = dsp.get(base + 2)
        val shown = freq.coerceIn(freqRange.start, freqRange.endInclusive)
        if (on && shown != freq) {
            dsp.commitAll(mapOf(base to 1f, base + 2 to shown))
        } else {
            dsp.commit(base, if (on) 1f else 0f)
        }
    }

    if (LocalContext.current.isHeadUnitDisplay()) {
        // Head unit: placed on the workspace art (REW/_UI/submenu_layout_editor.html), no scrolling.
        BmwDspTheme {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                ArtTitle(title, Modifier.artRect(artDp(190, 62, 400, 30)), color = bandColor)
                repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
                    val base = sectionBase(section)
                    val y = if (section == 0) 100 else 270
                    ArtSwitchRow(
                        label = "Section ${section + 1}",
                        checked = dsp.isOn(base),
                        onCheckedChange = { setEnabled(base, it) },
                        labelWidth = 110.dp,
                        modifier = Modifier.artRect(artDp(190, y, 230, 36)),
                    )
                    BmwDropdown(
                        options = OrderOptions.map { it.first },
                        selectedIndex = selectedOrder(base),
                        onSelect = { dsp.commit(base + 1, OrderOptions[it].second) },
                        textSize = ArtLabelSize,
                        minHeight = 36.dp,
                        modifier = Modifier.artRect(artDp(440, y, 260, 36)),
                    )
                    DspArtSlider(
                        dsp, "Frequency", base + 2, freqRange, freqStep, "Hz", sliderColor,
                        Modifier.artRect(artDp(190, y + 44, 1040, 50)), valueWidth = 110.dp,
                    )
                    DspArtSlider(
                        dsp, "Q", base + 3, 0.1f..30f, 0.01f, "", sliderColor,
                        Modifier.artRect(artDp(190, y + 102, 1040, 50)),
                    )
                }
            }
        }
        return
    }

    BmwDspTheme {
        BmwPanel(
            title = title,
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            titleColor = bandColor,
            leanStart = 20.dp,
            leanEnd = 20.dp,
            topContentGap = 2.dp,
            sliderLabels = listOf("Frequency", "Q"),
        ) {
            repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
                val base = sectionBase(section)
                BmwDropdownRow(
                    toggleChecked = dsp.isOn(base),
                    onToggleChange = { setEnabled(base, it) },
                    options = OrderOptions.map { it.first },
                    selectedIndex = selectedOrder(base),
                    onSelect = { dsp.commit(base + 1, OrderOptions[it].second) },
                )
                BmwSliderRow(
                    label = "Frequency",
                    value = dsp.get(base + 2),
                    valueRange = freqRange,
                    step = freqStep,
                    unit = "Hz",
                    accentColor = sliderColor,
                    onPreview = { dsp.preview(base + 2, it) },
                    onCommit = { dsp.commit(base + 2, it) },
                    onValueEntered = { dsp.commit(base + 2, it) },
                )
                BmwSliderRow(
                    label = "Q",
                    value = dsp.get(base + 3),
                    valueRange = 0.1f..30f,
                    step = 0.01f,
                    unit = "",
                    accentColor = sliderColor,
                    onPreview = { dsp.preview(base + 3, it) },
                    onCommit = { dsp.commit(base + 3, it) },
                    onValueEntered = { dsp.commit(base + 3, it) },
                )
            }
        }
    }
}
