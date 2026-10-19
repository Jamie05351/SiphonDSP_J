package app.siphondsp.compose.screens

import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ValuesKeyTest {
    private fun base() = NativeBmwDspValues.DEFAULTS.copyOf()

    private fun withLowSlopes(type: Float) = base().also {
        for (output in 0 until 2) {
            it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] = type
        }
    }

    @Test
    fun aLeftRightSlopeChangeThatTheHashMissesIsANewKey() {
        val lr4 = withLowSlopes(NativeBmwDspValues.CROSSOVER_TYPE_LR4)
        val bw2 = withLowSlopes(NativeBmwDspValues.CROSSOVER_TYPE_BW2)
        // The bug: the graphs keyed their recompute on this, and it doesn't change.
        assertEquals(lr4.contentHashCode(), bw2.contentHashCode())
        assertNotEquals(ValuesKey(lr4), ValuesKey(bw2))
    }

    @Test
    fun sameContentsAreTheSameKeyAndInPlaceEditsAreSeen() {
        val values = base()
        val before = ValuesKey(values)
        assertEquals(before, ValuesKey(values.copyOf()))
        values[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] = 123f  // mutated in place
        assertNotEquals(before, ValuesKey(values))
    }
}
