package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.siphondsp.compose.controls.BmwDropdownRow
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.abs

private val OrderOptions = listOf("First order" to 1f, "Second order" to 2f)

/**
 * Phase 9 of COMPOSE_MIGRATION_ROADMAP.md -- one Output all-pass page (per physical output).
 * The `repeat(ALL_PASS_SECTIONS_PER_OUTPUT)` loop over generated `INDEX_ALL_PASS` blocks is the
 * roadmap's "generated/repeated Compose rows" test: each section reads/writes its own
 * `base..base+3` slots (enabled / order / freq / Q) with per-`base` `onCommit` closures, and the
 * shared [rememberBmwDspState] snapshot means recomposition stays scoped per section.
 *
 * `DspPager` (still a View) hosts four of these, one per output.
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

    BmwDspTheme {
        BmwPanel(
            title = title,
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            titleColor = bandColor,
            // 4dp (View page()'s left pad) + 40dp (dashboardPanel lean indent).
            leanStart = 44.dp,
            topContentGap = 2.dp,
            sliderLabels = listOf("Frequency", "Q"),
        ) {
            repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
                val base = NativeBmwDspValues.INDEX_ALL_PASS +
                    (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) *
                    NativeBmwDspValues.ALL_PASS_SECTION_WIDTH

                val order = dsp.get(base + 1)
                val selectedOrder = OrderOptions.indices.minByOrNull { abs(OrderOptions[it].second - order) } ?: 0
                BmwDropdownRow(
                    toggleChecked = dsp.isOn(base),
                    onToggleChange = { dsp.commit(base, if (it) 1f else 0f) },
                    options = OrderOptions.map { it.first },
                    selectedIndex = selectedOrder,
                    onSelect = { dsp.commit(base + 1, OrderOptions[it].second) },
                )
                BmwSliderRow(
                    label = "Frequency",
                    value = dsp.get(base + 2),
                    valueRange = 20f..1000f,
                    step = 1f,
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
