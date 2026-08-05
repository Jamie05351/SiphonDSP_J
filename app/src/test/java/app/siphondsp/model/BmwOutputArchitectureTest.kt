package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class BmwOutputArchitectureTest {
    @Test fun defaultRoutingHasNoCrossfeedAndReconstructsStereo() {
        val routed = BmwRouting.route(.25f, -.5f)
        assertEquals(listOf(.25f, -.5f, .25f, -.5f), routed.toList())
        assertEquals(.5f to -1f, BmwRouting.reconstruct(routed))
        assertEquals(listOf(0f, 1f, 0f, 1f), BmwRouting.route(0f, 1f).toList())
    }

    @Test fun disabledAllPassIsIdentity() {
        assertTrue(BmwAllPassSection().coefficients(48_000f)!!.contentEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)))
    }

    @Test fun allPassMagnitudeIsUnityAndPhaseChanges() {
        for (type in BmwAllPassType.entries) {
            val c = BmwAllPassSection(true, type, 1_000f, .707f).coefficients(48_000f)!!
            val w = 2.0 * PI * 1_000.0 / 48_000.0
            val nr = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
            val ni = -(c[1] * sin(w) + c[2] * sin(2 * w))
            val dr = 1.0 + c[3] * cos(w) + c[4] * cos(2 * w)
            val di = -(c[3] * sin(w) + c[4] * sin(2 * w))
            assertEquals(1.0, hypot(nr, ni) / hypot(dr, di), 1e-6)
            assertNotEquals(0.0, atan2(ni, nr) - atan2(di, dr), 1e-3)
        }
    }

    @Test fun invalidFrequencyAndQAreRejected() {
        assertNull(BmwAllPassSection(true, frequencyHz = 24_000f).coefficients(48_000f))
        assertNull(BmwAllPassSection(true, frequencyHz = 1_000f, q = 0f).coefficients(48_000f))
        assertFalse(BmwAllPassSection(true, frequencyHz = Float.NaN).isValid(48_000f))
    }
}
