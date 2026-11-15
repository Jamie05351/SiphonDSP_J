package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class HouseCurveTest {

    private fun parse(text: String) = HouseCurveParser.parse(text.reader().buffered())

    private fun axis(count: Int = 192) = DoubleArray(count) { 20.0 * 1000.0.pow(it.toDouble() / (count - 1)) }

    private val sample = """
        * REW house curve
        # a comment
        Freq(Hz) SPL(dB)
        20	6.0
        100, 3
        1000 0
        10000,-3
        20000	-4,5
    """.trimIndent()

    @Test
    fun readsCommaSpaceAndTabSeparatedPointsSkippingCommentsAndHeaders() {
        val h = parse(sample)
        assertEquals(listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0), h.frequencies.toList())
        assertEquals(listOf(6.0, 3.0, 0.0, -3.0, -4.5), h.db.toList())
    }

    @Test
    fun sortsAndKeepsTheFirstOfARepeatedFrequency() {
        val h = parse("1000 0\n100 3\n100 9\n10000 -3")
        assertEquals(listOf(100.0, 1000.0, 10000.0), h.frequencies.toList())
        assertEquals(3.0, h.db[0], 0.0)
    }

    @Test
    fun interpolatesInLogFrequencyAndNeverExtrapolates() {
        val h = parse(sample)
        assertEquals(1.5, h.at(sqrt(100.0 * 1000.0)), 1e-9)  // halfway in log between 3 and 0 dB
        assertEquals(0.0, h.at(1000.0), 0.0)
        assertTrue(h.at(10.0).isNaN())
        assertTrue(h.at(25_000.0).isNaN())
        val grid = h.onGrid(axis())
        assertEquals(6.0, grid.first(), 1e-9)
    }

    @Test
    fun rejectsFilesWithoutAUsableCurve() {
        assertThrows(IllegalArgumentException::class.java) { parse("") }
        assertThrows(IllegalArgumentException::class.java) { parse("Freq SPL\nfoo bar") }
        assertThrows(IllegalArgumentException::class.java) { parse("1000 0") }
        // The alignment band has to be covered.
        assertThrows(IllegalArgumentException::class.java) { parse("300 0\n4000 1") }
    }

    @Test
    fun alignmentMovesTheHouseMeanOntoTheDisplayedMeanOver200HzTo5kHz() {
        val a = axis()
        val house = parse(sample).onGrid(a)
        val shown = DoubleArray(a.size) { house[it] + 7.0 }
        assertEquals(7.0, HouseCurve.alignmentOffset(house, listOf(shown), a), 1e-9)
        // A displayed curve that differs only outside 200 Hz - 5 kHz doesn't move the offset.
        val wild = DoubleArray(a.size) { if (a[it] < 200.0 || a[it] > 5_000.0) 40.0 else shown[it] }
        assertEquals(7.0, HouseCurve.alignmentOffset(house, listOf(wild), a), 1e-9)
    }

    @Test
    fun withBothChannelsShownOneOffsetTargetsTheirJointMean() {
        val a = axis()
        val house = parse(sample).onGrid(a)
        val left = DoubleArray(a.size) { house[it] + 7.0 }
        val right = DoubleArray(a.size) { house[it] + 3.0 }
        assertEquals(5.0, HouseCurve.alignmentOffset(house, listOf(left, right), a), 1e-9)
    }

    @Test
    fun noUsablePointGivesNoOffset() {
        val a = axis()
        val house = DoubleArray(a.size) { Double.NaN }
        assertEquals(0.0, HouseCurve.alignmentOffset(house, listOf(DoubleArray(a.size)), a), 0.0)
    }
}
