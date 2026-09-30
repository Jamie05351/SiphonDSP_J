package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtTest {

    private val keys = HomeArt.TILE_KEYS + HomeArt.LIVE_KEYS +
        listOf("screen", "power_btn", "power_led", "cog", "overflow")

    private val dspTiles = HomeArt.TILE_KEYS.take(5)

    private fun artSize(phone: Boolean) =
        if (phone) HomeArt.PHONE_IMAGE_WIDTH to HomeArt.PHONE_IMAGE_HEIGHT else HomeArt.IMAGE_WIDTH to HomeArt.IMAGE_HEIGHT

    private fun inside(inner: HomeArt.Frac, outer: HomeArt.Frac) =
        inner.x >= outer.x && inner.y >= outer.y &&
            inner.x + inner.w <= outer.x + outer.w && inner.y + inner.h <= outer.y + outer.h

    private fun overlaps(a: HomeArt.Frac, b: HomeArt.Frac) =
        a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    @Test
    fun everyLiveElementHasARect() {
        keys.forEach { assertNotNull("missing rect for $it", HomeArt.frac(it)) }
    }

    @Test
    fun everyLiveElementHasAPhoneRect() {
        keys.forEach { assertNotNull("missing phone rect for $it", HomeArt.frac(it, phone = true)) }
    }

    @Test
    fun headUnitMapsImageFractionsToScreenPixels() {
        // 1280x480 is the art's own 2340:878 aspect (to 0.05%), so fractions map straight across
        // with only a sub-pixel crop.
        val px = HomeArt.map(HomeArt.Frac(0.5f, 0.5f, 0.1f, 0.1f), 1280, 480)
        assertEquals(640.0, px.left.toDouble(), 1.0)
        assertEquals(240.0, px.top.toDouble(), 1.0)
        assertEquals(128.0, (px.right - px.left).toDouble(), 1.0)
        assertEquals(48.0, (px.bottom - px.top).toDouble(), 1.0)
    }

    @Test
    fun tallerDisplayCropsSidesLikeCenterCrop() {
        // 1000x1000: scale = 1000/878, image is wider than the view, so the left/right edges are
        // cropped equally and the vertical fractions still span the full height.
        val full = HomeArt.map(HomeArt.Frac(0f, 0f, 1f, 1f), 1000, 1000)
        assertEquals(0, full.top)
        assertEquals(1000, full.bottom)
        assertEquals(-full.left, full.right - 1000)
        assert(full.left < 0)
    }

    @Test
    fun phoneArtMapsStraightAcrossAtItsNativeSize() {
        // The 2340x1080 phone art is the phone's own size, so fractions map straight across.
        val px = HomeArt.map(
            HomeArt.Frac(0.5f, 0.5f, 0.1f, 0.1f), 2340, 1080,
            HomeArt.PHONE_IMAGE_WIDTH, HomeArt.PHONE_IMAGE_HEIGHT,
        )
        assertEquals(1170.0, px.left.toDouble(), 1.0)
        assertEquals(540.0, px.top.toDouble(), 1.0)
        assertEquals(234.0, (px.right - px.left).toDouble(), 1.0)
        assertEquals(108.0, (px.bottom - px.top).toDouble(), 1.0)
    }

    @Test
    fun tilesAndLivePanelSitInsideTheScreen() {
        for (phone in listOf(false, true)) {
            val screen = HomeArt.frac("screen", phone)!!
            (HomeArt.TILE_KEYS + HomeArt.LIVE_KEYS).forEach { key ->
                assertTrue("$key escapes the screen (phone=$phone)", inside(HomeArt.frac(key, phone)!!, screen))
            }
        }
    }

    @Test
    fun nothingOnTheScreenOverlaps() {
        for (phone in listOf(false, true)) {
            val all = HomeArt.TILE_KEYS + HomeArt.LIVE_KEYS
            for (i in all.indices) for (j in i + 1 until all.size) {
                assertTrue(
                    "${all[i]} overlaps ${all[j]} (phone=$phone)",
                    !overlaps(HomeArt.frac(all[i], phone)!!, HomeArt.frac(all[j], phone)!!),
                )
            }
        }
    }

    @Test
    fun dspTilesAreSquareAndEvenlyPitched() {
        for (phone in listOf(false, true)) {
            val (w, h) = artSize(phone)
            val tiles = dspTiles.map { HomeArt.frac(it, phone)!! }
            tiles.forEach { assertEquals("tile not square (phone=$phone)", it.w * w, it.h * h, 1f) }
            val pitches = tiles.zipWithNext { a, b -> b.x - a.x }
            pitches.forEach { assertEquals(pitches.first(), it, 1e-4f) }
            assertTrue(tiles.all { it.y == tiles.first().y })
        }
    }

    @Test
    fun settingsAndMoreEndFlushWithTheLastTile() {
        for (phone in listOf(false, true)) {
            val last = HomeArt.frac("tile_allpass", phone)!!
            val more = HomeArt.frac("tile_more", phone)!!
            assertEquals(last.x + last.w, more.x + more.w, 1e-3f)
        }
    }

    @Test
    fun powerButtonIsACircleLeftOfTheScreen() {
        for (phone in listOf(false, true)) {
            val (w, h) = artSize(phone)
            val power = HomeArt.frac("power_btn", phone)!!
            val screen = HomeArt.frac("screen", phone)!!
            assertEquals("power button not round (phone=$phone)", power.w * w, power.h * h, 1f)
            assertTrue("power button overlaps the screen (phone=$phone)", power.x + power.w <= screen.x)
            assertTrue(power.x >= 0f && power.y >= 0f && power.y + power.h <= 1f)
        }
    }
}
