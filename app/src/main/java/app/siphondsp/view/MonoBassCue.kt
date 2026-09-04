package app.siphondsp.view

import app.siphondsp.model.NativeBmwDspValues

/**
 * The mono-bass display cue's pure predicate/interpolation math, lifted verbatim out of
 * [ParametricEqSurface] (monoBassActive / monoBassFrequency / monoBassBlendAt). Reads only the
 * native BMW DSP config array, so it is unit-testable without a View.
 */
object MonoBassCue {

    fun isActive(values: FloatArray): Boolean =
        values[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED] >= .5f &&
            values[NativeBmwDspValues.INDEX_MONO_BASS_BLEND] > 0f &&
            values[NativeBmwDspValues.INDEX_LPF_PASS] < .5f

    fun frequency(values: FloatArray, maximumFrequency: Double): Double =
        values[NativeBmwDspValues.INDEX_MONO_BASS_FREQ].toDouble().coerceIn(20.0, maximumFrequency)

    /**
     * Blend fraction (0..1) the two sum curves are pulled toward their L/R mean by, at
     * [frequency]: full below the mono-bass corner, ramping back to 0 across the half-octave
     * above it. This is a display-only cue -- [app.siphondsp.dsp.BmwResponseCalculator] still
     * models the mono-bass low branch under an L=R assumption, so it can't actually show L/R
     * polarity cancellation; visibly collapsing the L and R sum lines together where mono bass
     * engages at least makes "the low end is mono here" unmissable on the graph. A full stereo
     * mono-sum model is deferred (see the calculator).
     */
    fun blendAt(values: FloatArray, frequency: Double, maximumFrequency: Double): Float {
        if (!isActive(values)) return 0f
        val corner = frequency(values, maximumFrequency)
        val strength = (values[NativeBmwDspValues.INDEX_MONO_BASS_BLEND] * .01f).coerceIn(0f, 1f)
        return when {
            frequency <= corner -> strength
            frequency >= corner * 1.5 -> 0f
            else -> strength * (1f - ((frequency - corner) / (corner * 0.5)).toFloat())
        }
    }
}
