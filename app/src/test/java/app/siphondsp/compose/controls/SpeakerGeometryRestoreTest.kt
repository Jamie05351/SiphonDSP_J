package app.siphondsp.compose.controls

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SpeakerGeometryRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tweeterL = DriverId(SpeakerKind.TWEETER, left = true)
    private val midR = DriverId(SpeakerKind.MID, left = false)

    @Before
    fun clear() {
        context.getSharedPreferences(SpeakerGeometryState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun backup(vararg entries: String): File =
        File.createTempFile("speaker_geometry", ".xml").apply {
            writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n${entries.joinToString("\n")}\n</map>\n")
            deleteOnExit()
        }

    @Test
    fun cleanRestoreReplacesValuesAndAnOpenPageSeesThem() {
        val state = SpeakerGeometryState(context).apply { startListening() }
        state.setDistanceCm(tweeterL, 111f)
        state.setDistanceCm(midR, 222f)

        SpeakerGeometryState.restoreFrom(
            context,
            backup("""<float name="cm_DRIVER_TWEETER_L" value="140.5" />""", """<string name="target">MULTI</string>"""),
            replace = true,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(140.5f, state.distanceCm(tweeterL), 0f)
        // Not in the backup, so a clean restore drops the edit back to the default.
        assertEquals(SpeakerGeometryMath.defaultDistanceCm(ListeningSeat.DRIVER, midR), state.distanceCm(midR), 0f)
        assertEquals(AlignTarget.MULTI, state.target)
        state.stopListening()
    }

    @Test
    fun dirtyRestoreMergesIntoCurrentValues() {
        SpeakerGeometryState(context).setDistanceCm(midR, 222f)

        SpeakerGeometryState.restoreFrom(
            context, backup("""<float name="cm_DRIVER_TWEETER_L" value="99.0" />"""), replace = false,
        )

        val fresh = SpeakerGeometryState(context)
        assertEquals(99f, fresh.distanceCm(tweeterL), 0f)
        assertEquals(222f, fresh.distanceCm(midR), 0f)
        assertEquals(AlignTarget.DRIVER, fresh.target)
    }
}
