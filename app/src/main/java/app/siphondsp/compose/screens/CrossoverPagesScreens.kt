package app.siphondsp.compose.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSectionHeader
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin

private val DefaultAccent = Color(BmwDashboardSkin.SLIDER_DEFAULT_COLOR)
private val NoMirror = IntArray(0)

/**
 * Phase 4 follow-up (COMPOSE_MIGRATION_ROADMAP.md) -- the other two Crossovers & Tilt pages,
 * finishing `CrossoverTiltFragment`'s move to Compose (the Tilt page went in Phase 4).
 *
 * Page 1: the read-only [CrossoverHandoffSurface] (kept as `AndroidView`; it self-updates off
 * the `ACTION_NATIVE_BMW_DSP_UPDATED` broadcast) over the numeric rows that set the crossover --
 * Lowpass / Highpass frequency (each mirrored onto its band's per-output blocks), Subsonic and
 * the linked Mid all-pass alignment (each with an inline enable switch), and a deep link to the
 * full per-output All-pass screen.
 */
@Composable
fun CrossoversPageScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val context = LocalContext.current
    val peqState = remember { BmwPeqState.load(context) }
    var graphMode by remember { mutableStateOf(CrossoverGraphMode.MAGNITUDE) }

    val lowSlider = Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR)
    val midSlider = Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR)
    val linkBlue = Color(BmwDashboardSkin.LIGHT_BLUE)
    val subsonicLabel = stringResource(R.string.bmw_dsp_subsonic_freq)

    // Section-1 Mid all-pass block for each Mid output: the frequency handle aligns the Mid
    // branch's phase through the handoff; L drives, R mirrors.
    val midLeftBase = NativeBmwDspValues.INDEX_ALL_PASS +
        (NativeBmwDspValues.OUTPUT_MID_LEFT * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) *
        NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
    val midRightBase = NativeBmwDspValues.INDEX_ALL_PASS +
        (NativeBmwDspValues.OUTPUT_MID_RIGHT * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) *
        NativeBmwDspValues.ALL_PASS_SECTION_WIDTH

    fun lowPair(field: Int) = intArrayOf(
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, field),
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, field),
    )
    fun midPair(field: Int) = intArrayOf(
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, field),
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, field),
    )

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            BmwPanel(
                title = "",
                modifier = Modifier.fillMaxWidth(),
                leanStart = 84.dp,
                topContentGap = 2.dp,
                sliderLabels = listOf(
                    "Lowpass freq (LR4)", "Highpass freq (LR4)", subsonicLabel, "Mid align (all-pass)",
                ),
            ) {
                // Compose port of NativeBmwDspResponseView (replacing the AndroidView-wrapped
                // CrossoverHandoffSurface). Step A: static frame + per-mode legend only; curves,
                // spectrum and the crossover-Hz marker arrive in Step B.
                BmwSegmentedControl(
                    options = listOf("MAG", "PHASE", "BOTH", "DELAY"),
                    selectedIndex = graphMode.ordinal,
                    onSelect = { graphMode = CrossoverGraphMode.entries[it] },
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
                        .height(180.dp)
                        .padding(bottom = 4.dp),
                )

                DspSliderRow(
                    "Lowpass freq (LR4)", NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f..200f, 1f, "Hz",
                    dsp, lowSlider, mirrors = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                )
                DspSliderRow(
                    "Highpass freq (LR4)", NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 80f..200f, 1f, "Hz",
                    dsp, midSlider, mirrors = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                )

                val subsonicFreqMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_FREQ)
                val subsonicEnMirror = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)
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

                val midAlignFreqMirror = intArrayOf(midRightBase + 2)
                val midAlignEnMirror = intArrayOf(midRightBase)
                BmwSliderRow(
                    label = "Mid align (all-pass)",
                    value = dsp.get(midLeftBase + 2),
                    valueRange = 20f..1000f, step = 1f, unit = "Hz",
                    accentColor = midSlider,
                    onPreview = { dsp.preview(midLeftBase + 2, it, midAlignFreqMirror) },
                    onCommit = { dsp.commit(midLeftBase + 2, it, midAlignFreqMirror) },
                    onValueEntered = { dsp.commit(midLeftBase + 2, it, midAlignFreqMirror) },
                    toggleChecked = dsp.isOn(midLeftBase),
                    onToggleChange = { on -> dsp.commit(midLeftBase, if (on) 1f else 0f, midAlignEnMirror) },
                )

                // Minimal on/off toggle for the subharmonic synthesizer (octave-under enhancer).
                // Band-level controls (freq range, level, gate) are a follow-up once this DSP
                // path is verified; this just exposes the global enable, same shape as the
                // "Limiter" toggle on the Output page.
                BmwSectionHeader(
                    title = "Subharmonic synth",
                    accentColor = lowSlider,
                    fontSize = 13.sp,
                    toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_SUB_ENABLED),
                    onToggleChange = { on ->
                        dsp.commit(NativeBmwDspValues.INDEX_SUB_ENABLED, if (on) 1f else 0f)
                    },
                )
                Text(
                    text = "Open full Subharmonic synth ›",
                    color = linkBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 2.dp, bottom = 6.dp)
                        .clickable {
                            context.startActivity(
                                Intent(context, CrossoverTiltActivity::class.java).putExtra(
                                    CrossoverTiltActivity.EXTRA_WORKSPACE_MODE,
                                    CrossoverTiltActivity.MODE_SUBHARMONIC,
                                ),
                            )
                        },
                )

                Text(
                    text = "Open full All-pass ›",
                    color = linkBlue,
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
    }
}

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
    )
}
