package app.siphondsp.view

import kotlin.math.max
import kotlin.math.min
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

    /** The phone art (`dsp_home_backdrop_phone.jpg`, the DSP-off version) is its own size,
     *  2800x1292 (the 2340x1080 phone's aspect). */
    const val PHONE_IMAGE_WIDTH = 2800f
    const val PHONE_IMAGE_HEIGHT = 1292f

    /** Image-fraction rect: left, top, width, height. */
    class Frac(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Rect in view pixels. */
    class Px(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** The seven navigation tiles, left to right, as drawn by HomeDashboardTilesView. */
    val TILE_KEYS = listOf(
        "tile_peq", "tile_gains", "tile_xovers", "tile_compressor",
        "tile_allpass", "tile_settings", "tile_more",
    )

    /**
     * Tile [index]'s square inside a [stripW] x [stripH] tile strip, in the strip's own units:
     * seven equal columns, each tile 0.76 of its column (capped at 0.92 of the strip height),
     * centred. HomeDashboardTilesView draws with this and the touch rects are built from it,
     * so what is tapped is always exactly what is drawn.
     */
    fun stripTile(index: Int, stripW: Float, stripH: Float): Frac {
        val cellW = stripW / TILE_KEYS.size
        val side = min(cellW * .76f, stripH * .92f)
        return Frac(cellW * index + (cellW - side) / 2f, (stripH - side) / 2f, side, side)
    }

    /** Touch rects for the seven tiles, derived from [strip] on an [imageW] x [imageH] art. */
    private fun tileRects(strip: Frac, imageW: Float, imageH: Float): List<Pair<String, Frac>> {
        val stripW = strip.w * imageW
        val stripH = strip.h * imageH
        return TILE_KEYS.mapIndexed { i, key ->
            val t = stripTile(i, stripW, stripH)
            key to Frac(
                strip.x + t.x / imageW,
                strip.y + t.y / imageH,
                t.w / imageW,
                t.h / imageH,
            )
        }
    }

    private val tileStrip = Frac(0.1175f, 0.665f, 0.765f, 0.235f)
    private val phoneTileStrip = Frac(0.1175f, 0.685f, 0.765f, 0.205f)

    private val rects = mapOf(
        // Flat AMOLED lower display and its live-drawn tile strip. The surface view masks the
        // legacy baked-in tile art so the dashboard can be rendered entirely from live UI.
        "lower_screen" to Frac(0.108f, 0.398f, 0.784f, 0.535f),
        "tile_strip" to tileStrip,
        *tileRects(tileStrip, IMAGE_WIDTH, IMAGE_HEIGHT).toTypedArray(),
        // Seven aligned live-status cells above the tile row.
        "live_strip" to Frac(0.125000f, 0.500000f, 0.706250f, 0.125000f),
        // The backdrop is the DSP-off art (grey button). This rect is exactly the pixel crop
        // saved as `dsp_home_power_on.png` from the DSP-on art (x 1..192, y 627..818 of
        // 2800x1050: the placed button plus a 10px margin so its purple glow fits), which
        // PowerHotspot paints over it while on. Both arts match to within 3/255 at its edge.
        "power_btn" to Frac(0.021875f, 0.633333f, 0.068750f, 0.183333f),
        // No LED dot on the head unit (the view is GONE there); kept so every key resolves.
        "power_led" to Frac(0.034f, 0.78f, 0.004f, 0.01f),
        "cog" to Frac(0.0109f, 0.0517f, 0.04f, 0.1373f),
        "overflow" to Frac(0.9471f, 0.0544f, 0.04f, 0.1289f),
        "box_left" to Frac(0.125000f, 0.116667f, 0.203125f, 0.250000f),
        "box_centre" to Frac(0.362500f, 0.116667f, 0.273438f, 0.250000f),
        "box_right" to Frac(0.668750f, 0.116667f, 0.203125f, 0.250000f),
    )

    // Same keys for the phone artwork (v4, 2800x1292), measured (Pillow edge scan) against
    // REW/_UI/Main_Menu_phone_v4_on.png. The art's top is one wide screen (x 174..2622,
    // y 24..549): the three live boxes sit side by side inside it with no frames of their own.
    // Its bezel has no cog or overflow drawn, so those two live icons sit in its top corners.
    // The knob on the right is decoration only (no key).
    private val phoneRects = mapOf(
        "lower_screen" to Frac(0.098f, 0.438f, 0.804f, 0.485f),
        "tile_strip" to phoneTileStrip,
        *tileRects(phoneTileStrip, PHONE_IMAGE_WIDTH, PHONE_IMAGE_HEIGHT).toTypedArray(),
        "live_strip" to Frac(0.1175f, 0.480f, 0.765f, 0.170f),
        // Exactly the pixel crop saved as `dsp_home_power_on_phone.png` from the DSP-on art
        // (x 2..198, y 893..1089: the disc plus a 10px margin for its glow), which PowerHotspot
        // paints over the DSP-off backdrop while on -- same scheme as the head unit.
        "power_btn" to Frac(2f / 2800f, 893f / 1292f, 196f / 2800f, 196f / 1292f),
        // No LED dot in this art (the view is GONE); kept so every key resolves.
        "power_led" to Frac(0.034f, 0.85f, 0.004f, 0.01f),
        "cog" to Frac(35f / 2800f, 60f / 1292f, 104f / 2800f, 104f / 1292f),
        "overflow" to Frac(2661f / 2800f, 60f / 1292f, 104f / 2800f, 104f / 1292f),
        "box_left" to Frac(205f / 2800f, 55f / 1292f, 475f / 2800f, 463f / 1292f),
        "box_centre" to Frac(710f / 2800f, 55f / 1292f, 1380f / 2800f, 463f / 1292f),
        "box_right" to Frac(2120f / 2800f, 55f / 1292f, 472f / 2800f, 463f / 1292f),
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
