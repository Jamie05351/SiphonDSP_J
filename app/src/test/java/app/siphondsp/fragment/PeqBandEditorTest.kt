package app.siphondsp.fragment

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PeqBandEditorTest {

    private fun band(
        freq: Double = 1000.0,
        gain: Double = 0.0,
        q: Double = 1.0,
        type: ParametricEqFilterType = ParametricEqFilterType.PEAKING,
        channel: ParametricEqChannel = ParametricEqChannel.LEFT_RIGHT,
    ) = ParametricEqBand(freq, gain, q, type, channel, UUID.randomUUID())

    private fun listOfBands(vararg bands: ParametricEqBand) =
        ParametricEqBandList().apply { bands.forEach { add(it) } }

    private fun fill(n: Int) = ParametricEqBandList().apply {
        repeat(n) { add(band(freq = 100.0 + it)) }
    }

    private fun state(
        full: ParametricEqBandList = ParametricEqBandList(),
        low: ParametricEqBandList = ParametricEqBandList(),
        mid: ParametricEqBandList = ParametricEqBandList(),
    ) = BmwPeqState.empty().copy(fullRangeBands = full, lowBandBands = low, midBandBands = mid)

    // --- copyWholeScope ---------------------------------------------------------------------

    @Test
    fun copyWholeScopeReplaceSwapsDestinationAndReStampsUuids() {
        val a = band(gain = -3.0)
        val original = state(full = listOfBands(a), low = listOfBands(band(), band()))

        val result = PeqBandEditor.copyWholeScope(original, PeqScope.FULL, PeqScope.LOW, append = false)

        result as PeqBandEditResult.Changed
        assertEquals("copy-scope-replace", result.undoSource)
        assertEquals(1, result.candidate.lowBandBands.size)
        assertEquals(-3.0, result.candidate.lowBandBands[0].gain, 0.0)
        assertFalse("copied band must get a fresh id", result.candidate.lowBandBands[0].uuid == a.uuid)
        // source state untouched
        assertEquals(2, original.lowBandBands.size)
    }

    @Test
    fun copyWholeScopeAppendKeepsDestination() {
        val original = state(full = listOfBands(band()), low = listOfBands(band(), band()))
        val result = PeqBandEditor.copyWholeScope(original, PeqScope.FULL, PeqScope.LOW, append = true)
        result as PeqBandEditResult.Changed
        assertEquals("copy-scope-append", result.undoSource)
        assertEquals(3, result.candidate.lowBandBands.size)
    }

    @Test
    fun copyWholeScopeOverflowsWhenCombinedSizeExceedsMax() {
        val original = state(full = fill(10), low = fill(10))
        val result = PeqBandEditor.copyWholeScope(original, PeqScope.FULL, PeqScope.LOW, append = true)
        assertEquals(PeqBandEditResult.Overflow(PeqScope.LOW, alreadyFull = false), result)
    }

    // --- duplicateFilter ------------------------------------------------------------------

    @Test
    fun duplicateFilterInsertsAfterIndexAndSelectsTheCopy() {
        val original = state(full = listOfBands(band(freq = 100.0), band(freq = 200.0), band(freq = 300.0)))
        val result = PeqBandEditor.duplicateFilter(original, PeqScope.FULL, index = 1)
        result as PeqBandEditResult.Changed
        assertEquals(4, result.candidate.fullRangeBands.size)
        assertEquals(200.0, result.candidate.fullRangeBands[2].frequency, 0.0)
        assertEquals(result.candidate.fullRangeBands[2].uuid, result.select)
        assertEquals(3, original.fullRangeBands.size)
    }

    @Test
    fun duplicateFilterOverflowsWhenBankAlreadyFull() {
        val result = PeqBandEditor.duplicateFilter(state(full = fill(16)), PeqScope.FULL, index = 0)
        assertEquals(PeqBandEditResult.Overflow(PeqScope.FULL, alreadyFull = true), result)
    }

    // --- copyFilter ----------------------------------------------------------------------

    @Test
    fun copyFilterMovesOneBandToAnotherBankWithNewId() {
        val src = band(freq = 640.0)
        val original = state(full = listOfBands(src), mid = listOfBands(band()))
        val result = PeqBandEditor.copyFilter(original, PeqScope.FULL, index = 0, to = PeqScope.MID)
        result as PeqBandEditResult.Changed
        assertEquals("copy-filter", result.undoSource)
        assertEquals(2, result.candidate.midBandBands.size)
        assertEquals(640.0, result.candidate.midBandBands[1].frequency, 0.0)
        assertEquals(result.candidate.midBandBands[1].uuid, result.select)
        assertFalse(result.candidate.midBandBands[1].uuid == src.uuid)
    }

    @Test
    fun copyFilterOverflowsWhenTargetBankFull() {
        val original = state(full = listOfBands(band()), mid = fill(16))
        val result = PeqBandEditor.copyFilter(original, PeqScope.FULL, index = 0, to = PeqScope.MID)
        assertEquals(PeqBandEditResult.Overflow(PeqScope.MID, alreadyFull = true), result)
    }

    // --- moveFilter --------------------------------------------------------------------

    @Test
    fun moveFilterReordersAndKeepsSelection() {
        val moved = band(freq = 111.0)
        val original = state(low = listOfBands(band(freq = 100.0), moved, band(freq = 300.0)))
        val result = PeqBandEditor.moveFilter(original, PeqScope.LOW, from = 1, to = 0)
        result as PeqBandEditResult.Changed
        assertEquals("reorder", result.undoSource)
        assertEquals(111.0, result.candidate.lowBandBands[0].frequency, 0.0)
        assertEquals(moved.uuid, result.select)
    }

    @Test
    fun moveFilterIgnoresOutOfRangeIndices() {
        val original = state(low = listOfBands(band(), band()))
        assertEquals(PeqBandEditResult.Ignored, PeqBandEditor.moveFilter(original, PeqScope.LOW, from = 0, to = 5))
        assertEquals(PeqBandEditResult.Ignored, PeqBandEditor.moveFilter(original, PeqScope.LOW, from = -1, to = 1))
    }

    // --- copyChannelFilters -----------------------------------------------------------

    @Test
    fun copyChannelFiltersDuplicatesMatchingChannelWithoutRemovingSource() {
        val original = state(
            full = listOfBands(
                band(freq = 100.0, channel = ParametricEqChannel.LEFT),
                band(freq = 200.0, channel = ParametricEqChannel.LEFT),
                band(freq = 300.0, channel = ParametricEqChannel.RIGHT),
            ),
        )
        val result = PeqBandEditor.copyChannelFilters(
            original, PeqScope.FULL, ParametricEqChannel.LEFT, ParametricEqChannel.RIGHT, replaceBoth = false,
        )
        result as PeqBandEditResult.Changed
        assertEquals("copy-channel", result.undoSource)
        val bands = result.candidate.fullRangeBands
        assertEquals(5, bands.size)
        assertEquals(2, bands.count { it.channel == ParametricEqChannel.LEFT })
        assertEquals(3, bands.count { it.channel == ParametricEqChannel.RIGHT })
    }

    @Test
    fun copyChannelFiltersSplitRemovesBothAndEmitsLeftAndRight() {
        val original = state(
            full = listOfBands(
                band(freq = 100.0, channel = ParametricEqChannel.LEFT_RIGHT),
                band(freq = 200.0, channel = ParametricEqChannel.LEFT_RIGHT),
            ),
        )
        val result = PeqBandEditor.copyChannelFilters(
            original, PeqScope.FULL, ParametricEqChannel.LEFT_RIGHT, ParametricEqChannel.LEFT, replaceBoth = true,
        )
        result as PeqBandEditResult.Changed
        val bands = result.candidate.fullRangeBands
        assertEquals(4, bands.size)
        assertEquals(0, bands.count { it.channel == ParametricEqChannel.LEFT_RIGHT })
        assertEquals(2, bands.count { it.channel == ParametricEqChannel.LEFT })
        assertEquals(2, bands.count { it.channel == ParametricEqChannel.RIGHT })
    }

    @Test
    fun copyChannelFiltersReportsNoMatchingChannel() {
        val original = state(full = listOfBands(band(channel = ParametricEqChannel.LEFT_RIGHT)))
        val result = PeqBandEditor.copyChannelFilters(
            original, PeqScope.FULL, ParametricEqChannel.LEFT, ParametricEqChannel.RIGHT, replaceBoth = false,
        )
        assertEquals(PeqBandEditResult.NoMatchingChannel, result)
    }

    @Test
    fun copyChannelFiltersOverflowsBeforeMutating() {
        val many = ParametricEqBandList().apply {
            repeat(9) { add(band(freq = 100.0 + it, channel = ParametricEqChannel.LEFT)) }
        }
        val result = PeqBandEditor.copyChannelFilters(
            state(full = many), PeqScope.FULL, ParametricEqChannel.LEFT, ParametricEqChannel.RIGHT, replaceBoth = false,
        )
        assertEquals(PeqBandEditResult.Overflow(PeqScope.FULL, alreadyFull = false), result)
    }

    // --- overflowToast ---------------------------------------------------------------

    @Test
    fun overflowToastRendersBothShapes() {
        assertEquals(
            "Low Band would exceed the maximum 16 filters",
            PeqBandEditor.overflowToast(PeqBandEditResult.Overflow(PeqScope.LOW, alreadyFull = false)),
        )
        assertEquals(
            "Mid Band already has the maximum 16 filters",
            PeqBandEditor.overflowToast(PeqBandEditResult.Overflow(PeqScope.MID, alreadyFull = true)),
        )
    }

    // --- helpers -------------------------------------------------------------------

    @Test
    fun replaceScopeBandsClearsThenAdds() {
        val s = state(low = listOfBands(band(), band(), band()))
        PeqBandEditor.replaceScopeBands(s, PeqScope.LOW, listOfBands(band(freq = 55.0)))
        assertEquals(1, s.lowBandBands.size)
        assertEquals(55.0, s.lowBandBands[0].frequency, 0.0)
    }

    @Test
    fun bandsForReturnsTheRequestedBank() {
        val s = state(full = fill(1), low = fill(2), mid = fill(3))
        assertEquals(1, PeqBandEditor.bandsFor(s, PeqScope.FULL).size)
        assertEquals(2, PeqBandEditor.bandsFor(s, PeqScope.LOW).size)
        assertEquals(3, PeqBandEditor.bandsFor(s, PeqScope.MID).size)
    }

    // --- importIntoScope --------------------------------------------------------------

    @Test
    fun importIntoScopeReplaceClearsAndAppliesPreampOnFullBank() {
        val original = state(full = listOfBands(band(), band())).copy(preampDb = 1f)
        val result = PeqBandEditor.importIntoScope(
            original, PeqScope.FULL, listOfBands(band(freq = 63.0)), importedPreamp = -4.5f, append = false,
        )
        result as PeqBandEditResult.Changed
        assertEquals("import-replace", result.undoSource)
        assertEquals(1, result.candidate.fullRangeBands.size)
        assertEquals(-4.5f, result.candidate.preampDb)
        assertEquals(1f, original.preampDb) // untouched
    }

    @Test
    fun importIntoScopeAppendKeepsPreampAndExistingBands() {
        val original = state(low = listOfBands(band())).copy(preampDb = 2f)
        val result = PeqBandEditor.importIntoScope(
            original, PeqScope.LOW, listOfBands(band(), band()), importedPreamp = -9f, append = true,
        )
        result as PeqBandEditResult.Changed
        assertEquals("import-append", result.undoSource)
        assertEquals(3, result.candidate.lowBandBands.size)
        assertEquals(2f, result.candidate.preampDb) // preamp only changes on a Full replace
    }

    @Test
    fun importIntoScopeOverflowsAfterClear() {
        val result = PeqBandEditor.importIntoScope(
            state(full = fill(3)), PeqScope.FULL, fill(17), importedPreamp = 0f, append = false,
        )
        assertEquals(PeqBandEditResult.Overflow(PeqScope.FULL, alreadyFull = false), result)
    }

    // --- importRoutedApo --------------------------------------------------------------

    private fun routed(scope: PeqScope, count: Int, preamp: Double = 0.0) = RoutedApoImport(
        name = "${scope.name}.txt",
        scope = scope,
        channel = ParametricEqChannel.LEFT_RIGHT,
        bands = fill(count),
        preampDb = preamp,
        skipped = 0,
    )

    @Test
    fun importRoutedApoReplaceClearsOnlyTouchedBanksAndTakesFullPreamp() {
        val original = state(full = fill(4), low = fill(4), mid = fill(4)).copy(preampDb = 0f)
        val result = PeqBandEditor.importRoutedApo(
            original,
            listOf(routed(PeqScope.FULL, 2, preamp = -6.0), routed(PeqScope.LOW, 3)),
            append = false,
        )
        result as PeqBandEditResult.Changed
        assertEquals("rew-import-replace", result.undoSource)
        assertEquals(2, result.candidate.fullRangeBands.size)
        assertEquals(3, result.candidate.lowBandBands.size)
        assertEquals(4, result.candidate.midBandBands.size) // not in the routed set -> untouched
        assertEquals(-6f, result.candidate.preampDb)
    }

    @Test
    fun importRoutedApoAppendKeepsBanksAndIgnoresPreamp() {
        val original = state(low = fill(2)).copy(preampDb = 3f)
        val result = PeqBandEditor.importRoutedApo(
            original, listOf(routed(PeqScope.FULL, 1, preamp = -6.0), routed(PeqScope.LOW, 2)), append = true,
        )
        result as PeqBandEditResult.Changed
        assertEquals("rew-import-append", result.undoSource)
        assertEquals(1, result.candidate.fullRangeBands.size)
        assertEquals(4, result.candidate.lowBandBands.size)
        assertEquals(3f, result.candidate.preampDb)
    }

    @Test
    fun importRoutedApoOverflowsOnTheOffendingBank() {
        val result = PeqBandEditor.importRoutedApo(
            state(), listOf(routed(PeqScope.MID, 20)), append = false,
        )
        assertEquals(PeqBandEditResult.Overflow(PeqScope.MID, alreadyFull = false), result)
    }

    @Test
    fun editorNeverMutatesTheInputState() {
        val original = state(full = listOfBands(band(), band()), low = listOfBands(band()))
        PeqBandEditor.copyWholeScope(original, PeqScope.FULL, PeqScope.LOW, append = true)
        PeqBandEditor.duplicateFilter(original, PeqScope.FULL, index = 0)
        PeqBandEditor.moveFilter(original, PeqScope.FULL, 0, 1)
        assertEquals(2, original.fullRangeBands.size)
        assertEquals(1, original.lowBandBands.size)
        assertTrue(original.midBandBands.isEmpty())
    }
}
