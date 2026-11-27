package app.siphondsp.compose.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.drawToBitmap
import app.siphondsp.compose.nav.AlignmentWorkspace
import app.siphondsp.compose.nav.AppNavigator
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.nav.Routes
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

/** The Align workspace on the 1280x480 head unit: Delay, Phase (Band Align) and its all-pass view. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class AlignWorkspaceRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null
    private val navigator = object : AppNavigator {
        override fun open(route: String) = Unit
        override fun openWorkspace(destination: DspDestination, page: Int) = Unit
        override fun back() = Unit
        override fun home() = Unit
    }

    private fun open(page: Int) {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        c.get().setContent {
            BmwDspTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) { AlignmentWorkspace(page) } }
        }
        compose.waitForIdle()
    }

    @After
    fun close() {
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun delayPageHasThreeTabsAndMonoBassMovesIntoMore() {
        open(0)
        compose.onNodeWithText("PHASE").assertIsDisplayed()
        compose.onNodeWithText("CENTRE").assertIsDisplayed()
        compose.onNodeWithText("LEFT").assertIsDisplayed()
        saveRender("align-delay")
        compose.onNodeWithContentDescription("More delay settings").performClick()
        compose.onNodeWithText("MONO BASS").assertIsDisplayed()
    }

    @Test
    fun phasePageOpensAllPassAndComesBack() {
        open(Routes.ALIGNMENT_PAGE_PHASE)
        compose.onNodeWithText("Band Align").assertIsDisplayed()
        saveRender("align-phase")
        compose.onNodeWithText("All-pass…").performClick()
        compose.onNodeWithText("‹ Back").assertIsDisplayed()
        saveRender("align-allpass")
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("Band Align").assertIsDisplayed()
    }

    @Test
    fun homeAllPassShortcutOpensPhaseOnAllPass() {
        open(Routes.ALIGNMENT_PAGE_ALLPASS)
        compose.onNodeWithText("‹ Back").assertIsDisplayed()
    }

    private fun saveRender(name: String) {
        val bitmap = controller!!.get().window.decorView.drawToBitmap()
        val directory = File("build/reports/tests/align-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
