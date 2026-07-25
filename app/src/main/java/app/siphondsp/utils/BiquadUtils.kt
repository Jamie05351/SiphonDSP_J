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

    data class StereoResponse(
        val frequencies: DoubleArray,
        val leftDb: DoubleArray,
        val rightDb: DoubleArray,
    )

    private data class Complex(val re: Double, val im: Double) {
        operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
        operator fun times(other: Complex) = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
        operator fun div(other: Complex): Complex {
            val d = other.re * other.re + other.im * other.im
            return if (d <= 1e-30) Complex(0.0, 0.0)
            else Complex((re * other.re + im * other.im) / d, (im * other.re - re * other.im) / d)
        }
        fun magnitude() = hypot(re, im)
    }

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
                    1.0 + alpha * a, -2.0 * cosOmega, 1.0 - alpha * a,
                    1.0 + alpha / a, -2.0 * cosOmega, 1.0 - alpha / a,
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

    private fun lowPassCoefficients(frequency: Double, q: Double, sampleRate: Double): BiquadCoefficients {
        val w = 2.0 * PI * frequency.coerceIn(20.0, sampleRate * 0.49) / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        return BiquadCoefficients((1.0 - c) * 0.5, 1.0 - c, (1.0 - c) * 0.5, 1.0 + alpha, -2.0 * c, 1.0 - alpha)
    }

    private fun highPassCoefficients(frequency: Double, q: Double, sampleRate: Double): BiquadCoefficients {
        val w = 2.0 * PI * frequency.coerceIn(20.0, sampleRate * 0.49) / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        return BiquadCoefficients((1.0 + c) * 0.5, -(1.0 + c), (1.0 + c) * 0.5, 1.0 + alpha, -2.0 * c, 1.0 - alpha)
    }

    private fun response(coeffs: BiquadCoefficients, frequency: Double, sampleRate: Double): Complex {
        val w = 2.0 * PI * frequency / sampleRate
        val z1 = Complex(cos(w), -sin(w))
        val z2 = Complex(cos(2.0 * w), -sin(2.0 * w))
        val numerator = Complex(coeffs.b0, 0.0) + Complex(coeffs.b1, 0.0) * z1 + Complex(coeffs.b2, 0.0) * z2
        val denominator = Complex(coeffs.a0, 0.0) + Complex(coeffs.a1, 0.0) * z1 + Complex(coeffs.a2, 0.0) * z2
        return numerator / denominator
    }

    private fun onePoleLowResponse(cutoff: Double, frequency: Double, sampleRate: Double): Complex {
        val k = tan(PI * cutoff.coerceIn(20.0, sampleRate * 0.49) / sampleRate)
        val a0 = k / (k + 1.0)
        val a1 = a0
        val b1 = (k - 1.0) / (k + 1.0)
        val w = 2.0 * PI * frequency / sampleRate
        val z1 = Complex(cos(w), -sin(w))
        return (Complex(a0, 0.0) + Complex(a1, 0.0) * z1) / (Complex(1.0, 0.0) + Complex(b1, 0.0) * z1)
    }

    private fun peqResponse(
        bands: List<ParametricEqBand>,
        channel: ParametricEqChannel,
        frequency: Double,
        sampleRate: Double,
    ): Complex {
        var result = Complex(1.0, 0.0)
        bands.filter { if (channel == ParametricEqChannel.LEFT) it.channel.appliesToLeft() else it.channel.appliesToRight() }
            .forEach { band ->
                result *= response(computeCoefficients(band.frequency, band.gain, band.q, band.filterType, sampleRate), frequency, sampleRate)
            }
        return result
    }

    fun computeBmwSystemResponse(
        fullRangeBands: List<ParametricEqBand>,
        lowBandBands: List<ParametricEqBand>,
        midBandBands: List<ParametricEqBand>,
        preampDb: Double,
        lowPassHz: Double,
        lowLr4: Boolean,
        highPassHz: Double,
        numPoints: Int = 256,
        minFreq: Double = 20.0,
        maxFreq: Double = 20000.0,
        sampleRate: Double = 48000.0,
    ): StereoResponse {
        val frequencies = DoubleArray(numPoints)
        val left = DoubleArray(numPoints)
        val right = DoubleArray(numPoints)
        val logMin = ln(minFreq)
        val logMax = ln(min(maxFreq, sampleRate * 0.5 * 0.999))
        val lpA = lowPassCoefficients(lowPassHz, if (lowLr4) 0.7071067812 else 1.0, sampleRate)
        val lpB = lowPassCoefficients(lowPassHz, 0.7071067812, sampleRate)
        val hpA = highPassCoefficients(highPassHz, 0.7071067812, sampleRate)
        val hpB = highPassCoefficients(highPassHz, 0.7071067812, sampleRate)
        val preamp = 10.0.pow(preampDb / 20.0)

        for (i in 0 until numPoints) {
            val t = i.toDouble() / (numPoints - 1).toDouble()
            val frequency = exp(logMin + t * (logMax - logMin))
            frequencies[i] = frequency
            for ((channel, target) in listOf(ParametricEqChannel.LEFT to left, ParametricEqChannel.RIGHT to right)) {
                var low = response(lpA, frequency, sampleRate)
                low *= if (lowLr4) response(lpB, frequency, sampleRate) else onePoleLowResponse(lowPassHz, frequency, sampleRate)
                low *= peqResponse(lowBandBands, channel, frequency, sampleRate)

                var mid = response(hpA, frequency, sampleRate) * response(hpB, frequency, sampleRate)
                mid *= peqResponse(midBandBands, channel, frequency, sampleRate)

                val full = peqResponse(fullRangeBands, channel, frequency, sampleRate)
                val summed = (low + mid) * full
                target[i] = 20.0 * log10(max(1e-12, summed.magnitude() * preamp))
            }
        }
        return StereoResponse(frequencies, left, right)
    }

    fun magnitudeResponse(coeffs: BiquadCoefficients, frequency: Double, sampleRate: Double = 48000.0): Double {
        val magnitude = response(coeffs, frequency, sampleRate).magnitude()
        return 20.0 * log10(max(magnitude, 1e-12))
    }

    fun computeCombinedResponse(
        bands: List<ParametricEqBand>, numPoints: Int = 512, minFreq: Double = 20.0,
        maxFreq: Double = 20000.0, sampleRate: Double = 48000.0,
        channel: ParametricEqChannel? = null,
    ): List<Pair<Double, Double>> {
        require(numPoints >= 2)
        if (channel == null && bands.any { it.channel != ParametricEqChannel.LEFT_RIGHT }) {
            return computeAverageStereoResponse(bands, numPoints, minFreq, maxFreq, sampleRate)
        }
        val selected = when (channel) {
            ParametricEqChannel.LEFT -> bands.filter { it.channel.appliesToLeft() }
            ParametricEqChannel.RIGHT -> bands.filter { it.channel.appliesToRight() }
            ParametricEqChannel.LEFT_RIGHT -> bands.filter { it.channel == ParametricEqChannel.LEFT_RIGHT }
            null -> bands
        }
        if (selected.isEmpty()) return emptyList()
        val logMin = ln(minFreq)
        val logMax = ln(min(maxFreq, sampleRate * 0.5 * 0.999))
        val coeffs = selected.map { computeCoefficients(it.frequency, it.gain, it.q, it.filterType, sampleRate) }
        return List(numPoints) { i ->
            val t = i.toDouble() / (numPoints - 1).toDouble()
            val freq = exp(logMin + t * (logMax - logMin))
            freq to coeffs.sumOf { magnitudeResponse(it, freq, sampleRate) }
        }
    }

    fun computeAverageStereoResponse(
        bands: List<ParametricEqBand>, numPoints: Int = 512, minFreq: Double = 20.0,
        maxFreq: Double = 20000.0, sampleRate: Double = 48000.0,
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
        for ((freq, gain) in response) sb.append("${dfFreq.format(freq)} ${dfGain.format(gain + preampOffset)}; ")
        return sb.toString()
    }
}
