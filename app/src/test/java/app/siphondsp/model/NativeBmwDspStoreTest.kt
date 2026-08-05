package app.siphondsp.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeBmwDspStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun saveLoadRoundTrips() {
        val store = NativeBmwDspStore(temporaryFolder.newFolder("round-trip"))
        val values = NativeBmwDspValues.DEFAULTS.copyOf().also {
            it[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] = 111f
            it[NativeBmwDspValues.INDEX_TILT_AMOUNT] = -2.5f
        }

        assertTrue(store.save(values))
        assertArrayEquals(values, store.load(), 0f)
    }

    @Test
    fun missingPrimaryRestoresFromRecovery() {
        val store = NativeBmwDspStore(temporaryFolder.newFolder("missing-primary"))
        val values = NativeBmwDspValues.DEFAULTS.copyOf().also { it[NativeBmwDspValues.INDEX_SUBSONIC_FREQ] = 40f }
        assertTrue(store.save(values))
        assertTrue(store.primaryPath().delete())

        val restored = NativeBmwDspStore(store.primaryPath().parentFile!!).load()

        assertArrayEquals(values, restored, 0f)
    }

    @Test
    fun corruptPrimaryDoesNotOverwriteValidRecovery() {
        val store = NativeBmwDspStore(temporaryFolder.newFolder("corrupt-primary"))
        val values = NativeBmwDspValues.DEFAULTS.copyOf().also { it[NativeBmwDspValues.INDEX_MID_DELAY_L] = 1.2f }
        assertTrue(store.save(values))
        val recoveryBefore = store.recoveryPath().readBytes()
        store.primaryPath().writeText("BMW_DSP_STATE_V1\nbad checksum\ntruncated")

        val restored = NativeBmwDspStore(store.primaryPath().parentFile!!).load()

        assertArrayEquals(values, restored, 0f)
        assertTrue(recoveryBefore.contentEquals(store.recoveryPath().readBytes()))
    }

    @Test
    fun neitherFilePresentReturnsNull() {
        val store = NativeBmwDspStore(temporaryFolder.newFolder("nothing-saved"))
        assertNull(store.load())
    }

    @Test
    fun anOldSavedFileWithoutRoutingOrAllPassIndicesMigratesToIdentityRoutingAndDisabledAllPass() {
        // Simulates a pre-routing-matrix save (only indices 0-45 present): every new routing
        // and all-pass index must come back at its safe default -- unity same-side routing,
        // zero crossfeed, all-pass disabled -- never zeroed/silent and never left uninitialized.
        val directory = temporaryFolder.newFolder("pre-routing-save")
        val payload = (0..45).joinToString("\n") { index -> "$index=${NativeBmwDspValues.DEFAULTS[index]}" }
        val header = "BMW_DSP_STATE_V1"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        File(directory, NativeBmwDspStore.FILE_NAME).writeText("$header\n$digest\n$payload")

        val loaded = NativeBmwDspStore(directory).load()!!

        assertArrayEquals(NativeBmwDspValues.DEFAULTS, loaded, 0f)
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT])
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT])
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_ALL_PASS])
    }

    @Test
    fun anIndexMissingFromAnOlderSavedFileKeepsItsDefaultInsteadOfDiscardingTheRest() {
        // Simulates loading a file written by an older build that didn't have this index yet --
        // the whole point of keying by index instead of position/length.
        val directory = temporaryFolder.newFolder("partial-index-set")
        val payload = "5=-9.0\n15=175.0"
        val header = "BMW_DSP_STATE_V1"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        File(directory, NativeBmwDspStore.FILE_NAME).writeText("$header\n$digest\n$payload")

        val loaded = NativeBmwDspStore(directory).load()

        val expected = NativeBmwDspValues.DEFAULTS.copyOf().also {
            it[5] = -9.0f
            it[15] = 175.0f
        }
        assertArrayEquals(expected, loaded, 0f)
    }
}
