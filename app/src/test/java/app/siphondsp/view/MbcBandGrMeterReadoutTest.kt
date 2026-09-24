package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MbcBandGrMeterReadoutTest {

    private fun formatted(db: Float) = String.format(Locale.ROOT, "%.1f", db)
    private fun cached(db: Float) = String.format(Locale.ROOT, "%.1f", readoutTenths(db) / 10.0)

    @Test
    fun reviewCasesShowWhatTheFormatterShows() {
        for (db in floatArrayOf(1.15f, 0.35f, 9.95f, 1.25f, 0.05f, 0f, 18f)) {
            assertEquals("db=$db", formatted(db), cached(db))
        }
    }

    @Test
    fun everyHalfTenthBoundaryAndItsNeighboursMatchTheFormatter() {
        // Meter range is 0..18 dB (Stage.COMPRESSOR_BAND full scale). Check each x.x5 boundary
        // as a float plus the floats either side of it.
        for (m in 0..360) {
            val boundary = m / 20f
            for (db in floatArrayOf(Math.nextDown(boundary), boundary, Math.nextUp(boundary))) {
                if (db < 0f) continue
                assertEquals("db=$db", formatted(db), cached(db))
            }
        }
    }

    @Test
    fun denseSweepMatchesTheFormatter() {
        var db = 0f
        while (db <= 18f) {
            assertEquals("db=$db", formatted(db), cached(db))
            db += 0.0007f
        }
    }
}
