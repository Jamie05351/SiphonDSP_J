package app.siphondsp.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.dsp.CarBaseline
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * The Output workspace's DEEP BASS page: the volume-dependent bass boost, after the master volume.
 *
 * - **On your car** (left on the head unit): the boost now and at the quietest volume, your car
 *   baseline with today's boost on it, the Low | Mid split, and a master-volume track. All from
 *   [NativeBmwDspValues.deepBassCurve], the same bell native DeepBass builds.
 * - **Deep bass**: the switch (the engine fades it over 50 ms), Strength and Max boost.
 * - **What it does**: the boost at the current volume and how it moves. It follows the master
 *   volume and is always smaller than the volume cut, so it never costs headroom.
 */
@Composable
fun DeepBassScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                DeepBassOnCarGraph(dsp, Modifier.artRect(artDp(GraphX, GroupTop, GraphWidth, GraphHeight)))
                DeepBassControls(dsp, ColumnLabelWidth) { y, h -> Modifier.artRect(artDp(ControlsX, GroupTop + y, HeadGroupWidth, h)) }
                ArtGroupHeader("What it does", Color.White, Modifier.artRect(artDp(InfoX, GroupTop, HeadGroupWidth, HeaderHeight)))
                DeepBassInfo(dsp, top = 52) { y, h -> Modifier.artRect(artDp(InfoX, GroupTop + y, HeadGroupWidth, h)) }
            }
        } else {
            PhoneDeepBassPage(dsp, modifier)
        }
    }
}

/** Where one row of a group goes: [y] dp down the group on the head unit, [h] tall. */
private typealias DeepBassRow = (y: Int, h: Int) -> Modifier

@Composable
private fun DeepBassControls(dsp: BmwDspState, labelWidth: androidx.compose.ui.unit.Dp, row: DeepBassRow) {
    ArtGroupHeader(
        title = "Deep bass",
        accent = DeepBassAccent,
        modifier = row(0, HeaderHeight),
        checked = dsp.isOn(NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED),
        onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED, if (it) 1f else 0f) },
    )
    DspArtSlider(
        dsp, "Strength", NativeBmwDspValues.INDEX_DEEP_BASS_STRENGTH, 0f..100f, 5f, "%", DeepBassAccent, row(52, 48),
        labelWidth = labelWidth,
    )
    DspArtSlider(
        dsp, "Max boost", NativeBmwDspValues.INDEX_DEEP_BASS_MAX_DB,
        NativeBmwDspValues.DEEP_BASS_MIN_MAX_DB..NativeBmwDspValues.DEEP_BASS_MAX_MAX_DB, 1f, "dB",
        DeepBassAccent, row(108, 48), labelWidth = labelWidth,
    )
}

/** What the boost is doing at the current volume, how it moves, and why it never costs headroom; from [top]. */
@Composable
private fun DeepBassInfo(dsp: BmwDspState, top: Int, row: DeepBassRow) {
    val on = dsp.isOn(NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED)
    val volumeDb = dsp.get(NativeBmwDspValues.INDEX_MASTER_VOLUME_DB)
    val now = NativeBmwDspValues.deepBassCurve(dsp.values)
    val quietest = NativeBmwDspValues.deepBassCurve(dsp.values, volumeDb = NativeBmwDspValues.MASTER_VOLUME_MIN_DB.toDouble())
    InfoLine(
        when {
            !on -> "Off: the bass is left as it is"
            now.gainDb < 0.05 -> "Now: no boost at full volume"
            else -> "Now: +%.1f dB at %.0f Hz".format(Locale.ROOT, now.gainDb, now.centreHz)
        },
        row(top, 28),
        color = if (on) DeepBassAccent else InfoColor,
    )
    var y = top + 30
    if (on) {
        InfoLine("Quietest: +%.1f dB at %.0f Hz".format(Locale.ROOT, quietest.gainDb, quietest.centreHz), row(y, 28))
        y += 30
    }
    InfoLine("Master %s dB · +0.3 dB per dB of cut".format(formatVolume(volumeDb)), row(y, 28))
    InfoLine("Glides 50 → 32 Hz as the volume drops", row(y + 30, 28))
    // After the master volume, and always less than its cut: it can't push the output past full scale.
    InfoLine("Never costs headroom: always less than the volume cut", row(y + 62, 52), maxLines = 2)
}

