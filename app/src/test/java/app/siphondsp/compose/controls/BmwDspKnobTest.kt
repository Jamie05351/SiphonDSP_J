package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun interactiveMappingIgnoresPointsTooCloseToCenter() {
        assertNull(dspKnobFractionForInteractivePoint(50f, 50f, 100f, 100f))
        assertNull(dspKnobFractionForInteractivePoint(61f, 50f, 100f, 100f))
        assertEquals(0.83f, dspKnobFractionForInteractivePoint(63f, 50f, 100f, 100f)!!, 0.01f)
    }

    @Test
    fun accessibilityProgressUsesTheSameStepSnappingAsTouch() {
        assertEquals(2.9f, snapToStep(2.86f, 1f..4f, 0.1f), 0.001f)
        assertEquals(1f, snapToStep(-5f, 1f..4f, 0.1f), 0.001f)
        assertEquals(4f, snapToStep(8f, 1f..4f, 0.1f), 0.001f)
    }
}
