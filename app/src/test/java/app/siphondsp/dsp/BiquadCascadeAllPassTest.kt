package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Mirrors NativeBmwRouting::AllPassSection::rebuild -- see BiquadCascade.addAllPass KDoc. */
class BiquadCascadeAllPassTest {
    private val sampleRate = 48_000.0

    private fun magnitudeAndPhaseAt(cascade: BiquadCascade, frequencyHz: Double): Pair<Double, Double> {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = 2.0 * cosW * cosW - 1.0
        val sin2W = 2.0 * sinW * cosW
        val acc = ComplexAcc()
        cascade.accumulate(cosW, sinW, cos2W, sin2W, acc)
        return acc.magnitude() to acc.phase()
    }

    @Test
    fun disabledAllPassAddsNoSectionAndActsAsIdentity() {
        val cascade = BiquadCascade(2)
        cascade.addAllPass(enabled = false, secondOrder = true, frequencyHz = 150.0, q = 0.707, sampleRate = sampleRate)

        assertEquals(0, cascade.sectionCount)
        val (magnitude, phase) = magnitudeAndPhaseAt(cascade, 150.0)
        assertEquals(1.0, magnitude, 1e-9)
        assertEquals(0.0, phase, 1e-9)
    }

    @Test
    fun secondOrderAllPassHasUnityMagnitudeAcrossTheAudibleBand() {
        val cascade = BiquadCascade(2)
        cascade.addAllPass(enabled = true, secondOrder = true, frequencyHz = 150.0, q = 0.707, sampleRate = sampleRate)

        for (frequency in listOf(20.0, 60.0, 150.0, 400.0, 2_000.0, 15_000.0)) {
            val (magnitude, _) = magnitudeAndPhaseAt(cascade, frequency)
            assertEquals("magnitude at ${frequency}Hz", 1.0, magnitude, 1e-6)
        }
    }

    @Test
    fun firstOrderAllPassHasUnityMagnitudeAcrossTheAudibleBand() {
        val cascade = BiquadCascade(2)
        cascade.addAllPass(enabled = true, secondOrder = false, frequencyHz = 300.0, q = 0.707, sampleRate = sampleRate)

        for (frequency in listOf(20.0, 100.0, 300.0, 1_000.0, 10_000.0)) {
            val (magnitude, _) = magnitudeAndPhaseAt(cascade, frequency)
            assertEquals("magnitude at ${frequency}Hz", 1.0, magnitude, 1e-6)
        }
    }

    @Test
    fun secondOrderAllPassPhaseCrosses180DegreesAtItsCentreFrequency() {
        val cascade = BiquadCascade(2)
        cascade.addAllPass(enabled = true, secondOrder = true, frequencyHz = 1_000.0, q = 0.707, sampleRate = sampleRate)

        val (_, farBelowPhase) = magnitudeAndPhaseAt(cascade, 20.0)
        val (_, centrePhase) = magnitudeAndPhaseAt(cascade, 1_000.0)

        // Far below its centre frequency an all-pass is close to 0 phase; at its own centre
        // frequency a 2nd-order section has rotated a full -180 degrees (wraps to +/-pi here).
        assertTrue("phase far below centre was $farBelowPhase", abs(farBelowPhase) < 0.1)
        assertTrue("centre phase was $centrePhase (degrees ${Math.toDegrees(centrePhase)})", abs(abs(centrePhase) - PI) < 0.05)
    }

    @Test
    fun invalidFrequencyIsRejectedAndAddsNoSection() {
        val cascade = BiquadCascade(2)
        cascade.addAllPass(enabled = true, secondOrder = true, frequencyHz = -10.0, q = 0.707, sampleRate = sampleRate)
        cascade.addAllPass(enabled = true, secondOrder = true, frequencyHz = sampleRate, q = 0.707, sampleRate = sampleRate)
        cascade.addAllPass(enabled = true, secondOrder = true, frequencyHz = Double.NaN, q = 0.707, sampleRate = sampleRate)

        assertEquals(0, cascade.sectionCount)
    }

    @Test
    fun invalidQIsRejectedAndAddsNoSection() {
        val cascade = BiquadCascade(2)
        cascade.addAllPass(enabled = true, secondOrder = true, frequencyHz = 150.0, q = 0.0, sampleRate = sampleRate)
        cascade.addAllPass(enabled = true, secondOrder = true, frequencyHz = 150.0, q = 31.0, sampleRate = sampleRate)

        assertEquals(0, cascade.sectionCount)
    }
}
