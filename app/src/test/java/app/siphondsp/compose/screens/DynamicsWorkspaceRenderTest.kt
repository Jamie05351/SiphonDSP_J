package app.siphondsp.compose.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.drawToBitmap
import app.siphondsp.compose.nav.AppNavigator
import app.siphondsp.compose.nav.DynamicsWorkspace
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.view.DspDestination
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Dynamics: Overview, one Bands page with a band selector, Limiters -- head unit and phone landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class DynamicsWorkspaceRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null
    private val navigator = object : AppNavigator {
        override fun open(route: String) = Unit
        override fun openWorkspace(destination: DspDestination, page: Int) = Unit
        override fun back() = Unit
        override fun home() = Unit
    }

    private fun open() {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        c.get().setContent {
            BmwDspTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) { DynamicsWorkspace() } }
        }
        compose.waitForIdle()
    }

    @After
    fun close() {
        controller?.pause()?.stop()?.destroy()
    }

    /** Moves the pager on: the head unit has named tabs, a phone taps the › arrow. */
    private fun next(tab: String, headUnit: Boolean) {
        if (headUnit) compose.onNodeWithText(tab).performClick()
        else compose.onNodeWithText("›").performClick()
        compose.waitForIdle()
    }

    private fun walkThePages(prefix: String, headUnit: Boolean) {
        open()
        compose.onAllNodesWithText("MULTIBAND")[0].assertExists()
        saveRender("$prefix-overview")
        next("BANDS", headUnit)
        compose.onNodeWithText("B3").performClick()
        compose.onAllNodesWithText("Threshold")[0].assertExists()
        compose.onNodeWithText("MEDIUM").assertExists()
        saveRender("$prefix-bands")
        next("LIMITERS", headUnit)
        compose.onAllNodesWithText("not limiting")[0].assertExists()
        saveRender("$prefix-limiters")
    }

    @Test
    fun headUnitHasThreeTabsAndOneBandsPage() {
        walkThePages("dynamics", headUnit = true)
        compose.onNodeWithText("OVERVIEW").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp-land-mdpi")
    fun phoneLandscapeReachesEveryBand() = walkThePages("phone-dynamics", headUnit = false)

    private fun saveRender(name: String) {
        val bitmap = controller!!.get().window.decorView.drawToBitmap()
        val directory = File("build/reports/tests/dynamics-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
