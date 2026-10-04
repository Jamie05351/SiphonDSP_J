package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
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
import app.siphondsp.compose.controls.BmwGrMeter
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CompressorSurfaceMath
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

    val graph: @Composable (Modifier) -> Unit = { m ->
        // Compose port of CompressorSurface: band regions, grid, threshold lines, GR readouts,
        // the live dry/wet spectrum + boost/cut delta fill, and the applied gain-reduction curve.
        CompressorGraph(systemValues = dsp.values, mbcMeter = mbcMeter, modifier = m.clip(RoundedCornerShape(20.dp)))
    }
    val controls: @Composable (MasterRow) -> Unit = { row -> MbcMasterControls(dsp, row) }

    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                graph(Modifier.artRect(artDp(MasterGraphX, 76, MasterGraphWidth, 354)))
                controls { y, h -> Modifier.artRect(artDp(MasterControlX, y, MasterColumnWidth, h)) }
            }
        } else {
            Row(
                modifier = modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                graph(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier
                        .width(MasterColumnWidth.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    controls { _, h -> Modifier.fillMaxWidth().height(h.dp) }
                }
            }
        }
    }
}

/**
 * Where one row of the master column goes: [y] down the head unit's editor space (its position
 * there) and [h] tall in dp. The head unit places it there; the phone stacks the rows in order.
 */
private typealias MasterRow = (y: Int, h: Int) -> Modifier

/**
 * The multiband compressor's master column: its on/off switch, the dry/wet Mix (0% bypasses the
 * compressor, 100% is fully processed), and the three band splits -- where band 1 meets band 2
 * and so on (defaults 80 / 500 / 4000 Hz).
 *
 * The engine sorts the stored splits and clamps each to its slot's bounds before using them, so
 * the controls work from that same normalized triple ([CompressorSurfaceMath.normalizedSplitSlots]):
 * a triple stored out of order (a restored or imported preset) is saved back in the engine's
 * order -- nothing audible changes -- so each control edits the slot it shows. Each split's range
 * then stops a whole tone short of its neighbours ([CompressorSurfaceMath.splitStepRange]), so
 * the −/+ can never push one past another.
 */
@Composable
private fun MbcMasterControls(dsp: BmwDspState, row: MasterRow) {
    val raw = List(3) { dsp.get(NativeBmwDspValues.INDEX_MBC_XO_0 + it) }
    val slots = CompressorSurfaceMath.normalizedSplitSlots(dsp.values)
    LaunchedEffect(raw) {
        if (raw != slots.toList()) {
            dsp.commitAll(List(3) { NativeBmwDspValues.INDEX_MBC_XO_0 + it to slots[it] }.toMap())
        }
    }
    ArtGroupHeader(
        title = "Multiband",
        accent = DefaultSliderAccent,
        modifier = row(80, 40),
        checked = dsp.isOn(NativeBmwDspValues.INDEX_MBC_ENABLED),
        onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_MBC_ENABLED, if (it) 1f else 0f) },
    )
    DspArtSlider(dsp, "Mix", NativeBmwDspValues.INDEX_MBC_MIX, 0f..100f, 1f, "%", DefaultSliderAccent, row(132, 48))
    Box(row(196, 22), contentAlignment = Alignment.BottomStart) {
        ArtLabel("BAND SPLITS", color = Color.White.copy(alpha = 0.55f), size = 13.sp)
    }
    repeat(3) { i ->
        DspArtSlider(
            dsp, "Bands ${i + 1} | ${i + 2}", NativeBmwDspValues.INDEX_MBC_XO_0 + i,
            CompressorSurfaceMath.splitStepRange(slots, i), 1f, "Hz", DefaultSliderAccent, row(222 + i * 58, 48),
        )
    }
}


@Composable
fun CompressorBandPage(band: Int, modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val mbcMeter = rememberMeterPoll { RootlessAudioProcessorService.nativeBmwMbcMeter() }
    val gr = mbcMeter?.getOrNull(band * 3 + 2) ?: 0f

    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)

    // Crossover range this band spans, live off the three MBC split frequencies (defaults
    // 80 / 500 / 4000 Hz) normalized as the engine (and the graph) use them, so a triple stored
    // out of order still labels each band correctly. Band 1 runs from 20 Hz, band 4 to 20 kHz.
    val rangeLabel = run {
        val (lo, hi) = CompressorSurfaceMath.bandRange(band, CompressorSurfaceMath.splitFrequencies(dsp.values))
        "${formatHz(lo.toFloat())} – ${formatHz(maxOf(hi, lo * 1.01).toFloat())}"
    }

    val header: @Composable (Modifier) -> Unit = { m -> BandHeader(band, rangeLabel, dsp, m) }
    val meter: @Composable (Modifier) -> Unit = { m -> ArtMeterRow(m) { BmwGrMeter(gr, it) } }
    val control: @Composable (BandSliderSpec, Modifier) -> Unit = { spec, m ->
        DspArtSlider(dsp, spec.label, idx(spec.field), spec.range, spec.step, spec.unit, DefaultSliderAccent, m)
    }

    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            HeadUnitCompressorBandPage(header, meter, control, modifier)
        } else {
            PhoneCompressorBandPage(header, meter, control, modifier)
        }
    }
}

