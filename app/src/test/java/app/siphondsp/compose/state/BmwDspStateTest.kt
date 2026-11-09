package app.siphondsp.compose.state

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.model.NativeBmwDspStore
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.DspUpdateRecorder
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
        DspUpdateRecorder().use { updates ->
            assertFalse(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -9f))
            shadowOf(Looper.getMainLooper()).idle()
            assertArrayEquals(previous, state.values, 0f)
            assertArrayEquals(previous, updates.received.last().values, 0f)
            assertArrayEquals(previous, NativeBmwDspValues.load(context), 0f)
            assertTrue(ShadowToast.getTextOfLatestToast().contains("could not be saved"))
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
        DspUpdateRecorder().use { updates ->
            // The recorder runs inside the send call: if it can already read the new value back
            // from disk, save() happened before the event was sent -- the ordering commit() must
            // preserve so a listener reacting to it (e.g. the native engine) never reads a config
            // older than what was just persisted.
            updates.onUpdate = {
                persistedAtBroadcastTime = NativeBmwDspValues.load(context)[NativeBmwDspValues.INDEX_HEADROOM]
            }
            assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -7f))
            assertEquals(-7f, persistedAtBroadcastTime)
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
        DspUpdateRecorder().use { updates ->
            state.refreshFromDisk()
            assertEquals(0, updates.received.size)
            state.preview(NativeBmwDspValues.INDEX_HEADROOM, -9f)
            state.refreshFromDisk()  // the previewed value was never saved: the engine must drop it
            assertEquals(2, updates.received.size)
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
        DspUpdateRecorder().use { updates ->
            state.preview(NativeBmwDspValues.INDEX_HEADROOM, -9f)
            assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -4f))
            // The preview's echo arriving after the commit must not win.
            val echo = updates.received.first()
            state.onExternalUpdate(echo.values, echo.origin)
            assertEquals(-4f, state.get(NativeBmwDspValues.INDEX_HEADROOM), 0f)
            // Someone else's update still applies.
            val external = state.values.copyOf().also { it[NativeBmwDspValues.INDEX_HEADROOM] = -2f }
            state.onExternalUpdate(external)
            assertEquals(-2f, state.get(NativeBmwDspValues.INDEX_HEADROOM), 0f)
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

    @Test
    fun committedCrossoverTypeAndFrequencyReadBackExactly() {
        // The fields whose commits a per-commit disk reload once made unreliable (#387).
        val context = context()
        val state = BmwDspState(context)
        val type = NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)
        val freq = NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)
        assertTrue(state.commit(type, NativeBmwDspValues.CROSSOVER_TYPE_BW2))
        assertTrue(state.commit(freq, 95f))
        assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -2f))  // a later, unrelated commit
        val saved = NativeBmwDspValues.load(context)
        assertEquals(NativeBmwDspValues.CROSSOVER_TYPE_BW2, saved[type], 0f)
        assertEquals(95f, saved[freq], 0f)
        assertEquals(NativeBmwDspValues.CROSSOVER_TYPE_BW2, state.get(type), 0f)
        assertEquals(95f, state.get(freq), 0f)
    }

    @Test
    fun aCommitKeepsAnotherControlsDragRunningWithoutSavingIt() {
        val context = context()
        val state = BmwDspState(context)
        val persistedDelay = NativeBmwDspValues.load(context)[NativeBmwDspValues.INDEX_LOW_DELAY_L]
        state.preview(NativeBmwDspValues.INDEX_LOW_DELAY_L, 1.7f)  // finger still on this slider
        assertTrue(state.commit(NativeBmwDspValues.INDEX_HEADROOM, -6f))  // another control commits
        assertEquals(1.7f, state.get(NativeBmwDspValues.INDEX_LOW_DELAY_L), 0f)  // no snap-back
        assertEquals(persistedDelay, NativeBmwDspValues.load(context)[NativeBmwDspValues.INDEX_LOW_DELAY_L], 0f)
    }
}
