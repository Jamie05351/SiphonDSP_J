package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.siphondsp.audio.SpectrumBars
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.view.LevelReadout
import kotlinx.coroutines.delay
import kotlin.math.ln

/**
 * The top screen's live spectrum: the processed output in [BandCount] third-octave bars from
 * 25 Hz to 16 kHz, lined up with the output meters' block (bars across its height, frequency marks
 * where the meters have their dB scale).
 *
 * The bars are [SpectrumBars] (the style the PEQ and crossover graphs share), read from
 * [SpectrumEngine], which the front page's level feed already keeps running. While [live] (the
 * front page showing and resumed) it redraws every [FrameMs] off its own clock, read only while
 * drawing, so a frame redraws this canvas and recomposes nothing. When the output is silent
 * ([meterLevels] at the floor, as it is once the feed stops) the bars drop to the floor at once.
 * [globalActive] blends the bars to grey while the DSP is bypassed. Purely visual.
 */
@Composable
fun SpectrumStrip(meterLevels: () -> LevelReadout, globalActive: Float, live: Boolean, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val measurer = rememberTextMeasurer()
    // The frequency marks change only with the scale, so they are measured once, not every frame.
    val marks = remember(measurer, s.k) {
        Marks.map { (hz, label) -> hz to measurer.measure(label, TextStyle(fontSize = s.sp(13f))) }
    }
    val bars = remember { SpectrumBars(LowestHz, BandCount) }
    // The clock tick the bars last stepped on: a redraw for any other reason (the power fade, a
    // layout change) repaints without stepping, so the fall rate stays the same.
    val lastStep = remember { intArrayOf(Int.MIN_VALUE) }
    val clock = remember { mutableIntStateOf(0) }
    LaunchedEffect(live) {
        while (live) {
            delay(FrameMs)
            clock.intValue++
        }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val tick = clock.intValue // read while drawing: each tick redraws this canvas only
        fun u(v: Float) = v * s.k * density
        val silent = meterLevels().silent
        // Silence zeroes the bars at once (repeating that is harmless); otherwise one step a tick.
        if (silent || tick != lastStep[0]) {
            bars.update(silent)
            lastStep[0] = tick
        }
        val g = globalActive
        val oy = (size.height - u(BlockHeight)) / 2f
        val barsTop = oy
        val barsBottom = oy + u(BarsHeight)
        val pitch = size.width / BandCount
        val barW = pitch * SpectrumBars.BAR_FILL
        val corner = CornerRadius(u(1.5f))
        val idle = HomePalette.Caption
        val lit = lerp(idle.copy(alpha = 0.55f), signal, g)
        val hot = lerp(idle.copy(alpha = 0.55f), HomePalette.MeterHot, g)
        val unlit = lerp(idle.copy(alpha = 0.10f), signal.copy(alpha = 0.14f), g)
        val brush = Brush.verticalGradient(listOf(hot, lit, lit), startY = barsTop, endY = barsBottom)
        for (i in 0 until BandCount) {
            val x = i * pitch + (pitch - barW) / 2
            drawRoundRect(unlit, Offset(x, barsTop), Size(barW, barsBottom - barsTop), corner)
            val h = (barsBottom - barsTop) * bars.level[i]
            if (h > 0f) drawRoundRect(brush, Offset(x, barsBottom - h), Size(barW, h), corner)
        }
        for ((hz, layout) in marks) {
            val band = 3 * ln(hz / LowestHz) / ln(2.0)
            val cx = (band.toFloat() + 0.5f) * pitch
            drawText(layout, HomePalette.Muted, Offset(cx - layout.size.width / 2f, oy + u(ScaleTop)))
        }
    }
}

// Third-octave bands from 25 Hz to 16 kHz, in the output meter's block (design units, matching
// OutputMeter's BlockHeight, CeilingHeight and ScaleTop).
private const val LowestHz = 25.0
private const val BandCount = 29
private const val FrameMs = 50L
private const val BlockHeight = 112f
private const val BarsHeight = 92f
private const val ScaleTop = 96f
private val Marks = listOf(50.0 to "50", 200.0 to "200", 1000.0 to "1k", 5000.0 to "5k")
