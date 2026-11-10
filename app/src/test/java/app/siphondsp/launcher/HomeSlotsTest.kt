package app.siphondsp.launcher

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeSlotsTest {
    /** Slots saved by the old nine-icon grid are cleared, so a tile never opens an unrelated app. */
    @Test
    fun oldGridSlotsAreClearedForTheTiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("launcher_home", Context.MODE_PRIVATE).edit {
            for (i in 0 until 9) putString("slot_$i", "app:com.example/com.example.Main$i")
            putBoolean("defaults_done", true)
        }
        val slots = HomeSlots.get(context)

        assertEquals(List(HomeSlots.SLOTS) { SlotTarget.Empty }, slots.slots.value)
        // And the tile defaults can fill them again.
        val waze = AppEntry("Waze", android.content.ComponentName("com.waze", "com.waze.Main"), null)
        slots.fillDefaults(listOf(waze))
        assertEquals(SlotTarget.App(waze.component), slots.slots.value[0])
    }
}
