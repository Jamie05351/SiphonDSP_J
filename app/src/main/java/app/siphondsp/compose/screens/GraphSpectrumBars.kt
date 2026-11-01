package app.siphondsp.compose.screens

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import app.siphondsp.audio.SpectrumBars
import app.siphondsp.audio.SpectrumEngine

/**
 * The live spectrum behind the PEQ and crossover graphs, in the front page's bar style: third-
 * octave [SpectrumBars] from 20 Hz, each a rounded bar up from the plot's floor in a gradient from
 * the hot magenta at the top of the plot to the signal colour at its foot, at [alpha] so the curves
 * in front stay the thing to read.
 *
 * [draw] steps the bars once per new spectrum [frame] (a redraw for any other reason repaints
 * without stepping, so the fall rate stays the same), and caches the gradient until the plot's
 * height or the colours change, so a frame allocates nothing.
 *
 * [drawFluid] is the PEQ graph's alternative: the same bands as one smooth, glowing filled curve
 * (a "liquid" analyser) instead of discrete bars. Use one style per instance.
 */
internal class GraphSpectrumBars {
    private val bars = SpectrumBars(LOWEST_HZ, BAND_COUNT)
    private var lastFrame = Int.MIN_VALUE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // What the cached gradient was built for.
    private var shaderTop = Float.NaN
    private var shaderBottom = Float.NaN
    private var shaderSignal = 0
    private var shaderHot = 0
    private var shaderAlpha = -1

