package app.siphondsp.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class BmwVirtualInput { FRONT_LEFT, FRONT_RIGHT }
enum class BmwOutputId { LOW_LEFT, LOW_RIGHT, MID_LEFT, MID_RIGHT }
enum class BmwAllPassType { FIRST_ORDER, SECOND_ORDER }

data class BmwAllPassSection(
    val enabled: Boolean = false,
    val type: BmwAllPassType = BmwAllPassType.SECOND_ORDER,
    val frequencyHz: Float = 150f,
    val q: Float = 0.70710677f,
) {
    fun isValid(sampleRate: Float): Boolean = !enabled || (
        sampleRate.isFinite() && sampleRate >= 8_000f && frequencyHz.isFinite() &&
            frequencyHz >= 20f && frequencyHz < sampleRate * .5f && q.isFinite() && q in .1f..30f
        )

    /** b0,b1,b2,a1,a2 in the same normalised convention as native. */
    fun coefficients(sampleRate: Float): DoubleArray? {
        if (!isValid(sampleRate)) return null
        if (!enabled) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        val w = 2.0 * PI * frequencyHz / sampleRate
        if (type == BmwAllPassType.FIRST_ORDER) {
            val a = (tan(w * .5) - 1.0) / (tan(w * .5) + 1.0)
            return doubleArrayOf(a, 1.0, 0.0, a, 0.0)
        }
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        return doubleArrayOf((1.0 - alpha) / a0, -2.0 * cos(w) / a0, 1.0, -2.0 * cos(w) / a0, (1.0 - alpha) / a0)
    }
}

object BmwRouting {
    val stereoDefaults = arrayOf(
        floatArrayOf(1f, 0f), floatArrayOf(0f, 1f),
        floatArrayOf(1f, 0f), floatArrayOf(0f, 1f),
    )

    fun route(left: Float, right: Float, matrix: Array<FloatArray> = stereoDefaults): FloatArray {
        require(matrix.size == BmwOutputId.entries.size && matrix.all { it.size == BmwVirtualInput.entries.size })
        return FloatArray(BmwOutputId.entries.size) { output ->
            left * matrix[output][BmwVirtualInput.FRONT_LEFT.ordinal] +
                right * matrix[output][BmwVirtualInput.FRONT_RIGHT.ordinal]
        }
    }

    fun reconstruct(outputs: FloatArray): Pair<Float, Float> {
        require(outputs.size == BmwOutputId.entries.size)
        return (outputs[BmwOutputId.LOW_LEFT.ordinal] + outputs[BmwOutputId.MID_LEFT.ordinal]) to
            (outputs[BmwOutputId.LOW_RIGHT.ordinal] + outputs[BmwOutputId.MID_RIGHT.ordinal])
    }
}
