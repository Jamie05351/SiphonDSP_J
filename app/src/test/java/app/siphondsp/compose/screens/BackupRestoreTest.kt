package app.siphondsp.compose.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.fragment.PeqGraphPreferences
import app.siphondsp.model.BmwPeqPreset
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspStore
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.PrivatePeqBackup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun context(): Context {
        val directory = temporaryFolder.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = directory
        }
    }

    private fun prompt(values: FloatArray): PendingBackupRestore {
        val candidate = BmwPeqState(true, -3f, ParametricEqBandList(), ParametricEqBandList(), ParametricEqBandList())
        return PendingBackupRestore(candidate, PrivatePeqBackup(
            createdAtEpochMs = 1L,
            state = BmwPeqPreset.fromState(candidate),
            nativeDspValues = values.toList(),
        ), "")
    }

    @Test
    fun failedDspSaveReportsPartialRestoreWithoutPublishingUnsavedSettings() {
        val context = context()
        val previous = NativeBmwDspValues.load(context)
        val holder = mock<PeqStateHolder> { on { applyCandidate(any(), any()) } doReturn true }
        val graph = PeqGraphPreferences(context).also { it.showIndividualFilters = false }
        assertTrue(File(context.noBackupFilesDir, NativeBmwDspStore.FILE_NAME + ".tmp").mkdir())
        var published = false

        applyBackupRestore(context, holder, graph, prompt(previous.copyOf().also { it[5] = -9f })) { published = true }

        assertFalse(published)
        assertArrayEquals(previous, NativeBmwDspValues.load(context), 0f)
        assertFalse(graph.showIndividualFilters)
        assertTrue(BmwPeqState.diagnosticMetadata(context).backupRestoreResult.startsWith("partial:"))
        assertTrue(ShadowToast.getTextOfLatestToast().contains("partly restored"))
    }

    @Test
    fun successfulRestoreSavesBeforePublishingAndRecordsSuccess() {
        val context = context()
        val values = NativeBmwDspValues.load(context).also { it[5] = -9f }
        val holder = mock<PeqStateHolder> { on { applyCandidate(any(), any()) } doReturn true }
        var published: FloatArray? = null

        applyBackupRestore(context, holder, PeqGraphPreferences(context), prompt(values)) {
            assertArrayEquals(it, NativeBmwDspValues.load(context), 0f)
            published = it
        }

        assertNotNull(published)
        assertEquals(-9f, published!![5], 0f)
        assertTrue(BmwPeqState.diagnosticMetadata(context).backupRestoreResult.startsWith("success-"))
    }
}
