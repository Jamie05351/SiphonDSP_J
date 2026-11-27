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
import app.siphondsp.compose.nav.OutputWorkspace
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

/** Output: the gain budget page, Convolver and Deep bass -- head unit and phone landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class OutputWorkspaceRenderTest {
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
            BmwDspTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) { OutputWorkspace() } }
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
        compose.onNodeWithText("GAIN BUDGET").assertExists()
        compose.onNodeWithText("clean").assertExists()
        compose.onAllNodesWithText("Post gain R")[0].assertExists()
        saveRender("$prefix-output")
        next("CONVOLVER", headUnit)
        compose.onAllNodesWithText("STEREO")[0].assertExists()
        saveRender("$prefix-convolver")
        next("DEEP BASS", headUnit)
        compose.onAllNodesWithText("Strength")[0].assertExists()
        saveRender("$prefix-deepbass")
    }

    @Test
    fun headUnitWalksTheThreePages() {
        walkThePages("output", headUnit = true)
        compose.onNodeWithText("OUTPUT").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp-land-mdpi")
    fun phoneLandscapeWalksTheThreePages() = walkThePages("phone-output", headUnit = false)

    private fun saveRender(name: String) {
        val bitmap = controller!!.get().window.decorView.drawToBitmap()
        val directory = File("build/reports/tests/output-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
