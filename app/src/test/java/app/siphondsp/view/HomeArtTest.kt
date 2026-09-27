package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun powerButtonRectIsThePowerOnCropAtHeadUnitSize() {
        // power_btn must stay the exact pixel crop saved as dsp_home_power_on.png
        // (x 40..218, y 595..781 of the 2340x878 art), or the on-patch drifts off the button.
        val px = HomeArt.map(HomeArt.frac("power_btn")!!, 2340, 878)
        assertEquals(40, px.left)
        assertEquals(595, px.top)
        assertEquals(218, px.right)
        assertEquals(781, px.bottom)
    }

    @Test
    fun phonePowerButtonRectIsThePowerOnCropAtArtSize() {
        // power_btn must stay the exact pixel crop saved as dsp_home_power_on_phone.png
        // (x 37..215, y 702..888 of the 2340x1080 phone art), or the on-patch drifts off the button.
        val px = HomeArt.map(
            HomeArt.frac("power_btn", phone = true)!!, 2340, 1080,
            HomeArt.PHONE_IMAGE_WIDTH, HomeArt.PHONE_IMAGE_HEIGHT,
        )
        assertEquals(37, px.left)
        assertEquals(702, px.top)
        assertEquals(215, px.right)
        assertEquals(888, px.bottom)
    }
}
