package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtRow
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSegmentedLevelMeter
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
 * [VirtualCentrePreset]); live meters of how much centre the engine is finding and how loud it is;
 * then three columns of steppers -- Level (centre and side), Delay (the centre's per-side delay)
 * and Spread (the all-pass pair, with its switch). Laid out the same way on both screens (Figma
 * "SiphonDSP Front Panel (from code)", Steppers page). The detector band and attack/release keep
 * their defaults (not exposed here).
 */
@Composable
fun VirtualCentreScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val geometry = rememberSpeakerGeometry()
    val meter = rememberCentreMeter()
    val parts = centreParts(dsp, geometry, meter)
    val headUnit = LocalContext.current.isHeadUnitDisplay()
    BmwDspTheme {
        if (headUnit) HeadUnitCentrePage(parts, modifier) else PhoneCentrePage(parts, modifier)
    }
}

/**
 * Where one row of a column goes: [y] dp down the column (its head-unit position) and [h] tall.
 * The head unit places the row there; the phone stacks the rows and uses only [h].
 */
private typealias CentreRow = (y: Int, h: Int) -> Modifier

/** The page's pieces, each written once and placed by [HeadUnitCentrePage] or [PhoneCentrePage]. */
private class CentreParts(
    val header: @Composable (Modifier) -> Unit,
    val preset: @Composable (Modifier) -> Unit,
    val foundMeter: @Composable (Modifier) -> Unit,
    val rmsMeter: @Composable (Modifier) -> Unit,
    /** The three columns -- Level, Delay, Spread -- each a heading and two steppers. */
    val columns: List<@Composable (CentreRow) -> Unit>,
)

private fun centreParts(dsp: BmwDspState, geometry: SpeakerGeometryState, meter: CentreMeterState): CentreParts {
    val stepper: @Composable (String, Int, ClosedFloatingPointRange<Float>, Float, String, Color, Modifier) -> Unit =
        { label, index, range, step, unit, accent, m -> DspArtSlider(dsp, label, index, range, step, unit, accent, m, labelAbove = true) }
    return CentreParts(
        header = { m ->
            ArtGroupHeader(
                title = "Virtual centre",
                accent = CentreAccent,
                modifier = m,
                checked = dsp.isOn(NativeBmwDspValues.INDEX_VIRTUAL_ENABLED),
                onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_VIRTUAL_ENABLED, if (on) 1f else 0f) },
            )
        },
        preset = { m -> PresetControl(dsp, geometry, m) },
        foundMeter = { m ->
            ArtRow("Centre found", m, labelWidth = MeterLabelWidth) { CentreFoundMeter(meter.found.floatValue, Modifier.weight(1f)) }
        },
        rmsMeter = { m ->
            ArtRow("Centre RMS", m, labelWidth = MeterLabelWidth) { CentreRmsMeter(meter.rmsDb.floatValue, Modifier.weight(1f)) }
        },
        columns = listOf(
            { row ->
                ArtGroupHeader("Level", Color.White, row(0, 40))
                stepper("Centre", NativeBmwDspValues.INDEX_VIRTUAL_CENTRE_LEVEL, CentreLevelRange, 0.5f, "dB", CentreAccent, row(52, 74))
                stepper("Side", NativeBmwDspValues.INDEX_VIRTUAL_SIDE_LEVEL, CentreLevelRange, 0.5f, "dB", SideAccent, row(138, 74))
            },
            { row ->
                ArtGroupHeader("Delay", Color.White, row(0, 40))
                stepper("Left", virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_DELAY), CentreDelayRange, 0.01f, "ms", DelayAccent, row(52, 74))
                stepper("Right", virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_DELAY), CentreDelayRange, 0.01f, "ms", DelayAccent, row(138, 74))
            },
            { row ->
                ArtGroupHeader("Spread", SpreadAccent, row(0, 40), checked = spreadOn(dsp), onCheckedChange = { on -> setSpread(dsp, on) })
                stepper("Left", virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_AP_FREQ), CentreSpreadRange, 50f, "Hz", SpreadAccent, row(52, 74))
                stepper("Right", virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_AP_FREQ), CentreSpreadRange, 50f, "Hz", SpreadAccent, row(138, 74))
            },
        ),
    )
}

/** Head unit: header and preset across the top, the two meters, then the three columns. */
@Composable
private fun HeadUnitCentrePage(parts: CentreParts, modifier: Modifier) {
    val half = (ContentWidth - 24) / 2
    WorkspaceArtBox(modifier.fillMaxSize()) {
        parts.header(Modifier.artRect(artDp(ContentX, Top, half, 40)))
        parts.preset(Modifier.artRect(artDp(ContentX + half + 24, Top, half, 40)))
        parts.foundMeter(Modifier.artRect(artDp(ContentX, Top + 54, half, 26)))
        parts.rmsMeter(Modifier.artRect(artDp(ContentX + half + 24, Top + 54, half, 26)))
        val gap = (ContentWidth - 3 * ColumnWidthHeadUnit) / 2
        parts.columns.forEachIndexed { i, column ->
            val x = ContentX + i * (ColumnWidthHeadUnit + gap)
            column { y, h -> Modifier.artRect(artDp(x, Top + 98 + y, ColumnWidthHeadUnit, h)) }
        }
    }
}

/** Phone: the same arrangement, the columns side by side; a phone too narrow for them stacks
 *  them, and the page scrolls only if it doesn't fit. */
@Composable
private fun PhoneCentrePage(parts: CentreParts, modifier: Modifier) {
    val stack: CentreRow = { _, h -> Modifier.fillMaxWidth().height(h.dp) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = (maxWidth - 24.dp).coerceAtMost(PhoneMaxWidth)
        val sideBySide = width >= ColumnWidthPhone * 3 + 32.dp
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    parts.header(Modifier.weight(1f).height(40.dp))
                    parts.preset(Modifier.weight(1f).height(40.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    parts.foundMeter(Modifier.weight(1f).height(26.dp))
                    parts.rmsMeter(Modifier.weight(1f).height(26.dp))
                }
                val column: @Composable (Int) -> Unit = { i ->
                    Column(Modifier.width(ColumnWidthPhone), verticalArrangement = Arrangement.spacedBy(12.dp)) { parts.columns[i](stack) }
                }
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { repeat(3) { column(it) } }
                } else {
                    repeat(3) { column(it) }
                }
            }
        }
    }
}

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

// Head-unit layout, in the 1280x480 editor's dp: the content area x 190..1230, three 300dp
// columns. The phone's columns are 233dp, within at most 749dp.
private const val ContentX = 190
private const val ContentWidth = 1040
private const val Top = 80
private const val ColumnWidthHeadUnit = 300
private val ColumnWidthPhone = 233.dp
private val PhoneMaxWidth = 749.dp
private val MeterLabelWidth = 120.dp

private val CentreLevelRange = -24f..6f
private val CentreDelayRange = 0f..NativeBmwDspValues.STAGE_DELAY_MAX_MS
private val CentreSpreadRange = 100f..10000f
private val CentreAccent = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
private val SideAccent = Color(BmwDashboardSkin.M_GREEN)
private val DelayAccent = Color(BmwDashboardSkin.M_BLUE)
private val SpreadAccent = Color(0xFFF0A608)
