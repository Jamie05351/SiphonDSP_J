package app.siphondsp.dsp

import app.siphondsp.model.NativeBmwDspValues

/**
 * Mirror of native `NativeBmwDsp::pairCrossovers` (NativeBmwCrossovers.h): the auto-correction
 * for crossover types that can't sum flat as a plain pair. When both sides of a boundary use the
 * same BW2 or BW4 type at the same corner (within [CORNER_TOLERANCE]), the corners spread apart
 * around it (lowpass at f / k, highpass at f * k, in the prewarped domain -- see [corner]) and,
 * for BW2, the band above is inverted -- within about +/-0.5 dB. The set frequency stays the
 * crossover point.
 *
 * [lowLowpass] .. [highHighpass] are each corner's spread factor; [midInverted] / [highInverted]
 * flip that band's polarity on top of the user's own.
 */
data class CrossoverPairing(
    val lowLowpass: Double = 1.0,
    val midHighpass: Double = 1.0,
    val midLowpass: Double = 1.0,
    val highHighpass: Double = 1.0,
    val midInverted: Boolean = false,
    val highInverted: Boolean = false,
) {
    /** True when the Low/Mid boundary is being corrected. */
    val lowMidCorrected: Boolean get() = midHighpass != 1.0

    /** True when the Mid/High boundary is being corrected. */
    val midHighCorrected: Boolean get() = highHighpass != 1.0

    companion object {
        /** Native kBw2PairSpread / kBw4PairSpread (float there, so the same float here). */
        const val BW2_SPREAD = 1.304f
        const val BW4_SPREAD = 1.128f

        /** Native kPairCornerTolerance: how far apart the two corners can be and still pair. */
        const val CORNER_TOLERANCE = 1.02f

        /**
         * Native spreadCorner(): [fc] moved by [factor] in the prewarped domain the filters are
         * designed in, tan(pi f' / fs) = factor * tan(pi fc / fs). Factor 1 returns [fc] exactly.
         */
        fun corner(fc: Double, factor: Double, sampleRate: Double): Double {
            if (factor == 1.0 || sampleRate <= 0.0) return fc
            // Float in native: factor and fc are floats there, so round the same way.
            val warped = kotlin.math.tan(Math.PI * fc.toFloat() / sampleRate.toFloat()) * factor.toFloat()
            return (kotlin.math.atan(warped) * sampleRate.toFloat() / Math.PI).toFloat().toDouble()
        }

        /** Decoded the way native configure() decodes a crossover-type slot. */
        private fun kind(type: Float): Float = when {
            type == NativeBmwDspValues.CROSSOVER_TYPE_BW1 -> NativeBmwDspValues.CROSSOVER_TYPE_BW1
            type == NativeBmwDspValues.CROSSOVER_TYPE_BW4 -> NativeBmwDspValues.CROSSOVER_TYPE_BW4
            type < .5f -> NativeBmwDspValues.CROSSOVER_TYPE_BW2
            type < 1.5f -> NativeBmwDspValues.CROSSOVER_TYPE_BW3
            else -> NativeBmwDspValues.CROSSOVER_TYPE_LR4
        }

        private fun spread(a: Float, b: Float, fa: Float, fb: Float): Float {
            val ka = kind(a)
            val ratio = if (fa > fb) fa / fb else fb / fa
            if (ka != kind(b) || !(ratio <= CORNER_TOLERANCE)) return 1f
            return when (ka) {
                NativeBmwDspValues.CROSSOVER_TYPE_BW2 -> BW2_SPREAD
                NativeBmwDspValues.CROSSOVER_TYPE_BW4 -> BW4_SPREAD
                else -> 1f
            }
        }

        /** One side's pairing; [left] picks the Low/Mid/High Left outputs, else the Right ones. */
        fun of(values: FloatArray, left: Boolean): CrossoverPairing {
            val low = if (left) 0 else 1
            val mid = if (left) 2 else 3
            val high = if (left) NativeBmwDspValues.OUTPUT_HIGH_LEFT else NativeBmwDspValues.OUTPUT_HIGH_RIGHT
            val lpfPass = values[NativeBmwDspValues.INDEX_LPF_PASS] >= .5f
            val hpfPass = values[NativeBmwDspValues.INDEX_HPF_PASS] >= .5f
            val highXoPass = values[NativeBmwDspValues.INDEX_HIGH_XO_PASS] >= .5f
            val highPass = values[NativeBmwDspValues.INDEX_HIGH_PASS] >= .5f
            val type = NativeBmwDspValues.FIELD_CROSSOVER_TYPE
            val freq = NativeBmwDspValues.FIELD_CROSSOVER_FREQ

            var lowLowpass = 1f
            var midHighpass = 1f
            var midInverted = false
            if (!lpfPass && !hpfPass) {
                val k = spread(
                    values[NativeBmwDspValues.outputIndex(low, type)],
                    values[NativeBmwDspValues.outputIndex(mid, type)],
                    values[NativeBmwDspValues.outputIndex(low, freq)],
                    values[NativeBmwDspValues.outputIndex(mid, freq)],
                )
                lowLowpass = 1f / k
                midHighpass = k
                midInverted = k == BW2_SPREAD
            }
            var midLowpass = 1f
            var highHighpass = 1f
            var midHighInverts = false
            val upperEnabled = values[NativeBmwDspValues.midUpperXoIndex(
                mid, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED,
            )] >= .5f
            if (!hpfPass && !highXoPass && !highPass && upperEnabled) {
                val k = spread(
                    values[NativeBmwDspValues.midUpperXoTypeIndex(mid)],
                    values[NativeBmwDspValues.highOutputIndex(high, type)],
                    values[NativeBmwDspValues.midUpperXoIndex(mid, NativeBmwDspValues.MID_UPPER_XO_FIELD_FREQ)],
                    values[NativeBmwDspValues.highOutputIndex(high, freq)],
                )
                midLowpass = 1f / k
                highHighpass = k
                midHighInverts = k == BW2_SPREAD
            }
            return CrossoverPairing(
                lowLowpass.toDouble(), midHighpass.toDouble(), midLowpass.toDouble(), highHighpass.toDouble(),
                midInverted, midInverted != midHighInverts,
            )
        }
    }
}
