package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class BmwDspKnobTest {
    @Test
    fun arcMapsLowerLeftTopAndLowerRightToStartMiddleAndEnd() {
        assertEquals(0f, dspKnobFractionForPoint(0f, 100f, 100f, 100f), 0.01f)
        assertEquals(0.5f, dspKnobFractionForPoint(50f, 0f, 100f, 100f), 0.01f)
        assertEquals(1f, dspKnobFractionForPoint(100f, 100f, 100f, 100f), 0.01f)
    }

    @Test
    fun bottomDeadZoneClampsToNearestEndpoint() {
        assertEquals(0f, dspKnobFractionForPoint(35f, 100f, 100f, 100f), 0.01f)
        assertEquals(1f, dspKnobFractionForPoint(65f, 100f, 100f, 100f), 0.01f)
    }
}
