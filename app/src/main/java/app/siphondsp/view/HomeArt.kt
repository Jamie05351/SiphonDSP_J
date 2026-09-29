package app.siphondsp.view

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where every live element sits on the front page's hardware-panel artwork. Rects are fractions
 * of the *image*, x/y/w/h.
 *
 * Head unit: a 2340x878 art space (the 1280x480 screen's aspect), placed in
 * REW/_UI/home_layout_placer_v5.html (home_layout_v5.json). The faceplate itself is no longer an
 * image: [app.siphondsp.compose.controls.HomeFaceplate] draws the plates, screens, bezel and knob
 * live, at the `screen_*` / `knob` rects below (measured from the old baked art so every live view
 * still lands inside its screen). Each tile is drawn by a Compose tile at its rect, so a tile's
 * picture and its touch area are always the same box.
 *
 * The art space is laid out full-bleed with cover scaling, so [map] turns an art fraction into view
 * pixels -- the touch areas and the chrome stay locked together on any display aspect, not just the
 * 1280x480 head unit.
 */
object HomeArt {
    const val IMAGE_WIDTH = 2340f
    const val IMAGE_HEIGHT = 878f

    /** The phone's art space is its own size, 2340x1080 (the phone's aspect). */
    const val PHONE_IMAGE_WIDTH = 2340f
    const val PHONE_IMAGE_HEIGHT = 1080f

    /** Image-fraction rect: left, top, width, height. */
    class Frac(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Rect in view pixels. */
    class Px(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** The seven navigation tiles, left to right; the live strip centres a column over each. */
    val TILE_KEYS = listOf(
        "tile_peq", "tile_gains", "tile_xovers", "tile_compressor",
        "tile_allpass", "tile_settings", "tile_more",
    )

    private fun px(x: Int, y: Int, w: Int, h: Int, imageW: Float, imageH: Float) =
        Frac(x / imageW, y / imageH, w / imageW, h / imageH)

    private fun hu(x: Int, y: Int, w: Int, h: Int) = px(x, y, w, h, IMAGE_WIDTH, IMAGE_HEIGHT)

    private fun ph(x: Int, y: Int, w: Int, h: Int) = px(x, y, w, h, PHONE_IMAGE_WIDTH, PHONE_IMAGE_HEIGHT)

    private val rects = mapOf(
        // Top row: crossover graph, PEQ graph, L/R output meters.
        "box_left" to Frac(0.1253f, 0.0532f, 0.2044f, 0.3802f),
        "box_centre" to Frac(0.3642f, 0.0532f, 0.2717f, 0.3816f),
        "box_right" to Frac(0.6732f, 0.0518f, 0.2032f, 0.3844f),
        "live_strip" to Frac(0.1241f, 0.5735f, 0.7117f, 0.16f),
        "tile_peq" to Frac(0.147f, 0.7579f, 0.0814f, 0.2169f),
        "tile_gains" to Frac(0.2526f, 0.7593f, 0.0815f, 0.2171f),
        "tile_xovers" to Frac(0.358f, 0.7621f, 0.0812f, 0.2163f),
        "tile_compressor" to Frac(0.4628f, 0.7591f, 0.0821f, 0.2188f),
        "tile_allpass" to Frac(0.5671f, 0.7588f, 0.0824f, 0.2197f),
        "tile_settings" to Frac(0.6809f, 0.775f, 0.0787f, 0.2008f),
        "tile_more" to Frac(0.7631f, 0.778f, 0.0691f, 0.1879f),
        // The power button and its whole glow (x 40..218, y 595..781): PowerHotspot draws the
        // button, off and on, within this rect.
        "power_btn" to hu(40, 595, 178, 186),
        // Faceplate chrome, measured from the old baked art: the three top screens (outer edge of
        // the black glass), the bottom screen, and the decorative volume knob (a circle; the
        // volume itself is the car's).
        "screen_left" to Frac(0.1120f, 0.0194f, 0.2303f, 0.4442f),
        "screen_centre" to Frac(0.3496f, 0.0194f, 0.3021f, 0.4442f),
        "screen_right" to Frac(0.6600f, 0.0194f, 0.2290f, 0.4442f),
        "screen_bottom" to Frac(0.1141f, 0.5376f, 0.7321f, 0.4578f),
        "knob" to Frac(0.8529f, 0.5658f, 0.1494f, 0.3982f),
        // No LED dot on the head unit (the view is GONE there); kept so every key resolves.
        "power_led" to Frac(0.034f, 0.78f, 0.004f, 0.01f),
        // Legacy icons, hidden now that Settings and More are tiles; kept so every key resolves.
        "cog" to Frac(0.0109f, 0.0517f, 0.04f, 0.1373f),
        "overflow" to Frac(0.9471f, 0.0544f, 0.04f, 0.1289f),
    )

    // Same keys for the phone artwork (2340x1080, px below). The head-unit layout mapped onto the
    // phone art's taller screens: top boxes scaled into the top screens, the live strip and tiles
    // into the lower screen, tiles at the same pixel size.
    private val phoneRects = mapOf(
        "box_left" to ph(293, 65, 478, 428),
        "box_centre" to ph(852, 65, 636, 430),
        "box_right" to ph(1575, 63, 475, 433),
        "live_strip" to ph(290, 615, 1665, 147),
        "tile_peq" to ph(344, 784, 190, 190),
        "tile_gains" to ph(591, 786, 191, 191),
        "tile_xovers" to ph(838, 788, 190, 190),
        "tile_compressor" to ph(1083, 786, 192, 192),
        "tile_allpass" to ph(1327, 785, 193, 193),
        "tile_settings" to ph(1593, 800, 184, 176),
        "tile_more" to ph(1786, 803, 162, 165),
        // The power button and its glow (x 37..215, y 702..888), same scheme as the head unit.
        "power_btn" to ph(37, 702, 178, 186),
        "screen_left" to Frac(0.1115f, 0.0241f, 0.2317f, 0.4537f),
        "screen_centre" to Frac(0.3491f, 0.0278f, 0.3039f, 0.4537f),
        "screen_right" to Frac(0.6600f, 0.0241f, 0.2290f, 0.4537f),
        "screen_bottom" to Frac(0.1128f, 0.5361f, 0.7342f, 0.3806f),
        "knob" to Frac(0.8535f, 0.5683f, 0.1497f, 0.3241f),
        "power_led" to Frac(0.034f, 0.85f, 0.004f, 0.01f),
        "cog" to ph(30, 50, 90, 90),
        "overflow" to ph(2220, 50, 90, 90),
    )

    /**
     * Centre of each tile's column across the live strip, as a fraction of the strip's width, so
     * each status cell sits over its own tile.
     */
    fun liveColumnCenters(phone: Boolean = false): FloatArray {
        val strip = frac("live_strip", phone) ?: return FloatArray(0)
        return FloatArray(TILE_KEYS.size) { i ->
            val t = frac(TILE_KEYS[i], phone)!!
            (t.x + t.w / 2f - strip.x) / strip.w
        }
    }

    /** [phone] selects the phone artwork's rects; the default is the head-unit set, unchanged. */
    fun frac(key: String, phone: Boolean = false): Frac? = (if (phone) phoneRects else rects)[key]

    /** [imageWidth]/[imageHeight] are the art's own size; the default is the head-unit art's. */
    fun map(
        frac: Frac,
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Float = IMAGE_WIDTH,
        imageHeight: Float = IMAGE_HEIGHT,
    ): Px {
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val shownW = imageWidth * scale
        val shownH = imageHeight * scale
        val offX = (viewWidth - shownW) / 2f
        val offY = (viewHeight - shownH) / 2f
        return Px(
            left = (offX + frac.x * shownW).roundToInt(),
            top = (offY + frac.y * shownH).roundToInt(),
            right = (offX + (frac.x + frac.w) * shownW).roundToInt(),
            bottom = (offY + (frac.y + frac.h) * shownH).roundToInt(),
        )
    }
}
