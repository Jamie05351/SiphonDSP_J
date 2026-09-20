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

    fun frac(key: String): Frac? = rects[key]

    fun map(frac: Frac, viewWidth: Int, viewHeight: Int): Px {
        val scale = max(viewWidth / IMAGE_WIDTH, viewHeight / IMAGE_HEIGHT)
        val shownW = IMAGE_WIDTH * scale
        val shownH = IMAGE_HEIGHT * scale
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
