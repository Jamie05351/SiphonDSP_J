package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParametricEqBandListApoTest {

    private fun parse(text: String) = ParametricEqBandList().fromApoString(text)

    @Test
    fun anInRangePreampIsKeptAsWritten() {
        val result = parse("Preamp: -6.5 dB\nFilter 1: ON PK Fc 100 Hz Gain 3 dB Q 1")
        assertEquals(-6.5, result.preampDb, 0.0)
        assertEquals(-6.5, result.preampInFileDb!!, 0.0)
    }

    @Test
    fun anOutOfRangePreampIsLimitedButTheFileValueIsKept() {
        val high = parse("Preamp: +14.5 dB")
        assertEquals(12.0, high.preampDb, 0.0)
        assertEquals(14.5, high.preampInFileDb!!, 0.0)
        val low = parse("Preamp: -42 dB")
        assertEquals(-30.0, low.preampDb, 0.0)
        assertEquals(-42.0, low.preampInFileDb!!, 0.0)
    }

    @Test
    fun aMissingOrUnreadablePreampLeavesNoFileValue() {
        val missing = parse("Filter 1: ON PK Fc 100 Hz Gain 3 dB Q 1")
        assertEquals(0.0, missing.preampDb, 0.0)
        assertNull(missing.preampInFileDb)
        val malformed = parse("Preamp: loud")
        assertEquals(0.0, malformed.preampDb, 0.0)
        assertNull(malformed.preampInFileDb)
        assertEquals(1, malformed.skippedFilters)
    }
}