/** "BAND 1  20 Hz – 80 Hz" on the left, the Enabled and Stereo link switches on the right, and a
 *  rule under them. */
@Composable
private fun BandHeader(band: Int, rangeLabel: String, dsp: BmwDspState, modifier: Modifier) {
    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ArtLabel("BAND ${band + 1}", color = Color.White, size = 18.sp)
            ArtLabel(rangeLabel, Modifier.padding(start = 14.dp).weight(1f))
            ArtLabel("Enabled", Modifier.padding(end = 12.dp))
            BmwSwitch(
                checked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_ENABLED)),
                onCheckedChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_ENABLED), if (it) 1f else 0f) },
                contentDescription = "Band ${band + 1} enabled",
            )
            ArtLabel("Stereo link", Modifier.padding(start = 24.dp, end = 12.dp))
            BmwSwitch(
                checked = dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK)),
                onCheckedChange = { dsp.commit(idx(NativeBmwDspValues.MBC_FIELD_STEREO_LINK), if (it) 1f else 0f) },
                contentDescription = "Band ${band + 1} stereo link",
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(DefaultSliderAccent.copy(alpha = 0.45f)))
    }
}

/** A small heading over one of the band page's two columns. */
@Composable
private fun BandColumnHeading(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        ArtLabel(text.uppercase(), color = Color.White.copy(alpha = 0.55f), size = 13.sp)
    }
}

/** Head unit: the header and meter across both columns, then the two columns of steppers. */
@Composable
private fun HeadUnitCompressorBandPage(
    header: @Composable (Modifier) -> Unit,
    meter: @Composable (Modifier) -> Unit,
    control: @Composable (BandSliderSpec, Modifier) -> Unit,
    modifier: Modifier,
) {
    WorkspaceArtBox(modifier.fillMaxSize()) {
        header(Modifier.artRect(artDp(BandLeftX, BandTop, BandWidth, 46)))
        meter(Modifier.artRect(artDp(BandLeftX, BandTop + 56, BandWidth, 28)))
        listOf(BandLeftX to DynamicsHeading, BandRightX to TimingHeading).forEachIndexed { column, (x, heading) ->
            BandColumnHeading(heading, Modifier.artRect(artDp(x, BandTop + 96, BandColumnWidth, 22)))
            BandColumnSpecs[column].forEachIndexed { row, spec ->
                control(spec, Modifier.artRect(artDp(x, BandTop + 124 + row * 60, BandColumnWidth, 48)))
            }
        }
    }
}

/** Phone: the same arrangement in the phone's workspace. A phone too narrow for the two columns
 *  side by side stacks them, and the page scrolls only if it doesn't fit the height. */
@Composable
private fun PhoneCompressorBandPage(
    header: @Composable (Modifier) -> Unit,
    meter: @Composable (Modifier) -> Unit,
    control: @Composable (BandSliderSpec, Modifier) -> Unit,
    modifier: Modifier,
) {
    val column: @Composable (Int) -> Unit = { i ->
        Column(Modifier.width(BandColumnWidth.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BandColumnHeading(if (i == 0) DynamicsHeading else TimingHeading, Modifier.fillMaxWidth().height(22.dp))
            BandColumnSpecs[i].forEach { control(it, Modifier.fillMaxWidth().height(48.dp)) }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= BandWidth.dp + 24.dp
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(if (sideBySide) BandWidth.dp else BandColumnWidth.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                header(Modifier.fillMaxWidth().height(46.dp))
                meter(Modifier.fillMaxWidth().height(28.dp))
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy((BandWidth - 2 * BandColumnWidth).dp)) {
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

/** The band page's two columns: the dynamics (threshold, ratio, knee), then timing and gain. */
private val BandColumnSpecs = BandSliderSpecs.chunked(3)
private const val DynamicsHeading = "Dynamics"
private const val TimingHeading = "Timing & gain"

// Band page layout, in the 1280x480 editor's dp: the same two 333dp columns as the Output page
// (x 317 and 770), the header and GR meter spanning both. The phone uses the same widths.
private const val BandLeftX = 317
private const val BandRightX = 770
private const val BandColumnWidth = 333
private const val BandWidth = BandRightX + BandColumnWidth - BandLeftX
private const val BandTop = 92

// Master page layout, the same as the Crossovers pages: the graph from x 168, then the 333dp
// controls column ending at x 1250.
private const val MasterGraphX = 168
private const val MasterGraphWidth = 719
private const val MasterControlX = 917
private const val MasterColumnWidth = 333

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
