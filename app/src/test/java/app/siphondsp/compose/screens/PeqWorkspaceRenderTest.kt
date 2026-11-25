package app.siphondsp.compose.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.drawToBitmap
import app.siphondsp.compose.nav.AppNavigator
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.fragment.PeqGraphPreferences
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.view.DspDestination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class PeqWorkspaceRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var holder: PeqStateHolder

    @Before
    fun openWorkspace() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val state = BmwPeqState.empty().copy(enabled = true)
        state.fullRangeBands.add(ParametricEqBand(648.0, -3.5, 1.4))
        state.fullRangeBands.add(ParametricEqBand(1100.0, -3.0, 1.15))
        assertTrue(state.persist(activity))
        holder = PeqStateHolder(activity)
        activity.setContent {
            BmwDspTheme {
                CompositionLocalProvider(LocalAppNavigator provides object : AppNavigator {
                    override fun open(route: String) = Unit
                    override fun openWorkspace(destination: DspDestination, page: Int) = Unit
                    override fun back() = Unit
                    override fun home() = Unit
                }) { PeqWorkspaceLayout(holder, showSpectrum = false) }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun closeWorkspace() {
        controller.pause().stop().destroy()
    }

    @Test
    fun selectingThenClosingFilterRestoresFullWidthGraphAndKeepsBezelClearance() {
        compose.onNodeWithTag("peq-filter-editor").assertDoesNotExist()
        val width = compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithText("01").performClick()
        compose.onNodeWithTag("peq-filter-editor").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot.width < width)
        saveRender("selected-filter")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("peq-filter-editor").assertDoesNotExist()
        assertEquals(width, compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot.width, 1f)
        val workspace = compose.onNodeWithTag("peq-workspace").fetchSemanticsNode().boundsInRoot
        val filterBar = compose.onNodeWithTag("peq-filter-bar").fetchSemanticsNode().boundsInRoot
        assertTrue(workspace.bottom - filterBar.bottom >= 23f)
        saveRender("graph")
    }

    @Test
    fun channelButtonsPersistTheViewAndTheFullFiltersPageRemainsReachable() {
        compose.onNodeWithText("R").performClick().assertIsSelected()
        assertEquals(PeqChannelDisplay.RIGHT, PeqGraphPreferences(controller.get()).channelDisplay)
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("FREQUENCY").assertIsDisplayed()
        compose.onNodeWithText("648 Hz").assertIsDisplayed()
        saveRender("filters")
        compose.onNodeWithText("Graph").performClick()
        compose.onNodeWithTag("peq-response-graph").assertIsDisplayed()
        compose.onNodeWithTag("peq-filter-editor").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun compactEditorLeavesAtLeastHalfTheGraphAreaAndBankControlsRemainReachable() {
        val graph = compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("High").assertIsDisplayed()
        compose.onNodeWithText("01").performClick()
        val selectedGraph = compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot
        assertTrue(selectedGraph.height >= graph.height * 0.5f)
        compose.onNodeWithTag("peq-filter-editor").assertIsDisplayed()
        saveRender("compact-selected")
    }

    private fun saveRender(name: String) {
        val view = controller.get().window.decorView
        val bitmap = view.drawToBitmap()
        val directory = File("build/reports/tests/peq-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
