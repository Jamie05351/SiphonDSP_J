package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class BmwPeqPresetTest {
    @Test
    fun completeStateRoundTripsWithIdentityAndScopes() {
        val id = UUID.randomUUID()
        val state = BmwPeqState(
            true,
            -3.5f,
            ParametricEqBandList().apply {
                add(ParametricEqBand(1000.5, -4.25, 1.2, uuid = id))
            },
            ParametricEqBandList().apply {
                add(ParametricEqBand(80.0, 2.0, 0.8, channel = ParametricEqChannel.LEFT))
            },
            ParametricEqBandList(),
        )

        val decoded = BmwPeqPreset.decode(
            BmwPeqPreset.encode(BmwPeqPreset.fromState(state, "Road tune"))
        )
        val restored = decoded.toState()

        assertEquals("Road tune", decoded.name)
        assertEquals(state.preampDb, restored.preampDb)
        assertEquals(id, restored.fullRangeBands.single().uuid)
        assertEquals(1, restored.lowBandBands.size)
        assertEquals(0, restored.midBandBands.size)
    }

    @Test
    fun rejectsFutureVersionAndDuplicateUuids() {
        val duplicate = UUID.randomUUID().toString()
        val band = BmwPeqPreset.PresetBand(duplicate, 1000.0, 0.0, 1.0, 0, 0)
        val preset = BmwPeqPreset(
            version = BmwPeqPreset.CURRENT_VERSION + 1,
            enabled = true,
            preampDb = 0f,
            fullRange = listOf(band, band),
            lowBand = emptyList(),
            midBand = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) { preset.toState() }

        assertThrows(IllegalArgumentException::class.java) {
            preset.copy(version = BmwPeqPreset.CURRENT_VERSION).toState()
        }
    }
}
