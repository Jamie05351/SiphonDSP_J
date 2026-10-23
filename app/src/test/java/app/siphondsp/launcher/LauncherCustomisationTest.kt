package app.siphondsp.launcher

import android.content.ComponentName
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherCustomisationTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    // A fresh instance per test: the app-wide one would keep the previous test's Application.
    private val look = LauncherCustomisation(context)
    private val waze = SlotTarget.App(ComponentName("com.waze", "com.waze.Main"))

    /**
     * Waits for [job] while running the main looper: the job finishes by posting its result to the
     * main thread, so blocking that thread instead would never let it end.
     */
    private fun await(job: Job) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun backupRestoresSlotsAndHiddenApps() {
        val slots = listOf(waze, SlotTarget.Car(CarFunction.SETTINGS)) + List(7) { SlotTarget.Empty }
        look.setHidden(waze.encode(), true)
        val file = File(context.cacheDir, "layout.json")
        var saved = false
        await(look.export(slots, Uri.fromFile(file)) { saved = it })
        assertTrue(saved)

        look.setHidden(waze.encode(), false)
        var restored: List<SlotTarget>? = null
        var ok = false
        await(look.import(Uri.fromFile(file), { restored = it }) { ok = it })

        assertTrue(ok)
        assertEquals(slots, restored)
        assertTrue(waze.encode() in look.look.value.hidden)
    }

    @Test
    fun aFileThatIsNotABackupChangesNothing() {
        look.setHidden(waze.encode(), true)
        val file = File(context.cacheDir, "not-a-layout.json").apply { writeText("""{"hello":"world"}""") }
        var restored: List<SlotTarget>? = null
        var ok = true
        await(look.import(Uri.fromFile(file), { restored = it }) { ok = it })

        assertFalse(ok)
        assertEquals(null, restored)
        assertTrue(waze.encode() in look.look.value.hidden)
    }
}
