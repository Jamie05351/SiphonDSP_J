package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
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
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.view.LevelReadout
import kotlin.math.ln
import kotlin.math.pow

/**
 * The top screen's live spectrum: the processed output in [BandCount] third-octave bars from
 * 25 Hz to 16 kHz, lined up with the output meters' block (bars across its height, frequency marks
 * where the meters have their dB scale).
 *
 * It reads [SpectrumEngine], which the front page's level feed already keeps running, and redraws
 * on each new meter frame: [meterLevels] is read only while drawing, so a frame redraws this canvas
 * and recomposes nothing. Bars rise at once and fall at [FallPerFrame] of full height a frame, so
 * they read like a meter rather than flicker. A silent frame (the feed stopped) lets them fall to
 * the floor. [globalActive] blends the bars to grey while the DSP is bypassed. Purely visual.
 */
@Composable
fun SpectrumStrip(meterLevels: () -> LevelReadout, globalActive: Float, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val measurer = rememberTextMeasurer()
    val shown = remember { FloatArray(BandCount) }
    Canvas(modifier.clearAndSetSemantics { }) {
        fun u(v: Float) = v * s.k * density
        val silent = meterLevels().silent
        val g = globalActive
        val oy = (size.height - u(BlockHeight)) / 2f
        val barsTop = oy
        val barsBottom = oy + u(BarsHeight)
        val pitch = size.width / BandCount
        val barW = pitch * BarFill
        val corner = CornerRadius(u(1.5f))
        val idle = HomePalette.Caption
        val lit = lerp(idle.copy(alpha = 0.55f), signal, g)
        val hot = lerp(idle.copy(alpha = 0.55f), HomePalette.MeterHot, g)
        val unlit = lerp(idle.copy(alpha = 0.10f), signal.copy(alpha = 0.14f), g)
        val brush = Brush.verticalGradient(listOf(hot, lit, lit), startY = barsTop, endY = barsBottom)
        for (i in 0 until BandCount) {
            val centre = LowestHz * 2.0.pow(i / 3.0)
            // The loudest of three points across the band, so narrow peaks still show.
            val db = maxOf(
                SpectrumEngine.magnitudeDbAt(centre * EdgeRatio),
                SpectrumEngine.magnitudeDbAt(centre),
                SpectrumEngine.magnitudeDbAt(centre / EdgeRatio),
            )
            val target = if (silent) 0f else
                ((db - SpectrumEngine.FLOOR_DB) / (SpectrumEngine.CEILING_DB - SpectrumEngine.FLOOR_DB)).coerceIn(0f, 1f)
            shown[i] = if (target >= shown[i]) target else (shown[i] - FallPerFrame).coerceAtLeast(target)
            val x = i * pitch + (pitch - barW) / 2
            drawRoundRect(unlit, Offset(x, barsTop), Size(barW, barsBottom - barsTop), corner)
            val h = (barsBottom - barsTop) * shown[i]
            if (h > 0f) drawRoundRect(brush, Offset(x, barsBottom - h), Size(barW, h), corner)
        }
        for ((hz, label) in Marks) {
            val band = 3 * ln(hz / LowestHz) / ln(2.0)
            val cx = (band.toFloat() + 0.5f) * pitch
            val layout = measurer.measure(label, TextStyle(fontSize = s.sp(13f)))
            drawText(layout, HomePalette.Muted, Offset(cx - layout.size.width / 2f, oy + u(ScaleTop)))
        }
    }
}

// Third-octave bands from 25 Hz to 16 kHz, in the output meter's block (design units, matching
// OutputMeter's BlockHeight, CeilingHeight and ScaleTop).
private const val LowestHz = 25.0
private const val BandCount = 29
private val EdgeRatio = 2.0.pow(1.0 / 6.0)
private const val BarFill = 0.62f
private const val FallPerFrame = 0.035f
private const val BlockHeight = 112f
private const val BarsHeight = 92f
private const val ScaleTop = 96f
private val Marks = listOf(50.0 to "50", 200.0 to "200", 1000.0 to "1k", 5000.0 to "5k")
