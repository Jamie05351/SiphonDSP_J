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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NativeBmwDspValues.update()'s broadcast step needs a real Context/Looper -- untestable in a
 * plain JVM unit test (LocalBroadcastManager throws without one). This is the one thing
 * Robolectric buys here that NativeBmwDspValuesTest (load/save round-trips) can't cover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBmwDspValuesBroadcastTest {
    @Test
    fun updateBroadcastsTheAppliedSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var received: FloatArray? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                received = intent?.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)
            }
        }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))

        val applied = NativeBmwDspValues.update(context) { values ->
            values[NativeBmwDspValues.INDEX_HEADROOM] = -3f
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("broadcast receiver should have fired", received)
        assertArrayEquals(applied, received, 0f)
    }

    @Test
    fun updatePersistsBeforeBroadcasting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var persistedAtBroadcastTime: Float? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // If the receiver can already read the new value back from disk, save()
                // happened before broadcast() -- exactly the load -> mutate -> save ->
                // broadcast ordering NativeBmwDspValues.update() documents.
                persistedAtBroadcastTime = NativeBmwDspValues.load(context)[NativeBmwDspValues.INDEX_HEADROOM]
            }
        }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))

        NativeBmwDspValues.update(context) { values -> values[NativeBmwDspValues.INDEX_HEADROOM] = -7f }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(-7f, persistedAtBroadcastTime)
    }
}
