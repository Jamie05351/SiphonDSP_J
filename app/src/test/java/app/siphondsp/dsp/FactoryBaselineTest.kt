package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow

/** FactoryBaseline parsing / interpolation, and the real (app x factory) response built on it. */
class FactoryBaselineTest {

    private fun driverJson(
        side: String,
        driver: String,
        db: Double = 0.0,
        arrivalMs: Double = 0.0,
        minHz: Double = 20.0,
        maxHz: Double = 20_000.0,
    ) = """{"side":"$side","driver":"$driver","arrivalMs":$arrivalMs,"minHz":$minHz,"maxHz":$maxHz,
        "freqHz":[20,1000,20000],"magDb":[$db,$db,$db],"phaseDeg":[0,0,0]}"""

    private fun baselineJson(vararg drivers: String, seatSums: List<String> = emptyList()) =
        """{"format":"siphondsp-factory-baseline","version":1,"source":"test","seat":"DRV",
        "drivers":[${drivers.joinToString(",")}],"seatSums":[${seatSums.joinToString(",")}]}"""

    private fun seatJson(side: String, db: Double) =
        """{"side":"$side","arrivalMs":0,"minHz":20,"maxHz":20000,"freqHz":[20,20000],"magDb":[$db,$db],"phaseDeg":[0,0]}"""

    /** Response curves for an app that passes everything through untouched (0 dB, 0 rad). */
    private fun unityCurves(points: Int = 64): BmwResponseCurves = BmwResponseCurves(points).apply {
        for (i in 0 until points) frequencies[i] = 20.0 * 1000.0.pow(i.toDouble() / (points - 1))
    }

    @Test
    fun parsesDriversAndFindsThemBySideAndDriver() {
        val baseline = FactoryBaseline.parse(baselineJson(driverJson("LEFT", "WOOFER", db = -3.0)))
        assertEquals("test", baseline.source)
        val woofer = baseline.driver(BmwOutputChannel.LEFT, FactoryDriver.WOOFER)!!
        assertEquals(20.0, woofer.minHz, 0.0)
        assertNull(baseline.driver(BmwOutputChannel.RIGHT, FactoryDriver.WOOFER))
        assertTrue(!baseline.hasSeatSums)
        val withSeat = FactoryBaseline.parse(baselineJson(driverJson("LEFT", "MID"), seatSums = listOf(seatJson("RIGHT", 0.0))))
        assertTrue(withSeat.hasSeatSums)
        assertEquals("DRV", withSeat.seat)
        assertNull(withSeat.seatSum(BmwOutputChannel.LEFT))
    }

    @Test
    fun theBuiltInBaselineParses() {
        val asset = java.io.File("src/main/assets/factory_baseline.json")
        if (!asset.isFile) return // built-in baseline is optional
        val baseline = FactoryBaseline.parse(asset.readText())
        for (side in BmwOutputChannel.entries) for (driver in FactoryDriver.entries) {
            assertTrue("$side $driver missing", baseline.driver(side, driver) != null)
        }
        assertTrue(baseline.hasSeatSums)
    }

