package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtTest {

    @Test
    fun everyLiveElementHasARect() {
        val keys = listOf(
            "tile_peq", "tile_gains", "tile_xovers", "tile_compressor", "tile_allpass",
            "tile_settings", "tile_more", "live_strip",
            "power_btn", "power_led", "cog", "overflow", "box_left", "box_centre", "box_right",
        )
        keys.forEach { assertNotNull("missing rect for $it", HomeArt.frac(it)) }
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
    fun everyLiveElementHasAPhoneRect() {
        val keys = listOf(
            "tile_peq", "tile_gains", "tile_xovers", "tile_compressor", "tile_allpass",
            "tile_settings", "tile_more", "live_strip",
            "power_btn", "power_led", "cog", "overflow", "box_left", "box_centre", "box_right",
        )
        keys.forEach { assertNotNull("missing phone rect for $it", HomeArt.frac(it, phone = true)) }
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
    fun headUnitTilesAreThePlacerLayout() {
        // home_layout_v5.json from REW/_UI/home_layout_placer_v5.html.
        val a = HomeArt.frac("tile_peq")!!
        assertEquals(0.147f, a.x, 0f)
        assertEquals(0.7579f, a.y, 0f)
    }

    @Test
    fun liveStripColumnsSitOverTheirTiles() {
        val centers = HomeArt.liveColumnCenters()
        assertEquals(HomeArt.TILE_KEYS.size, centers.size)
        val strip = HomeArt.frac("live_strip")!!
        HomeArt.TILE_KEYS.forEachIndexed { i, key ->
            val t = HomeArt.frac(key)!!
            assertEquals(t.x + t.w / 2f, strip.x + centers[i] * strip.w, 1e-5f)
        }
    }

    @Test
    fun powerButtonRectIsTheButtonAndItsGlowAtHeadUnitSize() {
        // power_btn is the button plus its whole glow (x 40..218, y 595..781 of the 2340x878 art
        // space); PowerHotspot draws the button within it.
        val px = HomeArt.map(HomeArt.frac("power_btn")!!, 2340, 878)
        assertEquals(40, px.left)
        assertEquals(595, px.top)
        assertEquals(218, px.right)
        assertEquals(781, px.bottom)
    }

    @Test
    fun phonePowerButtonRectIsTheButtonAndItsGlowAtArtSize() {
        // power_btn is the button plus its whole glow (x 37..215, y 702..888 of the 2340x1080 phone
        // art space); PowerHotspot draws the button within it.
        val px = HomeArt.map(
            HomeArt.frac("power_btn", phone = true)!!, 2340, 1080,
            HomeArt.PHONE_IMAGE_WIDTH, HomeArt.PHONE_IMAGE_HEIGHT,
        )
        assertEquals(37, px.left)
        assertEquals(702, px.top)
        assertEquals(215, px.right)
        assertEquals(888, px.bottom)
    }

    private val chromeKeys = listOf("screen_left", "screen_centre", "screen_right", "screen_bottom", "knob")

    @Test
    fun everyChromeRectResolvesForBothArts() {
        chromeKeys.forEach {
            assertNotNull("missing head unit rect for $it", HomeArt.frac(it))
            assertNotNull("missing phone rect for $it", HomeArt.frac(it, phone = true))
        }
    }

    @Test
    fun eachLiveBoxSitsInsideItsScreen() {
        for (phone in listOf(false, true)) {
            for ((box, screen) in listOf("box_left" to "screen_left", "box_centre" to "screen_centre", "box_right" to "screen_right")) {
                val b = HomeArt.frac(box, phone)!!
                val sc = HomeArt.frac(screen, phone)!!
                assertTrue("$box escapes $screen (phone=$phone)", b.x >= sc.x && b.y >= sc.y && b.x + b.w <= sc.x + sc.w && b.y + b.h <= sc.y + sc.h)
            }
            val strip = HomeArt.frac("live_strip", phone)!!
            val bottom = HomeArt.frac("screen_bottom", phone)!!
            assertTrue("live strip escapes the bottom screen (phone=$phone)", strip.x >= bottom.x && strip.x + strip.w <= bottom.x + bottom.w && strip.y >= bottom.y && strip.y + strip.h <= bottom.y + bottom.h)
            HomeArt.TILE_KEYS.forEach { key ->
                val t = HomeArt.frac(key, phone)!!
                assertTrue("$key escapes the bottom screen (phone=$phone)", t.x >= bottom.x && t.x + t.w <= bottom.x + bottom.w && t.y >= bottom.y && t.y + t.h <= bottom.y + bottom.h)
            }
        }
    }

    @Test
    fun theKnobIsACircleInPixels() {
        val head = HomeArt.frac("knob")!!
        assertEquals(head.w * HomeArt.IMAGE_WIDTH, head.h * HomeArt.IMAGE_HEIGHT, 2f)
        val phone = HomeArt.frac("knob", phone = true)!!
        assertEquals(phone.w * HomeArt.PHONE_IMAGE_WIDTH, phone.h * HomeArt.PHONE_IMAGE_HEIGHT, 2f)
    }
}
