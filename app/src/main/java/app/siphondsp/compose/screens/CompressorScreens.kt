package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwGrMeter
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwTitleRowWithSwitches
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.MbcBandGrMeter
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.roundToInt

/**
 * Phase 7 of COMPOSE_MIGRATION_ROADMAP.md -- the pre-crossover multiband compressor. Five pages
 * on a Compose `HorizontalPager` (see `NativeBmwCompressorFragment`):
 * - [CompressorVisualiserPage] -- the `CompressorGraph` (Compose port of `CompressorSurface`) +
 *   MBC enable / dry-wet Mix master strip.
 * - [CompressorBandPage] x4 -- per-band enable + stereo-link, a live GR meter, and the
 *   threshold / ratio / knee / attack / release / makeup sliders.
 *
 * [CompressorDriverPage] -- the per-bus brick-wall limiters (Low bus / Mid bus) -- is defined
 * here too but hosted on the Gains & Delay pager (with the master limiter), not this screen.
 *
 * Each page polls only the meters it shows, on a `LifecycleStartEffect` `Handler` loop scoped to
 * that page's composition -- and since Compose's `HorizontalPager` only composes the current page
 * by default (same as the old `ViewPager2`-based `DspPager` before it), that's effectively the
 * single ~30fps poll the fragment used to run.
 */

private const val MeterTickMs = 33L
private val DefaultSliderAccent = Color(BmwDashboardSkin.SLIDER_DEFAULT_COLOR)
private val LimiterAccent = Color(BmwDashboardSkin.SLIDER_LIMITER_COLOR)

/** "80 Hz" / "1.5 kHz" / "4 kHz" -- compact frequency label for the band crossover subheadings. */
private fun formatHz(hz: Float): String =
    if (hz >= 1_000f) {
        val k = hz / 1_000f
        if (k == k.toInt().toFloat()) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    } else {
        "${hz.roundToInt()} Hz"
    }

@Composable
fun CompressorVisualiserPage(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val mbcMeter = rememberMeterPoll { RootlessAudioProcessorService.nativeBmwMbcMeter() }

    if (LocalContext.current.isHeadUnitDisplay()) {
        BmwDspTheme { HeadUnitVisualiserPage(dsp, mbcMeter, modifier) }
        return
    }

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // Compose port of CompressorSurface: band regions, grid, threshold lines, GR
            // readouts, the live dry/wet spectrum + boost/cut delta fill, and the applied
            // gain-reduction curve.
            CompressorGraph(
                systemValues = dsp.values,
                mbcMeter = mbcMeter,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .height(220.dp),
            )
            BmwPanel(
                title = "Multiband compressor",
                titleFontSize = 18.sp,
                // Enable toggle nested by the header (same pattern as the bus limiters), not on
                // the Mix row.
                toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_MBC_ENABLED),
                onToggleChange = { dsp.commit(NativeBmwDspValues.INDEX_MBC_ENABLED, if (it) 1f else 0f) },
                subtitle = "Mix sets the dry/wet blend: 0% bypasses the compressor, 100% is fully processed. Pre-crossover, 4 bands.",
                modifier = Modifier.fillMaxWidth(),
                leanStart = 20.dp,
                leanEnd = 20.dp,
                sliderLabels = emptyList(),
            ) {
                DspArtKnob(
                    dsp = dsp,
                    label = "Mix",
                    index = NativeBmwDspValues.INDEX_MBC_MIX,
                    range = 0f..100f,
                    step = 1f,
                    unit = "%",
                    accent = DefaultSliderAccent,
                    diameter = 96.dp,
                    modifier = Modifier.fillMaxWidth().height(156.dp),
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

    // Crossover range this band spans, live off the three MBC split frequencies (defaults
    // 80 / 500 / 4000 Hz). Band 1 runs from 20 Hz, band 4 up to 20 kHz.
    val rangeLabel = run {
        val lo = if (band == 0) 20f else dsp.get(NativeBmwDspValues.INDEX_MBC_XO_0 + band - 1)
        val hi = if (band == NativeBmwDspValues.MBC_BAND_COUNT - 1) {
            20_000f
        } else {
            dsp.get(NativeBmwDspValues.INDEX_MBC_XO_0 + band)
        }
        "${formatHz(lo)} – ${formatHz(hi.coerceAtLeast(lo * 1.01f))}"
    }

    if (LocalContext.current.isHeadUnitDisplay()) {
        BmwDspTheme { HeadUnitCompressorBandPage(band, dsp, gr, rangeLabel, modifier) }
        return
    }

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            BmwPanel(
                title = "",
                modifier = Modifier.fillMaxWidth(),
                leanStart = 20.dp,
                leanEnd = 20.dp,
                topContentGap = 2.dp,
                sliderLabels = listOf("Threshold", "Ratio", "Soft knee", "Attack", "Release", "Makeup"),
            ) {
                BmwTitleRowWithSwitches(
                    title = "Band ${band + 1}",
                    subheading = rangeLabel,
                    enabledChecked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_ENABLED)),
                    onEnabledChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_ENABLED), if (it) 1f else 0f) },
                    secondLabel = "Stereo link",
                    secondChecked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK)),
                    onSecondChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK), if (it) 1f else 0f) },
                )
                BmwGrMeter(gr, Modifier.padding(top = 1.dp, bottom = 4.dp))
                CompressorKnobGrid(band, dsp)
            }
        }
    }
}

