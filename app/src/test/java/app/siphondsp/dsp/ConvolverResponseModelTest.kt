package app.siphondsp.dsp

import app.siphondsp.interop.NativeDsp
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** The convolver stage of [BmwResponseCalculator]: the IR's response times the engine's gain. */
class ConvolverResponseModelTest {
    private val calculator = BmwResponseCalculator(pointCount = POINTS)
    private val curves = BmwResponseCurves(POINTS)

    private fun grid(): DoubleArray {
        calculator.configureAxis(SAMPLE_RATE, 20.0, 20_000.0)
        return calculator.axisFrequencies()
    }

    /** Left: flat at [leftGain]; right: [rightGain] with a phase of -w (one sample) per frequency. */
    private fun response(freqs: DoubleArray, leftGain: Double, rightGain: Double, peakDb: Double) =
        ConvolverResponse(
            generation = 7,
            frequencies = freqs.copyOf(),
            re = arrayOf(DoubleArray(freqs.size) { leftGain }, DoubleArray(freqs.size) { rightGain * cos(w(freqs[it])) }),
            im = arrayOf(DoubleArray(freqs.size), DoubleArray(freqs.size) { -rightGain * sin(w(freqs[it])) }),
            peakGainDb = peakDb,
            latencyFrames = 0,
            sampleRate = SAMPLE_RATE,
        )

    private fun w(f: Double) = 2.0 * PI * f / SAMPLE_RATE

    private fun values(enabled: Boolean, trimDb: Float): FloatArray = NativeBmwDspValues.DEFAULTS.copyOf().also {
        it[NativeBmwDspValues.INDEX_CONVOLVER_ENABLED] = if (enabled) 1f else 0f
        it[NativeBmwDspValues.INDEX_CONVOLVER_GAIN] = trimDb
    }

    private fun sums(values: FloatArray, convolver: ConvolverResponse?): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        calculator.convolver = convolver
        calculator.invalidateAll()
        calculator.compute(values, BmwPeqState.empty(), curves)
        return Array(2) { curves.sumDb[it].copyOf() } to Array(2) { curves.sumPhase[it].copyOf() }
    }

    private fun wrap(a: Double) = atan2(sin(a), cos(a))

    @Test
    fun convolverMultipliesTheSumPerLogicalChannelAtTheAppliedGain() {
        val freqs = grid()
        // An IR at exactly -6 dB with a +6 dB trim: the peak cap (-peak = +6 dB) allows all of it.
        val conv = response(freqs, leftGain = MINUS_6_DB, rightGain = MINUS_6_DB, peakDb = -6.0)
        val (plainDb, plainPhase) = sums(values(enabled = true, trimDb = 6f), null)
        val (convDb, convPhase) = sums(values(enabled = true, trimDb = 6f), conv)
        for (i in freqs.indices) {
            // -6 dB of IR, +6 dB of trim: the magnitude is unchanged on both channels.
            assertEquals(plainDb[0][i], convDb[0][i], 1e-6)
            assertEquals(plainDb[1][i], convDb[1][i], 1e-6)
            assertEquals(0.0, wrap(convPhase[0][i] - plainPhase[0][i]), 1e-6)
            // Right carries the IR's own phase, left doesn't: the IR's left is the car's left.
            assertEquals(wrap(-w(freqs[i])), wrap(convPhase[1][i] - plainPhase[1][i]), 1e-6)
        }
    }

    @Test
    fun theGainIsCappedAtTheIrPeakAsTheEngineCapsIt() {
        val freqs = grid()
        // A +6 dB IR at 0 dB trim: capped at -6 dB, so the IR's boost is cancelled exactly.
        val conv = response(freqs, leftGain = PLUS_6_DB, rightGain = PLUS_6_DB, peakDb = 6.0)
        val (plainDb, _) = sums(values(enabled = true, trimDb = 0f), null)
        val (convDb, _) = sums(values(enabled = true, trimDb = 0f), conv)
        for (i in freqs.indices) assertEquals(plainDb[0][i], convDb[0][i], 1e-6)
        // A -10 dB trim sits under the cap: the IR's +6 dB and the -10 dB both land.
        val (trimmedDb, _) = sums(values(enabled = true, trimDb = -10f), conv)
        for (i in freqs.indices) assertEquals(plainDb[0][i] - 4.0, trimmedDb[0][i], 1e-6)
    }

    @Test
    fun offOrOnAnotherGridItContributesNothing() {
        val freqs = grid()
        val conv = response(freqs, leftGain = 0.25, rightGain = 0.25, peakDb = linToDb(0.25))
        val (plainDb, plainPhase) = sums(values(enabled = false, trimDb = 0f), null)
        val (offDb, offPhase) = sums(values(enabled = false, trimDb = 0f), conv)
        for (ch in 0..1) {
            assertArrayEquals(plainDb[ch], offDb[ch], 0.0)
            assertArrayEquals(plainPhase[ch], offPhase[ch], 0.0)
        }
        val shifted = response(DoubleArray(POINTS) { freqs[it] * 1.001 }, 0.25, 0.25, linToDb(0.25))
        val (otherGridDb, _) = sums(values(enabled = true, trimDb = 0f), shifted)
        val (onPlainDb, _) = sums(values(enabled = true, trimDb = 0f), null)
        for (ch in 0..1) assertArrayEquals(onPlainDb[ch], otherGridDb[ch], 0.0)
    }

    @Test
    fun parsesTheNativeLayout() {
        val freqs = doubleArrayOf(100.0, 1000.0)
        val raw = doubleArrayOf(
            12.0, 48_000.0, 4096.0, -3.0, // generation, rate, latency, peak
            1.0, 2.0, 3.0, 4.0, // left re/im x 2
            5.0, 6.0, 7.0, 8.0, // right re/im x 2
        )
        assertEquals(NativeDsp.CONVOLVER_RESPONSE_HEADER + 4 * freqs.size, raw.size)
        val r = checkNotNull(ConvolverResponse.fromNative(raw, freqs))
        assertEquals(12L, r.generation)
        assertEquals(4096, r.latencyFrames)
        assertEquals(-3.0, r.peakGainDb, 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 3.0), r.re[0], 0.0)
        assertArrayEquals(doubleArrayOf(2.0, 4.0), r.im[0], 0.0)
        assertArrayEquals(doubleArrayOf(5.0, 7.0), r.re[1], 0.0)
        assertArrayEquals(doubleArrayOf(6.0, 8.0), r.im[1], 0.0)
        assertNull(ConvolverResponse.fromNative(raw.copyOf(raw.size - 1), freqs))
    }

    private fun linToDb(x: Double) = 20.0 * kotlin.math.log10(x)

    private companion object {
        const val POINTS = 96
        const val SAMPLE_RATE = 48_000.0
        // Exactly -6 / +6 dB (not 0.5 / 2.0, which are -6.02 / +6.02 dB), so the expected offsets
        // are exact.
        val MINUS_6_DB = 10.0.pow(-6.0 / 20.0)
        val PLUS_6_DB = 10.0.pow(6.0 / 20.0)
    }
}
