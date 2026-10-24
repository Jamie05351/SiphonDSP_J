package app.siphondsp.compose.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.drawToBitmap
import app.siphondsp.compose.home.GlobalStage
import app.siphondsp.compose.home.HomeEngineState
import app.siphondsp.compose.home.HomeStage
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.launcher.AppEntry
import app.siphondsp.launcher.CarFunction
import app.siphondsp.launcher.SlotTarget
import app.siphondsp.launcher.Track
import app.siphondsp.view.LevelReadout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the launcher home screen at the head unit's 1280 x 480 in its three states (home, chain
 * raised, app drawer) and saves each as a PNG under build/launcher-renders for a visual check. The
 * assertions only check that something was drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class LauncherHomeRenderTest {
    private val engine = HomeEngineState(
        allPassOutputs = 0, tiltDb = 1f, mbcBands = 4, limiterDb = -1f, masterVolumeDb = -8f,
    )
    private val levels = LevelReadout(leftRmsDb = -9.6f, leftPeakDb = -7.2f, rightRmsDb = -10.6f, rightPeakDb = -7.8f)

    private val apps = listOf(
        "Waze" to 0xFF33CCFF, "Spotify" to 0xFF1ED760, "Google Maps" to 0xFF4285F4, "Drive" to 0xFFFABE1E,
        "MiXplorer" to 0xFFF57828, "KSW Toolkit 3" to 0xFF286EE6, "Play Store" to 0xFF00B48C, "Radio" to 0xFFE63C3C,
    ).map { (label, colour) -> AppEntry(label, ComponentName("test.$label", "test.Main"), icon(colour.toInt())) }

    private val slots = listOf(
        SlotTarget.App(apps[0].component), SlotTarget.App(apps[1].component), SlotTarget.App(apps[2].component),
        SlotTarget.Car(CarFunction.SETTINGS), SlotTarget.App(apps[3].component), SlotTarget.App(apps[4].component),
        SlotTarget.App(apps[5].component), SlotTarget.App(apps[6].component), SlotTarget.Empty,
    )

    @Test
    fun home() = render("home", expanded = false, overlay = LauncherOverlay.None)

    @Test
    fun chainRaised() = render("chain-raised", expanded = true, overlay = LauncherOverlay.None)

    @Test
    fun drawer() = render("drawer", expanded = false, overlay = LauncherOverlay.Drawer())

    @Test
    fun carMenu() = render("car-menu", expanded = false, overlay = LauncherOverlay.CarMenu())

    /**
     * The iDrive knob's first press and two turns, saved for a visual check of the focus ring. Not
     * asserted: in Robolectric's shared environment, focus requested after the first frame isn't
     * reliably delivered once other render tests have run, so an assertion here is flaky.
     */
    @Test
    fun knobFocus() = render("knob-focus", expanded = false, overlay = LauncherOverlay.None, knob = 1)

    private fun render(name: String, expanded: Boolean, overlay: LauncherOverlay, knob: Int = 0) {
        // Out of touch mode, as after a knob press, and with window focus, as on a real screen, so
        // knob (D-pad) focus can be taken and drawn.
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(knob == 0)
        try {
            renderIn(name, expanded, overlay, knob)
        } finally {
            // Touch mode is global to the instrumentation; put it back for the next test.
            instrumentation.setInTouchMode(true)
        }
    }

    private fun renderIn(name: String, expanded: Boolean, overlay: LauncherOverlay, knob: Int) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true)
        val activity = controller.get()
        activity.setContent {
            // As in the car: the knob's first press arrives after the home screen is up.
            var knobRequests by remember { mutableIntStateOf(0) }
            if (knob > 0) LaunchedEffect(Unit) { delay(500); knobRequests = 1 }
            BmwDspTheme {
                LauncherHome(
                    engine = engine, powered = true, readout = { levels }, meterLevels = { levels },
                    apps = apps, slots = slots, carFunctions = CarFunction.entries,
                    expanded = expanded, onExpandedChange = {}, overlay = overlay, onOverlayChange = {},
                    openingStage = null, animateTiles = false, actions = NoActions,
                    track = Track("Blinding Lights", "The Weeknd", icon(0xFF8E2DE2.toInt()), playing = true, packageName = "test.music"),
                    nowPlayingAllowed = false,
                    knobFocusRequests = knobRequests,
                )
            }
        }
        // Let enter animations and the power sweep finish.
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        if (knob > 0) {
            // The first press came in above; now two knob turns move focus on.
            repeat(2) {
                activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
            }
        }
        val bitmap = root.drawToBitmap()
        val out = File("build/launcher-renders").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width >= 1280 && bitmap.height >= 480)
        // Closed, so nothing from this screen (its window focus) carries into the next test.
        controller.pause().stop().destroy()
    }

    private fun icon(colour: Int) = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRoundRect(0f, 0f, 96f, 96f, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour })
    }.asImageBitmap()

    private object NoActions : LauncherActions {
        override fun togglePower() {}
        override fun openStage(stage: HomeStage) {}
        override fun openGlobal(stage: GlobalStage) {}
        override fun dspSettings() {}
        override fun more(bounds: Rect) {}
        override fun volumeStep(steps: Int) {}
        override fun toggleMute() {}
        override fun launchApp(app: AppEntry) {}
        override fun runCar(function: CarFunction) {}
        override fun assignSlot(index: Int, target: SlotTarget) {}
        override fun swapSlots(a: Int, b: Int) {}
        override fun appInfo(app: AppEntry) {}
        override fun androidSettings() {}
        override fun homeAppSettings() {}
        override fun homeFull() {}
        override fun pickIcon(key: String) {}
        override fun resetIcon(key: String) {}
        override fun setHidden(app: AppEntry, hidden: Boolean) {}
        override fun pickWallpaper() {}
        override fun resetWallpaper() {}
        override fun backUpLayout() {}
        override fun restoreLayout() {}
        override fun allowNowPlaying() {}
        override fun mediaPlayPause() {}
        override fun mediaNext() {}
        override fun mediaPrevious() {}
        override fun openTrackApp(track: Track) {}
    }
}
