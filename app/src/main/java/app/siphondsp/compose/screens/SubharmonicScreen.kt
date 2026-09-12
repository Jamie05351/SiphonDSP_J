package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin

/**
 * Subharmonic synthesizer full-control screen (Task 5 follow-up to the Task 4 DSP + minimal
 * toggle). A [app.siphondsp.view.DspPager] of four pages, hosted by
 * [app.siphondsp.fragment.SubharmonicFragment]: three per-band pages ([SubharmonicBandPage]) and
 * one [SubharmonicGlobalPage] for the ceiling, the live headroom recommendation, per-output
 * meters and momentary solo.
 *
 * Every control writes into the same `NativeBmwDspValues` v[192..214] block the Task 4 DSP engine
 * already reads via `configure()`; solo and the per-output meters are NOT config-array values --
 * they go through the dedicated [RootlessAudioProcessorService.setNativeBmwSubharmonicSolo] /
 * [RootlessAudioProcessorService.nativeBmwSubharmonicMeter] JNI bridges (mirroring the existing
 * meter-read bridges) since they're runtime-only, per Task 4 §5/§6.
 */

private const val MeterTickMs = 33L

@Composable
fun SubharmonicBandPage(
    band: Int,
    title: String,
    accentColor: Color,
    freqLoRange: ClosedFloatingPointRange<Float>,
    freqHiRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val dsp = rememberBmwDspState()
    fun idx(field: Int) = NativeBmwDspValues.subBandIndex(band, field)

    BmwDspTheme {
        Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            BmwPanel(
                title = title,
                titleColor = accentColor,
                modifier = Modifier.fillMaxWidth(),
                leanStart = 84.dp,
                topContentGap = 2.dp,
                toggleChecked = dsp.isOn(idx(NativeBmwDspValues.SUB_FIELD_ENABLED)),
                onToggleChange = {
                    dsp.commit(idx(NativeBmwDspValues.SUB_FIELD_ENABLED), if (it) 1f else 0f)
                },
                sliderLabels = listOf(
                    "Freq lo", "Freq hi", "Level", "Gate mode", "Gate depth", "Gate hold",
                ),
            ) {
                BandSlider("Freq lo", idx(NativeBmwDspValues.SUB_FIELD_FREQ_LO), freqLoRange, 1f, "Hz", accentColor, dsp)
                BandSlider("Freq hi", idx(NativeBmwDspValues.SUB_FIELD_FREQ_HI), freqHiRange, 1f, "Hz", accentColor, dsp)
                BandSlider("Level", idx(NativeBmwDspValues.SUB_FIELD_LEVEL_DB), 0f..12f, 0.5f, "dB", accentColor, dsp)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                    Text(
                        text = "Gate mode",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.width(84.dp),
                    )
                    BmwSegmentedControl(
                        options = listOf("OFF", "PERCUSSIVE", "SUSTAINED"),
                        selectedIndex = dsp.get(idx(NativeBmwDspValues.SUB_FIELD_GATE_MODE)).toInt().coerceIn(0, 2),
                        onSelect = { dsp.commit(idx(NativeBmwDspValues.SUB_FIELD_GATE_MODE), it.toFloat()) },
                        modifier = Modifier.weight(1f),
                    )
                }
                BandSlider("Gate depth", idx(NativeBmwDspValues.SUB_FIELD_GATE_DEPTH_PCT), 0f..100f, 1f, "%", accentColor, dsp)
                BandSlider("Gate hold", idx(NativeBmwDspValues.SUB_FIELD_GATE_HOLD_MS), 0f..500f, 5f, "ms", accentColor, dsp)
            }
        }
    }
}

