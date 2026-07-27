package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class BmwPeqStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cacheClearAndColdStartPreserveEveryThreeBankField() {
        val appData = temporaryFolder.newFolder("app-data")
        val persistent = File(appData, "no_backup")
        val cache = File(appData, "cache").apply { mkdirs() }
        File(cache, "transient/subdir").apply { mkdirs() }
        File(cache, "transient/subdir/entry.tmp").writeText("cache only")
        val expected = populatedState(enabled = true)

        assertTrue(BmwPeqStore(persistent).save(expected))
        cache.listFiles().orEmpty().forEach { assertTrue(it.deleteRecursively()) }

        val coldStartStore = BmwPeqStore(persistent)
        val restored = coldStartStore.load()
        assertEquals(BmwPeqStore.Source.PRIMARY, restored.source)
        assertStateEquals(expected, restored.state)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun missingPrimaryRestoresValidPersistentRecovery() {
        val store = BmwPeqStore(temporaryFolder.newFolder("missing-primary"))
        val expected = populatedState(enabled = true)
        assertTrue(store.save(expected))
        assertTrue(store.primaryPath().delete())

        val restored = BmwPeqStore(store.primaryPath().parentFile!!).load()
        assertEquals(BmwPeqStore.Source.RECOVERY, restored.source)
        assertStateEquals(expected, restored.state)
    }

    @Test
    fun corruptPrimaryDoesNotOverwriteValidSavedRecovery() {
        val store = BmwPeqStore(temporaryFolder.newFolder("corrupt-primary"))
        val expected = populatedState(enabled = true)
        assertTrue(store.save(expected))
        val recoveryBefore = store.recoveryPath().readBytes()
        store.primaryPath().writeText("BMW_PEQ_STATE_V2\nbad checksum\ntruncated")

        val restored = BmwPeqStore(store.primaryPath().parentFile!!).load()

        assertEquals(BmwPeqStore.Source.RECOVERY, restored.source)
        assertStateEquals(expected, restored.state)
        assertTrue(recoveryBefore.contentEquals(store.recoveryPath().readBytes()))
        assertFalse(store.primaryPath().readText().contains(expected.fullRangeBands.serialize()))
    }

    @Test
    fun deletingInternalAppDataRemovesStateWithoutExternalRecovery() {
        val appData = temporaryFolder.newFolder("clear-data")
        val persistent = File(appData, "no_backup")
        val store = BmwPeqStore(persistent)
        assertTrue(store.save(populatedState(enabled = true)))

        assertTrue(appData.deleteRecursively())
        val recreatedPersistent = File(appData, "no_backup").apply { mkdirs() }

        assertNull(BmwPeqStore(recreatedPersistent).load().state)
    }

    @Test
    fun disabledPeqWithPopulatedFiltersRestoresUnchanged() {
        val persistent = temporaryFolder.newFolder("disabled-populated")
        val expected = populatedState(enabled = false)
        assertTrue(BmwPeqStore(persistent).save(expected))

        val restored = BmwPeqStore(persistent).load()

        assertStateEquals(expected, restored.state)
        assertFalse(restored.state!!.enabled)
        assertTrue(restored.state.fullRangeBands.isNotEmpty())
        assertTrue(restored.state.lowBandBands.isNotEmpty())
        assertTrue(restored.state.midBandBands.isNotEmpty())
    }

    private fun populatedState(enabled: Boolean) = BmwPeqState(
        enabled = enabled,
        preampDb = -4.5f,
        fullRangeBands = ParametricEqBandList().apply {
            add(band(31.5, 2.25, 0.71, ParametricEqFilterType.LOW_SHELF, ParametricEqChannel.LEFT_RIGHT, 1))
            add(band(4500.0, -1.75, 1.4, ParametricEqFilterType.HIGH_SHELF, ParametricEqChannel.RIGHT, 2))
        },
        lowBandBands = ParametricEqBandList().apply {
            add(band(63.0, -3.5, 2.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT, 3))
            add(band(95.0, 1.25, 0.9, ParametricEqFilterType.PEAKING, ParametricEqChannel.RIGHT, 4))
        },
        midBandBands = ParametricEqBandList().apply {
            add(band(250.0, -2.0, 1.1, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT, 5))
            add(band(1800.0, 0.75, 0.8, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT, 6))
        },
    )

    private fun band(
        frequency: Double,
        gain: Double,
        q: Double,
        type: ParametricEqFilterType,
        channel: ParametricEqChannel,
        id: Int,
    ) = ParametricEqBand(
        frequency,
        gain,
        q,
        type,
        channel,
        UUID.fromString("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"),
    )

    private fun assertStateEquals(expected: BmwPeqState, actual: BmwPeqState?) {
        requireNotNull(actual)
        assertEquals(expected.enabled, actual.enabled)
        assertEquals(expected.preampDb, actual.preampDb)
        assertBandsEqual(expected.fullRangeBands, actual.fullRangeBands)
        assertBandsEqual(expected.lowBandBands, actual.lowBandBands)
        assertBandsEqual(expected.midBandBands, actual.midBandBands)
    }

    private fun assertBandsEqual(expected: ParametricEqBandList, actual: ParametricEqBandList) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            val expectedBand = expected[index]
            val actualBand = actual[index]
            assertEquals(expectedBand.frequency, actualBand.frequency, 0.0)
            assertEquals(expectedBand.gain, actualBand.gain, 0.0)
            assertEquals(expectedBand.q, actualBand.q, 0.0)
            assertEquals(expectedBand.filterType, actualBand.filterType)
            assertEquals(expectedBand.channel, actualBand.channel)
            assertEquals(expectedBand.uuid, actualBand.uuid)
        }
    }
}
