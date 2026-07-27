package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivatePeqBackupTest {
    @Test
    fun completeBackupRoundTripsStateAndGraphPreferences() {
        val state = BmwPeqState.empty().copy(
            enabled = true,
            preampDb = -2f,
            midBandBands = ParametricEqBandList().apply {
                add(ParametricEqBand(1000.0, -6.0, 1.0))
            },
        )
        val backup = PrivatePeqBackup(
            createdAtEpochMs = 1234L,
            state = BmwPeqPreset.fromState(state),
            graphDisplay = PrivatePeqBackup.GraphDisplay(false, "RIGHT"),
        )

        val decoded = PrivatePeqBackup.decode(PrivatePeqBackup.encode(backup))

        assertEquals(-2f, decoded.validatedState().preampDb)
        assertEquals(1, decoded.validatedState().midBandBands.size)
        assertEquals(false, decoded.graphDisplay.showIndividualFilters)
        assertEquals("RIGHT", decoded.graphDisplay.channelDisplay)
    }

    @Test
    fun rejectsFutureVersionAndInvalidGraphMode() {
        val valid = PrivatePeqBackup(
            createdAtEpochMs = 0,
            state = BmwPeqPreset.fromState(BmwPeqState.empty()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(version = PrivatePeqBackup.CURRENT_VERSION + 1).validatedState()
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(graphDisplay = PrivatePeqBackup.GraphDisplay(channelDisplay = "SIDEWAYS"))
                .validatedState()
        }
    }
}
