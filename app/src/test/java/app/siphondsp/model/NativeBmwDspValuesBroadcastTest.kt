package app.siphondsp.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.utils.Constants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BmwDspRepository.commit()'s broadcast step needs a real Context/Looper -- untestable in a
 * plain JVM unit test (LocalBroadcastManager throws without one). This is the one thing
 * Robolectric buys here that NativeBmwDspValuesTest (load/save round-trips) can't cover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBmwDspValuesBroadcastTest {
    @Test
    fun commitBroadcastsTheAppliedSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BmwDspRepository(context)
        var received: FloatArray? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                received = intent?.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)
            }
        }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))

        assertTrue(repo.commit(NativeBmwDspValues.INDEX_HEADROOM, -3f, IntArray(0)))
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("broadcast receiver should have fired", received)
        assertArrayEquals(repo.values.value, received, 0f)
    }

    @Test
    fun commitPersistsBeforeBroadcasting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BmwDspRepository(context)
        var persistedAtBroadcastTime: Float? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // If the receiver can already read the new value back from disk, save()
                // happened before broadcast() -- exactly the mutate -> save -> broadcast
                // ordering BmwDspRepository.commit() documents.
                persistedAtBroadcastTime = NativeBmwDspValues.load(context)[NativeBmwDspValues.INDEX_HEADROOM]
            }
        }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))

        repo.commit(NativeBmwDspValues.INDEX_HEADROOM, -7f, IntArray(0))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(-7f, persistedAtBroadcastTime)
    }
}
