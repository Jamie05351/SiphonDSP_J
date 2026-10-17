package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

class CarBaselineTest {

    private fun parse(text: String) = CarBaselineParser.parse(text.reader().buffered())

    /** A log sweep 20 Hz - 20 kHz, [perDecade] points a decade, level from [db]. */
    private fun sweep(perDecade: Int = 96, db: (Double) -> Double): CarBaselineMeasurement {
        val count = 3 * perDecade + 1
        val f = DoubleArray(count) { 20.0 * 10.0.pow(it.toDouble() / perDecade) }
        return CarBaselineMeasurement(f, DoubleArray(count) { db(f[it]) })
    }

    private fun axis(count: Int = 64) = DoubleArray(count) { 20.0 * 1000.0.pow(it.toDouble() / (count - 1)) }

    @Test
    fun `parses a REW text export, skipping comments and headers`() {
        val text = buildString {
            appendLine("* Measurement data measured by REW V5.31")
            appendLine("* Source: Left, ...")
            appendLine("* Format: 1/48 octave smoothing")
            appendLine("* Freq(Hz) SPL(dB) Phase(degrees)")
            for (i in 0 until 20) appendLine("${20.0 * 2.0.pow(i / 2.0)} ${80.0 + i} ${-10.0 * i}")
        }
        val m = parse(text)
        assertEquals(20, m.frequencies.size)
        assertEquals(20.0, m.frequencies.first(), 1e-9)
        assertEquals(80.0, m.db.first(), 1e-9)
        assertEquals(99.0, m.db.last(), 1e-9)
    }

    @Test
    fun `parses comma and semicolon separated exports`() {
        val comma = (0 until 12).joinToString("\n") { "${100 * (it + 1)}.0, ${70 + it}.5" }
        assertEquals(70.5, parse(comma).db.first(), 1e-9)
        val semicolon = "Freq;SPL\n" + (0 until 12).joinToString("\n") { "${100 * (it + 1)},0;${70 + it},5" }
        val m = parse(semicolon)
        assertEquals(100.0, m.frequencies.first(), 1e-9)
        assertEquals(70.5, m.db.first(), 1e-9)
    }

    @Test
    fun `reads a decimal comma in whitespace separated fields`() {
        assertEquals(listOf("20.5", "74.3", "-12.0"), CarBaselineParser.fields("20,5\t74,3\t-12,0"))
        assertEquals(listOf("20.5", "74.3"), CarBaselineParser.fields("20,5 74,3"))
        val tabbed = (0 until 12).joinToString("\n") { "${100 * (it + 1)},5\t${70 + it},3" }
        val m = parse(tabbed)
        assertEquals(100.5, m.frequencies.first(), 1e-9)
        assertEquals(70.3, m.db.first(), 1e-9)
    }

    @Test
    fun `comma and space separated fields stay separate`() {
        assertEquals(listOf("20.000", "75.30", "-12.5"), CarBaselineParser.fields("20.000, 75.30, -12.5"))
        assertEquals(listOf("20.000", "75.30"), CarBaselineParser.fields("20.000,75.30"))
        assertEquals(listOf("20.000", "75.30", "-12"), CarBaselineParser.fields("20.000 75.30,-12"))
    }

    @Test
    fun `sorts and drops duplicate frequencies`() {
        val m = parse("1000 3\n200 1\n500 2\n500 9\n2000 4\n3000 5\n4000 6\n5000 7\n6000 8")
        assertEquals(listOf(200.0, 500.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0, 6000.0), m.frequencies.toList())
        assertEquals(2.0, m.db[1], 1e-9)
    }

    @Test
    fun `rejects files that are not measurements`() {
        assertThrows(IllegalArgumentException::class.java) { parse("hello\nworld\n") }
        assertThrows(IllegalArgumentException::class.java) { parse("Preamp: -3 dB\nFilter 1: ON PK Fc 100 Hz Gain 3 dB Q 1") }
        // Only covers the top octave: can't be levelled against 200 Hz - 2 kHz.
        assertThrows(IllegalArgumentException::class.java) {
            parse((0 until 10).joinToString("\n") { "${10_000 + it * 1000} 80" })
        }
    }

