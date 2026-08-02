package app.siphondsp.model

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class NativeBmwDspValuesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val prefsByName = mutableMapOf<String, FakeSharedPreferences>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefsByName.clear()
        val noBackupDir = temporaryFolder.newFolder("no-backup")
        context = mock {
            on { getSharedPreferences(any(), any()) } doAnswer { invocation ->
                val name = invocation.getArgument<String>(0)
                prefsByName.getOrPut(name) { FakeSharedPreferences() }
            }
            on { noBackupFilesDir } doAnswer { noBackupDir }
        }
    }

    @Test
    fun loadFallsBackToDefaultsWhenNothingSaved() {
        val loaded = NativeBmwDspValues.load(context)
        assertArrayEquals(NativeBmwDspValues.DEFAULTS, loaded, 0f)
    }

    @Test
    fun loadMigratesLegacySharedPreferencesBlobByPosition() {
        // "1,2,3" predates NativeBmwDspStore -- shorter (or longer) than SIZE should no longer
        // discard the whole array, just leave every index it didn't cover at its default.
        val prefs = context.getSharedPreferences(NativeBmwDspValues.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(NativeBmwDspValues.KEY, "1,2,3").apply()

        val loaded = NativeBmwDspValues.load(context)

        val expected = NativeBmwDspValues.DEFAULTS.copyOf().also {
            it[0] = 1f; it[1] = 2f; it[2] = 3f
        }
        assertArrayEquals(expected, loaded, 0f)
        // Migration is one-time: the legacy blob should be cleared once picked up.
        assertArrayEquals(expected, NativeBmwDspValues.load(context), 0f)
    }

    @Test
    fun saveLoadRoundTripsAllFortyTwoValues() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf().also {
            it[NativeBmwDspValues.INDEX_TILT_FREQ] = 777f
            it[NativeBmwDspValues.INDEX_HEADROOM] = -3.5f
        }

        NativeBmwDspValues.save(context, values)
        val loaded = NativeBmwDspValues.load(context)

        assertArrayEquals(values, loaded, 0f)
    }

    // update() also broadcasts via LocalBroadcastManager, which needs a real main Looper --
    // not available in a plain JVM unit test (confirmed empirically: throws RuntimeException
    // here). Its mutate-only-requested-indices and broadcast-exactly-once behavior is covered
    // by a Robolectric test instead, added alongside the rest of the Robolectric suite.
}
