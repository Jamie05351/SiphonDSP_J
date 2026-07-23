package app.siphondsp.interop

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Encodes editable PEQ bands into JamesDSP's native ViPER-DDC/VDC format.
 *
 * VDC stores cascaded normalized SOS sections as:
 *   b0, b1, b2, -a1, -a2
 *
 * Feeding these coefficients through JamesDspWrapper.setVdc() keeps the PEQ in
 * libjamesdsp's established DDC processing path instead of adding another
 * per-buffer processing pass around LiveProg.
 */
internal object ParametricEqVdcEncoder {
    private val supportedSampleRates = intArrayOf(44100, 48000)
    private const val minQ = 0.05
    private const val maxQ = 30.0
    private const val unityEpsilonDb = 0.0001

    fun encode(bands: List<ParametricEqBand>, preampDb: Float): String? {
        if (!preampDb.isFinite() || preampDb !in -30f..0f) return null
        if (bands.any { it.channel != ParametricEqChannel.LEFT_RIGHT }) return null

        return supportedSampleRates.joinToString("\n") { sampleRate ->
            val coefficients = ArrayList<Double>((bands.size + 1) * 5)

            bands.forEach { band ->
                if (abs(band.gain) <= unityEpsilonDb) return@forEach
                val section = buildSection(band, sampleRate.toDouble()) ?: return null
                section.forEach(coefficients::add)
            }

            if (abs(preampDb) > unityEpsilonDb) {
                coefficients += 10.0.pow(preampDb.toDouble() / 20.0)
                coefficients += 0.0
                coefficients += 0.0
                coefficients += 0.0
                coefficients += 0.0
            }

            // DDC requires at least one valid SOS. A unity section is harmless
            // and lets the caller keep one deterministic encoding path.
            if (coefficients.isEmpty()) {
                coefficients += 1.0
                coefficients += 0.0
                coefficients += 0.0
                coefficients += 0.0
                coefficients += 0.0
            }

            "SR_$sampleRate:${coefficients.joinToString(",", transform = ::format)}"
        }
    }

    /** Cascades two VDC documents by concatenating their SOS lists per rate. */
    fun cascade(first: String?, second: String?): String? {
        val firstRates = parse(first)
        val secondRates = parse(second)
        if (firstRates == null || secondRates == null) return null

        val rates = (firstRates.keys + secondRates.keys).toSortedSet()
        if (rates.isEmpty()) return null

        return rates.joinToString("\n") { rate ->
            val values = buildList {
                addAll(firstRates[rate].orEmpty())
                addAll(secondRates[rate].orEmpty())
            }
            "SR_$rate:${values.joinToString(",", transform = ::format)}"
        }
    }

    private fun parse(contents: String?): Map<Int, List<Double>>? {
        if (contents.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<Int, List<Double>>()
        val regex = Regex("(?m)^\\s*SR_(\\d+)\\s*:\\s*([^\\r\\n]+)")
        regex.findAll(contents).forEach { match ->
            val rate = match.groupValues[1].toIntOrNull() ?: return null
            val values = match.groupValues[2]
                .split(',')
                .map { it.trim().toDoubleOrNull() ?: return null }
            if (values.isEmpty() || values.size % 5 != 0 || values.any { !it.isFinite() }) return null
            result[rate] = values
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun buildSection(band: ParametricEqBand, sampleRate: Double): DoubleArray? {
        val frequency = band.frequency
        val gain = band.gain
        val q = band.q
        if (!frequency.isFinite() || !gain.isFinite() || !q.isFinite() ||
            frequency < 20.0 || frequency >= sampleRate * 0.5 ||
            gain !in -30.0..30.0 || q !in minQ..maxQ
        ) return null

        val a = 10.0.pow(gain / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (band.filterType) {
            ParametricEqFilterType.PEAKING -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosOmega
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha / a
            }

            ParametricEqFilterType.LOW_SHELF -> {
                val sqrtA = sqrt(a)
                val term = 2.0 * sqrtA * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cosOmega + term)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosOmega)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosOmega - term)
                a0 = (a + 1.0) + (a - 1.0) * cosOmega + term
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosOmega)
                a2 = (a + 1.0) + (a - 1.0) * cosOmega - term
            }

            ParametricEqFilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(a)
                val term = 2.0 * sqrtA * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cosOmega + term)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosOmega)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosOmega - term)
                a0 = (a + 1.0) - (a - 1.0) * cosOmega + term
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosOmega)
                a2 = (a + 1.0) - (a - 1.0) * cosOmega - term
            }
        }

        if (!a0.isFinite() || a0 == 0.0) return null
        val invA0 = 1.0 / a0
        val section = doubleArrayOf(
            b0 * invA0,
            b1 * invA0,
            b2 * invA0,
            -a1 * invA0,
            -a2 * invA0,
        )
        return section.takeIf { values -> values.all { it.isFinite() } }
    }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.17g", value)
}
