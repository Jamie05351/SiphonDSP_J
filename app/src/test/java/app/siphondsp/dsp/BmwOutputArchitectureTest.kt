package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class BmwOutputArchitectureTest {

    @Test
    fun defaultRoutingIsIdentity() {
        val routed = BmwOutputRouting.route(3f, -5f)

        assertEquals(3f, routed.getValue(BmwRoutingOutput.LOW_LEFT), 0f)
        assertEquals(-5f, routed.getValue(BmwRoutingOutput.LOW_RIGHT), 0f)
        assertEquals(3f, routed.getValue(BmwRoutingOutput.MID_LEFT), 0f)
        assertEquals(-5f, routed.getValue(BmwRoutingOutput.MID_RIGHT), 0f)
    }

    @Test
    fun defaultRoutingHasNoCrossfeed() {
        val routed = BmwOutputRouting.route(1f, 0f)

        // Only left-fed outputs should see any signal when right is silent.
        assertEquals(1f, routed.getValue(BmwRoutingOutput.LOW_LEFT), 0f)
        assertEquals(0f, routed.getValue(BmwRoutingOutput.LOW_RIGHT), 0f)
        assertEquals(1f, routed.getValue(BmwRoutingOutput.MID_LEFT), 0f)
        assertEquals(0f, routed.getValue(BmwRoutingOutput.MID_RIGHT), 0f)
    }

    @Test
    fun finalStereoReconstructionSumsLowAndMidPerSide() {
        val outputs = mapOf(
            BmwRoutingOutput.LOW_LEFT to 2f,
            BmwRoutingOutput.LOW_RIGHT to -1f,
            BmwRoutingOutput.MID_LEFT to 0.5f,
            BmwRoutingOutput.MID_RIGHT to 3f,
        )

        val (left, right) = BmwOutputRouting.reconstruct(outputs)

        assertEquals(2.5f, left, 1e-6f)
        assertEquals(2f, right, 1e-6f)
    }

    @Test
    fun defaultRoutingReconstructsExactInputUnderUnityGains() {
        val routed = BmwOutputRouting.route(4f, -2f)
        val (left, right) = BmwOutputRouting.reconstruct(routed)

        // Each side is fed straight through to both bands, so summing Low+Mid on a side
        // doubles that side's input under default (pre-routing-matrix) unity coefficients --
        // this is the existing two-way crossover topology (Low + Mid recombine to full range).
        assertEquals(8f, left, 1e-6f)
        assertEquals(-4f, right, 1e-6f)
    }

    @Test
    fun crossfeedRoutingBlendsBothInputsIntoAnOutput() {
        val blended = mapOf(
            BmwRoutingOutput.LOW_LEFT to BmwRoutingCoefficients(.5f, .5f),
            BmwRoutingOutput.LOW_RIGHT to BmwRoutingCoefficients(0f, 1f),
            BmwRoutingOutput.MID_LEFT to BmwRoutingCoefficients(1f, 0f),
            BmwRoutingOutput.MID_RIGHT to BmwRoutingCoefficients(0f, 1f),
        )

        val routed = BmwOutputRouting.route(2f, 4f, blended)

        assertEquals(3f, routed.getValue(BmwRoutingOutput.LOW_LEFT), 1e-6f)
    }

    @Test
    fun nonFiniteCoefficientProductFallsBackToSilenceNotNaN() {
        val poisoned = mapOf(
            BmwRoutingOutput.LOW_LEFT to BmwRoutingCoefficients(Float.NaN, 0f),
            BmwRoutingOutput.LOW_RIGHT to BmwRoutingCoefficients(0f, 1f),
            BmwRoutingOutput.MID_LEFT to BmwRoutingCoefficients(1f, 0f),
            BmwRoutingOutput.MID_RIGHT to BmwRoutingCoefficients(0f, 1f),
        )

        val routed = BmwOutputRouting.route(1f, 1f, poisoned)

        assertEquals(0f, routed.getValue(BmwRoutingOutput.LOW_LEFT), 0f)
    }
}
