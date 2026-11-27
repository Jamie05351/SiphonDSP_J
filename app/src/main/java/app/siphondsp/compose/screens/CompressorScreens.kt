package app.siphondsp.compose.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.DspColors
import app.siphondsp.dsp.MbcBandPreset
import app.siphondsp.dsp.CompressorCurve
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import app.siphondsp.interop.NativeDsp
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
 * - [CompressorDriverPage] -- LIMITERS: the per-bus brick-wall limiters (Low / Mid / High bus).
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
fun CompressorVisualiserPage(modifier: Modifier = Modifier, onOpenBand: ((Int) -> Unit)? = null) {
    val dsp = rememberBmwDspState()
    val mbcMeter = rememberMeterPoll(NativeDsp.METER_MBC, MbcMeterSize)

    val graph: @Composable (Modifier) -> Unit = { m ->
        // Compose port of CompressorSurface: band regions, grid, threshold lines, GR readouts,
        // the live dry/wet spectrum + boost/cut delta fill, and the applied gain-reduction curve.
        // The meter goes in as a provider the graph calls from its draw lambda, so a meter tick
        // redraws the graph without recomposing this page.
        CompressorGraph(
            systemValues = dsp.values, mbcMeter = mbcMeter::latest, modifier = m.clip(RoundedCornerShape(20.dp)),
            onBandTap = onOpenBand,
        )
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
        accent = DspColors.Comp,
        modifier = row(80, 40),
        checked = dsp.isOn(NativeBmwDspValues.INDEX_MBC_ENABLED),
        onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_MBC_ENABLED, if (it) 1f else 0f) },
    )
    DspArtSlider(dsp, "Mix", NativeBmwDspValues.INDEX_MBC_MIX, 0f..100f, 1f, "%", DefaultSliderAccent, row(132, 48))
    Box(row(196, 22), contentAlignment = Alignment.BottomStart) {
        ArtLabel("BAND SPLITS", color = Color.White.copy(alpha = 0.55f), size = 13.sp)
    }
    repeat(3) { i ->
        // Each split in the colour of the band below it: B1 | B2 in coral, and so on.
        DspArtSlider(
            dsp, "Bands ${i + 1} | ${i + 2}", NativeBmwDspValues.INDEX_MBC_XO_0 + i,
            CompressorSurfaceMath.splitStepRange(slots, i), 1f, "Hz", Color(MbcBandColors[i]), row(222 + i * 58, 48),
        )
    }
}


/**
 * The BANDS page: one band at a time, picked with B1 | B2 | B3 | B4 (each in its band colour,
 * [MbcBandColors]). Head unit: the band's compressor curve (level in against out, threshold,
 * knee and ratio as the engine computes them, with a live dot at the band's current input level)
 * and its gain-reduction meter on the left; the header, the Level and Timing steppers and the
 * Gentle / Medium / Firm starting points on the right. Phone (landscape): the selector and
 * starting points above the band's usual controls.
 */