@Composable
private fun CompressorKnobGrid(band: Int, dsp: BmwDspState) {
    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)

    BandSliderSpecs.chunked(3).forEach { rowSpecs ->
        Row(Modifier.fillMaxWidth().height(154.dp)) {
            rowSpecs.forEach { spec ->
                DspArtKnob(
                    dsp = dsp,
                    label = spec.label,
                    index = idx(spec.field),
                    range = spec.range,
                    step = spec.step,
                    unit = spec.unit,
                    accent = DefaultSliderAccent,
                    diameter = 82.dp,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Where one row of a bus column goes: [y] dp down the column (its head-unit position) and [h] tall.
 * The head unit places the row there; the phone stacks the rows and uses only [h].
 */
private typealias BusRow = (y: Int, h: Int) -> Modifier

/**
 * The bus limiters ("driver protection"), on the Gains & Delay pager: one matching column per bus
 * -- Low, Mid, High -- laid out the same way on both screens (Figma "SiphonDSP Front Panel (from
 * code)", Steppers page). Each column is the bus name in its band colour with its on/off switch,
 * then Threshold and Release (cyan, label above the value box and −/+) and the live
 * gain-reduction meter. The High bus only acts while 3-way is on, but stays editable so it can be
 * set before the tweeters are ever switched in.
 */
@Composable
fun CompressorDriverPage(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val busMeter = rememberMeterPoll { RootlessAudioProcessorService.nativeBmwBusLimiterMeter() }
    val bus: @Composable (Int, BusRow) -> Unit = { i, row -> BusLimiterColumn(dsp, BusColumns[i], busMeter?.getOrNull(i) ?: 0f, row) }

    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                BusColumns.indices.forEach { i ->
                    val x = BusX + i * (BusWidthHeadUnit + BusGapHeadUnit)
                    bus(i) { y, h -> Modifier.artRect(artDp(x, BusTop + y, BusWidthHeadUnit, h)) }
                }
            }
        } else {
            PhoneBusLimiters(modifier, bus)
        }
    }
}

@Composable
private fun BusLimiterColumn(dsp: BmwDspState, col: BusColumn, gainReductionDb: Float, row: BusRow) {
    ArtGroupHeader(
        title = col.title,
        accent = Color(col.accent),
        modifier = row(0, 40),
        checked = dsp.isOn(col.enabled),
        onCheckedChange = { dsp.commit(col.enabled, if (it) 1f else 0f) },
    )
    DspArtSlider(dsp, "Threshold", col.threshold, -24f..0f, 0.5f, "dB", LimiterAccent, row(56, 74), labelAbove = true)
    DspArtSlider(dsp, "Release", col.release, 20f..800f, 5f, "ms", LimiterAccent, row(144, 74), labelAbove = true)
    Column(row(232, 64), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ArtLabel("Gain reduction")
        BmwGrMeter(gainReductionDb, Modifier.fillMaxWidth().height(36.dp), stage = MbcBandGrMeter.Stage.LIMITER)
    }
}

/** Phone: the three columns side by side, centred; a phone too narrow for them stacks them and
 *  scrolls. */
@Composable
private fun PhoneBusLimiters(modifier: Modifier, bus: @Composable (Int, BusRow) -> Unit) {
    val stack: BusRow = { _, h -> Modifier.fillMaxWidth().height(h.dp) }
    val column: @Composable (Int) -> Unit = { i ->
        Column(Modifier.width(BusWidthPhone.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { bus(i, stack) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= BusWidthPhone.dp * 3 + BusGapPhone * 2 + 24.dp
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(BusGapPhone)) { BusColumns.indices.forEach { column(it) } }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { BusColumns.indices.forEach { column(it) } }
            }
        }
    }
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

// ---- Head unit: the same controls placed on the workspace art (REW/_UI/submenu_layout_editor.html)
// so every page fits the 480 dp screen without scrolling.

@Composable
private fun HeadUnitVisualiserPage(dsp: BmwDspState, mbcMeter: FloatArray?, modifier: Modifier) {
    WorkspaceArtBox(modifier.fillMaxSize()) {
        ArtSwitchRow(
            label = "Multiband compressor",
            checked = dsp.isOn(NativeBmwDspValues.INDEX_MBC_ENABLED),
            onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_MBC_ENABLED, if (it) 1f else 0f) },
            labelWidth = 230.dp,
            labelColor = Color.White,
            modifier = Modifier.artRect(artDp(190, 72, 400, 38)),
        )
        CompressorGraph(
            systemValues = dsp.values,
            mbcMeter = mbcMeter,
            modifier = Modifier.artRect(artDp(190, 116, 820, 256)).clip(RoundedCornerShape(20.dp)),
        )
        DspArtKnob(
            dsp = dsp,
            label = "Mix",
            index = NativeBmwDspValues.INDEX_MBC_MIX,
            range = 0f..100f,
            step = 1f,
            unit = "%",
            accent = DefaultSliderAccent,
            diameter = 116.dp,
            valueWidth = 112.dp,
            modifier = Modifier.artRect(artDp(1030, 126, 200, 240)),
        )
    }
}

