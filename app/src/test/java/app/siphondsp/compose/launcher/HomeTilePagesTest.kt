package app.siphondsp.compose.launcher

import app.siphondsp.launcher.HomeSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTilePagesTest {
    @Test
    fun musicIsNeverInTheGrid() {
        assertFalse(HomeTile.MUSIC in HomeTile.Grid)
        assertEquals(HomeTile.entries.size - 1, HomeTile.Grid.size)
    }

    @Test
    fun theGridFitsTwoByTwo() {
        // Every tile on screen at once: two rows of HomeTile.Columns.
        assertEquals(
            listOf(HomeTile.NAVIGATION, HomeTile.FILES, HomeTile.DRIVE, HomeTile.TOOLKIT),
            HomeTile.visible(emptySet()),
        )
        assertTrue(HomeTile.Grid.size <= 2 * HomeTile.Columns)
    }

    @Test
    fun oneSlotPerTile() {
        assertEquals(HomeTile.entries.size, HomeSlots.SLOTS)
    }

    @Test
    fun aHiddenTileLeavesNoGap() {
        assertEquals(listOf(HomeTile.NAVIGATION, HomeTile.DRIVE, HomeTile.TOOLKIT), HomeTile.visible(setOf(HomeTile.FILES.key)))
    }

    @Test
    fun savedKeysForMusicOrRemovedTilesAreIgnored() {
        val stale = setOf(HomeTile.MUSIC.key, HomeTile.KeyPrefix + "SETTINGS", HomeTile.KeyPrefix + "DSP")
        assertEquals(HomeTile.visible(emptySet()), HomeTile.visible(stale))
        assertFalse(HomeTile.anyHidden(stale))
        assertTrue(HomeTile.anyHidden(setOf(HomeTile.DRIVE.key)))
    }

    @Test
    fun everyTileHiddenLeavesAnEmptyGrid() {
        assertTrue(HomeTile.visible(HomeTile.Grid.map { it.key }.toSet()).isEmpty())
    }
}
