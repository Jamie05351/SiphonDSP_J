package app.siphondsp.compose.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
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
        val state = BmwDspState(context)
        val previous = state.values.copyOf()
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
            assertArrayEquals(previous, state.values, 0f)
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
        val state = BmwDspState(context)
        assertTrue(state.commit(NativeBmwDspValues.INDEX_LOW_DELAY_L, 1.5f,
            intArrayOf(NativeBmwDspValues.INDEX_LOW_DELAY_R)))
        val saved = NativeBmwDspValues.load(context)
        assertEquals(1.5f, saved[NativeBmwDspValues.INDEX_LOW_DELAY_L], 0f)
        assertEquals(1.5f, saved[NativeBmwDspValues.INDEX_LOW_DELAY_R], 0f)
    }

    @Test
    fun successfulCommitPersistsBeforeBroadcasting() {
        val context = context()
        val state = BmwDspState(context)
        var persistedAtBroadcastTime: Float? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Deliberately reads via the outer `context` (the test's custom noBackupFilesDir
                // wrapper), not the receiver's own `ctx` param -- if the receiver can already read
                // the new value back from disk, save() happened before broadcast() -- the ordering
                // commit() must preserve so a listener reacting to the broadcast (e.g. the native
                // engine) never reads a config older than what was just persisted.
                persistedAtBroadcastTime = NativeBmwDspValues.load(context)[NativeBmwDspValues.INDEX_HEADROOM]
            }
        }
        val broadcasts = LocalBroadcastManager.getInstance(context)
        broadcasts.registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        try {
            assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -7f))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(-7f, persistedAtBroadcastTime)
        } finally {
            broadcasts.unregisterReceiver(receiver)
        }
    }
}
