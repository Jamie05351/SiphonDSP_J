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

    @Test
    fun screensShareOneCopyAndCommitsDoNotOverwriteEachOther() {
        val context = context()
        val crossover = BmwDspState(context)
        val gains = BmwDspState(context)
        assertTrue(crossover.commit(NativeBmwDspValues.INDEX_HEADROOM, -4f))
        // The other screen sees it at once, and its own later commit keeps it.
        assertEquals(-4f, gains.get(NativeBmwDspValues.INDEX_HEADROOM), 0f)
        assertTrue(gains.commit(NativeBmwDspValues.INDEX_LOW_DELAY_L, 0.8f))
        val saved = NativeBmwDspValues.load(context)
        assertEquals(-4f, saved[NativeBmwDspValues.INDEX_HEADROOM], 0f)
        assertEquals(0.8f, saved[NativeBmwDspValues.INDEX_LOW_DELAY_L], 0f)
    }

    @Test
    fun resumeWithNothingPendingDoesNotRebroadcast() {
        val context = context()
        val state = BmwDspState(context)
        var broadcasts = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                broadcasts++
            }
        }
        val manager = LocalBroadcastManager.getInstance(context)
        manager.registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        try {
            state.refreshFromDisk()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, broadcasts)
            state.preview(NativeBmwDspValues.INDEX_HEADROOM, -9f)
            state.refreshFromDisk()  // the previewed value was never saved: the engine must drop it
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, broadcasts)
        } finally {
            manager.unregisterReceiver(receiver)
        }
    }

    @Test
    fun aCommitDoesNotPersistAnotherScreensUnsavedPreview() {
        val context = context()
        val generator = BmwDspState(context)
        val gains = BmwDspState(context)
        val persistedType = NativeBmwDspValues.load(context)[NativeBmwDspValues.INDEX_MEAS_GEN_TYPE]
        generator.preview(NativeBmwDspValues.INDEX_MEAS_GEN_TYPE, 1f)  // preview-only on purpose
        assertTrue(gains.commit(NativeBmwDspValues.INDEX_HEADROOM, -5f))
        val saved = NativeBmwDspValues.load(context)
        assertEquals(persistedType, saved[NativeBmwDspValues.INDEX_MEAS_GEN_TYPE], 0f)
        assertEquals(-5f, saved[NativeBmwDspValues.INDEX_HEADROOM], 0f)
    }

    @Test
    fun aLateEchoOfOurOwnPreviewDoesNotRollBackACommit() {
        val context = context()
        val state = BmwDspState(context)
        val sent = mutableListOf<Pair<FloatArray, String?>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)?.let {
                    sent += it to intent.getStringExtra(Constants.EXTRA_NATIVE_BMW_DSP_ORIGIN)
                }
            }
        }
        val manager = LocalBroadcastManager.getInstance(context)
        manager.registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        try {
            state.preview(NativeBmwDspValues.INDEX_HEADROOM, -9f)
            assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -4f))
            shadowOf(Looper.getMainLooper()).idle()
            // The preview's echo arriving after the commit must not win.
            state.onExternalUpdate(sent.first().first, sent.first().second)
            assertEquals(-4f, state.get(NativeBmwDspValues.INDEX_HEADROOM), 0f)
            // Someone else's update still applies.
            val external = state.values.copyOf().also { it[NativeBmwDspValues.INDEX_HEADROOM] = -2f }
            state.onExternalUpdate(external)
            assertEquals(-2f, state.get(NativeBmwDspValues.INDEX_HEADROOM), 0f)
        } finally {
            manager.unregisterReceiver(receiver)
        }
    }

    @Test
    fun aCommitKeepsAChangeAnotherWriterSavedStraightToDisk() {
        // e.g. MasterVolume: saves to disk itself, then broadcasts.
        val context = context()
        val state = BmwDspState(context)
        val external = NativeBmwDspValues.load(context).also { it[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = -20f }
        assertTrue(NativeBmwDspValues.save(context, external))
        assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -3f))
        val saved = NativeBmwDspValues.load(context)
        assertEquals(-20f, saved[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB], 0f)
        assertEquals(-3f, saved[NativeBmwDspValues.INDEX_HEADROOM], 0f)
    }
}