@Composable
private fun InfoLine(text: String, modifier: Modifier, color: Color = InfoColor, maxLines: Int = 1) {
    Box(modifier, contentAlignment = Alignment.CenterStart) { ArtLabel(text, color = color, maxLines = maxLines) }
}

/**
 * Deep bass on your car: the boost now (solid, centre marked) and at the quietest volume (dashed),
 * your car baseline (dashed grey) with today's boost on it (white), the Low | Mid split, and where
 * the master volume sits between full (no boost) and quiet (Max boost).
 */
@Composable
private fun DeepBassOnCarGraph(dsp: BmwDspState, modifier: Modifier) {
    val context = LocalContext.current
    val holder = remember(context) { PeqStateHolder(context.applicationContext) }
    LaunchedEffect(holder) { holder.loadCarBaseline() }
    val baseline = holder.graphBaseline
    var side by rememberSaveable { mutableIntStateOf(0) }
    val values = dsp.values
    val now = NativeBmwDspValues.deepBassCurve(values)
    val quietest = NativeBmwDspValues.deepBassCurve(values, volumeDb = NativeBmwDspValues.MASTER_VOLUME_MIN_DB.toDouble())
    val splitHz = values.getOrNull(NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ)?.toDouble() ?: 80.0
    val volumeDb = values.getOrElse(NativeBmwDspValues.INDEX_MASTER_VOLUME_DB) { 0f }
    val shape = RoundedCornerShape(14.dp)
    val measurer = rememberTextMeasurer()
    val description = "Deep bass on your car: now +%.1f dB at %.0f Hz, quietest +%.1f dB at %.0f Hz".format(
        Locale.ROOT, now.gainDb, now.centreHz, quietest.gainDb, quietest.centreHz,
    )
    Column(
        modifier.clip(shape).background(Color(0xE60B0C0F)).border(1.5.dp, DeepBassAccent, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DEEP BASS ON YOUR CAR", color = DeepBassAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (baseline != null) {
                BmwSegmentedControl(
                    options = listOf("L", "R"), selectedIndex = side, onSelect = { side = it },
                    optionAccents = listOf(Color(LeftChannelColor), Color(RightChannelColor)),
                    modifier = Modifier.width(96.dp), segmentHeight = 28.dp, segmentGap = 4.dp,
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = description }) {
            drawDeepBassOnCar(now, quietest, baseline, side, splitHz, measurer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("— now", color = DeepBassAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("- - quietest", color = DeepBassAccent.copy(alpha = .7f), fontSize = 10.sp)
            if (baseline != null) {
                Text("- - car baseline", color = InfoColor, fontSize = 10.sp)
                Text("— car with boost", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("Load a car baseline on PEQ to see it on your car", color = InfoColor, fontSize = 10.sp)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(34.dp)) { drawVolumeTrack(volumeDb, quietest, measurer) }
    }
}

private fun DrawScope.drawDeepBassOnCar(
    now: NativeBmwDspValues.DeepBassCurve,
    quietest: NativeBmwDspValues.DeepBassCurve,
    baseline: CarBaseline?,
    side: Int,
    splitHz: Double,
    measurer: TextMeasurer,
) {
    val left = 30.dp.toPx()
    val bottom = size.height - 16.dp.toPx()
    val top = 4.dp.toPx()
    fun x(f: Double) = left + (ln(f / GraphMinHz) / ln(GraphMaxHz / GraphMinHz)).toFloat() * (size.width - left)
    fun y(db: Double) = top + ((GraphTopDb - db.coerceIn(GraphBottomDb, GraphTopDb)) / (GraphTopDb - GraphBottomDb)).toFloat() * (bottom - top)
    val label = TextStyle(color = InfoColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    for (g in listOf(15.0, 10.0, 5.0, 0.0, -5.0, -10.0)) {
        drawLine(if (g == 0.0) Color(0xFF3A3F4B) else GridColor, Offset(left, y(g)), Offset(size.width, y(g)), 1.dp.toPx())
        val t = measurer.measure(if (g > 0) "+${g.toInt()}" else "${g.toInt()}", label)
        drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y(g) - t.size.height / 2))
    }
    for (f in doubleArrayOf(20.0, 30.0, 50.0, 80.0, 100.0, 150.0, 200.0, 300.0)) {
        drawLine(GridColor, Offset(x(f), top), Offset(x(f), bottom), 1.dp.toPx())
        val t = measurer.measure(if (f == 300.0) "300 Hz" else f.toInt().toString(), label)
        drawText(t, topLeft = Offset((x(f) - t.size.width / 2).coerceIn(left, size.width - t.size.width), bottom + 2.dp.toPx()))
    }
    // The Low | Mid split: the boost should sit in the Low band.
    if (splitHz in GraphMinHz..GraphMaxHz) {
        val sx = x(splitHz)
        drawLine(SplitColor, Offset(sx, top), Offset(sx, bottom), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        val t = measurer.measure("Low | Mid ${splitHz.toInt()} Hz", TextStyle(color = SplitColor, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        drawText(t, topLeft = Offset((sx + 4.dp.toPx()).coerceAtMost(size.width - t.size.width), top))
    }
    val boostNow = boostDb(now)
    fun path(db: DoubleArray) = Path().apply {
        for (i in Axis.indices) if (i == 0) moveTo(x(Axis[i]), y(db[i])) else lineTo(x(Axis[i]), y(db[i]))
    }
    val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    if (baseline != null) {
        val car = baseline.offsetsFor(side, Axis)
        drawPath(path(car), InfoColor, style = Stroke(1.5.dp.toPx(), pathEffect = dashed))
        drawPath(path(DoubleArray(Axis.size) { car[it] + boostNow[it] }), Color.White, style = Stroke(2.5.dp.toPx()))
    }
    drawPath(path(boostDb(quietest)), DeepBassAccent.copy(alpha = .6f), style = Stroke(1.5.dp.toPx(), pathEffect = dashed))
    drawPath(path(boostNow), DeepBassAccent, style = Stroke(2.5.dp.toPx()))
    if (now.gainDb > 0.05) {
        val c = Offset(x(now.centreHz), y(now.gainDb))
        drawCircle(DeepBassAccent, 5.dp.toPx(), c)
        drawCircle(Color.White, 5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        val t = measurer.measure("now +%.1f dB at %.0f Hz".format(Locale.ROOT, now.gainDb, now.centreHz),
            TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
        val tx = (c.x + 10.dp.toPx()).coerceAtMost(size.width - t.size.width - 8.dp.toPx())
        val ty = (c.y - t.size.height - 12.dp.toPx()).coerceAtLeast(top)
        drawRoundRect(Color(0xE60B0C0F), Offset(tx - 6.dp.toPx(), ty - 2.dp.toPx()), Size(t.size.width + 12.dp.toPx(), t.size.height + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
        drawRoundRect(DeepBassAccent, Offset(tx - 6.dp.toPx(), ty - 2.dp.toPx()), Size(t.size.width + 12.dp.toPx(), t.size.height + 4.dp.toPx()), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawText(t, topLeft = Offset(tx, ty))
    }
}

/** The master volume from quiet to full, boost shading from Max boost to none, and where it sits now. */
private fun DrawScope.drawVolumeTrack(volumeDb: Float, quietest: NativeBmwDspValues.DeepBassCurve, measurer: TextMeasurer) {
    val name = measurer.measure("MASTER", TextStyle(color = InfoColor, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    drawText(name, topLeft = Offset(0f, 0f))
    val l = name.size.width + 12.dp.toPx()
    val r = size.width
    val h = 8.dp.toPx()
    val y = (name.size.height - h) / 2
    fun vx(v: Float) = l + ((v - TrackMinDb) / -TrackMinDb).coerceIn(0f, 1f) * (r - l)
    drawRoundRect(Color(0xFF1C1F27), Offset(l, y), Size(r - l, h), CornerRadius(h / 2))
    drawRoundRect(
        Brush.horizontalGradient(listOf(DeepBassAccent, DeepBassAccent.copy(alpha = .05f)), startX = l, endX = r),
        Offset(l, y), Size(r - l, h), CornerRadius(h / 2),
    )
    val mx = vx(volumeDb)
    drawRoundRect(Color.White, Offset(mx - 2.dp.toPx(), y - 6.dp.toPx()), Size(4.dp.toPx(), h + 12.dp.toPx()), CornerRadius(2.dp.toPx()))
    val small = TextStyle(color = InfoColor, fontSize = 10.sp)
    val below = y + h + 6.dp.toPx()
    drawText(measurer.measure("quiet: +%.1f dB".format(Locale.ROOT, quietest.gainDb), small.copy(color = DeepBassAccent)), topLeft = Offset(l, below))
    val full = measurer.measure("full: no boost", small)
    drawText(full, topLeft = Offset(r - full.size.width, below))
    val cur = measurer.measure("${formatVolume(volumeDb)} dB", TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
    drawText(cur, topLeft = Offset((mx - cur.size.width / 2).coerceIn(l + 70.dp.toPx(), r - full.size.width - cur.size.width - 8.dp.toPx()), below))
}

/** The bell [c] on [Axis], in dB (flat 0 with no boost). */
private fun boostDb(c: NativeBmwDspValues.DeepBassCurve): DoubleArray {
    if (c.gainDb <= 0.0) return DoubleArray(Axis.size)
    val coeffs = BiquadUtils.computeCoefficients(c.centreHz, c.gainDb, NativeBmwDspValues.DEEP_BASS_Q, ParametricEqFilterType.PEAKING, GraphSampleRate)
    return DoubleArray(Axis.size) { BiquadUtils.magnitudeResponse(coeffs, Axis[it], GraphSampleRate) }
}

/** Phone: the controls and what it does in one column, the graph beside or under them; scrolls. */
@Composable
private fun PhoneDeepBassPage(dsp: BmwDspState, modifier: Modifier) {
    val stack: DeepBassRow = { _, h -> Modifier.fillMaxWidth().height(h.dp) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= GroupWidth.dp * 2 + PhoneGroupGap + 24.dp
        val controls: @Composable () -> Unit = {
            Column(Modifier.width(GroupWidth.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeepBassControls(dsp, 150.dp, stack)
                DeepBassInfo(dsp, top = 0, row = stack)
            }
        }
        Box(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(PhoneGroupGap)) {
                    controls()
                    DeepBassOnCarGraph(dsp, Modifier.width(GroupWidth.dp).height(PhoneGraphHeight))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    controls()
                    DeepBassOnCarGraph(dsp, Modifier.widthIn(max = GroupWidth.dp * 2).fillMaxWidth().height(PhoneGraphHeight))
                }
            }
        }
    }
}

private fun formatVolume(db: Float): String = if (db >= 0f) "0" else "%.0f".format(Locale.ROOT, db)

private val DeepBassAccent = Color(BmwDashboardSkin.SLIDER_DEEP_BASS_COLOR)
private val InfoColor = Color(0xFF969EA8)
private val GridColor = Color(0xFF23262D)
private val SplitColor = Color(0xFFF0A608)
private const val GraphMinHz = 20.0
private const val GraphMaxHz = 300.0
private const val GraphTopDb = 15.0
private const val GraphBottomDb = -12.0
private const val GraphSampleRate = 48_000.0
private const val TrackMinDb = -60f
private val Axis = DoubleArray(140) { GraphMinHz * (GraphMaxHz / GraphMinHz).pow(it / 139.0) }

// Head-unit layout, in the 1280x480 editor's dp: the on-your-car graph on the left (x 152..626,
// like the Output page's gain budget), then the controls and "what it does" in two 296dp columns
// (x 646..1250), all starting under the toolbar line.
private const val GraphX = 152
private const val GraphWidth = 474
private const val GraphHeight = 384
private const val ControlsX = 646
private const val InfoX = 954
private const val HeadGroupWidth = 296
private val ColumnLabelWidth = 104.dp
private const val GroupWidth = 333
private const val GroupTop = 76
private const val HeaderHeight = 40
private val PhoneGroupGap = 60.dp
private val PhoneGraphHeight = 340.dp
