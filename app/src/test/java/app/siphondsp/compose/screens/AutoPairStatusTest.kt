package app.siphondsp.compose.screens

import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Auto-pair status line under each boundary's switch (see AutoPairRows). */
class AutoPairStatusTest {
    private fun lowMid(type: Float, autoPair: Boolean, midType: Float = type) = NativeBmwDspValues.DEFAULTS.copyOf().also {
        it[NativeBmwDspValues.INDEX_OUTPUT_SCHEMA_VERSION] = NativeBmwDspValues.OUTPUT_SCHEMA_VERSION
        it[NativeBmwDspValues.INDEX_LOW_MID_AUTO_PAIR] = if (autoPair) 1f else 0f
        for (output in 0 until 4) {
            it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 150f
            it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                if (output < 2) type else midType
        }
    }

    @Test
    fun onShowsTheCornersAsSetAndAsApplied() {
        val bw4 = lowMid(NativeBmwDspValues.CROSSOVER_TYPE_BW4, autoPair = true)
        assertEquals("LP 150 → 133, HP 150 → 169 Hz" to true, autoPairStatusText(bw4, lowMid = true))
        val lr4 = lowMid(NativeBmwDspValues.CROSSOVER_TYPE_LR4, autoPair = true)
        assertEquals("Idle: no BW2/BW4 pair here" to false, autoPairStatusText(lr4, lowMid = true))
    }

    @Test
    fun offSaysWhatThePairDoesUncorrected() {
        assertEquals(
            "BW4 pair: +3 dB at 150 Hz" to false,
            autoPairStatusText(lowMid(NativeBmwDspValues.CROSSOVER_TYPE_BW4, autoPair = false), lowMid = true),
        )
        assertEquals(
            "BW2 pair: null at 150 Hz" to false,
            autoPairStatusText(lowMid(NativeBmwDspValues.CROSSOVER_TYPE_BW2, autoPair = false), lowMid = true),
        )
        assertNull(autoPairStatusText(lowMid(NativeBmwDspValues.CROSSOVER_TYPE_LR4, autoPair = false), lowMid = true))
    }

    @Test
    fun offAccountsForOneBandsOwnPolarity() {
        fun midInverted(type: Float) = lowMid(type, autoPair = false).also {
            for (output in 2 until 4) it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)] = 1f
        }
        assertEquals(
            "BW2 pair: +3 dB at 150 Hz" to false,
            autoPairStatusText(midInverted(NativeBmwDspValues.CROSSOVER_TYPE_BW2), lowMid = true),
        )
        assertEquals(
            "BW4 pair: null at 150 Hz" to false,
            autoPairStatusText(midInverted(NativeBmwDspValues.CROSSOVER_TYPE_BW4), lowMid = true),
        )
    }
}
