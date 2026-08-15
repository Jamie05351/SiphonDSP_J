package app.siphondsp.model

import android.content.Context
import app.siphondsp.R
import app.siphondsp.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
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

    // --- load()/persist() through a (mocked) Context -----------------------------------
    //
    // BmwPeqStoreTest only drives the low-level BmwPeqStore(File) directly and never
    // touches the SharedPreferences-backed migration/fallback logic below -- exactly the
    // code the "Fix PEQ store load result" / "Fix PEQ cold-start persistence" /
    // "Persist PEQ outside cache-backed preferences" commits changed. These tests go
    // through BmwPeqState.load(context)/persist(context) itself instead.
    //
    // The legacy preference names/keys used below intentionally mirror BmwPeqState's
    // private constants: they are a durable on-disk migration contract, not incidental
    // internals.

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val prefsByName = mutableMapOf<String, FakeSharedPreferences>()
    private lateinit var noBackupDir: File
    private lateinit var context: Context

    @Before
    fun setUpContext() {
        prefsByName.clear()
        noBackupDir = temporaryFolder.newFolder("no_backup")
        context = mock {
            on { noBackupFilesDir } doReturn noBackupDir
            on { getSharedPreferences(any(), any()) } doAnswer { invocation ->
                val name = invocation.getArgument<String>(0)
                prefsByName.getOrPut(name) { FakeSharedPreferences() }
            }
            on { getString(R.string.key_peq_bands) } doReturn "peq_bands"
            on { getString(R.string.key_peq_enable) } doReturn "peq_enable"
            on { getString(R.string.key_peq_preamp) } doReturn "peq_preamp"
        }
    }

    private fun freshContext(): Context = mock {
        on { noBackupFilesDir } doReturn noBackupDir
        on { getSharedPreferences(any(), any()) } doAnswer { FakeSharedPreferences() }
    }

    @Test
    fun coldStartWithNothingPersistedReturnsEmptyState() {
        val restored = BmwPeqState.load(context)

        // load() always coerces enabled=true -- PEQ has no enable/disable control anymore,
        // it's always live, regardless of what's actually persisted. See BmwPeqState.load().
        assertTrue(restored.enabled)
        assertTrue(restored.fullRangeBands.isEmpty())
        assertTrue(restored.lowBandBands.isEmpty())
        assertTrue(restored.midBandBands.isEmpty())
    }

    @Test
    fun migratesFromLegacyBmwPreferenceStoreAndClearsIt() {
        val legacyBands = ParametricEqBandList().apply {
            add(ParametricEqBand(100.0, 2.0, 0.9, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT))
        }
        val legacyPrefs = context.getSharedPreferences("native_bmw_peq", Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putInt("version", 1)
            .putBoolean("enabled", true)
            .putFloat("preamp", -2.5f)
            .putString("full_range", legacyBands.serialize())
            .putString("low_band", ParametricEqBandList().serialize())
            .putString("mid_band", ParametricEqBandList().serialize())
            .apply()

        val restored = BmwPeqState.load(context)

        assertTrue(restored.enabled)
        assertEquals(-2.5f, restored.preampDb)
        assertEquals(1, restored.fullRangeBands.size)
        assertEquals(100.0, restored.fullRangeBands[0].frequency, 0.0)

        // Migration must clear the obsolete SharedPreferences copy so a later fallback
        // pass can't resurrect it once the file store is authoritative.
        assertFalse(legacyPrefs.contains("version"))
        assertFalse(legacyPrefs.contains("full_range"))

        // ...and must have durably written the migrated state into the real file store,
        // so a second load() on a fresh Context/process pointed at the same directory
        // succeeds from disk alone, without any SharedPreferences at all.
        val reloaded = BmwPeqState.load(freshContext())
        assertTrue(reloaded.enabled)
        assertEquals(1, reloaded.fullRangeBands.size)
    }

    @Test
    fun migratesFromLegacyGeneralPeqPreferencesWithoutClearingThem() {
        val generalPeqBands = ParametricEqBandList().apply {
            add(ParametricEqBand(50.0, -1.0, 1.2, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT))
        }
        val generalPeqPrefs = context.getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
        generalPeqPrefs.edit()
            .putBoolean("peq_enable", true)
            .putFloat("peq_preamp", -1.5f)
            .putString("peq_bands", generalPeqBands.serialize())
            .apply()

        val restored = BmwPeqState.load(context)

        assertTrue(restored.enabled)
        assertEquals(1, restored.fullRangeBands.size)
        assertTrue(restored.lowBandBands.isEmpty())
        assertTrue(restored.midBandBands.isEmpty())

        // Constants.PREF_PEQ is the live store for the separate, non-BMW Parametric EQ
        // feature (JamesDspBaseEngine reads it directly). Migrating into BMW PEQ must
        // never delete the user's actual general-EQ settings out from under it.
        assertTrue(generalPeqPrefs.contains("peq_bands"))
        assertTrue(generalPeqPrefs.getBoolean("peq_enable", false))
    }

    @Test
    fun persistedStateSurvivesFreshContextWithClearedSharedPreferences() {
        val state = BmwPeqState(
            enabled = true,
            preampDb = 3.25f,
            fullRangeBands = ParametricEqBandList().apply {
                add(ParametricEqBand(1000.0, 1.5, 0.8, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT))
            },
            lowBandBands = ParametricEqBandList(),
            midBandBands = ParametricEqBandList(),
        )
        assertTrue(state.persist(context))

        // Simulate a process restart with SharedPreferences wiped (e.g. cache clear) but
        // noBackupFilesDir intact -- what "Persist PEQ outside cache-backed preferences"
        // was supposed to guarantee.
        val restored = BmwPeqState.load(freshContext())

        assertTrue(restored.enabled)
        assertEquals(3.25f, restored.preampDb)
        assertEquals(1, restored.fullRangeBands.size)
        assertEquals(1000.0, restored.fullRangeBands[0].frequency, 0.0)
    }

    @Test
    fun loadLastKnownGoodReadsFromLkgPreferences() {
        val lkgBands = ParametricEqBandList().apply {
            add(ParametricEqBand(200.0, 0.5, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT))
        }
        val prefs = context.getSharedPreferences("native_bmw_peq", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("lkg_version", 1)
            .putBoolean("lkg_enabled", true)
            .putFloat("lkg_preamp", 1.0f)
            .putString("lkg_full_range", lkgBands.serialize())
            .putString("lkg_low_band", ParametricEqBandList().serialize())
            .putString("lkg_mid_band", ParametricEqBandList().serialize())
            .apply()

        val lkg = BmwPeqState.loadLastKnownGood(context)

        requireNotNull(lkg)
        assertTrue(lkg.enabled)
        assertEquals(1.0f, lkg.preampDb)
        assertEquals(1, lkg.fullRangeBands.size)
        assertEquals(200.0, lkg.fullRangeBands[0].frequency, 0.0)
    }
}
