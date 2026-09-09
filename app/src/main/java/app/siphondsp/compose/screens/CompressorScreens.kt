package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.compose.controls.BmwGrMeter
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSectionHeader
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.controls.BmwTitleRowWithSwitches
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CompressorSurface
import app.siphondsp.view.MbcBandGrMeter

/**
 * Phase 7 of COMPOSE_MIGRATION_ROADMAP.md -- the pre-crossover multiband compressor. Five
 * `DspPager` pages on this screen, each its own `ComposeView`:
 * - [CompressorVisualiserPage] -- the `CompressorSurface` (kept as `AndroidView`) + MBC
 *   enable / dry-wet Mix master strip.
 * - [CompressorBandPage] x4 -- per-band enable + stereo-link, a live GR meter, and the
 *   threshold / ratio / knee / attack / release / makeup sliders.
 *
 * [CompressorDriverPage] -- the per-bus brick-wall limiters (Low bus / Mid bus) -- is defined
 * here too but hosted on the Gains & Delay pager (with the master limiter), not this screen.
 *
 * Each page polls only the meters it shows, on a `LifecycleStartEffect` `Handler` loop scoped to
 * that page's composition -- and since `DspPager` (a `ViewPager2`) only keeps the current page
 * composed, that's effectively the single ~30fps poll the fragment used to run.
 */

private const val MeterTickMs = 33L
private val DefaultSliderAccent = Color(BmwDashboardSkin.SLIDER_DEFAULT_COLOR)

@Composable
fun CompressorVisualiserPage(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val mbcMeter = rememberMeterPoll { RootlessAudioProcessorService.nativeBmwMbcMeter() }

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            AndroidView(
                factory = { CompressorSurface(it, null) },
                update = { surface ->
                    surface.setSystemValues(dsp.values)
                    mbcMeter?.let(surface::setMbcMeter)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .height(220.dp),
            )
            BmwPanel(
                title = "Multiband compressor",
                subtitle = "Pre-crossover, 4 bands. Off = fully bypassed.",
                modifier = Modifier.fillMaxWidth(),
                // 8dp master-container margin + 4dp masterRoot pad + 80dp dashboardPanel lean.
                leanStart = 92.dp,
                sliderLabels = listOf("Mix"),
            ) {
                BmwSliderRow(
                    label = "Mix",
                    value = dsp.get(NativeBmwDspValues.INDEX_MBC_MIX),
                    valueRange = 0f..100f, step = 1f, unit = "%",
                    accentColor = DefaultSliderAccent,
                    onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MBC_MIX, it) },
                    onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MBC_MIX, it) },
                    onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MBC_MIX, it) },
                    toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_MBC_ENABLED),
                    onToggleChange = { dsp.commit(NativeBmwDspValues.INDEX_MBC_ENABLED, if (it) 1f else 0f) },
                )
            }
        }
    }
}

@Composable
fun CompressorBandPage(band: Int, modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val mbcMeter = rememberMeterPoll { RootlessAudioProcessorService.nativeBmwMbcMeter() }
    val gr = mbcMeter?.getOrNull(band * 3 + 2) ?: 0f

    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)

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
                sliderLabels = listOf("Threshold", "Ratio", "Soft knee", "Attack", "Release", "Makeup"),
            ) {
                BmwTitleRowWithSwitches(
                    title = "Band ${band + 1}",
                    enabledChecked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_ENABLED)),
                    onEnabledChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_ENABLED), if (it) 1f else 0f) },
                    secondLabel = "Stereo link",
                    secondChecked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK)),
                    onSecondChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK), if (it) 1f else 0f) },
                )
                BmwGrMeter(gr, Modifier.padding(top = 1.dp, bottom = 4.dp))
                CompressorSliderRow("Threshold", idx(NativeBmwDspValues.MBC_FIELD_THRESHOLD), -48f..0f, 0.5f, "dB", dsp)
                CompressorSliderRow("Ratio", idx(NativeBmwDspValues.MBC_FIELD_RATIO), 1f..20f, 0.1f, ":1", dsp)
                CompressorSliderRow("Soft knee", idx(NativeBmwDspValues.MBC_FIELD_KNEE), 0f..24f, 1f, "dB", dsp)
                CompressorSliderRow("Attack", idx(NativeBmwDspValues.MBC_FIELD_ATTACK), 1f..200f, 1f, "ms", dsp)
                CompressorSliderRow("Release", idx(NativeBmwDspValues.MBC_FIELD_RELEASE), 20f..1000f, 5f, "ms", dsp)
                CompressorSliderRow("Makeup", idx(NativeBmwDspValues.MBC_FIELD_MAKEUP), 0f..12f, 0.1f, "dB", dsp)
            }
        }
    }
}

@Composable
fun CompressorDriverPage(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val busMeter = rememberMeterPoll { RootlessAudioProcessorService.nativeBmwBusLimiterMeter() }

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            BmwPanel(
                title = "Driver protection",
                modifier = Modifier.fillMaxWidth(),
                leanStart = 84.dp,
                topContentGap = 2.dp,
                sliderLabels = listOf("Threshold", "Release", "Low bus", "Mid bus"),
            ) {
                BmwSectionHeader(
                    title = "Low bus",
                    accentColor = Color(BmwDashboardSkin.M_BLUE),
                    toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED),
                    onToggleChange = { dsp.commit(NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED, if (it) 1f else 0f) },
                )
                CompressorSliderRow("Threshold", NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_THRESHOLD, -24f..0f, 0.5f, "dB", dsp)
                CompressorSliderRow("Release", NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_RELEASE, 20f..800f, 5f, "ms", dsp)
                BmwGrMeter(busMeter?.getOrNull(0) ?: 0f, stage = MbcBandGrMeter.Stage.LIMITER)

                BmwSectionHeader(
                    title = "Mid bus",
                    accentColor = Color(BmwDashboardSkin.MID_BAND_YELLOW),
                    toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED),
                    onToggleChange = { dsp.commit(NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED, if (it) 1f else 0f) },
                )
                CompressorSliderRow("Threshold", NativeBmwDspValues.INDEX_BUS_LIMITER_MID_THRESHOLD, -24f..0f, 0.5f, "dB", dsp)
                CompressorSliderRow("Release", NativeBmwDspValues.INDEX_BUS_LIMITER_MID_RELEASE, 20f..800f, 5f, "ms", dsp)
                BmwGrMeter(busMeter?.getOrNull(1) ?: 0f, stage = MbcBandGrMeter.Stage.LIMITER)
            }
        }
    }
}

@Composable
private fun CompressorSliderRow(
    label: String,
    index: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    dsp: BmwDspState,
) {
    BmwSliderRow(
        label = label,
        value = dsp.get(index),
        valueRange = range,
        step = step,
        unit = unit,
        accentColor = DefaultSliderAccent,
        onPreview = { dsp.preview(index, it) },
        onCommit = { dsp.commit(index, it) },
        onValueEntered = { dsp.commit(index, it) },
    )
}

/** Polls [read] at ~30fps while the composition is at least STARTED; returns the latest result. */
@Composable
private fun rememberMeterPoll(read: () -> FloatArray?): FloatArray? {
    var value by remember { mutableStateOf<FloatArray?>(null) }
    val currentRead by rememberUpdatedState(read)
    LifecycleStartEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                value = currentRead()
                handler.postDelayed(this, MeterTickMs)
            }
        }
        handler.post(tick)
        onStopOrDispose { handler.removeCallbacks(tick) }
    }
    return value
}
