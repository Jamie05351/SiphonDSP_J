package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeqStateHistoryTest {
    private fun state(gain: Float) = BmwPeqState.empty().copy(preampDb = gain)

    @Test
    fun undoRedoAdvanceOnlyAfterConfirmation() {
        val history = PeqStateHistory()
        history.reset(state(0f))
        history.commit(state(-1f))

        assertEquals(0f, history.peekUndo()!!.preampDb)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(0f, history.peekUndo()!!.preampDb)

        history.confirmUndo()
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(-1f, history.peekRedo()!!.preampDb)
    }

    @Test
    fun newCommitClearsRedoAndCapacityIsBounded() {
        val history = PeqStateHistory(capacity = 3)
        history.reset(state(0f))
        history.commit(state(-1f))
        history.commit(state(-2f))
        history.confirmUndo()
        history.commit(state(-3f))

        assertFalse(history.canRedo)
        history.commit(state(-4f))
        assertEquals(3, history.size)
    }
}
