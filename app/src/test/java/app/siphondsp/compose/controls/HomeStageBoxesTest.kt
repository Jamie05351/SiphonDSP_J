package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStageBoxesTest {

    @Test
    fun frequenciesReadInHzBelowOneKilohertzAndKhzAbove() {
        assertEquals("80 Hz", formatHz(80f))
        assertEquals("999 Hz", formatHz(999.4f))
        assertEquals("1 kHz", formatHz(1000f))
        assertEquals("1.2 kHz", formatHz(1240f))
        assertEquals("16 kHz", formatHz(16000f))
    }
}
