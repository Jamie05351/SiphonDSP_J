package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwGrMeter
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSectionHeader
import app.siphondsp.compose.controls.BmwSliderRow
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

@Composable
fun CompressorDriverPage(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val busMeter = rememberMeterPoll { RootlessAudioProcessorService.nativeBmwBusLimiterMeter() }

    if (LocalContext.current.isHeadUnitDisplay()) {
        BmwDspTheme { HeadUnitDriverPage(dsp, busMeter, modifier) }
        return
    }

    BmwDspTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            BmwPanel(
                title = "Driver protection",
                modifier = Modifier.fillMaxWidth(),
                leanStart = 20.dp,
                leanEnd = 20.dp,
                topContentGap = 2.dp,
                sliderLabels = listOf("Threshold", "Release", "Low bus", "Mid bus", "High bus"),
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

                // Only acts while 3-way is on (High is silent otherwise), but stays editable so
                // it can be set before the tweeters are ever switched in.
                BmwSectionHeader(
                    title = "High bus",
                    accentColor = Color(BmwDashboardSkin.HIGH_BAND_PINK),
                    toggleChecked = dsp.isOn(NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED),
                    onToggleChange = { dsp.commit(NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED, if (it) 1f else 0f) },
                )
                CompressorSliderRow("Threshold", NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_THRESHOLD, -24f..0f, 0.5f, "dB", dsp)
                CompressorSliderRow("Release", NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_RELEASE, 20f..800f, 5f, "ms", dsp)
                BmwGrMeter(busMeter?.getOrNull(2) ?: 0f, stage = MbcBandGrMeter.Stage.LIMITER)
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
    compact: Boolean = false,
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
        // The band page stacks six rows plus the title and meter; 40dp keeps a fingertip-sized
        // target while fitting them all on the 480dp head unit without scrolling.
        sliderMinTouchHeight = if (compact) 40.dp else Dp.Unspecified,
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
        Row(Modifier.artRect(artDp(190, 140, 1040, 292))) {
            BandSliderSpecs.forEach { spec ->
                DspArtKnob(
                    dsp = dsp,
                    label = spec.label,
                    index = idx(spec.field),
                    range = spec.range,
                    step = spec.step,
                    unit = spec.unit,
                    accent = DefaultSliderAccent,
                    diameter = 108.dp,
                    valueWidth = 108.dp,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
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

/** Bus limiters: one matching column per bus -- enable, Threshold, Release, GR meter. */
@Composable
private fun HeadUnitDriverPage(dsp: BmwDspState, busMeter: FloatArray?, modifier: Modifier) {
    WorkspaceArtBox(modifier.fillMaxSize()) {
        BusColumns.forEachIndexed { bus, col ->
            ArtSwitchRow(
                label = col.title,
                checked = dsp.isOn(col.enabled),
                onCheckedChange = { dsp.commit(col.enabled, if (it) 1f else 0f) },
                labelWidth = 120.dp,
                labelColor = Color(col.accent),
                modifier = Modifier.artRect(artDp(col.x, 104, 330, 40)),
            )
            DspArtSlider(
                dsp, "Threshold", col.threshold, -24f..0f, 0.5f, "dB", DefaultSliderAccent,
                Modifier.artRect(artDp(col.x, 152, 330, 86)), labelAbove = true,
            )
            DspArtSlider(
                dsp, "Release", col.release, 20f..800f, 5f, "ms", DefaultSliderAccent,
                Modifier.artRect(artDp(col.x, 246, 330, 86)), labelAbove = true,
            )
            ArtMeterRow(Modifier.artRect(artDp(col.x, 342, 330, 34))) {
                BmwGrMeter(busMeter?.getOrNull(bus) ?: 0f, it, stage = MbcBandGrMeter.Stage.LIMITER)
            }
        }
    }
}

private class BusColumn(val title: String, val accent: Int, val x: Int, val enabled: Int, val threshold: Int, val release: Int)

private val BusColumns = listOf(
    BusColumn(
        "Low bus", BmwDashboardSkin.M_BLUE, 190, NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED,
        NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_THRESHOLD, NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_RELEASE,
    ),
    BusColumn(
        "Mid bus", BmwDashboardSkin.MID_BAND_YELLOW, 540, NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED,
        NativeBmwDspValues.INDEX_BUS_LIMITER_MID_THRESHOLD, NativeBmwDspValues.INDEX_BUS_LIMITER_MID_RELEASE,
    ),
    // Only acts while 3-way is on, but stays editable so it can be set before the tweeters are.
    BusColumn(
        "High bus", BmwDashboardSkin.HIGH_BAND_PINK, 890, NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED,
        NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_THRESHOLD, NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_RELEASE,
    ),
)
