package app.siphondsp.compose.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import kotlin.math.roundToInt

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
    fun livePreviewRedrawsTheResponseBeforeCommit() {
        compose.onNodeWithText("L").performClick()
        compose.onNodeWithText("01").performClick()
        val before = graphBitmap()
        compose.runOnIdle { holder.previewBandEdit(0, "curve-preview") { it.withGain(-12.0) } }
        val after = graphBitmap()
        assertTrue("A live filter preview must move the response curve before saving",
            !responsePixels(before).contentEquals(responsePixels(after)))
        before.recycle()
        after.recycle()
    }

    @Test
    fun changingFilterParametersRedrawsThePlottedCurve() {
        compose.onNodeWithText("L").performClick()
        compose.onNodeWithText("01").performClick()
        val before = graphBitmap()
        repeat(8) { compose.onNodeWithContentDescription("Increase Gain").performClick() }
        assertEquals(0.5, holder.visibleBands[0].gain, 0.001)
        val after = graphBitmap()
        assertTrue("Gain edits must move the purple response curve, not just the node",
            !responsePixels(before).contentEquals(responsePixels(after)))
        before.recycle()
        after.recycle()
    }

    private fun responsePixels(bitmap: android.graphics.Bitmap): BooleanArray {
        val pixels = BooleanArray(bitmap.width * bitmap.height)
        // Exclude the legend/buttons and axis labels; the first two filter shapes are teal/blue.
        for (y in 60 until bitmap.height - 30) for (x in 36 until bitmap.width - 12) {
            val color = bitmap.getPixel(x, y)
            pixels[y * bitmap.width + x] = android.graphics.Color.red(color) > 100 &&
                android.graphics.Color.blue(color) > 170 && android.graphics.Color.green(color) < 150
        }
        assertTrue("The response curve must be visible", pixels.any { it })
        return pixels
    }

    private fun graphBitmap(): android.graphics.Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: android.graphics.Bitmap
        compose.runOnIdle {
            val full = controller.get().window.decorView.drawToBitmap()
            bitmap = android.graphics.Bitmap.createBitmap(full, bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.width.roundToInt(), bounds.height.roundToInt())
            full.recycle()
        }
        return bitmap
    }

    @Test
    fun channelButtonsPersistTheViewAndTheFullFiltersPageRemainsReachable() {
        compose.onNodeWithText("R").performClick().assertIsSelected()
        assertEquals(PeqChannelDisplay.RIGHT, PeqGraphPreferences(controller.get()).channelDisplay)
        compose.onNodeWithTag("peq-open-filters").performClick()
        compose.onNodeWithText("FREQUENCY").assertIsDisplayed()
        compose.onNodeWithText("648 Hz").assertIsDisplayed()
        saveRender("filters")
        compose.onNodeWithTag("peq-open-graph").performClick()
        compose.onNodeWithTag("peq-response-graph").assertIsDisplayed()
        compose.onNodeWithTag("peq-filter-editor").assertDoesNotExist()
    }

    @Test
    fun addedBandUsesTheGraphNumberInItsChipAndEditor() {
        compose.onNodeWithText("Low").performClick()
        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("03").assertIsDisplayed()
        compose.onNodeWithText("Filter 3").assertIsDisplayed()
        compose.onNodeWithTag("peq-open-filters").performClick()
        compose.onNodeWithText("3").assertIsDisplayed()
        listOf("frequency", "gain", "Q").forEach { parameter ->
            compose.onNodeWithContentDescription("Edit Filter 3 $parameter").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w832dp-h480dp-land-mdpi")
    fun longFilterValuesFitOnTheSmallestFullStepperRow() {
        compose.runOnIdle {
            holder.commitBandEdit(0, "long-values") {
                it.withFrequency(19999.99).withGain(-29.5).withQ(29.99)
            }
        }
        assertFilterButtonsFitWidth()
        saveRender("filters-long-values")
        listOf("19999.99 Hz", "-29.5 dB", "29.99").forEach { value ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            // The paragraph allocation can exceed Text's measured size even when every glyph
            // fits. Check the actual character bounds instead of that paragraph overflow flag.
            assertTrue("$value must fit without clipping", layouts.isNotEmpty() && layouts.all { layout ->
                value.indices.all { character ->
                    val glyph = layout.getBoundingBox(character)
                    glyph.left >= -0.5f && glyph.right <= layout.size.width + 0.5f
                }
            })
        }
        saveRender("filters-long-values")
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun mediumWidthFiltersKeepEveryParameterButtonInsideThePage() {
        assertFilterButtonsFitWidth(minTouchWidth = 31f)
        saveRender("filters-medium")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phoneFiltersKeepEveryParameterButtonInsideThePage() {
        assertFilterButtonsFitWidth(showSteppers = false)
        saveRender("filters-phone")
    }

    private fun assertFilterButtonsFitWidth(showSteppers: Boolean = true, minTouchWidth: Float = 43f) {
        compose.onNodeWithTag("peq-open-filters").performClick()
        val table = compose.onNodeWithTag("peq-filter-table").fetchSemanticsNode().boundsInRoot
        val center = compose.onNodeWithContentDescription("Edit Filter 1 frequency").fetchSemanticsNode().boundsInRoot.center.y
        listOf("frequency", "gain", "Q").forEach { parameter ->
            assertEquals("All parameters must stay on one line", center,
                compose.onNodeWithContentDescription("Edit Filter 1 $parameter").fetchSemanticsNode().boundsInRoot.center.y, 1f)
            (if (showSteppers) listOf("Decrease", "Edit", "Increase") else listOf("Edit")).forEach { action ->
                val button = compose.onNodeWithContentDescription("$action Filter 1 $parameter")
                button.assertIsDisplayed()
                val bounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue("$action $parameter left edge", bounds.left >= table.left)
                assertTrue("$action $parameter right edge", bounds.right <= table.right)
                assertTrue("$action $parameter touch width", bounds.width >= minTouchWidth)
            }
        }
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
