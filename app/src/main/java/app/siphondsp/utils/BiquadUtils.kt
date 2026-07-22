package app.siphondsp.utils

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.math.*

object BiquadUtils {
    data class BiquadCoefficients(
        val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double
    )

    fun computeCoefficients(
        frequency: Double,
        gain: Double,
        q: Double,
        filterType: ParametricEqFilterType,
        sampleRate: Double = 48000.0
    ): BiquadCoefficients {
        val a = 10.0.pow(gain / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)

        return when (filterType) {
            ParametricEqFilterType.PEAKING -> {
                val alpha = sinOmega / (2.0 * q)
                BiquadCoefficients(
                    1.0 + alpha * a,
                    -2.0 * cosOmega,
                    1.0 - alpha * a,
                    1.0 + alpha / a,
                    -2.0 * cosOmega,
                    1.0 - alpha / a,
                )
            }
            ParametricEqFilterType.LOW_SHELF -> {
                val alpha = sinOmega / (2.0 * q)
                val sqrtA = sqrt(a)
                val twoSqrtAAlpha = 2.0 * sqrtA * alpha
                BiquadCoefficients(
                    a * ((a + 1.0) - (a - 1.0) * cosOmega + twoSqrtAAlpha),
                    2.0 * a * ((a - 1.0) - (a + 1.0) * cosOmega),
                    a * ((a + 1.0) - (a - 1.0) * cosOmega - twoSqrtAAlpha),
                    (a + 1.0) + (a - 1.0) * cosOmega + twoSqrtAAlpha,
                    -2.0 * ((a - 1.0) + (a + 1.0) * cosOmega),
                    (a + 1.0) + (a - 1.0) * cosOmega - twoSqrtAAlpha,
                )
            }
            ParametricEqFilterType.HIGH_SHELF -> {
                val alpha = sinOmega / (2.0 * q)
                val sqrtA = sqrt(a)
                val twoSqrtAAlpha = 2.0 * sqrtA * alpha
                BiquadCoefficients(
                    a * ((a + 1.0) + (a - 1.0) * cosOmega + twoSqrtAAlpha),
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cosOmega),
                    a * ((a + 1.0) + (a - 1.0) * cosOmega - twoSqrtAAlpha),
                    (a + 1.0) - (a - 1.0) * cosOmega + twoSqrtAAlpha,
                    2.0 * ((a - 1.0) - (a + 1.0) * cosOmega),
                    (a + 1.0) - (a - 1.0) * cosOmega - twoSqrtAAlpha,
                )
            }
        }
    }

    fun magnitudeResponse(
        coeffs: BiquadCoefficients,
        frequency: Double,
        sampleRate: Double = 48000.0
    ): Double {
        val omega = 2.0 * PI * frequency / sampleRate
        val cosW = cos(omega)
        val cos2W = cos(2.0 * omega)
        val sinW = sin(omega)
        val sin2W = sin(2.0 * omega)

        val numReal = coeffs.b0 + coeffs.b1 * cosW + coeffs.b2 * cos2W
        val numImag = -(coeffs.b1 * sinW + coeffs.b2 * sin2W)
        val denReal = coeffs.a0 + coeffs.a1 * cosW + coeffs.a2 * cos2W
        val denImag = -(coeffs.a1 * sinW + coeffs.a2 * sin2W)
        val numMagSq = numReal * numReal + numImag * numImag
        val denMagSq = denReal * denReal + denImag * denImag

        return if (denMagSq > 0.0) 10.0 * log10(numMagSq / denMagSq) else 0.0
    }

    fun computeCombinedResponse(
        bands: List<ParametricEqBand>,
        numPoints: Int = 512,
        minFreq: Double = 20.0,
        maxFreq: Double = 20000.0,
        sampleRate: Double = 48000.0,
        channel: ParametricEqChannel? = null,
    ): List<Pair<Double, Double>> {
        require(numPoints >= 2)
        val selected = when (channel) {
            ParametricEqChannel.LEFT -> bands.filter { it.channel.appliesToLeft() }
            ParametricEqChannel.RIGHT -> bands.filter { it.channel.appliesToRight() }
            ParametricEqChannel.LEFT_RIGHT -> bands.filter { it.channel == ParametricEqChannel.LEFT_RIGHT }
            null -> bands
        }
        if (selected.isEmpty()) return emptyList()

        val logMin = ln(minFreq)
        val logMax = ln(min(maxFreq, sampleRate * 0.5 * 0.999))
        val coeffs = selected.map {
            computeCoefficients(it.frequency, it.gain, it.q, it.filterType, sampleRate)
        }

        return List(numPoints) { i ->
            val t = i.toDouble() / (numPoints - 1).toDouble()
            val freq = exp(logMin + t * (logMax - logMin))
            freq to coeffs.sumOf { magnitudeResponse(it, freq, sampleRate) }
        }
    }

    /** Average of left and right dB curves for a compact stereo overview. */
    fun computeAverageStereoResponse(
        bands: List<ParametricEqBand>,
        numPoints: Int = 512,
        minFreq: Double = 20.0,
        maxFreq: Double = 20000.0,
        sampleRate: Double = 48000.0,
    ): List<Pair<Double, Double>> {
        if (bands.isEmpty()) return emptyList()
        val left = computeCombinedResponse(bands, numPoints, minFreq, maxFreq, sampleRate, ParametricEqChannel.LEFT)
        val right = computeCombinedResponse(bands, numPoints, minFreq, maxFreq, sampleRate, ParametricEqChannel.RIGHT)
        if (left.isEmpty() && right.isEmpty()) return emptyList()

        val frequencies = if (left.isNotEmpty()) left.map { it.first } else right.map { it.first }
        return frequencies.indices.map { i ->
            val leftGain = left.getOrNull(i)?.second ?: 0.0
            val rightGain = right.getOrNull(i)?.second ?: 0.0
            frequencies[i] to ((leftGain + rightGain) * 0.5)
        }
    }

    private val dfFreq = DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private val dfGain = DecimalFormat("0.000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    fun toGraphicEqString(response: List<Pair<Double, Double>>, preampOffset: Double = 0.0): String {
        val sb = StringBuilder("GraphicEQ: ")
        for ((freq, gain) in response) {
            sb.append("${dfFreq.format(freq)} ${dfGain.format(gain + preampOffset)}; ")
        }
        return sb.toString()
    }
}
