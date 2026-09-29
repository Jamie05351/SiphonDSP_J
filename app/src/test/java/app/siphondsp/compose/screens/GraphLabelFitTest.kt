package app.siphondsp.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLabelFitTest {
    @Test
    fun roomyAxisKeepsFullSize() {
        assertEquals(1f, graphLabelFitScale(minSpacingPx = 60f, widestLabelPx = 30f, gapPx = 3f), 0f)
    }

    @Test
    fun narrowAxisShrinksToFit() {
        val scale = graphLabelFitScale(minSpacingPx = 27f, widestLabelPx = 30f, gapPx = 3f)
        assertEquals(0.8f, scale, 1e-4f)
        assertTrue(30f * scale <= 27f - 3f + 1e-3f)
    }

    @Test
    fun neverShrinksBelowTheFloor() {
        assertEquals(0.68f, graphLabelFitScale(minSpacingPx = 5f, widestLabelPx = 30f, gapPx = 3f), 0f)
    }

    @Test
    fun degenerateInputsAreIgnored() {
        assertEquals(1f, graphLabelFitScale(0f, 30f, 3f), 0f)
        assertEquals(1f, graphLabelFitScale(20f, 0f, 3f), 0f)
    }

    @Test
    fun minSpacingFindsTheClosestPair() {
        assertEquals(4f, minNeighbourSpacing(floatArrayOf(50f, 10f, 30f, 34f)), 0f)
        assertEquals(0f, minNeighbourSpacing(floatArrayOf(10f)), 0f)
    }
}