    /**
     * Draws the bars between [top] and [bottom]; [xForHz] and [yForDb] are the graph's own axes
     * ([yForDb] takes the spectrum engine's dB). Bars are clipped to the axis, [minHz] to [maxHz].
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
        minHz: Double,
        maxHz: Double,
        xForHz: (Double) -> Float,
        yForDb: (Float) -> Float,
    ) {
        if (frame != lastFrame) {
            bars.update()
            lastFrame = frame
        }
        if (top != shaderTop || bottom != shaderBottom || signalArgb != shaderSignal || hotArgb != shaderHot || alpha != shaderAlpha) {
            paint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(ColorUtils.setAlphaComponent(hotArgb, alpha), ColorUtils.setAlphaComponent(signalArgb, alpha), ColorUtils.setAlphaComponent(signalArgb, alpha)),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
            shaderTop = top
            shaderBottom = bottom
            shaderSignal = signalArgb
            shaderHot = hotArgb
            shaderAlpha = alpha
        }
        val corner = CORNER_DP * density
        for (i in 0 until bars.bandCount) {
            if (bars.lowEdgeHz(i) >= maxHz) break
            if (bars.highEdgeHz(i) <= minHz) continue
            val x0 = xForHz(maxOf(bars.lowEdgeHz(i), minHz))
            val x1 = xForHz(minOf(bars.highEdgeHz(i), maxHz))
            val w = (x1 - x0) * SpectrumBars.BAR_FILL
            val x = x0 + (x1 - x0 - w) / 2
            val y = yForDb(bars.levelDb(i)).coerceIn(top, bottom)
            if (y < bottom) nc.drawRoundRect(x, y, x + w, bottom, corner, corner, paint)
        }
    }

    // --- fluid style -------------------------------------------------------------------------

    // The displayed curve: eased toward the bars each frame (rising as well as falling) and
    // lightly smoothed across neighbours, so the surface flows instead of snapping.
    private val fluid = FloatArray(BAND_COUNT)
    private val fluidScratch = FloatArray(BAND_COUNT)
    private val fluidX = FloatArray(BAND_COUNT + 2)
    private val fluidY = FloatArray(BAND_COUNT + 2)
    private val fluidPath = Path()
    private val fluidFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fluidEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fluidGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private var fluidShaderTop = Float.NaN
    private var fluidShaderBottom = Float.NaN
    private var fluidShaderColor = 0
    private var fluidShaderAlpha = -1

    /**
     * Draws the spectrum as one smooth filled curve in [colorArgb] between [top] and [bottom]: a
     * Catmull-Rom spline through the band centres, a gradient fill that is densest at the crest and
     * fades to the floor, and a soft glowing edge. [alpha] scales the whole thing. Same axes as [draw].
     */
    fun drawFluid(
        nc: Canvas,
        frame: Int,
        top: Float,
        bottom: Float,
        colorArgb: Int,
        alpha: Int,
        density: Float,
        minHz: Double,
        maxHz: Double,
        xForHz: (Double) -> Float,
        yForDb: (Float) -> Float,
    ) {
        if (frame != lastFrame) {
            bars.update()
            lastFrame = frame
            // Spatial smoothing (1-2-1) then temporal easing toward it.
            for (i in 0 until BAND_COUNT) {
                val l = bars.level[(i - 1).coerceAtLeast(0)]
                val r = bars.level[(i + 1).coerceAtMost(BAND_COUNT - 1)]
                fluidScratch[i] = maxOf(bars.level[i], (l + 2f * bars.level[i] + r) * 0.25f)
            }
            for (i in 0 until BAND_COUNT) {
                val target = fluidScratch[i]
                val ease = if (target > fluid[i]) FLUID_RISE else FLUID_FALL
                fluid[i] += (target - fluid[i]) * ease
            }
        }
        if (top != fluidShaderTop || bottom != fluidShaderBottom || colorArgb != fluidShaderColor || alpha != fluidShaderAlpha) {
            fun a(f: Float) = (alpha * f).toInt().coerceIn(0, 255)
            fluidFill.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(
                    ColorUtils.setAlphaComponent(ColorUtils.blendARGB(colorArgb, android.graphics.Color.WHITE, 0.15f), a(0.95f)),
                    ColorUtils.setAlphaComponent(colorArgb, a(0.55f)),
                    ColorUtils.setAlphaComponent(colorArgb, a(0.06f)),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            fluidEdge.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(colorArgb, android.graphics.Color.WHITE, 0.35f), a(1.6f))
            fluidGlow.color = ColorUtils.setAlphaComponent(colorArgb, a(0.45f))
            fluidShaderTop = top
            fluidShaderBottom = bottom
            fluidShaderColor = colorArgb
            fluidShaderAlpha = alpha
        }
        fluidEdge.strokeWidth = 1.6f * density
        fluidGlow.strokeWidth = 6f * density

        // Points: the plot's left edge, every band centre inside the axis, the right edge.
        val floorDb = SpectrumEngine.FLOOR_DB
        val rangeDb = SpectrumEngine.CEILING_DB - floorDb
        fun yAt(level: Float) = yForDb(floorDb + level * rangeDb).coerceIn(top, bottom)
        var n = 0
        var first = -1
        var last = -1
        for (i in 0 until BAND_COUNT) {
            val c = bars.centreHz(i)
            if (c <= minHz || c >= maxHz) continue
            if (first < 0) first = i
            last = i
        }
        if (first < 0) return
        fluidX[n] = xForHz(minHz); fluidY[n] = yAt(fluid[first]); n++
        for (i in first..last) {
            fluidX[n] = xForHz(bars.centreHz(i)); fluidY[n] = yAt(fluid[i]); n++
        }
        fluidX[n] = xForHz(maxHz); fluidY[n] = yAt(fluid[last]); n++

        // Catmull-Rom through the points as cubic Béziers, control points clamped to the plot so
        // the spline never overshoots past the floor or the ceiling.
        val path = fluidPath
        path.rewind()
        path.moveTo(fluidX[0], fluidY[0])
        for (k in 0 until n - 1) {
            val x0 = fluidX[(k - 1).coerceAtLeast(0)]; val y0 = fluidY[(k - 1).coerceAtLeast(0)]
            val x1 = fluidX[k]; val y1 = fluidY[k]
            val x2 = fluidX[k + 1]; val y2 = fluidY[k + 1]
            val x3 = fluidX[(k + 2).coerceAtMost(n - 1)]; val y3 = fluidY[(k + 2).coerceAtMost(n - 1)]
            val c1x = x1 + (x2 - x0) / 6f
            val c1y = (y1 + (y2 - y0) / 6f).coerceIn(top, bottom)
            val c2x = x2 - (x3 - x1) / 6f
            val c2y = (y2 - (y3 - y1) / 6f).coerceIn(top, bottom)
            path.cubicTo(c1x.coerceIn(x1, x2), c1y, c2x.coerceIn(x1, x2), c2y, x2, y2)
        }
        // Edge first (glow, then core) on the open curve, then close it down to the floor to fill.
        nc.drawPath(path, fluidGlow)
        nc.drawPath(path, fluidEdge)
        path.lineTo(fluidX[n - 1], bottom)
        path.lineTo(fluidX[0], bottom)
        path.close()
        nc.drawPath(path, fluidFill)
    }

    private companion object {
        // Per-frame easing of the fluid curve toward its target (at the ~20 fps graph tick).
        const val FLUID_RISE = 0.55f
        const val FLUID_FALL = 0.22f
        const val LOWEST_HZ = 20.0
        // 20 Hz .. 20 kHz in thirds of an octave.
        const val BAND_COUNT = 31
        const val CORNER_DP = 1.5f
    }
}
