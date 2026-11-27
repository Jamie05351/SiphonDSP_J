package app.siphondsp.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.compose.controls.DspColors
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.dsp.AutoHeadroom
import app.siphondsp.dsp.AutoHeadroomCalculator
import app.siphondsp.dsp.GainBudget
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Output page's gain budget: every gain in the chain as a step from 0 dBFS, in the engine's
 * order, ending at the worst-case peak ([GainBudget]: worked out from the settings, the same
 * response maths as Auto headroom). Under it, the live input and output peaks and a clip chip.
 */
@Composable
internal fun GainBudgetPanel(dsp: BmwDspState, peq: PeqStateHolder, modifier: Modifier = Modifier) {
    val values = dsp.values
    val peqState = peq.peqState
    var budget by remember { mutableStateOf<GainBudget?>(null) }
    LaunchedEffect(values, peqState) {
        if (values.size != NativeBmwDspValues.SIZE) return@LaunchedEffect
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate()?.takeIf { it >= 8000f } ?: 48_000f
        budget = withContext(Dispatchers.Default) {
            // A calculator per run: a superseded run can overlap the next, and it isn't thread-safe.
            val peak = AutoHeadroomCalculator().peakDb(values, peqState, sampleRate.toDouble())
            val headroom = if (AutoHeadroom.isAuto(values)) AutoHeadroom.headroomForPeak(peak).toDouble()
            else values[NativeBmwDspValues.INDEX_HEADROOM].toDouble()
            GainBudget.compute(values, peqState.preampDb.toDouble(), peak, headroom)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(Color(0xE60B0C0F)).border(1.5.dp, DspColors.Output, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GAIN BUDGET", color = DspColors.Output, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("worst case through the chain, from your settings", color = Muted, fontSize = 11.sp, maxLines = 1)
        }
        val measurer = rememberTextMeasurer()
        val b = budget
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (b != null) drawBudget(b, values, measurer)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF23262D)))
        OutputLevelsRow(Modifier.fillMaxWidth().height(30.dp))
    }
}

