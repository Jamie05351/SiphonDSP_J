package app.siphondsp.audio

import kotlin.math.pow

/**
 * A third-octave bar spectrum of the processed output, the one style shared by the front page and
 * the PEQ and crossover graphs. [update] reads [SpectrumEngine] once a frame into [level]: each
 * bar is the loudest of three points across its band (so narrow peaks still show), on 0..1 of the
 * engine's floor..ceiling, rising at once and falling at [fallPerFrame] of full height a frame so
 * it reads like a meter rather than flicker. Callers own the drawing and the frame clock.
 *
 * Band i is centred on [lowestHz] × 2^(i/3); [bandCount] bands.
 */
class SpectrumBars(private val lowestHz: Double, val bandCount: Int, private val fallPerFrame: Float = 0.035f) {
    val level = FloatArray(bandCount)

    fun centreHz(i: Int): Double = lowestHz * 2.0.pow(i / 3.0)
    fun lowEdgeHz(i: Int): Double = centreHz(i) / EDGE_RATIO
    fun highEdgeHz(i: Int): Double = centreHz(i) * EDGE_RATIO

    /** Bar [i]'s level back in the engine's dB, for graphs that map dB to height themselves. */
    fun levelDb(i: Int): Float = SpectrumEngine.FLOOR_DB + level[i] * (SpectrumEngine.CEILING_DB - SpectrumEngine.FLOOR_DB)

    /** One frame: [silent] drops every bar to the floor at once. */
    fun update(silent: Boolean = false) {
        for (i in 0 until bandCount) {
            if (silent) {
                level[i] = 0f
                continue
            }
            val centre = centreHz(i)
            val db = maxOf(
                SpectrumEngine.magnitudeDbAt(centre / EDGE_RATIO * MID_RATIO),
                SpectrumEngine.magnitudeDbAt(centre),
                SpectrumEngine.magnitudeDbAt(centre * EDGE_RATIO / MID_RATIO),
            )
            val target = ((db - SpectrumEngine.FLOOR_DB) / (SpectrumEngine.CEILING_DB - SpectrumEngine.FLOOR_DB)).coerceIn(0f, 1f)
            level[i] = if (target >= level[i]) target else (level[i] - fallPerFrame).coerceAtLeast(target)
        }
    }

    companion object {
        /** A third-octave band's edges sit a sixth of an octave either side of its centre. */
        val EDGE_RATIO: Double = 2.0.pow(1.0 / 6.0)

        // The two outer sample points, a twelfth of an octave in from each edge.
        private val MID_RATIO: Double = 2.0.pow(1.0 / 12.0)

        /** Each bar fills this share of its band's width, the rest a gap. */
        const val BAR_FILL = 0.62f
    }
}
