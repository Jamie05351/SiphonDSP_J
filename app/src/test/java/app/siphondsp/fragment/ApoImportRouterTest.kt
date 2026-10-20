package app.siphondsp.fragment

import app.siphondsp.model.ParametricEqChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class ApoImportRouterTest {

    private fun route(name: String) = ApoImportRouter.routeApoFileName(name)

    @Test
    fun inputCorrectionKeywordsRouteToFullAsLeftRight() {
        for (name in listOf("input.txt", "Correction.txt", "my full range.txt", "INPUT_left.txt")) {
            assertEquals(
                "routing $name",
                PeqScope.FULL to ParametricEqChannel.LEFT_RIGHT,
                route(name),
            )
        }
    }

    @Test
    fun lowAndMidPickUpTheSideTokenWhenPresent() {
        assertEquals(PeqScope.LOW to ParametricEqChannel.LEFT, route("low_left.txt"))
        assertEquals(PeqScope.LOW to ParametricEqChannel.RIGHT, route("low-r.txt"))
        assertEquals(PeqScope.MID to ParametricEqChannel.RIGHT, route("mid_right.txt"))
        assertEquals(PeqScope.MID to ParametricEqChannel.LEFT, route("mid l.txt"))
    }

    @Test
    fun lowAndMidWithoutASideTokenDefaultToLeftRight() {
        assertEquals(PeqScope.LOW to ParametricEqChannel.LEFT_RIGHT, route("low.txt"))
        assertEquals(PeqScope.MID to ParametricEqChannel.LEFT_RIGHT, route("midband.txt"))
    }

    @Test
    fun highAndTweeterRouteToHighWithTheSideToken() {
        assertEquals(PeqScope.HIGH to ParametricEqChannel.LEFT, route("high_left.txt"))
        assertEquals(PeqScope.HIGH to ParametricEqChannel.RIGHT, route("Tweeter R.txt"))
        assertEquals(PeqScope.HIGH to ParametricEqChannel.LEFT_RIGHT, route("tweeter.txt"))
    }

    @Test
    fun unrecognisedNamesRouteNowhere() {
        assertNull(route("random export.txt"))
        assertNull(route("speaker7.txt"))
    }

    @Test
    fun inputWinsOverASideTokenAndOverLowMid() {
        // "input" is checked before "low"/"mid", and Full is always L+R.
        assertEquals(PeqScope.FULL to ParametricEqChannel.LEFT_RIGHT, route("input_low_left.txt"))
    }

    @Test
    fun readImportTextReturnsTheWholeStream() {
        val text = "GraphicEQ: 20 -1; 20000 -1"
        val read = ApoImportRouter.readImportText(ByteArrayInputStream(text.toByteArray()))
        assertEquals(text, read)
    }

    @Test
    fun readImportTextRejectsAnythingOverTheSizeCeiling() {
        val tooBig = "x".repeat(ApoImportRouter.MAX_IMPORT_CHARS + 1).toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            ApoImportRouter.readImportText(ByteArrayInputStream(tooBig))
        }
    }

    @Test
    fun preampSummaryShowsTheAppliedValueAndAnyLimit() {
        assertEquals("Preamp -6.5 dB", ApoImportRouter.preampSummary(-6.5, -6.5))
        assertEquals(
            "Preamp +12.0 dB (file says +14.5 dB, limited to +12.0 dB)",
            ApoImportRouter.preampSummary(12.0, 14.5),
        )
        assertEquals(
            "Preamp -30.0 dB (file says -42.0 dB, limited to -30.0 dB)",
            ApoImportRouter.preampSummary(-30.0, -42.0),
        )
        assertNull(ApoImportRouter.preampSummary(0.0, null))
    }

    @Test
    fun formatDbSignsAndRoundsToOneDecimal() {
        assertEquals("+3.0 dB", ApoImportRouter.formatDb(3.0))
        assertEquals("-0.4 dB", ApoImportRouter.formatDb(-0.44))
        assertEquals("0.0 dB", ApoImportRouter.formatDb(-0.01))
        assertEquals("0.0 dB", ApoImportRouter.formatDb(0.0))
    }
}
