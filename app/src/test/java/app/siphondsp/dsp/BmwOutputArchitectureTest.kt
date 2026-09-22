package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class BmwOutputArchitectureTest {

    @Test
    fun nonFiniteInputsDoNotSilenceTheOtherSide() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(BmwOutputRouting.route(0f, 1f), BmwOutputRouting.route(invalid, 1f))
            assertEquals(BmwOutputRouting.route(-2f, 0f), BmwOutputRouting.route(-2f, invalid))
            assertEquals(BmwOutputRouting.route(0f, 0f), BmwOutputRouting.route(invalid, invalid))
        }
    }

    @Test
    fun mixedRoutingRetainsTheFiniteInputContribution() {
        val matrix = BmwRoutingOutput.entries.associateWith { BmwRoutingCoefficients(.5f, .25f) }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            BmwOutputRouting.route(invalid, 4f, matrix).values.forEach { assertEquals(1f, it, 0f) }
            BmwOutputRouting.route(4f, invalid, matrix).values.forEach { assertEquals(2f, it, 0f) }
        }
    }

    @Test
    fun overflowingFiniteInputProductsStillFallBackToSilence() {
        val matrix = BmwRoutingOutput.entries.associateWith { BmwRoutingCoefficients(2f, 2f) }
        BmwOutputRouting.route(Float.MAX_VALUE, Float.MAX_VALUE, matrix).values.forEach {
            assertEquals(0f, it, 0f)
        }
    }

    @Test
    fun defaultRoutingIsIdentity() {
        val routed = BmwOutputRouting.route(3f, -5f)

        assertEquals(3f, routed.getValue(BmwRoutingOutput.LOW_LEFT), 0f)
        assertEquals(-5f, routed.getValue(BmwRoutingOutput.LOW_RIGHT), 0f)
        assertEquals(3f, routed.getValue(BmwRoutingOutput.MID_LEFT), 0f)
        assertEquals(-5f, routed.getValue(BmwRoutingOutput.MID_RIGHT), 0f)
        assertEquals(3f, routed.getValue(BmwRoutingOutput.HIGH_LEFT), 0f)
        assertEquals(-5f, routed.getValue(BmwRoutingOutput.HIGH_RIGHT), 0f)
    }

    @Test
    fun defaultRoutingHasNoCrossfeed() {
        val routed = BmwOutputRouting.route(1f, 0f)

        // Only left-fed outputs should see any signal when right is silent.
        assertEquals(1f, routed.getValue(BmwRoutingOutput.LOW_LEFT), 0f)
        assertEquals(0f, routed.getValue(BmwRoutingOutput.LOW_RIGHT), 0f)
        assertEquals(1f, routed.getValue(BmwRoutingOutput.MID_LEFT), 0f)
        assertEquals(0f, routed.getValue(BmwRoutingOutput.MID_RIGHT), 0f)
        assertEquals(1f, routed.getValue(BmwRoutingOutput.HIGH_LEFT), 0f)
        assertEquals(0f, routed.getValue(BmwRoutingOutput.HIGH_RIGHT), 0f)
    }

    @Test
    fun finalStereoReconstructionSumsAllThreeBandsPerSide() {
        val outputs = mapOf(
            BmwRoutingOutput.LOW_LEFT to 2f,
            BmwRoutingOutput.LOW_RIGHT to -1f,
            BmwRoutingOutput.MID_LEFT to 0.5f,
            BmwRoutingOutput.MID_RIGHT to 3f,
            BmwRoutingOutput.HIGH_LEFT to 1f,
            BmwRoutingOutput.HIGH_RIGHT to -0.5f,
        )

        val (left, right) = BmwOutputRouting.reconstruct(outputs)

        assertEquals(3.5f, left, 1e-6f)
        assertEquals(1.5f, right, 1e-6f)
    }

    @Test
    fun defaultRoutingReconstructsExactInputUnderUnityGains() {
        val routed = BmwOutputRouting.route(4f, -2f)
        val (left, right) = BmwOutputRouting.reconstruct(routed)

        // Each side is fed straight through to all three bands, so summing Low+Mid+High on a
        // side triples that side's input under default (pre-routing-matrix) unity coefficients.
        assertEquals(12f, left, 1e-6f)
        assertEquals(-6f, right, 1e-6f)
    }

    @Test
    fun crossfeedRoutingBlendsBothInputsIntoAnOutput() {
        val blended = mapOf(
            BmwRoutingOutput.LOW_LEFT to BmwRoutingCoefficients(.5f, .5f),
            BmwRoutingOutput.LOW_RIGHT to BmwRoutingCoefficients(0f, 1f),
            BmwRoutingOutput.MID_LEFT to BmwRoutingCoefficients(1f, 0f),
            BmwRoutingOutput.MID_RIGHT to BmwRoutingCoefficients(0f, 1f),
            BmwRoutingOutput.HIGH_LEFT to BmwRoutingCoefficients(1f, 0f),
            BmwRoutingOutput.HIGH_RIGHT to BmwRoutingCoefficients(0f, 1f),
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
            BmwRoutingOutput.HIGH_LEFT to BmwRoutingCoefficients(1f, 0f),
            BmwRoutingOutput.HIGH_RIGHT to BmwRoutingCoefficients(0f, 1f),
        )

        val routed = BmwOutputRouting.route(1f, 1f, poisoned)

        assertEquals(0f, routed.getValue(BmwRoutingOutput.LOW_LEFT), 0f)
    }
}
