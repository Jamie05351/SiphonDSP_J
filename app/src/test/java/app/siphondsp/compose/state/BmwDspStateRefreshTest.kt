package app.siphondsp.compose.state

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.DspUpdateRecorder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BmwDspStateRefreshTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun refreshFromDiskBroadcastsPersistedTimingReferenceAfterPreview() {
        val directory = temporaryFolder.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = directory
        }
        val state = BmwDspState(context)
        val index = NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_ENABLED
        state.preview(index, 1f)
        shadowOf(Looper.getMainLooper()).idle()

        DspUpdateRecorder().use { updates ->
            state.refreshFromDisk()

            assertEquals(0f, state.get(index), 0f)
            assertEquals(0f, updates.received.last().values[index])
        }
    }
}
