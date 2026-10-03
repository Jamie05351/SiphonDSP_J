package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtRow
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSectionHeader
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSegmentedLevelMeter
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.controls.DriverId
import app.siphondsp.compose.controls.SpeakerGeometryState
import app.siphondsp.compose.controls.SpeakerKind
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.controls.rememberSpeakerGeometry
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_AP_ENABLED
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_AP_FREQ
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_DELAY
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_SIDE_LEFT
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_SIDE_RIGHT
import app.siphondsp.model.NativeBmwDspValues.virtualFeedIndex
import app.siphondsp.model.VirtualCentrePreset
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.roundToInt

/**
 * The CENTRE page (Gains & Delay pager, beside ALIGN): the virtual centre for the two-seat tune.
 * See docs/NATIVE_BMW_VIRTUAL_CHANNELS.md. On/off; a preset (Both seats / Driver / Custom, see
 * [VirtualCentrePreset]); live meters of how much centre the engine is finding and how loud it is; centre and side
 * level; the centre's per-side delay; and the "spread" all-pass pair. The detector band and
 * attack/release keep their defaults (not exposed here).
 */
@Composable
fun VirtualCentreScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val geometry = rememberSpeakerGeometry()
    val meter = rememberCentreMeter()
    val headUnit = LocalContext.current.isHeadUnitDisplay()
    BmwDspTheme {
        if (headUnit) HeadUnitCentrePage(dsp, geometry, meter, modifier)
        else PhoneCentrePage(dsp, geometry, meter, modifier)
    }
}

@Composable
private fun HeadUnitCentrePage(dsp: BmwDspState, geometry: SpeakerGeometryState, meter: CentreMeterState, modifier: Modifier) {
    WorkspaceArtBox(modifier.fillMaxSize()) {
        ArtSwitchRow(
            label = "Virtual centre",
            checked = dsp.isOn(NativeBmwDspValues.INDEX_VIRTUAL_ENABLED),
            onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_VIRTUAL_ENABLED, if (on) 1f else 0f) },
            labelColor = CentreAccent,
            labelWidth = 200.dp,
            modifier = Modifier.artRect(artDp(190, 92, 420, 44)),
        )
        PresetControl(dsp, geometry, Modifier.artRect(artDp(640, 92, 590, 44)))
        ArtRow("Centre found", Modifier.artRect(artDp(190, 150, 505, 30)), labelWidth = 150.dp) {
            CentreFoundMeter(meter.found.floatValue, Modifier.weight(1f))
        }
        ArtRow("Centre RMS", Modifier.artRect(artDp(725, 150, 505, 30)), labelWidth = 130.dp) {
            CentreRmsMeter(meter.rmsDb.floatValue, Modifier.weight(1f))
        }
        DspArtSlider(dsp, "Centre level", NativeBmwDspValues.INDEX_VIRTUAL_CENTRE_LEVEL, CentreLevelRange, 0.5f, "dB",
            CentreAccent, Modifier.artRect(artDp(190, 196, 505, 50)))
        DspArtSlider(dsp, "Side level", NativeBmwDspValues.INDEX_VIRTUAL_SIDE_LEVEL, CentreLevelRange, 0.5f, "dB",
            SideAccent, Modifier.artRect(artDp(725, 196, 505, 50)))
        DspArtSlider(dsp, "Delay L", virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_DELAY), CentreDelayRange, 0.01f, "ms",
            DelayAccent, Modifier.artRect(artDp(190, 254, 505, 50)))
        DspArtSlider(dsp, "Delay R", virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_DELAY), CentreDelayRange, 0.01f, "ms",
            DelayAccent, Modifier.artRect(artDp(725, 254, 505, 50)))
        ArtSwitchRow(
            label = "Spread",
            checked = spreadOn(dsp),
            onCheckedChange = { on -> setSpread(dsp, on) },
            labelColor = SpreadAccent,
            labelWidth = 200.dp,
            modifier = Modifier.artRect(artDp(190, 312, 420, 44)),
        )
        DspArtSlider(dsp, "Spread L", virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_AP_FREQ), CentreSpreadRange, 50f, "Hz",
            SpreadAccent, Modifier.artRect(artDp(190, 366, 505, 50)))
        DspArtSlider(dsp, "Spread R", virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_AP_FREQ), CentreSpreadRange, 50f, "Hz",
            SpreadAccent, Modifier.artRect(artDp(725, 366, 505, 50)))
    }
}

