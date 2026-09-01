package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors NativeBmwDspProcessor's multiband compressor: the crossover tree must reconstruct
 * flat, and the gain computer must match processCompressor's soft-knee curve. See
 * [MultibandCompressorTree]'s KDoc for the flat-reconstruction identity.
 */
class MultibandCompressorTreeTest {
    private val sampleRate = 48_000.0

    // Wide sweep incl. the exact crossover corners, where a missing all-pass compensator would
    // show up as a dip.
    private val sweepHz = listOf(
        20.0, 40.0, 80.0, 100.0, 120.0, 160.0, 250.0, 400.0, 500.0, 700.0,
        1_000.0, 2_000.0, 3_000.0, 4_000.0, 6_000.0, 10_000.0, 16_000.0, 20_000.0,
    )

    @Test
    fun compensatedFourBandTreeReconstructsFlatAcrossTheSpectrum() {
        val tree = MultibandCompressorTree(120.0, 500.0, 4_000.0, sampleRate)

        for (frequency in sweepHz) {
            assertEquals(
                "reconstruction at ${frequency}Hz",
                0.0,
                tree.reconstructionMagnitudeDbAt(frequency),
                1e-4,
            )
        }
    }

    @Test
    fun reconstructsFlatForOtherSplitChoicesToo() {
        for (splits in listOf(
            Triple(80.0, 350.0, 2_500.0),
            Triple(150.0, 900.0, 6_000.0),
            Triple(60.0, 250.0, 8_000.0),
        )) {
            val tree = MultibandCompressorTree(splits.first, splits.second, splits.third, sampleRate)
            for (frequency in sweepHz) {
                assertEquals(
                    "reconstruction at ${frequency}Hz for splits $splits",
                    0.0,
                    tree.reconstructionMagnitudeDbAt(frequency),
                    1e-4,
                )
            }
        }
    }

    @Test
    fun misorderedSplitsAreClampedMonotonicLikeNative() {
        val tree = MultibandCompressorTree(900.0, 300.0, 100.0, sampleRate)

        assertEquals(900.0, tree.f0, 1e-9)
        assertTrue("f1 pushed above f0", tree.f1 >= tree.f0 * 1.05)
        assertTrue("f2 pushed above f1", tree.f2 >= tree.f1 * 1.05)
        // Still reconstructs flat after the clamp.
        for (frequency in sweepHz) {
            assertEquals(0.0, tree.reconstructionMagnitudeDbAt(frequency), 1e-4)
        }
    }

    @Test
    fun gainComputerIsInertBelowTheKnee() {
        val gr = MultibandCompressorTree.targetGainReductionDb(
            detectorDb = -40.0, thresholdDb = -18.0, ratio = 4.0, kneeDb = 6.0,
        )
        assertEquals(0.0, gr, 0.0)
    }

    @Test
    fun gainComputerAppliesFullSlopeWellAboveTheKnee() {
        // detector -6 dB, threshold -18 dB -> 12 dB over; 2:1 -> slope 0.5 -> -6 dB reduction,
        // i.e. output lands at threshold + over/ratio = -12 dBFS.
        val gr = MultibandCompressorTree.targetGainReductionDb(
            detectorDb = -6.0, thresholdDb = -18.0, ratio = 2.0, kneeDb = 6.0,
        )
        assertEquals(-6.0, gr, 1e-9)
        assertEquals(-12.0, -6.0 + gr, 1e-9)
    }

    @Test
    fun gainComputerSoftKneeIsContinuousAtBothKneeEdges() {
        val threshold = -18.0
        val ratio = 3.0
        val knee = 8.0
        val slope = 1.0 - 1.0 / ratio

        // Lower edge: over = -knee/2 -> still zero, matching the "below" branch.
        assertEquals(
            0.0,
            MultibandCompressorTree.targetGainReductionDb(threshold - knee / 2, threshold, ratio, knee),
            1e-9,
        )
        // Upper edge: over = +knee/2 -> quadratic meets the straight line at -(knee/2)*slope.
        assertEquals(
            -(knee / 2) * slope,
            MultibandCompressorTree.targetGainReductionDb(threshold + knee / 2, threshold, ratio, knee),
            1e-9,
        )
        // Midpoint: over = 0 -> gentle reduction, strictly between 0 and the hard-knee value.
        val midKnee = MultibandCompressorTree.targetGainReductionDb(threshold, threshold, ratio, knee)
        assertTrue("soft knee reduces at threshold", midKnee < 0.0)
        assertTrue("soft knee is gentler than hard knee", midKnee > -(knee / 2) * slope)
    }

    @Test
    fun hardKneeGainComputerHasNoReductionExactlyAtThreshold() {
        val gr = MultibandCompressorTree.targetGainReductionDb(
            detectorDb = -18.0, thresholdDb = -18.0, ratio = 4.0, kneeDb = 0.0,
        )
        assertEquals(0.0, gr, 0.0)
    }

    @Test
    fun onePoleMixMatchesTheNativeTimeConstantFormula() {
        val mix = MultibandCompressorTree.onePoleMix(150.0, sampleRate)
        val expected = 1.0 - Math.exp(-1.0 / (0.150 * sampleRate))
        assertEquals(expected, mix, 1e-12)
        assertTrue(mix > 0.0 && mix < 1.0)
    }
}