private fun DrawScope.drawBudget(b: GainBudget, values: FloatArray, measurer: TextMeasurer) {
    val labelW = 136.dp.toPx()
    val axisH = 16.dp.toPx()
    val rows = b.steps.size + 1
    val rowH = (size.height - axisH) / rows
    val levels = b.steps.flatMap { listOf(it.startDb, it.endDb) }
    val lo = min(-12.0, floor((levels.min() - 1) / 6) * 6)
    val hi = max(6.0, ceil((levels.max() + 1) / 6) * 6)
    val x0 = labelW
    val x1 = size.width - 8.dp.toPx()
    fun x(db: Double) = (x0 + (db - lo) / (hi - lo) * (x1 - x0)).toFloat()
    val small = TextStyle(color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    var g = lo
    while (g <= hi + 1e-9) {
        val gx = x(g)
        drawLine(if (g == 0.0) Color.White.copy(alpha = .45f) else Grid, Offset(gx, 0f), Offset(gx, rows * rowH), 1.dp.toPx())
        val t = measurer.measure(signedInt(g), small)
        drawText(t, topLeft = Offset((gx - t.size.width / 2f).coerceIn(x0 - 8f, size.width - t.size.width.toFloat()), rows * rowH + 2.dp.toPx()))
        g += 6.0
    }
    val barH = min(18.dp.toPx(), rowH * .6f)
    val name = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val note = TextStyle(color = Muted, fontSize = 10.sp)
    b.steps.forEachIndexed { i, s ->
        val top = i * rowH
        val color = stepColor(s.kind)
        val label = measurer.measure(stepName(s.kind), name)
        val sub = stepNote(s, values, b)
        val textTop = top + (rowH - label.size.height - (if (sub.isEmpty()) 0 else 12.dp.toPx().toInt())) / 2f
        drawText(label, topLeft = Offset(0f, textTop))
        if (sub.isNotEmpty()) drawText(measurer.measure(sub, note), topLeft = Offset(0f, textTop + label.size.height))
        val a = x(s.startDb)
        val e = x(s.endDb)
        val y = top + (rowH - barH) / 2
        drawRoundRect(color.copy(alpha = .85f), Offset(min(a, e), y), Size(max(2.dp.toPx(), kotlin.math.abs(e - a)), barH), CornerRadius(4.dp.toPx()))
        val v = measurer.measure(signed1(s.db), TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        val vx = if (s.db >= 0) max(a, e) + 5.dp.toPx() else min(a, e) - v.size.width - 5.dp.toPx()
        drawText(v, topLeft = Offset(vx.coerceIn(x0, size.width - v.size.width), y + (barH - v.size.height) / 2))
        // The connector down to the next step's start.
        if (i < b.steps.size - 1) drawLine(Color.White.copy(alpha = .3f), Offset(e, y + barH), Offset(e, top + rowH + (rowH - barH) / 2), 1.dp.toPx())
    }
    // The result.
    val top = b.steps.size * rowH
    val ok = b.clean
    val resultColor = if (ok) Clean else Clip
    drawText(measurer.measure("At the output", name.copy(fontWeight = FontWeight.Bold)), topLeft = Offset(0f, top + (rowH - 16.dp.toPx()) / 2))
    val rx = x(b.outputDb)
    drawRoundRect(Color.White, Offset(rx - 2.dp.toPx(), top + (rowH - barH) / 2), Size(4.dp.toPx(), barH), CornerRadius(2.dp.toPx()))
    val text = "${signed1(b.outputDb)} dBFS · ${if (ok) "clean" else "can clip"}"
    val r = measurer.measure(text, TextStyle(color = resultColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
    val tx = if (rx + 8.dp.toPx() + r.size.width <= size.width) rx + 8.dp.toPx() else rx - 8.dp.toPx() - r.size.width
    drawText(r, topLeft = Offset(tx, top + (rowH - r.size.height) / 2))
}

private fun stepName(kind: GainBudget.Kind) = when (kind) {
    GainBudget.Kind.PREAMP -> "Preamp"
    GainBudget.Kind.EQ -> "EQ + crossovers"
    GainBudget.Kind.HEADROOM -> "Headroom"
    GainBudget.Kind.TILT -> "Tilt"
    GainBudget.Kind.POST_GAIN -> "Post gain"
    GainBudget.Kind.LIMITER -> "Limiter"
    GainBudget.Kind.VOLUME -> "Volume + deep bass"
}

private fun stepNote(s: GainBudget.Step, values: FloatArray, b: GainBudget): String = when (s.kind) {
    GainBudget.Kind.PREAMP -> ""
    GainBudget.Kind.EQ -> "loudest point"
    GainBudget.Kind.HEADROOM -> if (AutoHeadroom.isAuto(values)) "auto" else "manual"
    GainBudget.Kind.TILT -> if (values[NativeBmwDspValues.INDEX_TILT_ENABLED] < .5f) "off" else "boosted end"
    GainBudget.Kind.POST_GAIN -> "L ${signed1(values[NativeBmwDspValues.INDEX_POST_GAIN_L].toDouble())} · R ${signed1(values[NativeBmwDspValues.INDEX_POST_GAIN_R].toDouble())}"
    GainBudget.Kind.LIMITER -> "ceiling ${signed1(b.limiterCeilingDb ?: 0.0)}" + if (b.limiterCatches) " · holding" else ""
    GainBudget.Kind.VOLUME -> {
        val boost = NativeBmwDspValues.deepBassCurve(values).gainDb
        "master ${signedInt(values[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB].toDouble())}" +
            if (boost > 0.05) " · bass +%.1f".format(Locale.ROOT, boost) else ""
    }
}

private fun stepColor(kind: GainBudget.Kind) = when (kind) {
    GainBudget.Kind.PREAMP -> Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
    GainBudget.Kind.EQ -> DspColors.Peq
    GainBudget.Kind.HEADROOM -> Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
    GainBudget.Kind.TILT -> DspColors.Xover
    GainBudget.Kind.POST_GAIN -> Color(BmwDashboardSkin.M_GREEN)
    GainBudget.Kind.LIMITER -> DspColors.Output
    GainBudget.Kind.VOLUME -> Color(BmwDashboardSkin.SLIDER_DEEP_BASS_COLOR)
}

/**
 * The live input (pre-DSP) and output peaks, with a hold tick, and whether the output has hit full
 * scale lately: red "clipping", amber "clipped N s ago", green "clean". Measured by the level feed
 * ([SpectrumEngine]) while this is on screen.
 */
@Composable
private fun OutputLevelsRow(modifier: Modifier) {
    DisposableEffect(Unit) {
        SpectrumEngine.acquireLevels()
        onDispose { SpectrumEngine.releaseLevels() }
    }
    // Read in the draw phase only, so the ~30 Hz refresh redraws the meters, not the page.
    val tick = remember { mutableIntStateOf(0) }
    val levels = remember { FloatArray(4) }
    val hold = remember { FloatArray(2) { SpectrumEngine.LEVEL_FLOOR_DB } }
    val holdAt = remember { LongArray(2) }
    LifecycleStartEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val run = object : Runnable {
            override fun run() {
                tick.intValue++
                handler.postDelayed(this, 33L)
            }
        }
        handler.post(run)
        onStopOrDispose { handler.removeCallbacks(run) }
    }
    val measurer = rememberTextMeasurer()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.weight(1f).height(26.dp)) {
            tick.intValue
            SpectrumEngine.channelLevelsInto(levels)
            val now = System.nanoTime()
            val peaks = floatArrayOf(SpectrumEngine.inputPeakDb(), max(levels[0], levels[2]))
            val half = size.width / 2
            for (i in 0..1) {
                if (peaks[i] >= hold[i] || now - holdAt[i] > PeakHoldNanos) {
                    hold[i] = peaks[i]
                    holdAt[i] = now
                }
                drawPeakMeter(if (i == 0) "IN" else "OUT", peaks[i], hold[i], i * half, half - 10.dp.toPx(), measurer)
            }
        }
        ClipChip(Modifier.width(132.dp).height(26.dp))
    }
}

private fun DrawScope.drawPeakMeter(label: String, db: Float, holdDb: Float, left: Float, width: Float, measurer: TextMeasurer) {
    val lt = measurer.measure(label, TextStyle(color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    drawText(lt, topLeft = Offset(left, (size.height - lt.size.height) / 2))
    val vt = measurer.measure(if (db <= SpectrumEngine.LEVEL_FLOOR_DB) "—" else signed1(db.toDouble()),
        TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
    val barL = left + 34.dp.toPx()
    val barR = left + width - 44.dp.toPx()
    val h = 8.dp.toPx()
    val y = (size.height - h) / 2
    fun fx(v: Float) = barL + ((v - MeterFloorDb) / -MeterFloorDb).coerceIn(0f, 1f) * (barR - barL)
    drawRoundRect(Color(0xFF1C1F27), Offset(barL, y), Size(barR - barL, h), CornerRadius(h / 2))
    drawRoundRect(if (db >= -0.1f) Clip else Clean, Offset(barL, y), Size(fx(db) - barL, h), CornerRadius(h / 2))
    if (holdDb > MeterFloorDb) drawRect(Color.White.copy(alpha = .85f), Offset(fx(holdDb) - 1.dp.toPx(), y - 4.dp.toPx()), Size(2.dp.toPx(), h + 8.dp.toPx()))
    drawText(vt, topLeft = Offset(barR + 6.dp.toPx(), (size.height - vt.size.height) / 2))
}

/** "clipping" / "clipped N s ago" / "clean", from [SpectrumEngine.lastClipNanos]. Its own 5 Hz clock, so only it recomposes. */
@Composable
private fun ClipChip(modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            now = System.nanoTime()
        }
    }
    val last = SpectrumEngine.lastClipNanos
    val ageMs = if (last == 0L) Long.MAX_VALUE else (now - last) / 1_000_000
    val (text, color) = when {
        ageMs < 400 -> "clipping" to Clip
        ageMs < ClipHoldMs -> "clipped ${ageMs / 1000 + 1} s ago" to Color(0xFFFFA24C)
        else -> "clean" to Clean
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.clip(shape).background(color.copy(alpha = .16f)).border(1.dp, color.copy(alpha = .7f), shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private fun signed1(v: Double): String = when {
    v > 0.049 -> "+%.1f".format(Locale.ROOT, v)
    v < -0.049 -> "−%.1f".format(Locale.ROOT, -v)
    else -> "0.0"
}

private fun signedInt(v: Double): String = when {
    v > 0.5 -> "+%d".format(Locale.ROOT, Math.round(v))
    v < -0.5 -> "−%d".format(Locale.ROOT, -Math.round(v))
    else -> "0"
}

private val Muted = Color(0xFF9AA1AB)
private val Grid = Color(0xFF23262D)
private val Clean = Color(0xFF3DDC84)
private val Clip = Color(0xFFFF4D4D)
private const val MeterFloorDb = -48f
private const val PeakHoldNanos = 1_500_000_000L
private const val ClipHoldMs = 30_000L
