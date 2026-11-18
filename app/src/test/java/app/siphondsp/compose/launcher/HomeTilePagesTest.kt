package app.siphondsp.compose.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTilePagesTest {
    @Test
    fun musicIsNeverInTheSwipeRow() {
        assertFalse(HomeTile.MUSIC in HomeTile.Swipe)
        assertEquals(HomeTile.entries.size - 1, HomeTile.Swipe.size)
    }

    @Test
    fun pagesArePairsInOrder() {
        val pages = HomeTile.pages(emptySet())
        // Six swipe tiles: three full pages.
        assertEquals(
            listOf(
                listOf(HomeTile.NAVIGATION, HomeTile.FILES),
                listOf(HomeTile.DRIVE, HomeTile.TOOLKIT),
                listOf(HomeTile.SETTINGS, HomeTile.DSP),
            ),
            pages,
        )
    }

    @Test
    fun anOddCountLeavesOneTileOnTheLastPage() {
        val pages = HomeTile.pages(setOf(HomeTile.FILES.key))
        assertEquals(3, pages.size)
        assertEquals(listOf(HomeTile.NAVIGATION, HomeTile.DRIVE), pages[0])
        assertEquals(listOf(HomeTile.DSP), pages.last())
    }

    @Test
    fun aSavedHiddenMusicKeyIsIgnored() {
        assertEquals(HomeTile.pages(emptySet()), HomeTile.pages(setOf(HomeTile.MUSIC.key)))
        assertFalse(HomeTile.anyHidden(setOf(HomeTile.MUSIC.key)))
        assertTrue(HomeTile.anyHidden(setOf(HomeTile.DRIVE.key)))
    }

    @Test
    fun everyTileHiddenLeavesNoPages() {
        assertTrue(HomeTile.pages(HomeTile.Swipe.map { it.key }.toSet()).isEmpty())
    }
}
