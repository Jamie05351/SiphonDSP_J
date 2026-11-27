package app.siphondsp.dsp

import kotlin.math.max

/**
 * A compressor band's static curve, level in against level out, exactly as the engine computes it
 * (`softKneeGainReductionDb` in cpp/engine/NativeBmwDspMath.h, then the band's makeup), for the
 * Bands page's curve. Display only: the engine is the authority.
 */
object CompressorCurve {

    /** The gain change in dB (<= 0) for a level [overDb] above the threshold. 1:1 with the native soft knee. */
    fun gainReductionDb(overDb: Double, ratio: Double, kneeDb: Double): Double {
        val slope = 1 - 1 / max(1.001, ratio)
        if (kneeDb > 0) {
            val kh = kneeDb * .5
            if (overDb >= kh) return -overDb * slope
            if (overDb > -kh) {
                val x = overDb + kh
                return -slope * x * x / (2 * kneeDb)
            }
            return 0.0
        }
        return if (overDb > 0) -overDb * slope else 0.0
    }

    /** Output level for an input of [inDb] with these settings (makeup included). */
    fun outputDb(inDb: Double, thresholdDb: Double, ratio: Double, kneeDb: Double, makeupDb: Double): Double =
        inDb + gainReductionDb(inDb - thresholdDb, ratio, kneeDb) + makeupDb
}

/**
 * Starting points for a band's ratio, knee and timing. Threshold and makeup are left alone: they
 * depend on how loud that band runs in your car, so they're yours to set.
 */
enum class MbcBandPreset(val label: String, val ratio: Float, val knee: Float, val attackMs: Float, val releaseMs: Float) {
    GENTLE("Gentle", 2f, 10f, 25f, 250f),
    MEDIUM("Medium", 3f, 6f, 10f, 150f),
    FIRM("Firm", 5f, 3f, 5f, 80f),
}
