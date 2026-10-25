package app.siphondsp.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class AppDockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = AppDock.hide(context)

    @Test
    fun opensWithASlotTileEachThenShrinksToTheTab() {
        ShadowSettings.setCanDrawOverlays(true)
        val slots = HomeSlots.get(context)
        slots.setAll(
            listOf(
                SlotTarget.App(ComponentName("a.one", "a.one.Main")), SlotTarget.Car(CarFunction.SETTINGS), SlotTarget.Empty,
                SlotTarget.App(ComponentName("a.two", "a.two.Main")), SlotTarget.Empty, SlotTarget.App(ComponentName("a.three", "a.three.Main")),
            ),
        )

        AppDock.show(context)
        val panel = windows().single() as ViewGroup
        assertEquals("one tile per filled slot", 4, panel.childCount)
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertTrue("fits below the banner", panel.measuredHeight <= 480 - 48)
        File("build/launcher-renders").apply { mkdirs() }.resolve("app-dock.png").outputStream().use {
            panel.drawToBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        val tab = windows().single() as ViewGroup
        assertEquals("shrunk to the tab", 1, tab.childCount)

        AppDock.hide(context)
        assertTrue(windows().isEmpty())
    }

    @Test
    fun aTouchOnATileKeepsItOpen() {
        ShadowSettings.setCanDrawOverlays(true)
        HomeSlots.get(context).setAll(listOf(SlotTarget.Car(CarFunction.SETTINGS), SlotTarget.App(ComponentName("a.one", "a.one.Main"))))
        AppDock.show(context)
        val panel = windows().single() as ViewGroup
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        // Pressed on the first tile just before it would have shrunk.
        val tile = panel.getChildAt(0)
        val x = tile.left + tile.width / 2f
        val y = tile.top + tile.height / 2f
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        panel.dispatchTouchEvent(down)
        down.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertSame("still open past the original 5 s", panel, windows().single())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("then shrunk to the tab", 1, (windows().single() as ViewGroup).childCount)
    }

    @Test
    fun noSidebarWithNothingToOffer() {
        ShadowSettings.setCanDrawOverlays(true)
        HomeSlots.get(context).setAll(emptyList())
        AppDock.show(context)
        assertTrue(windows().isEmpty())
    }

    @Test
    fun staysAwayWithoutTheOverlayPermission() {
        ShadowSettings.setCanDrawOverlays(false)
        AppDock.show(context)
        assertTrue(windows().isEmpty())
    }

    private fun windows(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java)).views
}
