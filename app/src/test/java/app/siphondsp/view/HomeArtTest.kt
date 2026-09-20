package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeArtTest {

    @Test
    fun everyLiveElementHasARect() {
        val keys = listOf(
            "tile_peq", "tile_gains", "tile_xovers", "tile_compressor", "tile_allpass",
            "power_btn", "power_led", "cog", "overflow", "box_left", "box_centre", "box_right",
        )
        keys.forEach { assertNotNull("missing rect for $it", HomeArt.frac(it)) }
    }

    @Test
    fun headUnitMapsImageFractionsToScreenPixels() {
        // 1280x480 is (almost exactly) the art's own 2340:878 aspect, so fractions map straight
        // across with only a sub-pixel crop.
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
            "power_btn", "power_led", "cog", "overflow", "box_left", "box_centre", "box_right",
        )
        keys.forEach { assertNotNull("missing phone rect for $it", HomeArt.frac(it, phone = true)) }
    }

    @Test
    fun phoneArtMapsStraightAcrossAtItsNativeSize() {
        // 2340x1080 is the phone art's own size: fractions map 1:1 with no crop.
        val px = HomeArt.map(HomeArt.Frac(0.5f, 0.5f, 0.1f, 0.1f), 2340, 1080, HomeArt.PHONE_IMAGE_HEIGHT)
        assertEquals(1170.0, px.left.toDouble(), 1.0)
        assertEquals(540.0, px.top.toDouble(), 1.0)
        assertEquals(234.0, (px.right - px.left).toDouble(), 1.0)
        assertEquals(108.0, (px.bottom - px.top).toDouble(), 1.0)
    }

    @Test
    fun headUnitDefaultsAreUnchangedByPhoneSupport() {
        val a = HomeArt.frac("tile_peq")!!
        assertEquals(0.1094f, a.x, 0f)
        assertEquals(0.4499f, a.y, 0f)
    }
}
