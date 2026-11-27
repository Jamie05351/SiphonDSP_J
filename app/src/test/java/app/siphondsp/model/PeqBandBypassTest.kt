package app.siphondsp.model

import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.compose.screens.bandwidthOctaves
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** A bypassed band keeps its settings, round-trips through every store, and is never heard. */
class PeqBandBypassTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val on = ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT, id)
    private val off = ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT, id, enabled = false)

    private fun listOf(vararg bands: ParametricEqBand) = ParametricEqBandList().also { it.addAll(bands) }

    @Test
    fun enabledBandsSerializeExactlyAsBefore() {
        assertEquals("PEQ: 1000 3 1.41 0 1 $id; ", listOf(on).serialize())
    }

    @Test
    fun aBypassedBandSurvivesSerialization() {
        val serialized = listOf(off).serialize()
        assertEquals("PEQ: 1000 3 1.41 0 1 $id 0; ", serialized)
        val restored = ParametricEqBandList().also { it.deserialize(serialized) }.single()
        assertFalse(restored.enabled)
        assertEquals(id, restored.uuid)
        assertEquals(3.0, restored.gain, 0.0)
    }

    @Test
    fun bypassIsPartOfEquality() {
        assertNotEquals(on, off)
        assertEquals(off, ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT, enabled = false))
    }

    @Test
    fun apoExportWritesOffAndImportReadsItBack() {
        val text = listOf(on, off).toApoString()
        assertTrue(text.contains("Filter 1: ON PK"))
        assertTrue(text.contains("Filter 2: OFF PK"))
        val imported = ParametricEqBandList()
        val result = imported.fromApoString(text)
        assertEquals(0, result.skippedFilters)
        assertEquals(listOf(true, false), imported.map { it.enabled })
    }

    @Test
    fun theEngineNeverReceivesABypassedBand() {
        val state = BmwPeqState(true, 0f, listOf(on, off), ParametricEqBandList(), ParametricEqBandList(), ParametricEqBandList())
        assertArrayEquals(doubleArrayOf(1000.0, 3.0, 1.41, 0.0, 1.0), state.nativeValues(state.fullRangeBands), 0.0)
    }

    @Test
    fun aBypassedBandAppliesToNoOutput() {
        assertTrue(BmwSignalChain.bandAppliesTo(on, BmwOutputChannel.LEFT))
        assertFalse(BmwSignalChain.bandAppliesTo(off, BmwOutputChannel.LEFT))
        assertFalse(BmwSignalChain.bandAppliesTo(off, BmwOutputChannel.RIGHT))
    }

    @Test
    fun presetsKeepBypass() {
        val state = BmwPeqState(true, 0f, listOf(off), ParametricEqBandList(), ParametricEqBandList(), ParametricEqBandList())
        val restored = BmwPeqPreset.decode(BmwPeqPreset.encode(BmwPeqPreset.fromState(state))).toState()
        assertFalse(restored.fullRangeBands.single().enabled)
    }

    @Test
    fun qOf141IsAboutOneOctave() {
        assertEquals(1.0, bandwidthOctaves(1.41), 0.01)
        assertEquals(1.0 / 3.0, bandwidthOctaves(4.32), 0.01)
    }
}
