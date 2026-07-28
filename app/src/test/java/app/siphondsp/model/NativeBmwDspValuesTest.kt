package app.siphondsp.model

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class NativeBmwDspValuesTest {
    private val prefsByName = mutableMapOf<String, FakeSharedPreferences>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefsByName.clear()
        context = mock {
            on { getSharedPreferences(any(), any()) } doAnswer { invocation ->
                val name = invocation.getArgument<String>(0)
                prefsByName.getOrPut(name) { FakeSharedPreferences() }
            }
        }
    }

    @Test
    fun loadFallsBackToDefaultsWhenNothingSaved() {
        val loaded = NativeBmwDspValues.load(context)
        assertArrayEquals(NativeBmwDspValues.DEFAULTS, loaded, 0f)
    }

    @Test
    fun loadFallsBackToDefaultsOnWrongSize() {
        val prefs = context.getSharedPreferences(NativeBmwDspValues.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(NativeBmwDspValues.KEY, "1,2,3").apply()

        val loaded = NativeBmwDspValues.load(context)

        assertArrayEquals(NativeBmwDspValues.DEFAULTS, loaded, 0f)
    }

    @Test
    fun saveLoadRoundTripsAllThirtyFiveValues() {
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
