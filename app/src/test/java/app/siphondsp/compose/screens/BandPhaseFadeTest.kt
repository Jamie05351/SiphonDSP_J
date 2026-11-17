package app.siphondsp.compose.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandPhaseFadeTest {
    @Test fun fullWithin15dBOfThePassbandHiddenFrom20AndLinearBetween() {
        assertEquals(1f, BandPhaseFade.strength(-3.0, 0.0))
        assertEquals(1f, BandPhaseFade.strength(-15.0, 0.0))
        assertEquals(0.5f, BandPhaseFade.strength(-17.5, 0.0), 1e-6f)
        assertEquals(0f, BandPhaseFade.strength(-20.0, 0.0))
        assertEquals(0f, BandPhaseFade.strength(-60.0, 0.0))
        assertEquals(0f, BandPhaseFade.strength(Double.NaN, 0.0))
    }

    @Test fun thePassbandIsTheBandsOwnPeakNotZeroDb() {
        // A band trimmed to -6 dB is still drawn 15 dB under its own level.
        assertEquals(-6.0, BandPhaseFade.passbandDb(doubleArrayOf(-30.0, -6.0, -8.0))!!, 0.0)
        assertEquals(1f, BandPhaseFade.strength(-21.0, -6.0))
        assertEquals(0f, BandPhaseFade.strength(-26.0, -6.0))
    }

    @Test fun aSilentBandDrawsNothing() {
        assertNull(BandPhaseFade.passbandDb(DoubleArray(5) { -120.0 }))
        assertArrayEquals(FloatArray(4), BandPhaseFade.segmentStrengths(DoubleArray(5) { -120.0 }), 0f)
    }

    @Test fun aSegmentTakesItsWeakerEndAndIsQuantisedUp() {
        // A low-pass: flat, then rolling off through the fade, then gone.
        val db = doubleArrayOf(0.0, 0.0, -14.0, -16.0, -19.0, -25.0, -40.0)
        val s = BandPhaseFade.segmentStrengths(db, levels = 8)
        assertEquals(6, s.size)
        assertEquals(1f, s[0])           // 0 -> 0
        assertEquals(1f, s[1])           // 0 -> -14
        assertEquals(0.875f, s[2])       // -14 -> -16: 0.8 at -16 -> ceil(6.4) / 8
        assertEquals(0.25f, s[3])        // -16 -> -19: 0.2 -> ceil(1.6)/8
        assertEquals(0f, s[4])           // -19 -> -25: the far end is hidden
        assertEquals(0f, s[5])
        s.forEach { assertTrue(it in 0f..1f) }
    }
}
