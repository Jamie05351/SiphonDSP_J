package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSectionHeader
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.model.NativeBmwDspValues

/**
 * Measurement signal generator controls (native sweep/pink noise, PRs #344/#345) -- a standalone
 * screen, deliberately separate from `MeasurementCaptureActivity` (that one captures/exports raw
 * input+output WAVs; this one drives what's playing in the first place). Reachable from the main
 * overflow menu, same as Measurement Capture.
 *
 * The OFF/SWEEP/PINK segmented control doubles as both the signal-type selector and the
 * start/stop control: `NativeBmwDspProcessor` treats `measGenType == 0` as fully inactive and any
 * other value as "generating right now" (see `processFrame()`), so there's no separate
 * "armed but stopped" state to model in the UI -- picking a type starts it immediately, and OFF
 * stops it. A distinct Start/Stop button next to a type dropdown would need to track that
 * intermediate state purely in the UI, for no behavioural benefit.
 *
 * Band isolation reuses `INDEX_MEASUREMENT_MUTE` directly -- the same field the "Measurement mute"
 * preference on the main BMW DSP card already writes to (`NativeBmwDspCardFragment`) -- rather
 * than inventing a second mechanism, so the two controls always agree (this satisfies the
 * measurement-generator plan's "wire generator state together with meas_mute_sel" step; the
 * stereo-bus mute-leak fix it also asks to confirm was already shipped, see `rebuildMeasBus()`).
 *
 * Neutral accent colour throughout ([BmwTheme.colors.sliderDefault]) rather than the low/mid band
 * neon colours -- this screen isn't band-specific in that sense.
 */
@Composable
fun SignalGeneratorScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val accent = BmwTheme.colors.sliderDefault

    val type = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_TYPE).toInt().coerceIn(0, 2)
    val band = dsp.get(NativeBmwDspValues.INDEX_MEASUREMENT_MUTE).toInt().coerceIn(0, 2)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.signal_generator_explainer),
            color = BmwTheme.colors.sliderDefault,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 84.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        )

        BmwPanel(
            title = stringResource(R.string.signal_generator_section_signal),
            modifier = Modifier.fillMaxWidth(),
            leanStart = 84.dp,
            sliderLabels = listOf(
                stringResource(R.string.signal_generator_sweep_start),
                stringResource(R.string.signal_generator_sweep_end),
                stringResource(R.string.signal_generator_sweep_duration),
                stringResource(R.string.signal_generator_sweep_level),
                stringResource(R.string.signal_generator_pink_period),
                stringResource(R.string.signal_generator_pink_level),
            ),
        ) {
            BmwSegmentedControl(
                options = listOf("OFF", "SWEEP", "PINK"),
                selectedIndex = type,
                // preview(), not commit(): the active generator type must stay transient runtime
                // state, never written to disk. Otherwise engine init on the next service/device
                // restart would reload a nonzero type and silently replace normal playback with
                // the test signal with no new user action. Sweep/pink parameters below still use
                // onCommit as normal -- only "which generator is currently running" is transient.
                onSelect = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_TYPE, it.toFloat()) },
                optionAccents = listOf(accent, accent, accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                segmentGap = 4.dp,
            )

            BmwSectionHeader(title = "SWEEP", accentColor = accent)
            BmwSliderRow(
                label = stringResource(R.string.signal_generator_sweep_start),
                value = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_START_HZ),
                valueRange = 10f..24000f,
                step = 10f,
                unit = "Hz",
                accentColor = accent,
                enabled = type == 1,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_START_HZ, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_START_HZ, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_START_HZ, it) },
            )
            BmwSliderRow(
                label = stringResource(R.string.signal_generator_sweep_end),
                value = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_END_HZ),
                valueRange = 10f..24000f,
                step = 10f,
                unit = "Hz",
                accentColor = accent,
                enabled = type == 1,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_END_HZ, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_END_HZ, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_END_HZ, it) },
            )
            BmwSliderRow(
                label = stringResource(R.string.signal_generator_sweep_duration),
                value = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_DURATION_S),
                valueRange = 0.5f..60f,
                step = 0.1f,
                unit = "s",
                accentColor = accent,
                enabled = type == 1,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_DURATION_S, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_DURATION_S, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_DURATION_S, it) },
            )
            BmwSliderRow(
                label = stringResource(R.string.signal_generator_sweep_level),
                value = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_LEVEL_DB),
                valueRange = -60f..0f,
                step = 1f,
                unit = "dB",
                accentColor = accent,
                enabled = type == 1,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_LEVEL_DB, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_LEVEL_DB, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_SWEEP_LEVEL_DB, it) },
            )

            BmwSectionHeader(title = "PINK NOISE", accentColor = accent)
            BmwSliderRow(
                label = stringResource(R.string.signal_generator_pink_period),
                value = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_PERIOD_S),
                valueRange = 0.1f..10f,
                step = 0.1f,
                unit = "s",
                accentColor = accent,
                enabled = type == 2,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_PERIOD_S, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_PERIOD_S, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_PERIOD_S, it) },
            )
            BmwSliderRow(
                label = stringResource(R.string.signal_generator_pink_level),
                value = dsp.get(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_LEVEL_DB),
                valueRange = -60f..0f,
                step = 1f,
                unit = "dB",
                accentColor = accent,
                enabled = type == 2,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_LEVEL_DB, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_LEVEL_DB, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MEAS_GEN_PINK_LEVEL_DB, it) },
            )
        }

        BmwPanel(
            title = stringResource(R.string.signal_generator_band_isolation),
            modifier = Modifier.fillMaxWidth(),
            leanStart = 84.dp,
        ) {
            BmwSegmentedControl(
                options = listOf("OFF", "LOW", "MID"),
                selectedIndex = band,
                onSelect = { dsp.commit(NativeBmwDspValues.INDEX_MEASUREMENT_MUTE, it.toFloat()) },
                optionAccents = listOf(accent, accent, accent),
                modifier = Modifier.fillMaxWidth(),
                segmentGap = 4.dp,
            )
        }
    }
}