@Composable
fun SubharmonicGlobalPage(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val ceilingColor = Color(BmwDashboardSkin.M_BLUE)
    val meter = rememberSubharmonicMeterPoll()
    val headroomDb = rememberHeadroomPoll()

    BmwDspTheme {
        Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            BmwPanel(
                title = "Sub ceiling",
                titleColor = ceilingColor,
                modifier = Modifier.fillMaxWidth(),
                leanStart = 84.dp,
                topContentGap = 2.dp,
                sliderLabels = listOf("Ceiling"),
            ) {
                BandSlider(
                    "Ceiling", NativeBmwDspValues.INDEX_SUB_CEILING_DB, -12f..0f, 0.5f, "dBFS",
                    ceilingColor, dsp,
                )
                // Read-only recommendation, not an editable control -- see
                // NativeBmwDspProcessor::readSubharmonicHeadroomDb.
                Text(
                    text = "Suggested preamp cut: %.1f dB".format(headroomDb),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            BmwPanel(
                title = "Per-output level / solo",
                titleColor = Color.White,
                modifier = Modifier.fillMaxWidth(),
                leanStart = 84.dp,
                topContentGap = 2.dp,
            ) {
                OutputMeterRow("Low L", NativeBmwDspValues.OUTPUT_LOW_LEFT, meter?.getOrNull(0), Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR))
                OutputMeterRow("Low R", NativeBmwDspValues.OUTPUT_LOW_RIGHT, meter?.getOrNull(1), Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR))
                OutputMeterRow("Mid L", NativeBmwDspValues.OUTPUT_MID_LEFT, meter?.getOrNull(2), Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR))
                OutputMeterRow("Mid R", NativeBmwDspValues.OUTPUT_MID_RIGHT, meter?.getOrNull(3), Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR))
            }
        }
    }
}

@Composable
private fun BandSlider(
    label: String,
    index: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accentColor: Color,
    dsp: BmwDspState,
) {
    BmwSliderRow(
        label = label,
        value = dsp.get(index),
        valueRange = range,
        step = step,
        unit = unit,
        accentColor = accentColor,
        onPreview = { dsp.preview(index, it) },
        onCommit = { dsp.commit(index, it) },
        onValueEntered = { dsp.commit(index, it) },
    )
}

/**
 * One output's live injected level (dBFS, read-only text -- the existing GR meter widget
 * ([app.siphondsp.compose.controls.BmwGrMeter]) is calibrated for "amount of gain reduction",
 * clamped to >= 0; this is a signed peak *level*, a different quantity, so reusing it as-is would
 * always read empty/0 for any real (negative dBFS) value. A plain readout avoids that mismatch
 * rather than repurposing a widget built for a different measurement.) Below it, a momentary
 * press-and-hold button: no existing solo/press-hold control exists anywhere in the app (checked)
 * so this uses the plain `pointerInput` + `detectTapGestures(onPress, tryAwaitRelease)` idiom.
 */
@Composable
private fun OutputMeterRow(label: String, outputId: Int, levelDb: Float?, accentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            color = accentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = if (levelDb == null || levelDb <= -99f) "-- dBFS" else "%.1f dBFS".format(levelDb),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        SoloButton(outputId, accentColor)
    }
}

@Composable
private fun SoloButton(outputId: Int, accentColor: Color) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(64.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) accentColor else accentColor.copy(alpha = 0.25f))
            .pointerInput(outputId) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        RootlessAudioProcessorService.setNativeBmwSubharmonicSolo(outputId)
                        tryAwaitRelease()
                        pressed = false
                        RootlessAudioProcessorService.setNativeBmwSubharmonicSolo(-1)
                    },
                )
            },
    ) {
        Text(text = "SOLO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Polls the 4-float per-output meter at ~30fps while the composition is at least STARTED. */
@Composable
private fun rememberSubharmonicMeterPoll(): FloatArray? {
    var value by remember { mutableStateOf<FloatArray?>(null) }
    LifecycleStartEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                value = RootlessAudioProcessorService.nativeBmwSubharmonicMeter()
                handler.postDelayed(this, MeterTickMs)
            }
        }
        handler.post(tick)
        onStopOrDispose { handler.removeCallbacks(tick) }
    }
    return value
}

/** Polls the suggested-preamp-cut headroom reading at ~30fps -- live as ceiling/level move. */
@Composable
private fun rememberHeadroomPoll(): Float {
    var value by remember { mutableStateOf(0f) }
    LifecycleStartEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                value = RootlessAudioProcessorService.nativeBmwSubharmonicHeadroomDb() ?: 0f
                handler.postDelayed(this, MeterTickMs)
            }
        }
        handler.post(tick)
        onStopOrDispose { handler.removeCallbacks(tick) }
    }
    return value
}
