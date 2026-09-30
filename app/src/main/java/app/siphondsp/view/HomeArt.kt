package app.siphondsp.view

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where every live element sits on the front page. Rects are fractions of the art space, x/y/w/h.
 *
 * Head unit: a 2340x878 art space (the 1280x480 screen's aspect). The layout follows James's
 * front-page mock-up: the power button alone on the left panel, one big screen holding the five
 * DSP tiles across the top, Settings and More bottom-right, and a live panel (output, audio engine,
 * global stages) in the space bottom-left. [app.siphondsp.compose.controls.HomeFaceplate] draws the
 * plate, the `screen` and the bezel; each tile is a Compose tile at its rect, so a tile's picture
 * and its touch area are always the same box.
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

    /** The seven navigation tiles: the five DSP tiles left to right, then Settings and More. */
    val TILE_KEYS = listOf(
        "tile_peq", "tile_gains", "tile_xovers", "tile_compressor",
        "tile_allpass", "tile_settings", "tile_more",
    )

    private fun px(x: Int, y: Int, w: Int, h: Int, imageW: Float, imageH: Float) =
        Frac(x / imageW, y / imageH, w / imageW, h / imageH)

    private fun ph(x: Int, y: Int, w: Int, h: Int) = px(x, y, w, h, PHONE_IMAGE_WIDTH, PHONE_IMAGE_HEIGHT)

    // Head unit. The screen leaves 19dp margins inside it (0.015 of the width, 0.044 of the
    // height); the five DSP tiles are square, evenly pitched, and Settings/More end flush with the
    // last one. The live panel's three blocks fill the space left of Settings.
    private val rects = mapOf(
        "screen" to Frac(0.1140f, 0.0760f, 0.8485f, 0.8040f),
        "tile_peq" to Frac(0.1290f, 0.1200f, 0.1520f, 0.4050f),
        "tile_gains" to Frac(0.2956f, 0.1200f, 0.1520f, 0.4050f),
        "tile_xovers" to Frac(0.4622f, 0.1200f, 0.1520f, 0.4050f),
        "tile_compressor" to Frac(0.6288f, 0.1200f, 0.1520f, 0.4050f),
        "tile_allpass" to Frac(0.7954f, 0.1200f, 0.1520f, 0.4050f),
        "tile_settings" to Frac(0.7560f, 0.6227f, 0.0800f, 0.2133f),
        "tile_more" to Frac(0.8674f, 0.6227f, 0.0800f, 0.2133f),
        "live_output" to Frac(0.1290f, 0.5690f, 0.2580f, 0.2670f),
        "live_engine" to Frac(0.3970f, 0.5690f, 0.1500f, 0.2670f),
        "live_stages" to Frac(0.5570f, 0.5690f, 0.1690f, 0.2670f),
        // The power button and its glow, a circle centred on the left panel. PowerHotspot draws the
        // button, off and on, within this rect.
        "power_btn" to Frac(0.0160f, 0.4040f, 0.0850f, 0.2267f),
        // No LED dot on the head unit (the view is GONE there); kept so every key resolves.
        "power_led" to Frac(0.034f, 0.78f, 0.004f, 0.01f),
        // Legacy icons, hidden now that Settings and More are tiles; kept so every key resolves.
        "cog" to Frac(0.0109f, 0.0517f, 0.04f, 0.1373f),
        "overflow" to Frac(0.9471f, 0.0544f, 0.04f, 0.1289f),
    )

    // Phone (2340x1080): the same columns; the taller art gives the live panel more height.
    // Heights are rescaled so tiles and the power button stay square/round in pixels.
    private val phoneRects = mapOf(
        "screen" to Frac(0.1140f, 0.0600f, 0.8485f, 0.8800f),
        "tile_peq" to Frac(0.1290f, 0.1000f, 0.1520f, 0.3293f),
        "tile_gains" to Frac(0.2956f, 0.1000f, 0.1520f, 0.3293f),
        "tile_xovers" to Frac(0.4622f, 0.1000f, 0.1520f, 0.3293f),
        "tile_compressor" to Frac(0.6288f, 0.1000f, 0.1520f, 0.3293f),
        "tile_allpass" to Frac(0.7954f, 0.1000f, 0.1520f, 0.3293f),
        "tile_settings" to Frac(0.7560f, 0.7267f, 0.0800f, 0.1733f),
        "tile_more" to Frac(0.8674f, 0.7267f, 0.0800f, 0.1733f),
        "live_output" to Frac(0.1290f, 0.4693f, 0.2580f, 0.4307f),
        "live_engine" to Frac(0.3970f, 0.4693f, 0.1500f, 0.4307f),
        "live_stages" to Frac(0.5570f, 0.4693f, 0.1690f, 0.4307f),
        "power_btn" to Frac(0.0160f, 0.4079f, 0.0850f, 0.1842f),
        "power_led" to Frac(0.034f, 0.85f, 0.004f, 0.01f),
        "cog" to ph(30, 50, 90, 90),
        "overflow" to ph(2220, 50, 90, 90),
    )

    /** The live panel's blocks, left to right; the faceplate draws a divider between each pair. */
    val LIVE_KEYS = listOf("live_output", "live_engine", "live_stages")

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
