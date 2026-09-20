package app.siphondsp.view

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where every live element sits on the front page's hardware-panel artwork
 * (`dsp_home_backdrop.png`, 2340x878). Rects are fractions of the *image*, x/y/w/h, placed with
 * the layout-placer page (REW/_UI/home_layout_placer.html) directly against the art
 * (Main_Menu_v2.png, saved as home_layout_v2.json).
 *
 * The art is drawn full-bleed with `centerCrop`, so [map] applies the same cover scale + offset
 * to turn an image fraction into view pixels -- the touch areas stay locked to the art on any
 * display aspect, not just the 1280x480 head unit.
 */
object HomeArt {
    const val IMAGE_WIDTH = 2340f
    const val IMAGE_HEIGHT = 878f

    /** The phone art (`dsp_home_backdrop_phone.png`) is the same 2340 wide but 1080 tall. */
    const val PHONE_IMAGE_HEIGHT = 1080f

    /** Image-fraction rect: left, top, width, height. */
    class Frac(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Rect in view pixels. */
    class Px(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val rects = mapOf(
        "tile_peq" to Frac(0.1216f, 0.461f, 0.1326f, 0.359f),
        "tile_gains" to Frac(0.2636f, 0.461f, 0.13f, 0.359f),
        "tile_xovers" to Frac(0.4022f, 0.461f, 0.131f, 0.359f),
        "tile_compressor" to Frac(0.5423f, 0.461f, 0.13f, 0.359f),
        "tile_allpass" to Frac(0.6813f, 0.461f, 0.1321f, 0.359f),
        "power_btn" to Frac(0.024f, 0.555f, 0.072f, 0.193f),
        "power_led" to Frac(0.053f, 0.827f, 0.012f, 0.032f),
        "cog" to Frac(0.0239f, 0.1106f, 0.0452f, 0.1458f),
        "overflow" to Frac(0.9295f, 0.1147f, 0.0448f, 0.1361f),
        "box_left" to Frac(0.094f, 0.08f, 0.16f, 0.296f),
        "box_centre" to Frac(0.2656f, 0.08f, 0.4687f, 0.296f),
        "box_right" to Frac(0.7459f, 0.0827f, 0.1631f, 0.297f),
    )

    // Same keys for the phone artwork (`dsp_home_backdrop_phone.png`, 2340x1080). Placed in the
    // same layout-placer page (REW/_UI/home_layout_placer_phone.html) against Main_Menu_phone.png.
    private val phoneRects = mapOf(
        "tile_peq" to Frac(0.1175f, 0.468f, 0.134f, 0.426f),
        "tile_gains" to Frac(0.2605f, 0.468f, 0.1325f, 0.426f),
        "tile_xovers" to Frac(0.402f, 0.468f, 0.1325f, 0.426f),
        "tile_compressor" to Frac(0.5435f, 0.468f, 0.1325f, 0.426f),
        "tile_allpass" to Frac(0.685f, 0.468f, 0.1335f, 0.426f),
        "power_btn" to Frac(0.02f, 0.5385f, 0.075f, 0.185f),
        "power_led" to Frac(0.052f, 0.777f, 0.01f, 0.022f),
        "cog" to Frac(0.02f, 0.1f, 0.045f, 0.12f),
        "overflow" to Frac(0.935f, 0.1f, 0.045f, 0.12f),
        "box_left" to Frac(0.0815f, 0.0704f, 0.162f, 0.317f),
        "box_centre" to Frac(0.2555f, 0.0704f, 0.488f, 0.317f),
        "box_right" to Frac(0.755f, 0.0704f, 0.164f, 0.317f),
    )

    /** [phone] selects the phone artwork's rects; the default is the head-unit set, unchanged. */
    fun frac(key: String, phone: Boolean = false): Frac? = (if (phone) phoneRects else rects)[key]

    /** [imageHeight] is the art's own height; the default is the head-unit art's. */
    fun map(frac: Frac, viewWidth: Int, viewHeight: Int, imageHeight: Float = IMAGE_HEIGHT): Px {
        val scale = max(viewWidth / IMAGE_WIDTH, viewHeight / imageHeight)
        val shownW = IMAGE_WIDTH * scale
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
