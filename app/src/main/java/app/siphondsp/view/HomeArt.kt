package app.siphondsp.view

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where every live element sits on the front page's hardware-panel artwork
 * (`dsp_home_backdrop.jpg`, 2800x1050 -- the head unit's exact 1280x480 aspect). Rects are
 * fractions of the *image*, x/y/w/h, measured (Pillow edge scan) against Main_Menu_v4 and
 * confirmed in the layout placer (REW/_UI/home_layout_placer_v4.html).
 *
 * The art is drawn full-bleed with `centerCrop`, so [map] applies the same cover scale + offset
 * to turn an image fraction into view pixels -- the touch areas stay locked to the art on any
 * display aspect, not just the 1280x480 head unit.
 */
object HomeArt {
    const val IMAGE_WIDTH = 2800f
    const val IMAGE_HEIGHT = 1050f

    /** The phone art (`dsp_home_backdrop_phone.png`) is its own size, 2340x1080. */
    const val PHONE_IMAGE_WIDTH = 2340f
    const val PHONE_IMAGE_HEIGHT = 1080f

    /** Image-fraction rect: left, top, width, height. */
    class Frac(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Rect in view pixels. */
    class Px(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val rects = mapOf(
        "tile_peq" to Frac(0.0855f, 0.482f, 0.135f, 0.43f),
        "tile_gains" to Frac(0.2365f, 0.482f, 0.133f, 0.43f),
        "tile_xovers" to Frac(0.385f, 0.482f, 0.128f, 0.43f),
        "tile_compressor" to Frac(0.5275f, 0.482f, 0.132f, 0.43f),
        "tile_allpass" to Frac(0.6745f, 0.482f, 0.136f, 0.43f),
        // The backdrop is the DSP-off art (grey button). This rect is exactly the pixel crop
        // saved as `dsp_home_power_on.png` from the DSP-on art (x 1..192, y 627..818 of
        // 2800x1050: the placed button plus a 10px margin so its purple glow fits), which
        // PowerHotspot paints over it while on. Both arts match to within 3/255 at its edge.
        "power_btn" to Frac(1f / 2800f, 627f / 1050f, 191f / 2800f, 191f / 1050f),
        // No LED dot on the head unit (the view is GONE there); kept so every key resolves.
        "power_led" to Frac(0.034f, 0.78f, 0.004f, 0.01f),
        "cog" to Frac(0.0109f, 0.0517f, 0.04f, 0.1373f),
        "overflow" to Frac(0.9471f, 0.0544f, 0.04f, 0.1289f),
        "box_left" to Frac(0.07f, 0.053f, 0.227f, 0.314f),
        "box_centre" to Frac(0.299f, 0.053f, 0.406f, 0.314f),
        "box_right" to Frac(0.716f, 0.053f, 0.214f, 0.314f),
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
