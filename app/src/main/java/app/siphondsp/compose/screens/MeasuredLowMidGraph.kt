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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.bandalign.MeasuredLowMid
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * The car's measured Low + Mid sum through the crossover (Band Align's REW imports, re-timed for
 * the delays and polarities in [values]): white for now, dashed grey for as measured, with the
 * Low-only and Mid-only levels behind them and the level at the crossover called out. Says how to
 * get it when the files aren't loaded; nothing is modelled or faked.
 */
@Composable
internal fun MeasuredLowMidGraph(model: BandAlignViewModel, values: FloatArray, modifier: Modifier = Modifier) {
    var sideIndex by rememberSaveable { mutableIntStateOf(0) }
    val side = BmwOutputChannel.entries[sideIndex]
    val seat = BandAlignViewModel.Seat.entries.firstOrNull { model.measured(it, side, values, Probe) != null }
    val result = seat?.let { model.measured(it, side, values, Axis) }
    val low = seat?.let { model.imports[BandAlignViewModel.Key(it, side, BandAlignViewModel.Slot.LOW)]?.measurement }
    val mid = seat?.let { model.imports[BandAlignViewModel.Key(it, side, BandAlignViewModel.Slot.MID)]?.measurement }
    val crossoverHz = values.getOrNull(NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ)?.toDouble() ?: 80.0
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(Color(0xE60B0C0F))
            .border(1.5.dp, Brush.horizontalGradient(listOf(LowColor.copy(alpha = .8f), MidColor.copy(alpha = .8f))), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LOW + MID · ${seat?.label?.uppercase(Locale.ROOT) ?: "MEASURED"}",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            BmwSegmentedControl(
                options = listOf("L", "R"), selectedIndex = sideIndex, onSelect = { sideIndex = it },
                optionAccents = listOf(Color(LeftChannelColor), Color(RightChannelColor)),
                modifier = Modifier.width(96.dp), segmentHeight = 28.dp, segmentGap = 4.dp,
            )
        }
        if (result == null || low == null || mid == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Load Low only and Mid only for this side on Phase (Band Align) to see delay on your car.",
                    color = Color(0xFF9AA1AB), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            return@Column
        }
        // Levelled so "as measured" averages 0 dB over the crossover region: an REW export is absolute SPL.
        val lowDb = DoubleArray(Axis.size) { low.at(Axis[it])?.let { c -> 10 * log10(c.power().coerceAtLeast(1e-20)) } ?: Double.NaN }
        val midDb = DoubleArray(Axis.size) { mid.at(Axis[it])?.let { c -> 10 * log10(c.power().coerceAtLeast(1e-20)) } ?: Double.NaN }
        val ref = result.thenDb.withIndex().filter { (i, v) -> v.isFinite() && Axis[i] in 50.0..300.0 }.map { it.value }.average()
            .takeIf { it.isFinite() } ?: 0.0
        val nowAtX = valueAt(result.nowDb, crossoverHz) - ref
        val thenAtX = valueAt(result.thenDb, crossoverHz) - ref
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                val top = TopDb
                val bottom = BottomDb
                fun x(f: Double) = (log10(f / Axis.first()) / log10(Axis.last() / Axis.first())).toFloat() * size.width
                fun y(db: Double) = ((top - db.coerceIn(bottom, top)) / (top - bottom)).toFloat() * size.height
                for (g in listOf(6.0, 0.0, -6.0, -12.0)) {
                    drawLine(if (g == 0.0) Color(0xFF3A3F4B) else Color(0xFF23262D), Offset(0f, y(g)), Offset(size.width, y(g)), 1.dp.toPx())
                }
                val cx = x(crossoverHz)
                drawLine(Color(0x809AA1AB), Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                fun curve(db: DoubleArray, color: Color, width: Float, dash: Boolean = false) {
                    val path = Path()
                    var on = false
                    for (i in Axis.indices) {
                        val v = db[i]
                        if (!v.isFinite()) { on = false; continue }
                        val px = x(Axis[i]); val py = y(v - ref)
                        if (on) path.lineTo(px, py) else path.moveTo(px, py)
                        on = true
                    }
                    drawPath(path, color, style = Stroke(width,
                        pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null))
                }
                curve(lowDb, LowColor.copy(alpha = .7f), 1.5.dp.toPx())
                curve(midDb, MidColor.copy(alpha = .7f), 1.5.dp.toPx())
                curve(result.thenDb, Color(0xFF9AA1AB), 2.dp.toPx(), dash = true)
                curve(result.nowDb, Color.White, 3.dp.toPx())
            }
            Text(
                "${crossoverHz.toInt()} Hz: ${signed(nowAtX)} dB now · ${signed(thenAtX)} dB when measured" +
                    if (result.crossoverChanged) " · crossover changed since" else "",
                color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xE60B0C0F), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF2BB46B), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("— now", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("- - when measured", color = Color(0xFF9AA1AB), fontSize = 10.sp)
            Text("— Low only", color = LowColor, fontSize = 10.sp)
            Text("— Mid only", color = MidColor, fontSize = 10.sp)
        }
    }
}

private fun signed(v: Double): String = if (!v.isFinite()) "—" else String.format(Locale.ROOT, "%+.1f", v)

/** [db] on [Axis] at [hz] (nearest point). */
private fun valueAt(db: DoubleArray, hz: Double): Double {
    var best = 0
    for (i in Axis.indices) if (abs(Axis[i] - hz) < abs(Axis[best] - hz)) best = i
    return db[best]
}

private val Axis = DoubleArray(160) { 20.0 * 50.0.pow(it / 159.0) } // 20 Hz .. 1 kHz
private val Probe = doubleArrayOf(100.0)
private const val TopDb = 9.0
private const val BottomDb = -18.0
private val LowColor = Color(BmwDashboardSkin.LIGHT_BLUE)
private val MidColor = Color(BmwDashboardSkin.MID_BAND_YELLOW)
