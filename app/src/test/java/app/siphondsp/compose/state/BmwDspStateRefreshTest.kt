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
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
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
        val repo = BmwDspRepository(context)
        val index = NativeBmwDspValues.INDEX_MEAS_GEN_TIMING_REF_ENABLED
        repo.preview(index, 1f, IntArray(0))
        shadowOf(Looper.getMainLooper()).idle()

        var broadcastValue: Float? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                broadcastValue = intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)?.get(index)
            }
        }
        val broadcasts = LocalBroadcastManager.getInstance(context)
        broadcasts.registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        try {
            repo.refreshFromDisk()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0f, repo.values.value[index], 0f)
            assertEquals(0f, broadcastValue)
        } finally {
            broadcasts.unregisterReceiver(receiver)
        }
    }
}
