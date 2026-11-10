package app.siphondsp.utils.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.R
import app.siphondsp.utils.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NamespaceStore.clearForTest()
    }

    @After
    fun tearDown() = NamespaceStore.clearForTest()

    @Test
    fun unsetReadsTheDefault() {
        val prefs = Preferences(context).App()
        assertEquals(true, prefs.get<Boolean>(R.string.key_powersave_suspend))
        assertEquals("3000", prefs.get<String>(R.string.key_session_continuous_polling_rate))
    }

    @Test
    fun firstUseCopiesTheOldSharedPreferences() {
        val key = context.getString(R.string.key_powersave_suspend)
        context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit()
            .putBoolean(key, false)
            .putString(context.getString(R.string.key_session_continuous_polling_rate), "500")
            .commit()

        val prefs = Preferences(context).App()
        assertEquals(false, prefs.get<Boolean>(R.string.key_powersave_suspend))
        assertEquals("500", prefs.get<String>(R.string.key_session_continuous_polling_rate))
    }

    @Test
    fun aWriteIsReadBackAtOnceAndSurvivesAReopen() {
        Preferences(context).App().set(R.string.key_session_continuous_polling_rate, "750", async = false)
        assertEquals("750", Preferences(context).App().get<String>(R.string.key_session_continuous_polling_rate))

        // A new process: the value comes from the file, not the in-memory copy.
        NamespaceStore.clearForTest()
        assertEquals("750", Preferences(context).App().get<String>(R.string.key_session_continuous_polling_rate))
    }

    @Test
    fun namespacesAreSeparate() {
        Preferences(context).App().set(R.string.key_powersave_suspend, false, async = false)
        assertEquals(true, Preferences(context).Var().get<Boolean>(R.string.key_powersave_suspend))
    }

    @Test
    fun listenersHearChangesOnlyOnTheMainThreadAndOnlyForNewValues() {
        val prefs = Preferences(context).App()
        val heard = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            prefs.set(R.string.key_powersave_suspend, false)
            assertEquals(emptyList<String?>(), heard) // posted, not called inside set()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(context.getString(R.string.key_powersave_suspend)), heard)

            prefs.set(R.string.key_powersave_suspend, false) // unchanged
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, heard.size)
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
