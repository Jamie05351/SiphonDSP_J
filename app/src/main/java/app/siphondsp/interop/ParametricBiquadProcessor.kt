package app.siphondsp.interop

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.utils.BiquadUtils
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Stateful stereo cascade of normalized RBJ biquads.
 *
 * Configuration is replaced atomically while audio processing is serialized by
 * JamesDspLocalEngine.nativeLock. No allocations occur in the sample loops.
 */
internal class ParametricBiquadProcessor {
    private data class Section(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
        var z1L: Double = 0.0,
        var z2L: Double = 0.0,
        var z1R: Double = 0.0,
        var z2R: Double = 0.0,
    )

    private var sections: Array<Section> = emptyArray()
    private var preampLinear = 1.0
    private var enabled = false

    fun configure(enable: Boolean, serializedBands: String, preampDb: Float, sampleRate: Float): Boolean {
        if (!enable) {
            enabled = false
            sections = emptyArray()
            preampLinear = 1.0
            return true
        }

        val fs = sampleRate.toDouble()
        if (!fs.isFinite() || fs < 8000.0 || !preampDb.isFinite()) {
            disable()
            return false
        }

        val bands = ParametricEqBandList().apply { deserialize(serializedBands) }
        if (bands.isEmpty() || bands.any { !isValid(it, fs) }) {
            disable()
            return false
        }

        val rebuilt = ArrayList<Section>(bands.size)
        for (band in bands) {
            val c = BiquadUtils.computeCoefficients(
                band.frequency,
                band.gain,
                band.q,
                band.filterType,
                fs,
            )
            if (!c.a0.isFinite() || c.a0 == 0.0) {
                disable()
                return false
            }

            val invA0 = 1.0 / c.a0
            val values = doubleArrayOf(
                c.b0 * invA0,
                c.b1 * invA0,
                c.b2 * invA0,
                c.a1 * invA0,
                c.a2 * invA0,
            )
            if (values.any { !it.isFinite() }) {
                disable()
                return false
            }
            rebuilt += Section(values[0], values[1], values[2], values[3], values[4])
        }

        sections = rebuilt.toTypedArray()
        preampLinear = 10.0.pow(preampDb.toDouble() / 20.0)
        enabled = true
        return true
    }

    private fun isValid(band: ParametricEqBand, sampleRate: Double): Boolean =
        band.frequency.isFinite() &&
            band.gain.isFinite() &&
            band.q.isFinite() &&
            band.frequency >= 20.0 &&
            band.frequency < sampleRate * 0.5 &&
            band.gain in -30.0..30.0 &&
            band.q in MIN_Q..MAX_Q

    private fun disable() {
        enabled = false
        sections = emptyArray()
        preampLinear = 1.0
    }

    private fun processLeft(input: Double): Double {
        var value = input * preampLinear
        for (section in sections) {
            val output = section.b0 * value + section.z1L
            section.z1L = section.b1 * value - section.a1 * output + section.z2L
            section.z2L = section.b2 * value - section.a2 * output
            value = output
        }
        return if (value.isFinite()) value else 0.0
    }

    private fun processRight(input: Double): Double {
        var value = input * preampLinear
        for (section in sections) {
            val output = section.b0 * value + section.z1R
            section.z1R = section.b1 * value - section.a1 * output + section.z2R
            section.z2R = section.b2 * value - section.a2 * output
            value = output
        }
        return if (value.isFinite()) value else 0.0
    }

    fun process(buffer: FloatArray, sampleCount: Int) {
        if (!enabled) return
        val limit = sampleCount.coerceAtMost(buffer.size) and -2
        var i = 0
        while (i < limit) {
            buffer[i] = processLeft(buffer[i].toDouble()).coerceIn(-1.0, 1.0).toFloat()
            buffer[i + 1] = processRight(buffer[i + 1].toDouble()).coerceIn(-1.0, 1.0).toFloat()
            i += 2
        }
    }

    fun process(buffer: ShortArray, sampleCount: Int) {
        if (!enabled) return
        val limit = sampleCount.coerceAtMost(buffer.size) and -2
        var i = 0
        while (i < limit) {
            buffer[i] = processLeft(buffer[i].toDouble()).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer[i + 1] = processRight(buffer[i + 1].toDouble()).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i += 2
        }
    }

    fun process(buffer: IntArray, sampleCount: Int) {
        if (!enabled) return
        val limit = sampleCount.coerceAtMost(buffer.size) and -2
        var i = 0
        while (i < limit) {
            buffer[i] = processLeft(buffer[i].toDouble()).roundToLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            buffer[i + 1] = processRight(buffer[i + 1].toDouble()).roundToLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            i += 2
        }
    }

    companion object {
        const val MIN_Q = 0.05
        const val MAX_Q = 30.0
    }
}