    @Test
    fun rejectsWrongFormatVersionAndDuplicates() {
        assertThrows(IllegalArgumentException::class.java) { FactoryBaseline.parse("""{"format":"other","version":1,"drivers":[]}""") }
        assertThrows(IllegalArgumentException::class.java) {
            FactoryBaseline.parse("""{"format":"siphondsp-factory-baseline","version":2,"drivers":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FactoryBaseline.parse(baselineJson(driverJson("LEFT", "MID"), driverJson("LEFT", "MID")))
        }
        assertThrows(IllegalArgumentException::class.java) { FactoryBaseline.parse(baselineJson(driverJson("CENTRE", "MID"))) }
        assertThrows(IllegalArgumentException::class.java) { FactoryBaseline.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            FactoryBaseline.parse(baselineJson(seatSums = listOf(seatJson("LEFT", 0.0), seatJson("LEFT", 1.0))))
        }
    }

    @Test
    fun interpolatesInLogFrequencyAndReappliesTheArrival() {
        val json = baselineJson(
            """{"side":"LEFT","driver":"MID","arrivalMs":1.0,"minHz":100,"maxHz":1000,
            "freqHz":[100,1000],"magDb":[0,-10],"phaseDeg":[0,-90]}""",
        )
        val mid = FactoryBaseline.parse(json).driver(BmwOutputChannel.LEFT, FactoryDriver.MID)!!
        val out = ComplexAcc()
        // sqrt(100 * 1000) is halfway in log frequency: -5 dB, -45 deg, then 1 ms of arrival.
        val f = 316.22776601683796
        assertTrue(mid.evaluate(f, out))
        assertEquals(-5.0, out.magnitudeDb(), 1e-9)
        val expected = -PI / 4 - 2.0 * PI * f * 0.001
        val wrapped = Math.IEEEremainder(expected, 2.0 * PI)
        assertEquals(wrapped, out.phase(), 1e-9)
        assertTrue(!mid.evaluate(50.0, out))
    }

    @Test
    fun unityAppShowsTheFactoryResponseAndNothingOutsideTheMeasuredBand() {
        val baseline = FactoryBaseline.parse(
            baselineJson(driverJson("LEFT", "WOOFER", db = -6.0, maxHz = 400.0)),
        )
        val curves = unityCurves()
        val real = FactoryAcousticCurves(curves.pointCount)
        real.compute(curves, baseline)
        val woofer = real.driverDbFor(BmwOutputChannel.LEFT, FactoryDriver.WOOFER)
        for (i in 0 until curves.pointCount) {
            if (curves.frequencies[i] <= 400.0) assertEquals(-6.0, woofer[i], 1e-9)
            else assertTrue(woofer[i].isNaN())
        }
        assertTrue(real.driverDbFor(BmwOutputChannel.LEFT, FactoryDriver.MID).all { it.isNaN() })
        assertTrue(real.sumDbFor(BmwOutputChannel.RIGHT).all { it.isNaN() })
    }

    @Test
    fun appGainMultipliesEveryDriverAndTheSeatSweepIsTheSum() {
        val baseline = FactoryBaseline.parse(
            baselineJson(driverJson("RIGHT", "MID"), driverJson("RIGHT", "TWEETER"), seatSums = listOf(seatJson("RIGHT", -2.0))),
        )
        val curves = unityCurves().apply { sumDb[BmwOutputChannel.RIGHT.ordinal].fill(3.0) }
        val real = FactoryAcousticCurves(curves.pointCount)
        real.compute(curves, baseline)
        val mid = real.driverDbFor(BmwOutputChannel.RIGHT, FactoryDriver.MID)
        val sum = real.sumDbFor(BmwOutputChannel.RIGHT)
        for (i in 0 until curves.pointCount) {
            assertEquals(3.0, mid[i], 1e-9)
            // The seat sweep, not the near-field drivers summed (which would read 3 + 6.02).
            assertEquals(1.0, sum[i], 1e-9)
        }
        assertTrue(real.sumDbFor(BmwOutputChannel.LEFT).all { it.isNaN() })
    }

    @Test
    fun longArrivalsReadAsGroupDelayAcrossTheWholeBand() {
        // 11 ms turns the phase far more than pi between neighbouring points of a 192-point log
        // axis above a few hundred Hz; wrapped-phase differencing aliased there.
        val baseline = FactoryBaseline.parse(
            baselineJson(driverJson("LEFT", "MID", arrivalMs = 11.0), seatSums = listOf(seatJson("LEFT", 0.0))),
        )
        val curves = unityCurves(points = 192)
        val real = FactoryAcousticCurves(curves.pointCount)
        real.compute(curves, baseline)
        val driver = real.driverGroupDelayMsFor(BmwOutputChannel.LEFT, FactoryDriver.MID)
        val seat = real.groupDelayMsFor(BmwOutputChannel.LEFT)
        for (i in 0 until curves.pointCount) {
            assertEquals("driver at ${curves.frequencies[i]} Hz", 11.0, driver[i], 1e-9)
            assertEquals("seat at ${curves.frequencies[i]} Hz", 0.0, seat[i], 1e-9)
        }
    }

    @Test
    fun groupDelayFollowsTheStoredPhaseSlope() {
        // -90 deg per decade of log-frequency: tau = -dphi/domega = 0.25 / (ln(10) * f) seconds.
        val json = baselineJson(
            """{"side":"RIGHT","driver":"TWEETER","arrivalMs":2.0,"minHz":1000,"maxHz":10000,
            "freqHz":[1000,10000],"magDb":[0,0],"phaseDeg":[0,-90]}""",
        )
        val tweeter = FactoryBaseline.parse(json).driver(BmwOutputChannel.RIGHT, FactoryDriver.TWEETER)!!
        for (f in doubleArrayOf(1000.0, 3162.0, 10000.0)) {
            assertEquals(2.0 + 0.25 / (kotlin.math.ln(10.0) * f) * 1000.0, tweeter.groupDelayMs(f), 1e-9)
        }
        assertTrue(tweeter.groupDelayMs(500.0).isNaN())
    }
    @Test
    fun displayPhaseTakesTheArrivalBackOut() {
        // Flat stored phase, 1.2 ms tweeter / 0 ms mid / 0.4 ms seat: raw phase spins, display phase
        // must not. Shape = each curve's own arrival out; timed = the side's earliest (the mid's).
        val baseline = FactoryBaseline.parse(
            baselineJson(
                driverJson("RIGHT", "MID", arrivalMs = 0.0),
                driverJson("RIGHT", "TWEETER", arrivalMs = 1.2),
                seatSums = listOf(seatJson("RIGHT", 0.0).replace("\"arrivalMs\":0", "\"arrivalMs\":0.4")),
            ),
        )
        val curves = unityCurves(points = 192)
        val real = FactoryAcousticCurves(curves.pointCount)
        real.compute(curves, baseline)
        val r = BmwOutputChannel.RIGHT
        val shape = real.driverPhaseShapeFor(r, FactoryDriver.TWEETER)
        val timed = real.driverPhaseTimedFor(r, FactoryDriver.TWEETER)
        val raw = real.driverPhaseFor(r, FactoryDriver.TWEETER)
        val sum = real.sumPhaseShapeFor(r)
        var rawMoves = false
        for (i in 0 until curves.pointCount) {
            val f = curves.frequencies[i]
            assertEquals("tweeter shape at $f Hz", 0.0, shape[i], 1e-9)
            assertEquals("seat sum shape at $f Hz", 0.0, sum[i], 1e-9)
            // Timed keeps the 1.2 ms against the mid, so it still turns -- exactly by that delay.
            assertEquals("tweeter timed at $f Hz", 0.0, Math.IEEEremainder(timed[i] + 2.0 * PI * f * 0.0012, 2.0 * PI), 1e-9)
            if (kotlin.math.abs(raw[i]) > 0.1) rawMoves = true
        }
        assertTrue(rawMoves)
        assertTrue(real.driverPhaseShapeFor(BmwOutputChannel.LEFT, FactoryDriver.MID).all { it.isNaN() })
    }
}
