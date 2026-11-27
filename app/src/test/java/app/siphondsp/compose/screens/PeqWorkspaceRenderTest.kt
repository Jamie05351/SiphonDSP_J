package app.siphondsp.compose.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.siphondsp.compose.dialogs.AppDialog
import app.siphondsp.compose.dialogs.AppDialogs
import app.siphondsp.compose.nav.DspWorkspaceScaffold
import androidx.compose.ui.geometry.Offset
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
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.compose.state.GraphDbRangeState
import app.siphondsp.view.PeqPlotGeometry
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
    private lateinit var inputMode: InputModeManager
    private val navigator = object : AppNavigator {
        override fun open(route: String) = Unit
        override fun openWorkspace(destination: DspDestination, page: Int) = Unit
        override fun back() = Unit
        override fun home() = Unit
    }

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
            inputMode = LocalInputModeManager.current
            BmwDspTheme {
                CompositionLocalProvider(LocalAppNavigator provides navigator) { PeqWorkspaceLayout(holder, showSpectrum = false) }
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
        compose.onNodeWithContentDescription("Close filter").performClick()
        compose.onNodeWithTag("peq-filter-editor").assertDoesNotExist()
        assertEquals(width, compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot.width, 1f)
        val workspace = compose.onNodeWithTag("peq-workspace").fetchSemanticsNode().boundsInRoot
        val filterBar = compose.onNodeWithTag("peq-filter-bar").fetchSemanticsNode().boundsInRoot
        // The filter bar took over the old 24dp bottom clearance: 64dp chips, a small inset kept.
        assertEquals(64f, filterBar.height, 1f)
        assertTrue(workspace.bottom - filterBar.bottom >= 3f)
        saveRender("graph")
    }

    @Test
    fun inspectorFitsTheGraphRowAndBypassDimsTheFilter() {
        compose.onNodeWithText("02").performClick()
        val editor = compose.onNodeWithTag("peq-filter-editor").fetchSemanticsNode().boundsInRoot
        val graph = compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot
        assertEquals(300f, editor.width, 1f)
        // Everything fits the head unit's graph row: the last action row is inside the editor.
        val copy = compose.onNodeWithText("Copy to…").fetchSemanticsNode().boundsInRoot
        assertTrue("actions must fit without scrolling", copy.bottom <= editor.bottom)
        assertEquals(graph.height, editor.height, 1f)
        compose.onNodeWithContentDescription("Filter on, tap to bypass").performClick()
        compose.waitForIdle()
        assertTrue(!holder.peqState.fullRangeBands[1].enabled)
        assertTrue(holder.canUndo)
        saveRender("selected-filter-bypassed")
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitForIdle()
        assertTrue(holder.peqState.fullRangeBands[1].enabled)
        compose.onNodeWithContentDescription("Previous filter").performClick()
        compose.onNodeWithText("Filter 01").assertIsDisplayed()
    }

    @Test
    fun nodesFadeAfterFiveIdleSecondsAndInteractionRestartsTheTimer() {
        compose.onNodeWithText("01").performClick()
        compose.onNodeWithTag("peq-response-graph").performTouchInput { click(Offset(10f, 100f)) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(4900)
            assertTrue("Nodes must remain visible before five seconds", nodeOverlayPixels() > 20)
            compose.mainClock.advanceTimeBy(600)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals("Nodes must disappear when the combined response takes over", 0, nodeOverlayPixels())

            compose.onNodeWithTag("peq-response-graph").performTouchInput { click(Offset(10f, 100f)) }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            assertTrue("Touching the graph must restore nodes", nodeOverlayPixels() > 20)
            compose.mainClock.advanceTimeBy(4900)
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { holder.previewBandEdit(0, "idle-reset") { it.withGain(it.gain - 0.5) } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(600)
            assertTrue("Editing must restart the five-second timer", nodeOverlayPixels() > 20)
            compose.mainClock.advanceTimeBy(5000)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals("Nodes must fade again after the edit becomes idle", 0, nodeOverlayPixels())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun nodeOverlayPixels(): Int {
        val bitmap = graphBitmap()
        val range = GraphDbRangeState.read(controller.get())
        val geometry = PeqPlotGeometry(36f, bitmap.width - 12f, 24f, bitmap.height - 30f,
            20000.0, range.bottomDb, range.topDb)
        val band = holder.visibleBands[0]
        val x = geometry.xForFrequency(band.frequency).roundToInt()
        val y = geometry.yForGain(band.gain).roundToInt()
        var count = 0
        for (py in y - 14..y + 14) for (px in x - 14..x + 14) {
            if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) continue
            val color = bitmap.getPixel(px, py)
            if (android.graphics.Color.green(color) > 130 && android.graphics.Color.blue(color) > 90 &&
                android.graphics.Color.red(color) < 150) count++
        }
        bitmap.recycle()
        return count
    }

    private fun settleToCombinedResponse() {
        val autoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(5500)
        compose.mainClock.autoAdvance = autoAdvance
        compose.waitForIdle()
    }

    @Test
    fun combinedResponseTracksCrossoversAndDelayWhileEditingFilters() {
        compose.onNodeWithText("L").performClick()
        compose.onNodeWithText("01").performClick()
        val values = NativeBmwDspValues.load(controller.get()).apply {
            this[NativeBmwDspValues.INDEX_HEADROOM_AUTO] = 0f
            this[NativeBmwDspValues.INDEX_HEADROOM] = 0f
            this[NativeBmwDspValues.INDEX_CROSSOVER_BYPASS] = 0f
            this[NativeBmwDspValues.INDEX_HIGH_XO_PASS] = 1f
            this[NativeBmwDspValues.INDEX_LOW_DELAY_L] = 0f
            this[NativeBmwDspValues.INDEX_LOW_DELAY_R] = 0f
            this[NativeBmwDspValues.INDEX_MID_DELAY_L] = 0f
            this[NativeBmwDspValues.INDEX_MID_DELAY_R] = 0f
            repeat(4) { output ->
                this[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 150f
                this[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] = 2f
                this[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_MUTE)] = 0f
                this[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)] = 0f
            }
        }
        compose.runOnIdle { AppEvents.send(AppEvent.NativeBmwDspUpdated(values.copyOf())) }
        settleToCombinedResponse()
        val before = graphBitmap()
        values[NativeBmwDspValues.INDEX_MID_DELAY_L] = 3f
        values[NativeBmwDspValues.INDEX_MID_DELAY_R] = 3f
        compose.runOnIdle { AppEvents.send(AppEvent.NativeBmwDspUpdated(values.copyOf())) }
        val delayed = graphBitmap()
        saveRender("combined-response-delayed")
        assertMainCurveMatchesCombinedResponse(delayed, values)
        assertTrue("Branch delay must change the main purple combined response",
            !responsePixels(before).contentEquals(responsePixels(delayed)))
        values[NativeBmwDspValues.outputIndex(0, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 400f
        values[NativeBmwDspValues.outputIndex(1, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 400f
        compose.runOnIdle { AppEvents.send(AppEvent.NativeBmwDspUpdated(values.copyOf())) }
        val crossed = graphBitmap()
        assertMainCurveMatchesCombinedResponse(crossed, values)
        assertTrue("Crossover edits must change the main purple combined response",
            !responsePixels(delayed).contentEquals(responsePixels(crossed)))
        saveRender("combined-response")
        compose.onNodeWithText("R").performClick()
        val right = graphBitmap()
        assertMainCurveMatchesCombinedResponse(right, values, BmwOutputChannel.RIGHT)
        before.recycle()
        delayed.recycle()
        crossed.recycle()
        right.recycle()
        saveRender("combined-response-right")
    }

    private fun assertMainCurveMatchesCombinedResponse(
        bitmap: android.graphics.Bitmap,
        values: FloatArray,
        channel: BmwOutputChannel = BmwOutputChannel.LEFT,
    ) {
        val curves = BmwResponseCurves(192)
        BmwResponseCalculator(192).apply {
            configureAxis(48000.0, 20.0, 20000.0)
            compute(values, holder.peqState, curves)
        }
        val range = GraphDbRangeState.read(controller.get())
        val left = channel.ordinal
        val sample = curves.frequencies.indices.filter { i ->
            curves.frequencies[i] in 80.0..500.0 &&
                curves.sumDb[left][i] in (range.bottomDb + 4)..(range.topDb - 4)
        }.maxBy { kotlin.math.abs(curves.sumDb[left][it] - curves.preSplitDb[left][it]) }
        assertTrue("Fixture must distinguish the combined response from Pre EQ",
            kotlin.math.abs(curves.sumDb[left][sample] - curves.preSplitDb[left][sample]) > 3.0)
        val geometry = PeqPlotGeometry(36f, bitmap.width - 12f, 24f, bitmap.height - 30f,
            20000.0, range.bottomDb, range.topDb)
        val x = geometry.xForFrequency(curves.frequencies[sample]).roundToInt()
        val y = geometry.yForGain(curves.sumDb[left][sample]).roundToInt()
        assertTrue("Main $channel curve must follow the full crossover/delay sum, not Pre EQ: " +
            "f=${curves.frequencies[sample]} gain=${curves.sumDb[left][sample]} x=$x y=$y size=${bitmap.width}x${bitmap.height}",
            (y - 4..y + 4).any { py -> (x - 3..x + 3).any { px ->
                if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) false else {
                    val color = bitmap.getPixel(px, py)
                    if (channel == BmwOutputChannel.LEFT) {
                        android.graphics.Color.red(color) > 100 && android.graphics.Color.blue(color) > 170 &&
                            android.graphics.Color.green(color) < 150
                    } else {
                        android.graphics.Color.green(color) > 170 && android.graphics.Color.red(color) < 140 &&
                            android.graphics.Color.blue(color) < 140
                    }
                }
            } })
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
        swipeToFilters()
        compose.onNodeWithText("FREQUENCY").assertIsDisplayed()
        compose.onNodeWithText("648 Hz").assertIsDisplayed()
        saveRender("filters")
        swipeToGraph()
        compose.onNodeWithTag("peq-response-graph").assertIsDisplayed()
        compose.onNodeWithTag("peq-filter-editor").assertDoesNotExist()
    }

    @Test
    fun addedBandUsesTheGraphNumberInItsChipAndEditor() {
        compose.onNodeWithText("Low").performClick()
        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("03").assertIsDisplayed()
        compose.onNodeWithText("Filter 3").assertIsDisplayed()
        swipeToFilters()
        compose.onNodeWithText("3").assertIsDisplayed()
        listOf("frequency", "gain", "Q").forEach { parameter ->
            compose.onNodeWithContentDescription("Edit Filter 3 $parameter").assertIsDisplayed()
        }
    }

    @Test
    fun selectingAClippedRowRevealsTheWholeRow() {
        compose.runOnIdle {
            repeat(8) { holder.addBand() }
            holder.selectedUuid = null
        }
        swipeToFilters()
        val row = compose.onNodeWithTag("peq-filter-row-1")
        val original = row.fetchSemanticsNode().boundsInRoot
        row.onParent()
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 28f) }
        compose.waitForIdle()
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height < original.height)
        compose.runOnIdle { holder.selectedUuid = holder.visibleBands.first().uuid }
        compose.waitForIdle()
        assertEquals(original.height, row.fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }

    @Test
    fun wideTableSeparatesAdjacentParameterSteppers() {
        swipeToFilters()
        val plus = compose.onNodeWithContentDescription("Increase Filter 1 frequency").fetchSemanticsNode()
        val minus = compose.onNodeWithContentDescription("Decrease Filter 1 gain").fetchSemanticsNode()
        assertEquals(20f, minus.boundsInRoot.left - plus.boundsInRoot.right, 1f)
    }

    @Test
    fun adjustingAnotherVisibleFilterKeepsItsRowInPlace() {
        compose.runOnIdle {
            repeat(8) { holder.addBand() }
            holder.selectedUuid = holder.visibleBands.first().uuid
        }
        swipeToFilters()
        val row = compose.onNodeWithTag("peq-filter-row-3")
        val top = row.fetchSemanticsNode().boundsInRoot.top
        val gain = holder.visibleBands[2].gain
        compose.onNodeWithContentDescription("Increase Filter 3 gain").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(gain + 0.5, holder.visibleBands[2].gain, 0.001)
        assertEquals(top, row.fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }

    @Test
    fun tapsNearTheValueStillStepTheFilter() {
        swipeToFilters()
        val row = compose.onNodeWithTag("peq-filter-row-1")
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val decrease = compose.onNodeWithContentDescription("Decrease Filter 1 gain").fetchSemanticsNode().boundsInRoot
        val increase = compose.onNodeWithContentDescription("Increase Filter 1 gain").fetchSemanticsNode().boundsInRoot

        try {
            row.performTouchInput { click(Offset(decrease.left + 46f - rowBounds.left, center.y)) }
            assertEquals(-4.0, holder.visibleBands[0].gain, 0.001)
            row.performTouchInput { click(Offset(increase.right - 46f - rowBounds.left, center.y)) }
            assertEquals(-3.5, holder.visibleBands[0].gain, 0.001)
            compose.runOnIdle { assertEquals(null, AppDialogs.current) }

            compose.onNodeWithContentDescription("Edit Filter 1 gain").performTouchInput { click() }
            compose.runOnIdle {
                assertEquals("Gain", (AppDialogs.current as? AppDialog.NumberInput)?.title)
            }
        } finally {
            compose.runOnIdle { AppDialogs.dismiss() }
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
    @Config(qualifiers = "w600dp-h480dp-land-mdpi")
    fun compactSteppersLeaveRoomForLongValuesAtTheirLowerWidthBoundary() {
        compose.runOnIdle {
            holder.commitBandEdit(0, "compact-long-values") {
                it.withFrequency(999.99).withGain(-29.5).withQ(29.99)
            }
        }
        assertFilterButtonsFitWidth(minTouchWidth = 35f)
        listOf("999.99", "-29.5", "29.99").forEach { value ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$value must fit beside the compact steppers", layouts.isNotEmpty() && layouts.all { layout ->
                value.indices.all { character ->
                    val glyph = layout.getBoundingBox(character)
                    glyph.left >= -0.5f && glyph.right <= layout.size.width + 0.5f
                }
            })
        }
        saveRender("filters-compact-long-values")
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun mediumWidthFiltersKeepEveryParameterButtonInsideThePage() {
        assertFilterButtonsFitWidth()
        saveRender("filters-medium")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phoneFiltersKeepEveryParameterButtonInsideThePage() {
        assertFilterButtonsFitWidth(showSteppers = false)
        saveRender("filters-phone")
    }

    private fun assertFilterButtonsFitWidth(showSteppers: Boolean = true, minTouchWidth: Float = 47f) {
        swipeToFilters()
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
                val requiredWidth = if (action == "Edit") minOf(minTouchWidth, 43f) else minTouchWidth
                assertTrue("$action $parameter touch width", bounds.width >= requiredWidth)
                if (action != "Edit") {
                    assertTrue("$action $parameter touch height", bounds.height >= 47f)
                }
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

    @Test
    fun dpadLeftFromTheFiltersBoundaryReturnsToTheGraph() {
        swipeToFilters()
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val channel = compose.onAllNodesWithText("L+R")[0]
        channel.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        channel.assertIsFocused()
        channel.performKeyInput { pressKey(Key.DirectionRight) }
        val type = compose.onAllNodesWithText("PK")[0]
        type.assertIsFocused()
        type.performKeyInput { pressKey(Key.DirectionLeft) }
        channel.assertIsFocused()
        compose.onNodeWithTag("peq-filter-table").assertIsDisplayed()
        channel.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithTag("peq-response-graph").assertIsDisplayed()
        compose.onNodeWithTag("peq-filter-table").assertIsNotDisplayed()
    }

    @Test
    fun dpadLeftFromAddFilterReturnsToTheGraph() {
        swipeToFilters()
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val add = compose.onNodeWithText("+ Add filter")
        add.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        add.assertIsFocused()
        add.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithTag("peq-response-graph").assertIsDisplayed()
    }

    private fun swipeToFilters() {
        compose.onNodeWithTag("peq-pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    private fun swipeToGraph() {
        compose.onNodeWithTag("peq-pages").performTouchInput { swipeRight() }
        compose.waitForIdle()
    }

    @Test
    fun swipeNavigationReclaimsTheFiltersHeaderAndKeepsTheBlueFrame() {
        compose.onNodeWithTag("peq-open-filters").assertDoesNotExist()
        val graph = compose.onNodeWithTag("peq-response-graph").fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("peq-content-frame").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = controller.get().window.decorView.drawToBitmap()
            assertEquals(android.graphics.Color.rgb(10, 142, 223),
                bitmap.getPixel(frame.center.x.roundToInt(), frame.top.roundToInt() + 1))
            bitmap.recycle()
        }
        swipeToFilters()
        compose.onNodeWithTag("peq-open-graph").assertDoesNotExist()
        val table = compose.onNodeWithTag("peq-filter-table").fetchSemanticsNode().boundsInRoot
        assertEquals("The filter table must start directly below the toolbar", graph.top, table.top, 1f)
        assertTrue("The removed navigation row must become usable table space", table.height >= graph.height + 43f)
        saveRender("filters-no-navigation")
        swipeToGraph()
        compose.onNodeWithTag("peq-response-graph").assertIsDisplayed()
    }

    @Test
    fun headUnitSidebarsHaveIdenticalGeometryAcrossAllSubmenus() = assertSharedSidebarGeometry()

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phoneSidebarsHaveIdenticalGeometryAcrossAllSubmenus() = assertSharedSidebarGeometry()

    private fun assertSharedSidebarGeometry() {
        val peq = compose.onNodeWithTag("dsp-sidebar").fetchSemanticsNode().boundsInRoot
        DspDestination.entries.filter { it != DspDestination.PARAMETRIC_EQ }.forEach { destination ->
            compose.runOnIdle {
                controller.get().setContent {
                    BmwDspTheme {
                        CompositionLocalProvider(LocalAppNavigator provides navigator) {
                            DspWorkspaceScaffold(destination, {}, {}, showStatusStrip = false) {
                                Box(Modifier.fillMaxSize().testTag("test-workspace-content"))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            assertEquals("$destination must match the PEQ sidebar", peq,
                compose.onNodeWithTag("dsp-sidebar").fetchSemanticsNode().boundsInRoot)
        }
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
