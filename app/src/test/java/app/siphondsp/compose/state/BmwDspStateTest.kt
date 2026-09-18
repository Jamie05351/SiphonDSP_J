package app.siphondsp.compose.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.model.BmwDspRepository
import app.siphondsp.model.NativeBmwDspStore
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BmwDspStateTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun context(): Context {
        val directory = temporaryFolder.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = directory
        }
    }

    @Test
    fun failedCommitRestoresPreviewAndReportsFailure() {
        val context = context()
        val repo = BmwDspRepository(context)
        val previous = repo.values.value.copyOf()
        val state = BmwDspState(context, repo, repo.values.value)
        state.preview(NativeBmwDspValues.INDEX_HEADROOM, -9f)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(File(context.noBackupFilesDir, NativeBmwDspStore.FILE_NAME + ".tmp").mkdir())
        var received: FloatArray? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received = intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)
            }
        }
        val broadcasts = LocalBroadcastManager.getInstance(context)
        broadcasts.registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        try {
            assertFalse(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -9f))
            shadowOf(Looper.getMainLooper()).idle()
            assertArrayEquals(previous, repo.values.value, 0f)
            assertArrayEquals(previous, received, 0f)
            assertArrayEquals(previous, NativeBmwDspValues.load(context), 0f)
            assertTrue(ShadowToast.getTextOfLatestToast().contains("could not be saved"))
        } finally {
            broadcasts.unregisterReceiver(receiver)
        }
    }

    @Test
    fun successfulCommitPersistsMirroredValues() {
        val context = context()
        val repo = BmwDspRepository(context)
        val state = BmwDspState(context, repo, repo.values.value)
        assertTrue(state.commit(NativeBmwDspValues.INDEX_LOW_DELAY_L, 1.5f,
            intArrayOf(NativeBmwDspValues.INDEX_LOW_DELAY_R)))
        val saved = NativeBmwDspValues.load(context)
        assertEquals(1.5f, saved[NativeBmwDspValues.INDEX_LOW_DELAY_L], 0f)
        assertEquals(1.5f, saved[NativeBmwDspValues.INDEX_LOW_DELAY_R], 0f)
    }

    @Test
    fun committingOneIndexDoesNotPersistAnotherIndexsLivePreview() {
        val context = context()
        val repo = BmwDspRepository(context)
        val state = BmwDspState(context, repo, repo.values.value)

        // Mirrors SignalGeneratorScreen's generator-type / timing-ref toggles: preview-only,
        // deliberately never committed so they don't survive a restart.
        state.preview(NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_ENABLED, 1f)

        assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -3f))

        val saved = NativeBmwDspValues.load(context)
        assertEquals(-3f, saved[NativeBmwDspValues.INDEX_HEADROOM], 0f)
        assertEquals(0f, saved[NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_ENABLED], 0f)
    }
}