@Composable
private fun HeadUnitCompressorBandPage(band: Int, dsp: BmwDspState, gr: Float, rangeLabel: String, modifier: Modifier) {
    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)

    WorkspaceArtBox(modifier.fillMaxSize()) {
        ArtSwitchRow(
            label = "Enabled",
            checked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_ENABLED)),
            onCheckedChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_ENABLED), if (it) 1f else 0f) },
            labelWidth = 100.dp,
            modifier = Modifier.artRect(artDp(190, 72, 210, 36)),
        )
        ArtSwitchRow(
            label = "Stereo link",
            checked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK)),
            onCheckedChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK), if (it) 1f else 0f) },
            labelWidth = 120.dp,
            modifier = Modifier.artRect(artDp(420, 72, 230, 36)),
        )
        Box(Modifier.artRect(artDp(790, 72, 440, 36)), contentAlignment = Alignment.CenterEnd) {
            ArtLabel(rangeLabel)
        }
        ArtMeterRow(Modifier.artRect(artDp(190, 112, 1040, 20))) { BmwGrMeter(gr, it) }
        // Two rows of three: a value box and -/+ is wider than the knob it replaced, so six no
        // longer fit across one row.
        Column(Modifier.artRect(artDp(190, 140, 1040, 292))) {
            BandSliderSpecs.chunked(3).forEach { rowSpecs ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    rowSpecs.forEach { spec ->
                        DspArtKnob(
                            dsp = dsp,
                            label = spec.label,
                            index = idx(spec.field),
                            range = spec.range,
                            step = spec.step,
                            unit = spec.unit,
                            accent = DefaultSliderAccent,
                            valueWidth = 108.dp,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

private class BandSliderSpec(
    val label: String,
    val field: Int,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val unit: String,
)

private val BandSliderSpecs = listOf(
    BandSliderSpec("Threshold", NativeBmwDspValues.MBC_FIELD_THRESHOLD, -48f..0f, 0.5f, "dB"),
    BandSliderSpec("Ratio", NativeBmwDspValues.MBC_FIELD_RATIO, 1f..20f, 0.1f, ":1"),
    BandSliderSpec("Soft knee", NativeBmwDspValues.MBC_FIELD_KNEE, 0f..24f, 1f, "dB"),
    BandSliderSpec("Attack", NativeBmwDspValues.MBC_FIELD_ATTACK, 1f..200f, 1f, "ms"),
    BandSliderSpec("Release", NativeBmwDspValues.MBC_FIELD_RELEASE, 20f..1000f, 5f, "ms"),
    BandSliderSpec("Makeup", NativeBmwDspValues.MBC_FIELD_MAKEUP, 0f..12f, 0.1f, "dB"),
)

private class BusColumn(val title: String, val accent: Int, val enabled: Int, val threshold: Int, val release: Int)

// Head-unit layout, in the 1280x480 editor's dp: three 220dp columns, 120dp apart, centred across
// the content area (x 190..1230). The phone's columns are 200dp, 40dp apart.
private const val BusWidthHeadUnit = 220
private const val BusGapHeadUnit = 120
private const val BusX = 260
private const val BusTop = 104
private const val BusWidthPhone = 200
private val BusGapPhone = 40.dp

private val BusColumns = listOf(
    BusColumn(
        "Low bus", BmwDashboardSkin.SLIDER_LOW_BAND_COLOR, NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED,
        NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_THRESHOLD, NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_RELEASE,
    ),
    BusColumn(
        "Mid bus", BmwDashboardSkin.SLIDER_MID_BAND_COLOR, NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED,
        NativeBmwDspValues.INDEX_BUS_LIMITER_MID_THRESHOLD, NativeBmwDspValues.INDEX_BUS_LIMITER_MID_RELEASE,
    ),
    // Only acts while 3-way is on, but stays editable so it can be set before the tweeters are.
    BusColumn(
        "High bus", BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR, NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED,
        NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_THRESHOLD, NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_RELEASE,
    ),
)