    @Test
    fun `levels to 0 dB over the midrange and keeps the shape`() {
        // +6 dB bass shelf below 100 Hz on an 85 dB SPL measurement.
        val m = sweep { f -> 85.0 + if (f < 100.0) 6.0 else 0.0 }
        val a = axis()
        val offsets = CarBaseline(m, null).offsetsFor(BmwOutputChannel.LEFT.ordinal, a)
        for (i in a.indices) {
            val expected = if (a[i] < 90.0) 6.0 else if (a[i] > 110.0) 0.0 else continue
            assertEquals("at ${a[i]} Hz", expected, offsets[i], 1e-6)
        }
    }

    @Test
    fun `one shared reference keeps a left right level difference`() {
        val left = sweep { 80.0 }
        val right = sweep { 77.0 }
        val baseline = CarBaseline(left, right)
        val a = axis()
        val l = baseline.offsetsFor(BmwOutputChannel.LEFT.ordinal, a)
        val r = baseline.offsetsFor(BmwOutputChannel.RIGHT.ordinal, a)
        for (i in a.indices) {
            assertEquals(1.5, l[i], 1e-6)
            assertEquals(-1.5, r[i], 1e-6)
        }
    }

    @Test
    fun `a missing channel uses the other`() {
        val baseline = CarBaseline(null, sweep { f -> 10.0 * log10(f) })
        val a = axis()
        val l = baseline.offsetsFor(BmwOutputChannel.LEFT.ordinal, a)
        val r = baseline.offsetsFor(BmwOutputChannel.RIGHT.ordinal, a)
        for (i in a.indices) assertEquals(r[i], l[i], 0.0)
    }

    @Test
    fun `sparse measurements interpolate and hold their ends`() {
        val m = CarBaselineMeasurement(
            doubleArrayOf(100.0, 1_000.0, 10_000.0),
            doubleArrayOf(0.0, 10.0, 0.0),
        )
        val a = doubleArrayOf(20.0, 100.0, 316.227766, 1_000.0, 20_000.0)
        val out = CarBaseline.resample(m, a)
        assertEquals(0.0, out[0], 1e-9)   // held below the first point
        assertEquals(0.0, out[1], 1e-9)
        assertEquals(5.0, out[2], 1e-6)   // halfway in log frequency
        assertEquals(10.0, out[3], 1e-9)
        assertEquals(0.0, out[4], 1e-9)   // held above the last point
    }

    @Test
    fun `dense measurements are averaged per graph band`() {
        // Alternating +-3 dB ripple on every measured point averages out on a coarse axis.
        val m = sweep(perDecade = 960) { _ -> 0.0 }
        for (i in m.db.indices) m.db[i] = if (i % 2 == 0) 3.0 else -3.0
        val out = CarBaseline.resample(m, axis(32))
        for (i in 1 until out.size - 1) assertEquals(0.0, out[i], 0.6)
    }

    @Test
    fun `applyTo adds the baseline to every magnitude curve`() {
        val calculator = BmwResponseCalculator(pointCount = 64)
        val curves = BmwResponseCurves(64)
        calculator.configureAxis(48_000.0, 20.0, 20_000.0)
        calculator.compute(FloatArray(BmwSignalChain.VALUE_COUNT), app.siphondsp.model.BmwPeqState.empty(), curves)
        val before = arrayOf(curves.sumDb, curves.preSplitDb, curves.lowBranchDb, curves.midBranchDb, curves.highBranchDb)
            .map { pair -> Array(2) { pair[it].copyOf() } }
        val phaseBefore = curves.sumPhase[0].copyOf()

        val baseline = CarBaseline(sweep { f -> 85.0 + if (f < 100.0) 6.0 else 0.0 }, sweep { 82.0 })
        baseline.applyTo(curves)

        val after = arrayOf(curves.sumDb, curves.preSplitDb, curves.lowBranchDb, curves.midBranchDb, curves.highBranchDb)
        for ((k, arr) in after.withIndex()) {
            for (ch in 0..1) {
                val offset = baseline.offsetsFor(ch, curves.frequencies)
                for (i in arr[ch].indices) {
                    val b = before[k][ch][i]
                    if (b.isFinite()) assertEquals(b + offset[i], arr[ch][i], 1e-9)
                }
            }
        }
        // Phase stays modelled.
        assertEquals(phaseBefore.toList(), curves.sumPhase[0].toList())
    }
}
