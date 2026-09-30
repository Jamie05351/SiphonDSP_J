package app.siphondsp.view

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where every live element sits on the front page. Rects are fractions of the art space, x/y/w/h.
 *
 * Head unit: a 2340x878 art space (the 1280x480 screen's aspect). The layout is the approved
 * split faceplate (Figma "SiphonDSP Faceplate", frame "APPROVED -- split 1/3 + 2/3"): the power
 * button alone on the left panel, then two screens recessed into the plate with a strip of plate
 * between them. The top screen (a third of the height) holds Settings on the left, the live panel
 * (output, audio engine, global stages) in the middle and More on the right; the bottom screen (two
 * thirds) holds the five DSP tiles. [app.siphondsp.compose.controls.HomeFaceplate] draws the plate,
 * the `screen_top` / `screen_bottom` and the bezel; each tile is a Compose tile at its rect, so a
 * tile's picture and its touch area are always the same box.
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

    // Head unit, measured off the approved Figma frame (1280x480 px / 1280, / 480), then the gap
    // between the screens widened from 12 to 28 px so a real strip of plate (with the faceplate's
    // engraved seam) separates them, each screen keeping its share and its contents centred. The
    // DSP tiles
    // are square and evenly pitched; More ends flush with the last DSP tile, Settings starts flush
    // with the first. Settings and More are a third smaller than before so the live panel fits
    // between them.
    private val rects = mapOf(
        "screen_top" to Frac(0.1141f, 0.0750f, 0.8484f, 0.2479f),
        "screen_bottom" to Frac(0.1141f, 0.3813f, 0.8484f, 0.4979f),
        "tile_peq" to Frac(0.1290f, 0.4277f, 0.1520f, 0.4050f),
        "tile_gains" to Frac(0.2956f, 0.4277f, 0.1520f, 0.4050f),
        "tile_xovers" to Frac(0.4622f, 0.4277f, 0.1520f, 0.4050f),
        "tile_compressor" to Frac(0.6288f, 0.4277f, 0.1520f, 0.4050f),
        "tile_allpass" to Frac(0.7954f, 0.4277f, 0.1520f, 0.4050f),
        "tile_settings" to Frac(0.1290f, 0.1275f, 0.0536f, 0.1429f),
        "tile_more" to Frac(0.8938f, 0.1275f, 0.0536f, 0.1429f),
        "live_output" to Frac(0.2013f, 0.0802f, 0.2813f, 0.2375f),
        "live_engine" to Frac(0.5266f, 0.0802f, 0.1484f, 0.2375f),
        "live_stages" to Frac(0.7191f, 0.0802f, 0.1563f, 0.2375f),
        // The power button and its glow, a circle centred on the left panel. PowerHotspot draws the
        // button, off and on, within this rect.
        "power_btn" to Frac(0.0096f, 0.3871f, 0.0977f, 0.2606f),
        // No LED dot on the head unit (the view is GONE there); kept so every key resolves.
        "power_led" to Frac(0.034f, 0.78f, 0.004f, 0.01f),
        // Legacy icons, hidden now that Settings and More are tiles; kept so every key resolves.
        "cog" to Frac(0.0109f, 0.0517f, 0.04f, 0.1373f),
        "overflow" to Frac(0.9471f, 0.0544f, 0.04f, 0.1289f),
    )

    // Phone (2340x1080): the same DSP tile columns. The taller art gives the top screen a bigger
    // share (40 % of the screen height, not a third) for the live panel, whose text shrinks to fit
    // if the box is still too short. Settings and More are bigger than the head unit's, so they stay
    // at least 48dp even when a 16:9 phone fits the art to only ~640dp wide.
    private val phoneRects = mapOf(
        "screen_top" to Frac(0.1141f, 0.0602f, 0.8484f, 0.3318f),
        "screen_bottom" to Frac(0.1141f, 0.4420f, 0.8484f, 0.4978f),
        "tile_peq" to Frac(0.1290f, 0.5263f, 0.1520f, 0.3293f),
        "tile_gains" to Frac(0.2956f, 0.5263f, 0.1520f, 0.3293f),
        "tile_xovers" to Frac(0.4622f, 0.5263f, 0.1520f, 0.3293f),
        "tile_compressor" to Frac(0.6288f, 0.5263f, 0.1520f, 0.3293f),
        "tile_allpass" to Frac(0.7954f, 0.5263f, 0.1520f, 0.3293f),
        "tile_settings" to Frac(0.1290f, 0.1438f, 0.0760f, 0.1647f),
        "tile_more" to Frac(0.8714f, 0.1438f, 0.0760f, 0.1647f),
        "live_output" to Frac(0.2180f, 0.0761f, 0.2700f, 0.3000f),
        "live_engine" to Frac(0.5060f, 0.0761f, 0.1600f, 0.3000f),
        "live_stages" to Frac(0.6840f, 0.0761f, 0.1744f, 0.3000f),
        "power_btn" to Frac(0.0096f, 0.3942f, 0.0977f, 0.2117f),
        "power_led" to Frac(0.034f, 0.85f, 0.004f, 0.01f),
        "cog" to ph(30, 50, 90, 90),
        "overflow" to ph(2220, 50, 90, 90),
    )

    /** The two screens, top then bottom; the faceplate recesses each into the plate. */
    val SCREEN_KEYS = listOf("screen_top", "screen_bottom")

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
