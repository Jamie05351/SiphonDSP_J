package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BmwPeqStateTest {
    @Test
    fun rejectsDuplicateUuidsAcrossScopes() {
        val id = UUID.randomUUID()
        val state = BmwPeqState.empty().copy(
            fullRangeBands = ParametricEqBandList().apply {
                add(ParametricEqBand(1000.0, 0.0, 1.0, uuid = id))
            },
            lowBandBands = ParametricEqBandList().apply {
                add(ParametricEqBand(80.0, 0.0, 1.0, uuid = id))
            },
        )

        assertEquals("PEQ contains duplicate filter identities", state.validate(48_000f))
    }

    @Test
    fun emptyBanksAreValidAndIndependent() {
        val state = BmwPeqState.empty()
        assertNull(state.validate(48_000f))
        assertNotSame(state.fullRangeBands, state.lowBandBands)
        assertNotSame(state.lowBandBands, state.midBandBands)
    }

    @Test
    fun deepCopyDoesNotMutateAuthoritativeBank() {
        val original = BmwPeqState.empty()
        val candidate = original.deepCopy()
        candidate.lowBandBands += ParametricEqBand(80.0, -12.0, 1.0)

        assertTrue(original.lowBandBands.isEmpty())
        assertEquals(1, candidate.lowBandBands.size)
    }

    @Test
    fun serializedBandsPreserveUuidAndAcceptLegacyEntries() {
        val id = UUID.randomUUID()
        val bands = ParametricEqBandList().apply {
            add(ParametricEqBand(1000.0, -3.0, 1.2, uuid = id))
        }
        val restored = ParametricEqBandList().apply { deserialize(bands.serialize()) }
        val legacy = ParametricEqBandList().apply { deserialize("PEQ: 80 -6 1 0 2;") }

        assertEquals(id, restored.single().uuid)
        assertEquals(ParametricEqChannel.RIGHT, legacy.single().channel)
    }

    @Test
    fun validationRejectsNyquistAndGainViolations() {
        val nyquist = BmwPeqState.empty().deepCopy().also {
            it.fullRangeBands += ParametricEqBand(24_000.0, 0.0, 1.0)
        }
        val gain = BmwPeqState.empty().deepCopy().also {
            it.midBandBands += ParametricEqBand(1_000.0, 31.0, 1.0)
        }

        assertTrue(nyquist.validate(48_000f)?.contains("Nyquist") == true)
        assertTrue(gain.validate(48_000f)?.contains("gain") == true)
    }
}
