package app.siphondsp.interop

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.BiquadUtils
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Lightweight stateful stereo RBJ biquad cascade.
 *
 * Coefficients and delay states use Float because the Android audio path is
 * already Float/PCM. Left and right bands are expanded into separate primitive
 * arrays during configuration, so the real-time loop performs no allocations,
 * enum checks, channel checks, or Double conversions.
 */
internal class ParametricBiquadProcessor {
    private class Cascade(capacity: Int) {
        private val b0 = FloatArray(capacity)
        private val b1 = FloatArray(capacity)
        private val b2 = FloatArray(capacity)
        private val a1 = FloatArray(capacity)
        private val a2 = FloatArray(capacity)
        private val z1 = FloatArray(capacity)
        private val z2 = FloatArray(capacity)
        var count: Int = 0
            private set

        fun add(coefficients: BiquadUtils.BiquadCoefficients): Boolean {
            if (!coefficients.a0.isFinite() || coefficients.a0 == 0.0) return false
            val invA0 = 1.0 / coefficients.a0
            val nb0 = (coefficients.b0 * invA0).toFloat()
            val nb1 = (coefficients.b1 * invA0).toFloat()
            val nb2 = (coefficients.b2 * invA0).toFloat()
            val na1 = (coefficients.a1 * invA0).toFloat()
            val na2 = (coefficients.a2 * invA0).toFloat()
            if (!nb0.isFinite() || !nb1.isFinite() || !nb2.isFinite() ||
                !na1.isFinite() || !na2.isFinite()) return false

            val i = count
            b0[i] = nb0
            b1[i] = nb1
            b2[i] = nb2
            a1[i] = na1
            a2[i] = na2
            count++
            return true
        }

        fun process(input: Float): Float {
            var value = input
            var i = 0
            while (i < count) {
                val output = b0[i] * value + z1[i]
                z1[i] = b1[i] * value - a1[i] * output + z2[i]
                z2[i] = b2[i] * value - a2[i] * output
                value = output
                i++
            }
            return if (value.isFinite()) value else 0f
        }
    }

    private var left = Cascade(0)
    private var right = Cascade(0)
    private var preampLinear = 1f

    var isActive: Boolean = false
        private set

    fun configure(enable: Boolean, serializedBands: String, preampDb: Float, sampleRate: Float): Boolean {
        if (!enable) {
            disable()
            return true
        }

        val fs = sampleRate.toDouble()
        if (!fs.isFinite() || fs < 8000.0 || !preampDb.isFinite() || preampDb !in -30f..0f) {
            disable()
            return false
        }

        val bands = ParametricEqBandList().apply { deserialize(serializedBands) }
        if (bands.isEmpty() || bands.any { !isValid(it, fs) }) {
            disable()
            return false
        }

        // A 0 dB RBJ section is unity. Removing it avoids unnecessary work on
        // every sample while preserving the requested response.
        val activeBands = bands.filter { abs(it.gain) > UNITY_GAIN_EPSILON_DB }
        val rebuiltLeft = Cascade(activeBands.count { it.channel.appliesToLeft() })
        val rebuiltRight = Cascade(activeBands.count { it.channel.appliesToRight() })

        for (band in activeBands) {
            val coefficients = BiquadUtils.computeCoefficients(
                band.frequency,
                band.gain,
                band.q,
                band.filterType,
                fs,
            )
            if (band.channel.appliesToLeft() && !rebuiltLeft.add(coefficients)) {
                disable()
                return false
            }
            if (band.channel.appliesToRight() && !rebuiltRight.add(coefficients)) {
                disable()
                return false
            }
        }

        left = rebuiltLeft
        right = rebuiltRight
        preampLinear = 10.0.pow(preampDb.toDouble() / 20.0).toFloat()
        isActive = left.count > 0 || right.count > 0 || preampLinear != 1f
        return true
    }

    private fun isValid(band: ParametricEqBand, sampleRate: Double): Boolean =
        band.frequency.isFinite() &&
            band.gain.isFinite() &&
            band.q.isFinite() &&
            band.frequency >= 20.0 &&
            band.frequency < sampleRate * 0.5 &&
            band.gain in -30.0..30.0 &&
            band.q in MIN_Q..MAX_Q &&
            band.channel in ParametricEqChannel.entries

    private fun disable() {
        isActive = false
        left = Cascade(0)
        right = Cascade(0)
        preampLinear = 1f
    }

    private fun processLeft(input: Float): Float = left.process(input * preampLinear)
    private fun processRight(input: Float): Float = right.process(input * preampLinear)

    fun process(buffer: FloatArray, sampleCount: Int) {
        if (!isActive) return
        val limit = sampleCount.coerceAtMost(buffer.size) and -2
        var i = 0
        while (i < limit) {
            buffer[i] = processLeft(buffer[i]).coerceIn(-1f, 1f)
            buffer[i + 1] = processRight(buffer[i + 1]).coerceIn(-1f, 1f)
            i += 2
        }
    }

    fun process(buffer: ShortArray, sampleCount: Int) {
        if (!isActive) return
        val limit = sampleCount.coerceAtMost(buffer.size) and -2
        var i = 0
        while (i < limit) {
            buffer[i] = processLeft(buffer[i].toFloat()).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer[i + 1] = processRight(buffer[i + 1].toFloat()).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i += 2
        }
    }

    fun process(buffer: IntArray, sampleCount: Int) {
        if (!isActive) return
        val limit = sampleCount.coerceAtMost(buffer.size) and -2
        var i = 0
        while (i < limit) {
            buffer[i] = processLeft(buffer[i].toFloat()).roundToLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            buffer[i + 1] = processRight(buffer[i + 1].toFloat()).roundToLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            i += 2
        }
    }

    companion object {
        const val MIN_Q = 0.05
        const val MAX_Q = 30.0
        private const val UNITY_GAIN_EPSILON_DB = 0.0001
    }
}
