package app.siphondsp.dsp

import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GainBudgetTest {

    private fun values(block: FloatArray.() -> Unit = {}) = NativeBmwDspValues.DEFAULTS.copyOf().apply {
        this[NativeBmwDspValues.INDEX_TILT_ENABLED] = 0f
        this[NativeBmwDspValues.INDEX_POST_GAIN_L] = 0f
        this[NativeBmwDspValues.INDEX_POST_GAIN_R] = 0f
        this[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED] = 0f
        this[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = 0f
        this[NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED] = 0f
        block()
    }

    @Test
    fun stepsRunInChainOrderAndAddUp() {
        val v = values {
            this[NativeBmwDspValues.INDEX_TILT_ENABLED] = 1f
            this[NativeBmwDspValues.INDEX_TILT_AMOUNT] = -2f
            this[NativeBmwDspValues.INDEX_POST_GAIN_L] = 1f
            this[NativeBmwDspValues.INDEX_POST_GAIN_R] = 0.5f
        }
        val b = GainBudget.compute(v, preampDb = -6.0, eqPeakDb = -1.5, headroomDb = -3.0)
        assertEquals(
            listOf(GainBudget.Kind.PREAMP, GainBudget.Kind.EQ, GainBudget.Kind.HEADROOM, GainBudget.Kind.TILT,
                GainBudget.Kind.POST_GAIN, GainBudget.Kind.VOLUME),
            b.steps.map { it.kind },
        )
        assertEquals(4.5, b.steps[1].db, 1e-9) // EQ adds what's above the preamp
        assertEquals(3.0, b.steps[3].db, 1e-9) // |−2| x 1.5 at the boosted end
        assertEquals(1.0, b.steps[4].db, 1e-9) // the louder post gain
        assertEquals(-6.0 + 4.5 - 3.0 + 3.0 + 1.0, b.intoLimiterDb, 1e-9)
        assertEquals(b.intoLimiterDb, b.outputDb, 1e-9)
        assertTrue(b.clean) // −0.5 dBFS
        assertFalse(GainBudget.compute(v, preampDb = -6.0, eqPeakDb = -1.5, headroomDb = -2.0).clean) // +0.5
        b.steps.zipWithNext { a, c -> assertEquals(a.endDb, c.startDb, 1e-9) }
    }

    @Test
    fun theLimiterHoldsTheLevelAtItsCeiling() {
        val v = values {
            this[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED] = 1f
            this[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD] = -1f
        }
        val b = GainBudget.compute(v, preampDb = 0.0, eqPeakDb = 4.0, headroomDb = 0.0)
        assertTrue(b.limiterCatches)
        assertEquals(-5.0, b.steps.single { it.kind == GainBudget.Kind.LIMITER }.db, 1e-9)
        assertEquals(-1.0, b.outputDb, 1e-9)
        assertTrue(b.clean)
        // Under the ceiling it does nothing.
        val quiet = GainBudget.compute(v, preampDb = 0.0, eqPeakDb = -3.0, headroomDb = 0.0)
        assertFalse(quiet.limiterCatches)
        assertEquals(0.0, quiet.steps.single { it.kind == GainBudget.Kind.LIMITER }.db, 0.0)
    }

    @Test
    fun volumeAndDeepBassNeverAddLevel() {
        val v = values {
            this[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = -18f
            this[NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED] = 1f
            this[NativeBmwDspValues.INDEX_DEEP_BASS_STRENGTH] = 100f
            this[NativeBmwDspValues.INDEX_DEEP_BASS_MAX_DB] = 15f
        }
        val b = GainBudget.compute(v, preampDb = 0.0, eqPeakDb = 0.0, headroomDb = 0.0)
        val boost = NativeBmwDspValues.deepBassCurve(v).gainDb
        assertTrue(boost > 0)
        assertEquals(-18.0 + boost, b.steps.last().db, 1e-9)
        assertTrue(b.steps.last().db < 0)
        assertNull(b.limiterCeilingDb)
    }
}