@Composable
private fun PhoneCentrePage(dsp: BmwDspState, geometry: SpeakerGeometryState, meter: CentreMeterState, modifier: Modifier) {
    val labels = listOf("Centre level", "Side level", "Delay L", "Delay R", "Spread L", "Spread R")
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BmwPanel(
            title = "Virtual centre",
            modifier = Modifier.fillMaxWidth(),
            toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_VIRTUAL_ENABLED),
            onToggleChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_VIRTUAL_ENABLED, if (on) 1f else 0f) },
            titleColor = CentreAccent,
            leanStart = 20.dp,
            leanEnd = 20.dp,
            topContentGap = 2.dp,
            sliderLabels = labels,
        ) {
            PresetControl(dsp, geometry, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            ArtRow("Centre found", Modifier.fillMaxWidth().padding(vertical = 4.dp), labelWidth = 120.dp) {
                CentreFoundMeter(meter.found.floatValue, Modifier.weight(1f))
            }
            ArtRow("Centre RMS", Modifier.fillMaxWidth().padding(vertical = 4.dp), labelWidth = 120.dp) {
                CentreRmsMeter(meter.rmsDb.floatValue, Modifier.weight(1f))
            }
            PhoneSlider(dsp, labels[0], NativeBmwDspValues.INDEX_VIRTUAL_CENTRE_LEVEL, CentreLevelRange, 0.5f, "dB", CentreAccent)
            PhoneSlider(dsp, labels[1], NativeBmwDspValues.INDEX_VIRTUAL_SIDE_LEVEL, CentreLevelRange, 0.5f, "dB", SideAccent)
            PhoneSlider(dsp, labels[2], virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_DELAY), CentreDelayRange, 0.01f, "ms", DelayAccent)
            PhoneSlider(dsp, labels[3], virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_DELAY), CentreDelayRange, 0.01f, "ms", DelayAccent)
            BmwSectionHeader(
                title = "Spread",
                accentColor = SpreadAccent,
                fontSize = 16.sp,
                toggleChecked = spreadOn(dsp),
                onToggleChange = { on -> setSpread(dsp, on) },
            )
            PhoneSlider(dsp, labels[4], virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_AP_FREQ), CentreSpreadRange, 50f, "Hz", SpreadAccent)
            PhoneSlider(dsp, labels[5], virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_AP_FREQ), CentreSpreadRange, 50f, "Hz", SpreadAccent)
        }
    }
}

@Composable
private fun PhoneSlider(
    dsp: BmwDspState,
    label: String,
    index: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accent: Color,
) = BmwSliderRow(
    label = label,
    value = dsp.get(index),
    valueRange = range, step = step, unit = unit,
    accentColor = accent,
    onPreview = { dsp.preview(index, it) },
    onCommit = { dsp.commit(index, it) },
    onValueEntered = { dsp.commit(index, it) },
)

/** Both seats / Driver / Custom. Shows whichever preset the current values match. */
@Composable
private fun PresetControl(dsp: BmwDspState, geometry: SpeakerGeometryState, modifier: Modifier) {
    val left = geometry.distanceCm(DriverId(SpeakerKind.MID, left = true))
    val right = geometry.distanceCm(DriverId(SpeakerKind.MID, left = false))
    val current = VirtualCentrePreset.detect(dsp::get, left, right)
    BmwSegmentedControl(
        options = listOf("Both seats", "Driver", "Custom"),
        selectedIndex = current.ordinal,
        onSelect = { i ->
            val updates = VirtualCentrePreset.updates(VirtualCentrePreset.entries[i], left, right)
            if (updates.isNotEmpty()) dsp.commitAll(updates)
        },
        modifier = modifier,
        segmentHeight = 40.dp,
        segmentGap = 6.dp,
    )
}

/** How much of the signal the extractor currently reads as centre (0..1). */
@Composable
private fun CentreFoundMeter(weight: Float, modifier: Modifier) {
    BmwSegmentedLevelMeter(
        levelDb = weight,
        valueRange = 0f..1f,
        accentColor = CentreAccent,
        accessibilityLabel = "Centre found",
        modifier = modifier,
    )
}

/**
 * The extracted centre's own level (RMS over ~300 ms, dBFS, -60..0), with its value beside the
 * bar. Shows how loud the virtual centre is, where Centre found shows how much is treated as one.
 */
@Composable
private fun RowScope.CentreRmsMeter(rmsDb: Float, modifier: Modifier) {
    BmwSegmentedLevelMeter(
        levelDb = rmsDb,
        valueRange = -60f..0f,
        accentColor = CentreAccent,
        accessibilityLabel = "Centre RMS",
        modifier = modifier,
    )
    ArtLabel(
        text = if (rmsDb <= -60f) "-- dB" else "${rmsDb.roundToInt()} dB",
        modifier = Modifier.width(64.dp).padding(start = 8.dp),
        textAlign = TextAlign.End,
    )
}

/** The virtual-centre meter's two live values; [found] 0..1 and [rmsDb] dBFS (-60 = none). */
private class CentreMeterState {
    val found = mutableFloatStateOf(0f)
    val rmsDb = mutableFloatStateOf(-60f)
}

/** Polls the virtual-centre meter at ~30 fps while the page is at least STARTED. */
@Composable
private fun rememberCentreMeter(): CentreMeterState {
    val state = remember { CentreMeterState() }
    LifecycleStartEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val values = RootlessAudioProcessorService.nativeBmwVirtualMeter()
                state.found.floatValue = values?.getOrNull(0) ?: 0f
                state.rmsDb.floatValue = values?.getOrNull(1) ?: -60f
                handler.postDelayed(this, 33L)
            }
        }
        handler.post(tick)
        onStopOrDispose { handler.removeCallbacks(tick) }
    }
    return state
}

private fun spreadOn(dsp: BmwDspState): Boolean =
    dsp.isOn(virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_AP_ENABLED)) ||
        dsp.isOn(virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_AP_ENABLED))

/** Spread is the all-pass pair: both sides on or both off together. */
private fun setSpread(dsp: BmwDspState, on: Boolean) {
    val v = if (on) 1f else 0f
    dsp.commitAll(
        mapOf(
            virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_AP_ENABLED) to v,
            virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_AP_ENABLED) to v,
        ),
    )
}

private val CentreLevelRange = -24f..6f
private val CentreDelayRange = 0f..NativeBmwDspValues.STAGE_DELAY_MAX_MS
private val CentreSpreadRange = 100f..10000f
private val CentreAccent = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
private val SideAccent = Color(BmwDashboardSkin.M_GREEN)
private val DelayAccent = Color(BmwDashboardSkin.M_BLUE)
private val SpreadAccent = Color(0xFFF0A608)