@Composable
fun CompressorBandsPage(band: Int, onBand: (Int) -> Unit, modifier: Modifier = Modifier) {
    val selector: @Composable (Modifier) -> Unit = { m ->
        BmwSegmentedControl(
            options = List(CompressorSurfaceMath.BAND_COUNT) { "B${it + 1}" },
            selectedIndex = band,
            onSelect = onBand,
            optionAccents = MbcBandColors.map { Color(it) },
            modifier = m,
            segmentHeight = 38.dp,
            segmentGap = 4.dp,
        )
    }
    if (LocalContext.current.isHeadUnitDisplay()) {
        BmwDspTheme { HeadUnitBandsPage(band, selector, modifier) }
    } else {
        Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                selector(Modifier.width(260.dp))
                BandPresets(rememberBmwDspState(), band, Modifier.weight(1f).height(40.dp))
            }
            key(band) { CompressorBandPage(band, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HeadUnitBandsPage(band: Int, selector: @Composable (Modifier) -> Unit, modifier: Modifier) {
    val dsp = rememberBmwDspState()
    val mbcMeter = rememberMeterPoll(NativeDsp.METER_MBC, MbcMeterSize)
    val color = Color(MbcBandColors[band])
    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)
    val rangeLabel = run {
        val (lo, hi) = CompressorSurfaceMath.bandRange(band, CompressorSurfaceMath.splitFrequencies(dsp.values))
        "${formatHz(lo.toFloat())} – ${formatHz(maxOf(hi, lo * 1.01).toFloat())}"
    }
    WorkspaceArtBox(modifier.fillMaxSize()) {
        selector(Modifier.artRect(artDp(152, 76, 300, 44)))
        Box(Modifier.artRect(artDp(466, 76, 160, 44)), contentAlignment = Alignment.CenterStart) {
            ArtLabel(rangeLabel, color = color, size = 15.sp)
        }
        CompressorCurveGraph(
            threshold = dsp.get(idx(NativeBmwDspValues.MBC_FIELD_THRESHOLD)).toDouble(),
            ratio = dsp.get(idx(NativeBmwDspValues.MBC_FIELD_RATIO)).toDouble(),
            knee = dsp.get(idx(NativeBmwDspValues.MBC_FIELD_KNEE)).toDouble(),
            makeup = dsp.get(idx(NativeBmwDspValues.MBC_FIELD_MAKEUP)).toDouble(),
            color = color,
            liveInputDb = { mbcMeter.latest()?.getOrNull(band * 3)?.toDouble() },
            enabled = dsp.isOn(NativeBmwDspValues.INDEX_MBC_ENABLED) && dsp.isOn(idx(NativeBmwDspValues.MBC_FIELD_ENABLED)),
            modifier = Modifier.artRect(artDp(152, 128, 474, 262)),
        )
        Column(Modifier.artRect(artDp(152, 398, 474, 58)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArtLabel("Gain reduction")
            BmwGrMeter({ mbcMeter.value(band * 3 + 2, 0f) }, Modifier.fillMaxWidth().height(30.dp))
        }
        BandHeader(band, "", dsp, Modifier.artRect(artDp(646, 76, 604, 46)))
        BandColumnHeading("Level", Modifier.artRect(artDp(646, 128, 300, 20)))
        BandColumnHeading("Timing", Modifier.artRect(artDp(646, 312, 300, 20)))
        val level = listOf(0, 1, 2, 5).map { BandSliderSpecs[it] } // threshold, ratio, knee, makeup
        level.forEachIndexed { i, spec ->
            DspArtSlider(dsp, spec.label, idx(spec.field), spec.range, spec.step, spec.unit, color,
                Modifier.artRect(artDp(646 + (i % 2) * 304, 150 + (i / 2) * 80, 296, 74)), labelAbove = true)
        }
        listOf(3, 4).map { BandSliderSpecs[it] }.forEachIndexed { i, spec ->
            DspArtSlider(dsp, spec.label, idx(spec.field), spec.range, spec.step, spec.unit, color,
                Modifier.artRect(artDp(646 + i * 304, 334, 296, 74)), labelAbove = true)
        }
        BandPresets(dsp, band, Modifier.artRect(artDp(646, 418, 604, 40)))
    }
}

/** Gentle / Medium / Firm: one tap sets the band's ratio, knee, attack and release ([MbcBandPreset]). */
@Composable
private fun BandPresets(dsp: BmwDspState, band: Int, modifier: Modifier) {
    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)
    val current = MbcBandPreset.entries.indexOfFirst { p ->
        dsp.get(idx(NativeBmwDspValues.MBC_FIELD_RATIO)) == p.ratio && dsp.get(idx(NativeBmwDspValues.MBC_FIELD_KNEE)) == p.knee &&
            dsp.get(idx(NativeBmwDspValues.MBC_FIELD_ATTACK)) == p.attackMs && dsp.get(idx(NativeBmwDspValues.MBC_FIELD_RELEASE)) == p.releaseMs
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ArtLabel("Start from")
        BmwSegmentedControl(
            options = MbcBandPreset.entries.map { it.label },
            selectedIndex = current,
            onSelect = { i ->
                val p = MbcBandPreset.entries[i]
                dsp.commitAll(mapOf(
                    idx(NativeBmwDspValues.MBC_FIELD_RATIO) to p.ratio,
                    idx(NativeBmwDspValues.MBC_FIELD_KNEE) to p.knee,
                    idx(NativeBmwDspValues.MBC_FIELD_ATTACK) to p.attackMs,
                    idx(NativeBmwDspValues.MBC_FIELD_RELEASE) to p.releaseMs,
                ))
            },
            optionAccents = List(MbcBandPreset.entries.size) { Color(MbcBandColors[band]) },
            modifier = Modifier.weight(1f),
            segmentHeight = 34.dp,
            segmentGap = 4.dp,
        )
    }
}

/**
 * The band's static curve: input level along the bottom, output up the side (-60..0 dB in,
 * -60..+6 dB out), the 1:1 line dashed, the knee shaded, the threshold dashed in the band colour,
 * and a white dot at the band's live input level riding the curve. The dot is read in the draw
 * phase only, so the meter tick redraws the canvas, not the page.
 */
@Composable
private fun CompressorCurveGraph(
    threshold: Double,
    ratio: Double,
    knee: Double,
    makeup: Double,
    color: Color,
    liveInputDb: () -> Double?,
    enabled: Boolean,
    modifier: Modifier,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.Canvas(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xE60B0C0F))
            .border(1.5.dp, color.copy(alpha = .7f), RoundedCornerShape(14.dp)),
    ) {
        val padL = 40.dp.toPx(); val padR = 12.dp.toPx(); val padT = 14.dp.toPx(); val padB = 26.dp.toPx()
        val w = size.width - padL - padR; val h = size.height - padT - padB
        fun x(db: Double) = padL + ((db + 60) / 60).toFloat() * w
        fun y(db: Double) = padT + ((OutTopDb - db) / (OutTopDb + 60)).toFloat().coerceIn(0f, 1f) * h
        val grid = Color(0xFF23262D)
        val labelStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF9AA1AB), fontSize = 10.sp)
        for (d in listOf(-48.0, -36.0, -24.0, -12.0, 0.0)) {
            drawLine(grid, androidx.compose.ui.geometry.Offset(x(d), padT), androidx.compose.ui.geometry.Offset(x(d), padT + h), 1.dp.toPx())
            drawLine(grid, androidx.compose.ui.geometry.Offset(padL, y(d)), androidx.compose.ui.geometry.Offset(padL + w, y(d)), 1.dp.toPx())
            val tx = measurer.measure(d.toInt().toString(), labelStyle)
            drawText(tx, topLeft = androidx.compose.ui.geometry.Offset(x(d) - tx.size.width / 2f, padT + h + 6.dp.toPx()))
            drawText(tx, topLeft = androidx.compose.ui.geometry.Offset(padL - tx.size.width - 6.dp.toPx(), y(d) - tx.size.height / 2f))
        }
        // Knee region and threshold.
        if (knee > 0) {
            drawRect(color.copy(alpha = .08f), androidx.compose.ui.geometry.Offset(x(threshold - knee / 2), padT),
                androidx.compose.ui.geometry.Size(x(threshold + knee / 2) - x(threshold - knee / 2), h))
        }
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        drawLine(color, androidx.compose.ui.geometry.Offset(x(threshold), padT), androidx.compose.ui.geometry.Offset(x(threshold), padT + h), 1.5.dp.toPx(), pathEffect = dash)
        drawLine(Color(0x669AA1AB), androidx.compose.ui.geometry.Offset(x(-60.0), y(-60.0)), androidx.compose.ui.geometry.Offset(x(0.0), y(0.0)), 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
        val path = androidx.compose.ui.graphics.Path()
        var i = -60.0
        path.moveTo(x(i), y(CompressorCurve.outputDb(i, threshold, ratio, knee, makeup)))
        while (i < 0.0) { i += 0.5; path.lineTo(x(i), y(CompressorCurve.outputDb(i, threshold, ratio, knee, makeup))) }
        drawPath(path, if (enabled) color else color.copy(alpha = .35f), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        val th = measurer.measure("threshold ${"%.1f".format(threshold)}", labelStyle.copy(color = color))
        drawText(th, topLeft = androidx.compose.ui.geometry.Offset(x(threshold) + 6.dp.toPx(), padT + 4.dp.toPx()))
        val live = liveInputDb()
        if (enabled && live != null && live > -60.0) {
            val inDb = live.coerceAtMost(0.0)
            val outDb = CompressorCurve.outputDb(inDb, threshold, ratio, knee, makeup)
            val c = androidx.compose.ui.geometry.Offset(x(inDb), y(outDb))
            drawCircle(color, 8.dp.toPx(), c)
            drawCircle(Color.White, 5.dp.toPx(), c)
            val gr = -CompressorCurve.gainReductionDb(inDb - threshold, ratio, knee)
            val t = measurer.measure("in ${"%.0f".format(inDb)} · GR ${"%.1f".format(gr)}",
                androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            val tl = androidx.compose.ui.geometry.Offset((c.x - t.size.width - 14.dp.toPx()).coerceAtLeast(padL + 4.dp.toPx()), c.y - t.size.height - 6.dp.toPx())
            drawRect(Color(0xE60B0C0F), tl - androidx.compose.ui.geometry.Offset(4.dp.toPx(), 2.dp.toPx()),
                androidx.compose.ui.geometry.Size(t.size.width + 8.dp.toPx(), t.size.height + 4.dp.toPx()))
            drawText(t, topLeft = tl)
        }
        val axis = measurer.measure("IN (dB) →", labelStyle)
        drawText(axis, topLeft = androidx.compose.ui.geometry.Offset(padL + w - axis.size.width, padT + h - axis.size.height - 2.dp.toPx()))
    }
}

private const val OutTopDb = 6.0

@Composable
fun CompressorBandPage(band: Int, modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val mbcMeter = rememberMeterPoll(NativeDsp.METER_MBC, MbcMeterSize)

    fun idx(field: Int) = NativeBmwDspValues.mbcBandIndex(band, field)

    // Crossover range this band spans, live off the three MBC split frequencies (defaults
    // 80 / 500 / 4000 Hz) normalized as the engine (and the graph) use them, so a triple stored
    // out of order still labels each band correctly. Band 1 runs from 20 Hz, band 4 to 20 kHz.
    val rangeLabel = run {
        val (lo, hi) = CompressorSurfaceMath.bandRange(band, CompressorSurfaceMath.splitFrequencies(dsp.values))
        "${formatHz(lo.toFloat())} – ${formatHz(maxOf(hi, lo * 1.01).toFloat())}"
    }

    val header: @Composable (Modifier) -> Unit = { m -> BandHeader(band, rangeLabel, dsp, m) }
    val meter: @Composable (Modifier) -> Unit = { m ->
        ArtMeterRow(m) { BmwGrMeter({ mbcMeter.value(band * 3 + 2, 0f) }, it) }
    }
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
    val busMeter = rememberMeterPoll(NativeDsp.METER_BUS_LIMITER, BusMeterSize)
    // When each bus last limited (uptime ms), sampled 5x a second off the same meter, so the chip
    // under each meter can say "limited 3 s ago" after the meter itself has fallen back.
    val lastLimited = remember { mutableStateListOf(0L, 0L, 0L) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(busMeter) {
        while (true) {
            now = android.os.SystemClock.uptimeMillis()
            for (i in 0 until BusMeterSize) {
                if (busMeter.valid && busMeter.values[i] > LimitingDb) lastLimited[i] = now
            }
            kotlinx.coroutines.delay(200)
        }
    }
    val bus: @Composable (Int, BusRow) -> Unit = { i, row ->
        BusLimiterColumn(dsp, BusColumns[i], { busMeter.value(i, 0f) }, row, lastLimited[i], now)
    }

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
private fun BusLimiterColumn(dsp: BmwDspState, col: BusColumn, gainReductionDb: () -> Float, row: BusRow, lastLimitedMs: Long = 0L, nowMs: Long = 0L) {
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
    LimiterActivityChip(lastLimitedMs, nowMs, row(304, 30))
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

private const val MbcMeterSize = 12
private const val BusMeterSize = 3

/**
 * One native meter, refreshed into a single reused array ~30 times a second while the page is
 * STARTED. Nothing here is read during composition: [value] and [latest] read [tick], so call them
 * only where the meter is drawn (a Canvas lambda, an AndroidView update). A refresh then redraws
 * just that meter instead of recomposing the whole page and its sliders, and the poll allocates
 * nothing (NativeDsp.readNativeBmwMeter fills [values] in place).
 */
@Stable
private class MeterPoll(size: Int) {
    val values = FloatArray(size)
    val tick = mutableIntStateOf(0)
    var valid = false

    /** values[index], or [fallback] before the first successful read. Draw/update phase only. */
    fun value(index: Int, fallback: Float): Float {
        tick.intValue
        return if (valid) values.getOrElse(index) { fallback } else fallback
    }

    /** The whole array, or null before the first successful read. Draw/update phase only. */
    fun latest(): FloatArray? {
        tick.intValue
        return if (valid) values else null
    }
}

@Composable
private fun rememberMeterPoll(kind: Int, size: Int): MeterPoll {
    val poll = remember(kind, size) { MeterPoll(size) }
    LifecycleStartEffect(poll) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                poll.valid = RootlessAudioProcessorService.readNativeBmwMeter(kind, poll.values)
                poll.tick.intValue++
                handler.postDelayed(this, MeterTickMs)
            }
        }
        handler.post(tick)
        onStopOrDispose { handler.removeCallbacks(tick) }
    }
    return poll
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


/** Below this much reduction a bus counts as not limiting. */
private const val LimitingDb = 0.1f
/** How long "limited N s ago" stays up after the last limiting. */
private const val LimitedHoldMs = 10_000L

/**
 * Whether the bus has been limiting lately: red "limiting now", amber "limited N s ago" for
 * [LimitedHoldMs], grey "not limiting" -- so a glance in the car still catches it after the meter
 * has dropped back.
 */
@Composable
private fun LimiterActivityChip(lastLimitedMs: Long, nowMs: Long, modifier: Modifier) {
    val age = if (lastLimitedMs == 0L) Long.MAX_VALUE else nowMs - lastLimitedMs
    val (text, color) = when {
        age < 400 -> "limiting now" to DspColors.Comp
        age < LimitedHoldMs -> "limited ${age / 1000 + 1} s ago" to Color(0xFFFFA24C)
        else -> "not limiting" to Color(0xFF9AA1AB)
    }
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .16f))
            .border(1.dp, color.copy(alpha = .7f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
