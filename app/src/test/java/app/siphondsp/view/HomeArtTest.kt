package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtTest {

    private val liveKeys = listOf("live_output", "live_stages")

    private val keys = HomeArt.TILE_KEYS + liveKeys +
        HomeArt.SCREEN_KEYS + listOf("power_btn", "power_led", "cog", "overflow")

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
    fun tallerDisplayFitsTheWholeArtWithNothingCropped() {
        // 1000x1000: scale = 1000/2340, so the art spans the full width and is centred vertically,
        // with plate above and below; no rect can land off screen.
        val full = HomeArt.map(HomeArt.Frac(0f, 0f, 1f, 1f), 1000, 1000)
        assertEquals(0, full.left)
        assertEquals(1000, full.right)
        assertTrue(full.top > 0)
        assertEquals(full.top.toDouble(), (1000 - full.bottom).toDouble(), 1.0)
    }

    @Test
    fun sixteenByNineKeepsThePowerButtonAndMoreOnScreen() {
        // The old cover mapping cropped both off a 16:9 display.
        for (phone in listOf(false, true)) {
            val (aw, ah) = artSize(phone)
            listOf("power_btn", "tile_more").forEach { key ->
                val px = HomeArt.map(HomeArt.frac(key, phone)!!, 1920, 1080, aw, ah)
                assertTrue("$key cropped (phone=$phone)", px.left >= 0 && px.right <= 1920 && px.top >= 0 && px.bottom <= 1080)
            }
        }
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
    fun dspTilesSitInTheBottomScreenAndTheRestInTheTop() {
        for (phone in listOf(false, true)) {
            val top = HomeArt.frac("screen_top", phone)!!
            val bottom = HomeArt.frac("screen_bottom", phone)!!
            dspTiles.forEach { key ->
                assertTrue("$key escapes the bottom screen (phone=$phone)", inside(HomeArt.frac(key, phone)!!, bottom))
            }
            listOf("tile_settings", "tile_more", "live_stages").forEach { key ->
                assertTrue("$key escapes the top screen (phone=$phone)", inside(HomeArt.frac(key, phone)!!, top))
            }
        }
    }

    @Test
    fun topScreenIsAboveAndSmallerThanTheBottomWithPlateBetween() {
        for (phone in listOf(false, true)) {
            val top = HomeArt.frac("screen_top", phone)!!
            val bottom = HomeArt.frac("screen_bottom", phone)!!
            assertTrue("screens touch (phone=$phone)", top.y + top.h < bottom.y)
            // A real strip of plate between them, not a hairline: at least ~4.5 % of the height
            // (28 px of 480 on the head unit), wider than the two recess wells around it.
            assertTrue("screens too close (phone=$phone)", bottom.y - (top.y + top.h) >= 0.045f)
            assertTrue("top screen not the smaller (phone=$phone)", top.h < bottom.h)
        }
    }

    @Test
    fun nothingOnTheScreenOverlaps() {
        for (phone in listOf(false, true)) {
            val all = HomeArt.TILE_KEYS + liveKeys + "power_btn"
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
    fun settingsEndsFlushWithTheLastDspTileAndMoreSitsLeftOfIt() {
        for (phone in listOf(false, true)) {
            val last = HomeArt.frac("tile_allpass", phone)!!
            val settings = HomeArt.frac("tile_settings", phone)!!
            val more = HomeArt.frac("tile_more", phone)!!
            assertEquals(last.x + last.w, settings.x + settings.w, 1e-3f)
            assertTrue("More not left of Settings (phone=$phone)", more.x + more.w <= settings.x)
            assertEquals(more.y, settings.y, 1e-4f)
        }
    }

    @Test
    fun meterAndPowerButtonShareTheLeftPanel() {
        for (phone in listOf(false, true)) {
            val screen = HomeArt.frac("screen_top", phone)!!
            val meter = HomeArt.frac("live_output", phone)!!
            val power = HomeArt.frac("power_btn", phone)!!
            assertTrue("meter overlaps the screens (phone=$phone)", meter.x + meter.w <= screen.x)
            assertTrue("meter not left of the power button (phone=$phone)", meter.x + meter.w <= power.x)
            assertTrue("meter taller than the art (phone=$phone)", meter.y >= 0f && meter.y + meter.h <= 1f)
        }
    }

    @Test
    fun settingsAndMoreAreAtLeastTheMinimumTouchSize() {
        // Head unit: 1280x480dp. Phone: a 16:9 1920x1080 phone at 3x is 640x360dp, where fitting
        // the 2340x1080 art leaves it only 640dp wide -- the smallest common case.
        for ((phone, size) in listOf(false to (1280 to 480), true to (640 to 360))) {
            val (aw, ah) = artSize(phone)
            listOf("tile_settings", "tile_more").forEach { key ->
                val px = HomeArt.map(HomeArt.frac(key, phone)!!, size.first, size.second, aw, ah)
                assertTrue("$key under 48dp (phone=$phone)", px.right - px.left >= 48 && px.bottom - px.top >= 48)
            }
        }
    }

    @Test
    fun powerButtonIsACircleLeftOfTheScreen() {
        for (phone in listOf(false, true)) {
            val (w, h) = artSize(phone)
            val power = HomeArt.frac("power_btn", phone)!!
            val screen = HomeArt.frac("screen_top", phone)!!
            assertEquals("power button not round (phone=$phone)", power.w * w, power.h * h, 1f)
            assertTrue("power button overlaps the screen (phone=$phone)", power.x + power.w <= screen.x)
            assertTrue(power.x >= 0f && power.y >= 0f && power.y + power.h <= 1f)
        }
    }
}
