package app.siphondsp.compose.screens

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import app.siphondsp.audio.SpectrumBars

/**
 * The live spectrum behind the PEQ and crossover graphs, in the front page's bar style: third-
 * octave [SpectrumBars] from 20 Hz, each a rounded bar up from the plot's floor in a gradient from
 * the hot magenta at the top of the plot to the signal colour at its foot, at [alpha] so the curves
 * in front stay the thing to read.
 *
 * [draw] steps the bars once per new spectrum [frame] (a redraw for any other reason repaints
 * without stepping, so the fall rate stays the same), and caches the gradient until the plot's
 * height or the colours change, so a frame allocates nothing.
 */
internal class GraphSpectrumBars {
    private val bars = SpectrumBars(LOWEST_HZ, BAND_COUNT)
    private var lastFrame = Int.MIN_VALUE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var shaderKey: List<Any>? = null

    /**
     * Draws the bars between [top] and [bottom]; [xForHz] and [yForDb] are the graph's own axes
     * ([yForDb] takes the spectrum engine's dB). Bands above [maxHz] are left out.
     */
    fun draw(
        nc: Canvas,
        frame: Int,
        top: Float,
        bottom: Float,
        signalArgb: Int,
        hotArgb: Int,
        alpha: Int,
        density: Float,
        maxHz: Double,
        xForHz: (Double) -> Float,
        yForDb: (Float) -> Float,
    ) {
        if (frame != lastFrame) {
            bars.update()
            lastFrame = frame
        }
        val key = listOf(top, bottom, signalArgb, hotArgb, alpha)
        if (key != shaderKey) {
            paint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(ColorUtils.setAlphaComponent(hotArgb, alpha), ColorUtils.setAlphaComponent(signalArgb, alpha), ColorUtils.setAlphaComponent(signalArgb, alpha)),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
            shaderKey = key
        }
        val corner = CORNER_DP * density
        for (i in 0 until bars.bandCount) {
            if (bars.lowEdgeHz(i) >= maxHz) break
            val x0 = xForHz(bars.lowEdgeHz(i))
            val x1 = xForHz(minOf(bars.highEdgeHz(i), maxHz))
            val w = (x1 - x0) * SpectrumBars.BAR_FILL
            val x = x0 + (x1 - x0 - w) / 2
            val y = yForDb(bars.levelDb(i)).coerceIn(top, bottom)
            if (y < bottom) nc.drawRoundRect(x, y, x + w, bottom, corner, corner, paint)
        }
    }

    private companion object {
        const val LOWEST_HZ = 20.0
        // 20 Hz .. 20 kHz in thirds of an octave.
        const val BAND_COUNT = 31
        const val CORNER_DP = 1.5f
    }
}
