package app.siphondsp.view

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where every live element sits on the front page. Rects are fractions of the art space, x/y/w/h.
 *
 * Head unit: a 2340x878 art space (the 1280x480 screen's aspect). The layout is the rack-panel
 * faceplate (Figma "SiphonDSP Front Face v2", built to the owner's mockup, with the screens from
 * "SiphonDSP Faceplate v3", re-balanced in Figma "SiphonDSP Front Panel (from code)"): screens
 * recessed into the plate, with an edge of plate around them twice as wide on the right as the
 * old one, mirrored on the left by the top row. Across the top (about 30 % of the height) three
 * screens spanning the plate: GLOBAL STAGES on the left; headroom over the MBC band squares and
 * all-pass boxes in the centre; More and Settings, bare icons with labels, on the right. Below,
 * the power button sits centred in the panel left of the bottom screen, which holds the signal
 * chain: the five DSP cards on a line that runs into the live output scope (`live_scope`).
 * [app.siphondsp.compose.controls.HomeFaceplate] draws the plate, the [SCREEN_KEYS] screens, the
 * chain's line and the bezel; each card is a Compose card at its rect, so a card's picture and its touch area are always
 * the same box.
 *
 * [map] fits the whole art space inside the view (contain, not cover), so nothing is ever cropped
 * on a display of another aspect: the plate fills whatever margin is left over. The touch areas and
 * the chrome stay locked together on any display.
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

    // Head unit, measured off the owner's 2000x750 mockup (same aspect as the art space). The DSP
    // cards are one size and evenly pitched, centred vertically in the bottom screen. The plate's
    // right edge is 4.5 % of the width; the top band runs from the same 4.5 % on the left, its
    // three screens in the Faceplate v3 proportions (409 : 904 : 419) with 30 px of plate between
    // them; More and Settings are centred in the right one. The bottom screen ends level with
    // the top band, leaving the power panel on its left.
    private val rects = mapOf(
        // The top band: not drawn itself, it bounds the three top screens.
        "screen_top" to Frac(0.0450f, 0.0667f, 0.9100f, 0.3027f),
        "screen_left" to Frac(0.04500f, 0.0667f, 0.20882f, 0.3027f),
        "screen_centre" to Frac(0.26663f, 0.0667f, 0.46159f, 0.3027f),
        "screen_right" to Frac(0.74104f, 0.0667f, 0.21396f, 0.3027f),
        "screen_bottom" to Frac(0.2085f, 0.4840f, 0.7465f, 0.4067f),
        // The signal chain, centred vertically in the bottom screen: five 240x294 cards on an even
        // 267 px pitch from x 578, then the output scope from x 1910 to x 2266.
        "tile_peq" to Frac(0.22451f, 0.51993f, 0.10256f, 0.33485f),
        "tile_gains" to Frac(0.33861f, 0.51993f, 0.10256f, 0.33485f),
        "tile_xovers" to Frac(0.45271f, 0.51993f, 0.10256f, 0.33485f),
        "tile_compressor" to Frac(0.56682f, 0.51993f, 0.10256f, 0.33485f),
        "tile_allpass" to Frac(0.68092f, 0.51993f, 0.10256f, 0.33485f),
        "live_scope" to Frac(0.79374f, 0.49089f, 0.15226f, 0.39294f),
        "tile_more" to Frac(0.76335f, 0.1050f, 0.0650f, 0.2267f),
        "tile_settings" to Frac(0.86764f, 0.1050f, 0.0650f, 0.2267f),
        // GLOBAL STAGES fills the left screen, inset, and centres its text vertically. The centre
        // screen's block (headroom over the MBC band squares and all-pass boxes) is the whole
        // screen; HomeCentreScreen centres and scales its content inside it.
        "live_stages" to Frac(0.0540f, 0.08497f, 0.19082f, 0.26616f),
        "live_centre" to Frac(0.26663f, 0.0667f, 0.46159f, 0.3027f),
        // The power button and its glow, a circle centred in the left panel beside the bottom
        // screen, level with its centre. PowerHotspot draws the button, off and on, within this rect.
        "power_btn" to Frac(0.0554f, 0.5584f, 0.0977f, 0.2606f),
        // No LED dot on the head unit (the view is GONE there); kept so every key resolves.
        "power_led" to Frac(0.034f, 0.78f, 0.004f, 0.01f),
        // Legacy icons, hidden now that Settings and More are tiles; kept so every key resolves.
        "cog" to Frac(0.0109f, 0.0517f, 0.04f, 0.1373f),
        "overflow" to Frac(0.9471f, 0.0544f, 0.04f, 0.1289f),
    )

    // Phone (2340x1080): the head unit's layout, scaled and centred vertically, so the screens keep
    // the head unit's proportions and the tiles fill the bottom screen instead of floating in a
    // stretched one; the taller art just leaves more plate above and below. Each head-unit y maps
    // to (y * 878 + 101) / 1080 and each height to h * 878 / 1080. More and Settings are wider
    // than the head unit's so they stay at least 48dp when a 16:9 phone fits the art to only
    // ~640dp wide; with a narrower gap between them they still fit the right screen.
    private val phoneRects = mapOf(
        "screen_top" to Frac(0.0450f, 0.1477f, 0.9100f, 0.2461f),
        "screen_left" to Frac(0.04500f, 0.1477f, 0.20882f, 0.2461f),
        "screen_centre" to Frac(0.26663f, 0.1477f, 0.46159f, 0.2461f),
        "screen_right" to Frac(0.74104f, 0.1477f, 0.21396f, 0.2461f),
        "screen_bottom" to Frac(0.2085f, 0.4870f, 0.7465f, 0.3306f),
        "tile_peq" to Frac(0.22451f, 0.51620f, 0.10256f, 0.27222f),
        "tile_gains" to Frac(0.33861f, 0.51620f, 0.10256f, 0.27222f),
        "tile_xovers" to Frac(0.45271f, 0.51620f, 0.10256f, 0.27222f),
        "tile_compressor" to Frac(0.56682f, 0.51620f, 0.10256f, 0.27222f),
        "tile_allpass" to Frac(0.68092f, 0.51620f, 0.10256f, 0.27222f),
        "live_scope" to Frac(0.79374f, 0.49259f, 0.15226f, 0.31944f),
        "tile_more" to Frac(0.75308f, 0.1789f, 0.0770f, 0.1843f),
        "tile_settings" to Frac(0.86595f, 0.1789f, 0.0770f, 0.1843f),
        "live_stages" to Frac(0.0540f, 0.16259f, 0.19082f, 0.21632f),
        "live_centre" to Frac(0.26663f, 0.1477f, 0.46159f, 0.2461f),
        "power_btn" to Frac(0.0554f, 0.5475f, 0.0977f, 0.2119f),
        "power_led" to Frac(0.034f, 0.85f, 0.004f, 0.01f),
        "cog" to ph(30, 50, 90, 90),
        "overflow" to ph(2220, 50, 90, 90),
    )

    /** The four screens, the top three left to right then the bottom; the faceplate recesses each into the plate. */
    val SCREEN_KEYS = listOf("screen_left", "screen_centre", "screen_right", "screen_bottom")

    /** The three top screens, left to right, within the `screen_top` band. */
    val TOP_SCREEN_KEYS = SCREEN_KEYS.take(3)

    /** The live output scope at the end of the signal chain, in the bottom screen. */
    const val SCOPE_KEY = "live_scope"

    /** The top screens' live blocks: GLOBAL STAGES, then the centre screen (headroom, MBC / all-pass boxes). */
    val LIVE_KEYS = listOf("live_stages", "live_centre")

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
        // Contain: the whole art space always fits, centred; the plate fills any margin.
        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
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
