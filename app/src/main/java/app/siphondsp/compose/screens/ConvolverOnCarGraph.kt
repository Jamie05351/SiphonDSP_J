package app.siphondsp.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.DspColors
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.dsp.CarBaseline
import app.siphondsp.dsp.ConvolverResponse
import app.siphondsp.view.BmwDashboardSkin
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * The convolver on your car: the running IR's magnitude (cyan, at the gain the engine applies),
 * the car baseline it corrects (dashed grey) and the two together (white). Per-band mode draws the
 * three band FIRs in their band colours instead. Says what's missing rather than drawing a guess.
 * With the convolver off the engine bypasses it (the IR stays loaded), so the IR is drawn faded and
 * dashed as "not applied" and the result is the car as it is.
 */
@Composable
internal fun ConvolverOnCarGraph(gainDb: Float, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val holder = remember(context) { PeqStateHolder(context.applicationContext) }
    LaunchedEffect(holder) { holder.loadCarBaseline() }
    val baseline = holder.graphBaseline
    val response = rememberConvolverResponse(show = true, grid = Grid)
    var side by rememberSaveable { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(Color(0xE60B0C0F)).border(1.5.dp, DspColors.Output, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("CONVOLVER ON YOUR CAR", color = DspColors.Output, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (!enabled && response != null) {
                Text("  · OFF: IR not applied", color = Color(0xFFFFA24C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f))
            BmwSegmentedControl(
                options = listOf("L", "R"), selectedIndex = side, onSelect = { side = it },
                optionAccents = listOf(Color(LeftChannelColor), Color(RightChannelColor)),
                modifier = Modifier.width(96.dp), segmentHeight = 28.dp, segmentGap = 4.dp,
            )
        }
        if (response == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Load an IR and start the DSP to see its response on your car.",
                    color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            return@Column
        }
        val applied = ConvolverResponse.appliedGainDb(gainDb.toDouble(), response.peakGainDb)
        val measurer = rememberTextMeasurer()
        Canvas(Modifier.fillMaxWidth().weight(1f)) { drawConvolverOnCar(response, baseline, side, applied, enabled, measurer) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (response.bands != null) {
                Text("— Low", color = LowColor, fontSize = 10.sp)
                Text("— Mid", color = MidColor, fontSize = 10.sp)
                Text("— High", color = HighColor, fontSize = 10.sp)
            } else {
                Text(if (enabled) "— IR" else "- - IR (off)", color = DspColors.Output, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (baseline != null) {
                    Text("- - car baseline", color = Muted, fontSize = 10.sp)
                    Text("— result", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.weight(1f))
            Text(
                "peak %+.1f · latency %.1f ms".format(Locale.ROOT, response.peakGainDb, 1000.0 * response.latencyFrames / response.sampleRate),
                color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
        }
        if (baseline == null && response.bands == null) {
            Text("Load a car baseline on PEQ (Graph menu) to see the result on your car.", color = Muted, fontSize = 11.sp)
        }
    }
}

private fun DrawScope.drawConvolverOnCar(
    response: ConvolverResponse,
    baseline: CarBaseline?,
    side: Int,
    appliedGainDb: Double,
    enabled: Boolean,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val left = 30.dp.toPx()
    val bottom = size.height - 16.dp.toPx()
    fun x(f: Double) = left + (log10(f / Grid.first()) / log10(Grid.last() / Grid.first())).toFloat() * (size.width - left)
    fun y(db: Double) = ((TopDb - db.coerceIn(BottomDb, TopDb)) / (TopDb - BottomDb)).toFloat() * bottom
    val label = TextStyle(color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    for (g in listOf(10.0, 5.0, 0.0, -5.0, -10.0, -15.0)) {
        drawLine(if (g == 0.0) Color(0xFF3A3F4B) else Color(0xFF1E2128), Offset(left, y(g)), Offset(size.width, y(g)), 1.dp.toPx())
        val t = measurer.measure(if (g > 0) "+${g.toInt()}" else "${g.toInt()}", label)
        drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y(g) - t.size.height / 2))
    }
    for ((f, s) in listOf(20.0 to "20", 50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500", 1000.0 to "1k",
        2000.0 to "2k", 5000.0 to "5k", 10000.0 to "10k", 20000.0 to "20k")) {
        drawLine(Color(0xFF1E2128), Offset(x(f), 0f), Offset(x(f), bottom), 1.dp.toPx())
        val t = measurer.measure(s, label)
        drawText(t, topLeft = Offset((x(f) - t.size.width / 2).coerceIn(left, size.width - t.size.width), bottom + 2.dp.toPx()))
    }
    fun curve(db: DoubleArray, color: Color, width: Float, dash: Boolean = false) {
        val path = Path()
        for (i in Grid.indices) {
            if (i == 0) path.moveTo(x(Grid[i]), y(db[i])) else path.lineTo(x(Grid[i]), y(db[i]))
        }
        drawPath(path, color, style = Stroke(width, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null))
    }
    fun magnitudeDb(r: ConvolverResponse) = DoubleArray(Grid.size) {
        10 * log10((r.re[side][it] * r.re[side][it] + r.im[side][it] * r.im[side][it]).coerceAtLeast(1e-20)) + appliedGainDb
    }
    val bands = response.bands
    if (bands != null) {
        listOf(LowColor, MidColor, HighColor).forEachIndexed { i, c ->
            curve(magnitudeDb(bands[i]), if (enabled) c else c.copy(alpha = .35f), 2.dp.toPx(), dash = !enabled)
        }
        return
    }
    val ir = magnitudeDb(response)
    val irColor = if (enabled) DspColors.Output else DspColors.Output.copy(alpha = .35f)
    if (baseline != null) {
        val car = baseline.offsetsFor(side, Grid)
        curve(car, Muted, 1.5.dp.toPx(), dash = true)
        curve(ir, irColor, 2.dp.toPx(), dash = !enabled)
        // Off, the engine bypasses the IR: what you hear is the car as it is.
        curve(if (enabled) DoubleArray(Grid.size) { car[it] + ir[it] } else car, Color.White, 2.5.dp.toPx())
    } else {
        curve(ir, irColor, 2.5.dp.toPx(), dash = !enabled)
    }
}

private val Grid = DoubleArray(240) { 20.0 * 1000.0.pow(it / 239.0) } // 20 Hz .. 20 kHz
private const val TopDb = 10.0
private const val BottomDb = -15.0
private val Muted = Color(0xFF9AA1AB)
private val LowColor = Color(BmwDashboardSkin.LIGHT_BLUE)
private val MidColor = Color(BmwDashboardSkin.MID_BAND_YELLOW)
private val HighColor = Color(BmwDashboardSkin.HIGH_BAND_PINK)
