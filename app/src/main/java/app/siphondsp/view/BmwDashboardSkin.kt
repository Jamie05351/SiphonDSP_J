package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import app.siphondsp.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/** Shared visual skin for the dedicated BMW DSP workspaces. */
object BmwDashboardSkin {
    const val LIGHT_BLUE = 0xFF46B5E8.toInt()
    // A lighter/whiter blend of LIGHT_BLUE for the sidebar's active tile, so it reads as
    // brighter/"more lit" than the plain accent color used elsewhere (unselected borders,
    // slider tracks, etc.).
    const val LIGHT_BLUE_BRIGHT = 0xFF87CFF0.toInt()
    const val M_BLUE = 0xFF135BA7.toInt()
    const val M_RED = 0xFFE32B3B.toInt()
    // Status green, paired with M_RED as a fixed convention: polarity NORMAL (vs INVERT) on the
    // Gains & Delay channel cards, and the Output page's post-gain sliders.
    const val M_GREEN = 0xFF3DDC84.toInt()
    // Mid-band accent, paired with LIGHT_BLUE for the app-wide Low=blue / Mid=yellow convention
    // (PEQ visualizer, Gains & Delay channel cards/sliders, Crossovers Lowpass/Highpass sliders).
    const val MID_BAND_YELLOW = 0xFFFFCA28.toInt()
    // Solid, flat fill for the sidebar panel -- deliberately not the photo texture the workspace
    // background/cards use, so it reads as a distinct, well-defined fixture rather than blending
    // into the content behind it. Not near-black, so it sits alongside the main panel without the
    // harsh contrast a true near-black would read as "a separate slab dropped on top."
    const val SIDEBAR_GUNMETAL = 0xFF171B21.toInt()

    private val inactiveSurface = Color.rgb(18, 23, 29)
    private val inactiveStroke = Color.rgb(61, 71, 82)
    private val inactiveText = Color.rgb(211, 217, 223)

    // Same flat fill/stroke language as styleCheckableButton's segmented controls (Input
    // Correction/Low Band/Mid Band etc.) -- exposed publicly so the sidebar nav tiles
    // (DspCrossNavBar) can match that look instead of having their own distinct treatment.
    // `get()` (not a plain `val`) because these reference `selectedSurface`, declared further
    // down this same object -- a plain val here would read it before it's initialized.
    val SELECTED_TILE_SURFACE get() = selectedSurface
    val INACTIVE_TILE_SURFACE get() = inactiveSurface
    val INACTIVE_TILE_STROKE get() = inactiveStroke
    private val selectedSurface = Color.rgb(24, 69, 101)

    // Brightness multipliers applied (via ColorMatrix scale) to the same plain-metal crop for the
    // sidebar's unselected vs. selected tile fills, so the selected tile's darker backing makes it
    // visibly "pop" against the lighter unselected ones instead of both reading the same shade.
    private const val UNSELECTED_TILE_BRIGHTNESS = 1.7f
    internal const val SELECTED_TILE_BRIGHTNESS = 0.55f

    fun brushedPanelDrawable(context: Context): Drawable = PhotoBrushedMetalDrawable(context)

    /** Plain (no stripe, no logo) brushed-metal tile fill for sidebar nav tiles -- [darkened]
     *  picks the selected (darker) or unselected (lighter) brightness variant of the same crop.
     *  [strokeColor], if given, is drawn as this drawable's own border (see [MetalTileDrawable]'s
     *  doc for why it can't just be MaterialButton.strokeColor here). */
    fun metalTileDrawable(context: Context, darkened: Boolean, strokeColor: Int? = null): Drawable =
        MetalTileDrawable(context, if (darkened) SELECTED_TILE_BRIGHTNESS else UNSELECTED_TILE_BRIGHTNESS, strokeColor)

    @Volatile private var plainMetalBitmap: Bitmap? = null

    // A dedicated clean texture photo (no stripe or logo baked in, unlike the old composite
    // asset this replaced) -- decoded once and reused (tiled/stretched, at two different
    // brightness levels) for every sidebar tile *and* as PhotoBrushedMetalDrawable's full-screen
    // cover fill. Used near-whole, not a small crop of it: a small crop is fine at sidebar-tile
    // scale but gets magnified far more to cover a full screen, and on a real device's actual
    // pixel dimensions (much larger than this source photo's own 1280x480) that over-magnification
    // blurred a small patch into a flat, washed-out smear -- confirmed on-device (not visible on a
    // lower-res emulator render). Using nearly the whole photo needs much less magnification to
    // cover the same bounds, so the grain stays sharp.
    internal fun loadPlainMetalBitmap(context: Context): Bitmap {
        plainMetalBitmap?.let { return it }
        synchronized(this) {
            plainMetalBitmap?.let { return it }
            val decoded = BitmapFactory.decodeResource(context.applicationContext.resources, R.drawable.bmw_workspace_texture)
            plainMetalBitmap = decoded
            return decoded
        }
    }

    @Volatile private var workspaceBackgroundBitmap: Bitmap? = null

    // The DSP workspace full-screen background: a dedicated asset (distinct from
    // bmw_workspace_texture, which stays reserved for the sidebar tiles) so this can be swapped
    // without affecting tile rendering -- decoded once and reused across every workspace Activity.
    internal fun loadWorkspaceBackgroundBitmap(context: Context): Bitmap {
        workspaceBackgroundBitmap?.let { return it }
        synchronized(this) {
            workspaceBackgroundBitmap?.let { return it }
            val decoded = BitmapFactory.decodeResource(context.applicationContext.resources, R.drawable.bmw_bg_workspace)
            workspaceBackgroundBitmap = decoded
            return decoded
        }
    }

    /**
     * The sidebar's active-tile background: a bright, genuinely "lit" fill (the accent blue
     * itself, not the muted navy used for selected pills/chips elsewhere -- against the sidebar's
     * own dark background that muted tone read as barely different from unselected) with a
     * blurred glow ring around the border so the tile visibly radiates rather than just having a
     * crisp outline. Requires the host view to run on a software layer -- BlurMaskFilter has no
     * hardware-accelerated path -- see the LAYER_TYPE_SOFTWARE call in DspCrossNavBar.populate().
     */
    fun litTileDrawable(context: Context): Drawable = IlluminatedTileDrawable(context)

    /**
     * Transparent everywhere except when the host view has Android keyboard/D-pad focus, in which
     * case it draws the same glow-ring language as [litTileDrawable] around the tile's edge. This
     * is what makes a hardware rotary controller's (e.g. a car's iDrive wheel, wired in through a
     * CAN-to-Android adapter) current position visible on an unselected sidebar tile -- that
     * input moves Android focus exactly like a Tab key would, never touching the screen, so there
     * is otherwise no on-screen indication of where it currently sits.
     */
    fun sidebarTileFocusRingDrawable(context: Context): Drawable = TileFocusRingDrawable(context)

    // Single slider thumb/track/capsule shared by every DSP workspace slider (Gains/Delay,
    // Crossovers & Tilt, Mono Bass, Routing, Compressor). Recreates the user-supplied
    // J_DSP_slider_master_style_4_colour pack (slider_{cyan,dark_blue,yellow,purple}_vector.xml):
    // a compact 18dp housing with an inset recessed groove (outer capsule shell + inset bezel +
    // thin highlight line) and a slim 6.5dp value fill, with the fill and thumb both tinted per
    // accent. The groove/bezel layers are painted onto the Slider's own `background`
    // (SliderCapsuleDrawable, below); the actual active/inactive fill stays Slider's real native
    // track (its height set from SLIDER_TRACK_HEIGHT_DP, recolored via trackColorActive/
    // trackColorInactive) rather than hand-painted, deliberately -- Slider only exposes
    // trackHeight/trackColorActive/trackColorInactive, not a pluggable track drawable, so
    // repainting the live value-fill ourselves would need a Slider subclass reverse-engineering
    // BaseSlider's internal padding/thumb-radius inset math to find the exact active/inactive
    // split each frame, and any
    // drift from that math would visibly desync the fill's edge from the real thumb position. The
    // reference's own gradient gets approximated as a single lightened solid for the same reason
    // (trackColorActive only accepts a flat tint, not a Shader) -- reads correctly at the size
    // this actually renders at, so not worth the alignment risk. Dimensions below are starting
    // numbers picked by eye to match the reference's proportions against this app's existing row
    // heights, same as every other pixel spec in this codebase, meant to be refined on-device.
    const val SLIDER_ROW_MIN_HEIGHT_DP = 28
    // Used by CrossoverDashboardBuilder's slider rows (Gains & Delay, Crossovers & Tilt, Routing,
    // and now the multiband Compressor). Safe regardless of title-box width: every title box in a
    // group is sized to match its longest sibling (see CrossoverDashboardBuilder's
    // pendingTitleBoxes), so this gap no longer needs to be 0 to avoid the "different title
    // widths -> different slider lengths" problem that briefly motivated zeroing it.
    const val SLIDER_TITLE_GAP_DP = 75
    const val SLIDER_VALUE_GAP_DP = 24
    const val SLIDER_TITLE_HEIGHT_DP = 30
    const val SLIDER_VALUE_HEIGHT_DP = 30
    const val SLIDER_VALUE_MIN_WIDTH_DP = 64
    // Still referenced by SliderCapsuleDrawable below (the slider track's own capsule fill),
    // independent of the value/title box family.
    internal val SLIDER_BOX_BACKGROUND_COLOR = Color.rgb(0x10, 0x13, 0x18)

    // "Glass box" value/title box background: recreates the user-supplied sidebar glass-panel
    // artwork (glass_panel_1536x768.xml) at 40% overall opacity, so every value/title box across
    // the app's DSP workspaces (Gains & Delay, Crossovers & Tilt, Routing, Parametric EQ) reads
    // as the same fixture as the sidebar. Every color below is that vector's own color with its
    // alpha channel scaled to 40% (0.8x the earlier 50% pass). Built as a Canvas Drawable rather
    // than reused directly as a VectorDrawable: that source vector has a fixed 1536x768 viewport
    // with hardcoded pixel corner/rim geometry, and stretching it non-uniformly to fit boxes this
    // small and this varied in aspect ratio would squash the rounded corners exactly like the
    // sidebar bar-art squish bug this app already hit once. Drawing with a RectF keeps the corner
    // radius correct at any size.
    internal const val GLASS_BOX_CORNER_RADIUS_DP = 5f
    internal const val GLASS_BOX_STROKE_WIDTH_DP = 1.25f
    internal val GLASS_FILL_TOP = Color.argb(0x66, 0x11, 0x13, 0x16)
    internal val GLASS_FILL_MID = Color.argb(0x66, 0x08, 0x0A, 0x0C)
    internal val GLASS_FILL_BOTTOM = Color.argb(0x66, 0x02, 0x02, 0x03)
    internal val GLASS_RIM_TOP = Color.argb(0x56, 0xD4, 0xD6, 0xD8)
    internal val GLASS_RIM_MID = Color.argb(0x3B, 0x34, 0x38, 0x3D)
    internal val GLASS_RIM_BOTTOM = Color.argb(0x4B, 0xD9, 0xDB, 0xDD)
    internal val GLASS_SHEEN_NEAR = Color.argb(0x2C, 0xFF, 0xFF, 0xFF)
    internal val GLASS_SHEEN_FAR = Color.argb(0x00, 0xFF, 0xFF, 0xFF)
    internal val GLASS_BOX_TOP_GLINT_COLOR = Color.argb(0x39, 0xFF, 0xFF, 0xFF)
    internal val GLASS_BOX_BOTTOM_GLINT_COLOR = Color.argb(0x2E, 0xDE, 0xDE, 0xDE)

    // accentColor, when given, tints the box's border to that exact color instead of the default
    // neutral glass rim -- used to match a colour-coded slider row's title/value boxes to that
    // row's own band colour (Low=blue/Mid=yellow) rather than leaving them neutral while the
    // slider itself is colored.
    fun glassBoxDrawable(context: Context, showBorder: Boolean = true, accentColor: Int? = null): Drawable =
        GlassBoxDrawable(context, showBorder, accentColor)

    // Compact "master style" geometry, taken from slider_{cyan,dark_blue,yellow,purple}_vector.xml
    // (the 320x22dp master art, viewport 1280x88 -> divide by 4 for dp): a 36.5x18dp thumb whose
    // body matches the 18dp housing height exactly rather than overhanging it, over a slim 6.5dp
    // active fill -- replacing the earlier taller-thumb / 15dp-fill treatment that read as bulky.
    const val SLIDER_THUMB_WIDTH_DP = 36
    const val SLIDER_THUMB_HEIGHT_DP = 18
    internal const val SLIDER_THUMB_CORNER_RADIUS_DP = 7.5f
    internal const val SLIDER_THUMB_BORDER_WIDTH_DP = 1f
    internal val SLIDER_THUMB_BORDER_COLOR = Color.rgb(0x7A, 0x7A, 0x7A)
    // 3-stop vertical gradient: lit grey at top, mid grey centre, dark grey at bottom.
    internal val thumbGradientTop = Color.rgb(0x77, 0x7B, 0x80)
    internal val thumbGradientCenter = Color.rgb(0x51, 0x55, 0x5A)
    internal val thumbGradientBottom = Color.rgb(0x32, 0x35, 0x3A)
    internal val SLIDER_THUMB_HIGHLIGHT_COLOR = Color.argb(153, 0xC5, 0xC8, 0xCB) // 60%
    internal val SLIDER_THUMB_SHADOW_COLOR = Color.argb(140, 0, 0, 0) // 55%
    internal const val SLIDER_THUMB_INSET_MARGIN_DP = 5.25f
    internal const val SLIDER_THUMB_INSET_CORNER_RADIUS_DP = 3.75f
    internal val SLIDER_THUMB_INSET_FILL_COLOR = Color.rgb(0x18, 0x18, 0x18)
    internal val SLIDER_THUMB_INSET_BORDER_COLOR = Color.rgb(0x11, 0x13, 0x17)
    internal const val SLIDER_THUMB_INSET_BORDER_WIDTH_DP = 1f
    // Three short accent grip ticks above and below the thumb's inset slot -- the master art's
    // own thumb-face detail (viewport y15..23 above, y65..73 below -> 2dp long, ~5dp apart).
    internal const val SLIDER_THUMB_TICK_COUNT = 3
    internal const val SLIDER_THUMB_TICK_LENGTH_DP = 2f
    internal const val SLIDER_THUMB_TICK_SPACING_DP = 5f
    internal const val SLIDER_THUMB_TICK_STROKE_WIDTH_DP = 0.75f

    // Per-band slider accent -- neon variants of the J_DSP_slider_master_style_4_colour pack's
    // hues: Low=blue, Mid=yellow, Headroom=purple, everything else=cyan (the default, when no
    // accentColor is passed). The pack's originals (#00266D / #FEF200 / #4632A5 / #009AED) read
    // too dark on-device once the thumb gradient blends each toward black at its base, so these
    // are lifted to high-value neon tones. Distinct from LIGHT_BLUE/MID_BAND_YELLOW (used for
    // title text/PEQ curves/diagram lines elsewhere): those stay as they are, this is the
    // sliders' own accent palette so a slider's thumb+capsule+fill can use its own exact hue
    // without recoloring anything else on the row.
    const val SLIDER_LOW_BAND_COLOR = 0xFF3D6BFF.toInt()
    const val SLIDER_MID_BAND_COLOR = 0xFFFFE500.toInt()
    const val SLIDER_HEADROOM_COLOR = 0xFFB14DFF.toInt()
    // Tonality Tilt: a bright neon orange, distinct from the Mid band's pure yellow.
    const val SLIDER_TILT_COLOR = 0xFFFF6A00.toInt()
    const val SLIDER_DEFAULT_COLOR = 0xFF12CFFF.toInt()

    // The exact neon green the ON/OFF glass switch lights up with -- reused by the page-toggle
    // strip so a selected page reads as "on" in the same language.
    const val TOGGLE_ON_GREEN = 0xFF39FF14.toInt()

    // Master art (viewport / 4): 18dp housing, 8.5dp inset groove, 6.5dp active fill. The active
    // fill is now slimmer than the groove it rides in, so the groove reads as a real channel the
    // fill sits inside of rather than the fill packing the whole capsule top to bottom.
    const val SLIDER_TRACK_HEIGHT_DP = 6.5f
    internal const val SLIDER_CAPSULE_HEIGHT_DP = 18f
    internal const val SLIDER_CAPSULE_BORDER_WIDTH_DP = 1f
    // The groove sits inset within the outer capsule shell (master art: 18dp shell, 8.5dp groove
    // -> a 4.75dp margin each side), and gets its own thin inner highlight line 1dp further in.
    internal const val SLIDER_GROOVE_MARGIN_DP = 4.75f
    internal const val SLIDER_GROOVE_STROKE_WIDTH_DP = 1f
    internal const val SLIDER_GROOVE_HIGHLIGHT_MARGIN_DP = 1.5f
    internal val SLIDER_GROOVE_FILL_COLOR = Color.rgb(0x07, 0x07, 0x07)
    internal val SLIDER_GROOVE_STROKE_COLOR = Color.rgb(0x2B, 0x2B, 0x2B)
    internal val SLIDER_GROOVE_HIGHLIGHT_COLOR = Color.rgb(0x26, 0x26, 0x28)
    // Fully transparent, not a dark fill colour: the groove drawn underneath (see
    // SliderCapsuleDrawable) already supplies the "unfilled" look, so the real native track only
    // needs to contribute the active (lit) portion on top of it.
    val SLIDER_TRACK_INACTIVE_COLOR = Color.TRANSPARENT
    val SLIDER_HALO_COLOR = Color.argb(42, 70, 181, 232)
    const val SLIDER_HALO_RADIUS_DP = 4

    // [accentColor], when given, replaces the thumb's neutral grey 3-stop gradient with one
    // derived from that color (lit/mid/dark shades of it) instead -- used to color-code the
    // Low-band (blue) / Mid-band (yellow) sliders on the Crossovers and Gains & Delay pages, so
    // those specific rows read as a distinct colored knob rather than the plain metal one every
    // other slider still uses.
    fun sliderThumbDrawable(context: Context, accentColor: Int? = null): Drawable = SliderThumbDrawable(context, accentColor)
    fun sliderCapsuleDrawable(context: Context, accentColor: Int? = null): Drawable = SliderCapsuleDrawable(context, accentColor)

    // Round ON/OFF switch, recreated from the dedicated glass_toggle_on.xml art -- same glass-
    // panel language as the slider capsule/glass box above. The switch now carries a red/green
    // status convention: OFF = neon-red shell border + red thumb, ON = neon-green shell border +
    // green thumb (both the track's shell border and the thumb are stateful on state_checked).
    internal const val GLASS_SWITCH_TRACK_WIDTH_DP = 75f
    internal const val GLASS_SWITCH_TRACK_HEIGHT_DP = 20f
    internal const val GLASS_SWITCH_TRACK_BORDER_WIDTH_DP = 1.3f
    internal val GLASS_SWITCH_TRACK_FILL_TOP = Color.rgb(0x11, 0x11, 0x11)
    internal val GLASS_SWITCH_TRACK_FILL_MID = Color.rgb(0x09, 0x09, 0x09)
    internal val GLASS_SWITCH_TRACK_FILL_BOTTOM = Color.BLACK
    internal val GLASS_SWITCH_TRACK_RIM_COLOR = Color.argb(0x6B, 0xF5, 0xF5, 0xF5)
    internal val GLASS_SWITCH_TRACK_SHEEN_NEAR = Color.argb(0x61, 0xFF, 0xFF, 0xFF)
    internal val GLASS_SWITCH_TRACK_SHEEN_FAR = Color.argb(0x00, 0xFF, 0xFF, 0xFF)

    // Neon red (OFF) / neon green (ON) status pair, shared by the switch's shell border and thumb.
    internal val GLASS_SWITCH_OFF_COLOR = Color.rgb(0xFF, 0x31, 0x31)
    internal val GLASS_SWITCH_ON_COLOR = Color.rgb(0x39, 0xFF, 0x14)

    // Matches the 20dp track exactly -- flush, no overhang. glow/inset/border/highlight below
    // stay their own fixed dp (unchanged), so this only sets how big the visible ball reads.
    internal const val GLASS_SWITCH_THUMB_SIZE_DP = 20f
    internal val GLASS_SWITCH_THUMB_ON_FILL_NEAR = blend(GLASS_SWITCH_ON_COLOR, Color.WHITE, 0.3f)
    internal val GLASS_SWITCH_THUMB_ON_FILL_FAR = GLASS_SWITCH_ON_COLOR
    internal val GLASS_SWITCH_THUMB_ON_BORDER = Color.argb(0xF0, Color.red(GLASS_SWITCH_ON_COLOR), Color.green(GLASS_SWITCH_ON_COLOR), Color.blue(GLASS_SWITCH_ON_COLOR))
    internal val GLASS_SWITCH_THUMB_ON_GLOW = Color.argb(0xA0, Color.red(GLASS_SWITCH_ON_COLOR), Color.green(GLASS_SWITCH_ON_COLOR), Color.blue(GLASS_SWITCH_ON_COLOR))
    internal val GLASS_SWITCH_THUMB_ON_RING = Color.argb(0xF5, Color.red(GLASS_SWITCH_ON_COLOR), Color.green(GLASS_SWITCH_ON_COLOR), Color.blue(GLASS_SWITCH_ON_COLOR))
    internal val GLASS_SWITCH_THUMB_HIGHLIGHT = Color.argb(0x6B, 0xFF, 0xFF, 0xFF)
    internal val GLASS_SWITCH_THUMB_OFF_FILL_NEAR = blend(GLASS_SWITCH_OFF_COLOR, Color.WHITE, 0.25f)
    internal val GLASS_SWITCH_THUMB_OFF_FILL_FAR = GLASS_SWITCH_OFF_COLOR
    internal val GLASS_SWITCH_THUMB_OFF_BORDER = Color.argb(0xF0, Color.red(GLASS_SWITCH_OFF_COLOR), Color.green(GLASS_SWITCH_OFF_COLOR), Color.blue(GLASS_SWITCH_OFF_COLOR))
    internal val GLASS_SWITCH_THUMB_OFF_GLOW = Color.argb(0xA0, Color.red(GLASS_SWITCH_OFF_COLOR), Color.green(GLASS_SWITCH_OFF_COLOR), Color.blue(GLASS_SWITCH_OFF_COLOR))
    internal val GLASS_SWITCH_THUMB_OFF_RING = Color.argb(0xF5, Color.red(GLASS_SWITCH_OFF_COLOR), Color.green(GLASS_SWITCH_OFF_COLOR), Color.blue(GLASS_SWITCH_OFF_COLOR))

    fun glassSwitchTrackDrawable(context: Context): Drawable = GlassSwitchTrackDrawable(context)
    // The switch's red/green states are a fixed status convention now, so no accentColor hook --
    // every ON/OFF switch reads the same regardless of which colour-coded row it sits in.
    fun glassSwitchThumbDrawable(context: Context): Drawable = GlassSwitchThumbDrawable(context)

    // NORMAL/INVERT segmented polarity toggle (buildMiniToggleRow), recreated from the dedicated
    // glass_normal_invert_toggle.xml art -- a fully-rounded stadium capsule rather than
    // segmentButton's previous small-corner-radius rectangle.
    internal const val GLASS_SEGMENT_BORDER_WIDTH_DP = 1.3f
    internal const val GLASS_SEGMENT_GLOW_WIDTH_DP = 3f
    internal val GLASS_SEGMENT_TRACK_BORDER_COLOR = Color.rgb(0x3E, 0x43, 0x52)
    internal val GLASS_SEGMENT_TRACK_RIM_COLOR = Color.argb(0x29, 0xF0, 0xF0, 0xF0)
    internal val GLASS_SEGMENT_TRACK_FILL_NEAR = Color.rgb(0x0B, 0x0C, 0x10)
    internal val GLASS_SEGMENT_TRACK_FILL_FAR = Color.BLACK
    internal val GLASS_SEGMENT_FILL_NEAR = Color.rgb(0x4A, 0xA9, 0xD8)
    internal val GLASS_SEGMENT_FILL_MID = Color.rgb(0x0D, 0x5D, 0x85)
    internal val GLASS_SEGMENT_FILL_FAR = Color.rgb(0x0A, 0x4F, 0x73)
    internal val GLASS_SEGMENT_BORDER_COLOR = Color.rgb(0x31, 0xD2, 0xFF)
    internal val GLASS_SEGMENT_GLOW_COLOR = Color.argb(0x50, 0x31, 0xD2, 0xFF)
    internal val GLASS_SEGMENT_SHEEN_NEAR = Color.argb(0x54, 0xFF, 0xFF, 0xFF)
    internal val GLASS_SEGMENT_SHEEN_FAR = Color.argb(0x00, 0xFF, 0xFF, 0xFF)

    fun glassSegmentTrackDrawable(context: Context): Drawable = GlassSegmentTrackDrawable(context)
    // accentColor recolors the selected segment's gradient fill/glow/border, for the two-way
    // (eg. NORMAL/INVERT) toggle variant -- same blend-toward-accent technique SliderThumbDrawable
    // uses. (The plain ON/OFF switch has no such hook: it's a fixed red/green status pair.)
    fun glassSegmentDrawable(context: Context, accentColor: Int? = null): Drawable = GlassSegmentDrawable(context, accentColor)

    /** Apply automotive chrome to existing XML-driven cards and controls without touching behavior. */
    fun styleTree(root: View) {
        when (root) {
            is MaterialCardView -> styleCard(root)
            is Slider -> styleSlider(root)
            is MaterialSwitch -> styleSwitch(root)
            is SwitchCompat -> styleSwitch(root)
            is Chip -> styleChip(root)
            is MaterialButton -> if (root.isCheckable) styleCheckableButton(root)
        }

        if (root is ViewGroup) {
            for (index in 0 until root.childCount) styleTree(root.getChildAt(index))
        }
    }

    // Public so callers that build/inflate sliders, cards, or switches onto pages ViewPager2 may
    // not have attached yet (e.g. NativeBmwCompressorFragment's off-screen pager pages) can style
    // them directly at creation time, instead of relying on styleTree's later recursive walk to
    // reach them.
    fun styleSlider(slider: Slider, accentColor: Int? = null) {
        val context = slider.context
        val accent = accentColor ?: SLIDER_DEFAULT_COLOR
        slider.trackHeight = dpF(context, SLIDER_TRACK_HEIGHT_DP).roundToInt()
        slider.thumbWidth = dp(context, SLIDER_THUMB_WIDTH_DP)
        slider.thumbHeight = dp(context, SLIDER_THUMB_HEIGHT_DP)
        slider.haloRadius = dp(context, SLIDER_HALO_RADIUS_DP)
        // The active fill tints per [accent] too (a lightened blend, approximating the master
        // art's gradient -- see the class doc above), not just the handle: the master pack's
        // slider_{cyan,dark_blue,yellow,purple}_vector.xml all show the whole value fill tinted,
        // not just the thumb.
        slider.setTrackActiveTintList(ColorStateList.valueOf(blend(accent, Color.WHITE, 0.2f)))
        slider.setTrackInactiveTintList(ColorStateList.valueOf(SLIDER_TRACK_INACTIVE_COLOR))
        slider.setHaloTintList(ColorStateList.valueOf(SLIDER_HALO_COLOR))
        // [accent] (resolved above, never null) is passed rather than the raw nullable
        // [accentColor] -- every slider now gets a colored thumb+track+capsule, defaulting to
        // SLIDER_DEFAULT_COLOR's cyan, matching how all 4 of the master pack's own variants are
        // colored presets with no neutral-grey option among them.
        slider.setCustomThumbDrawable(sliderThumbDrawable(context, accent))
        // The capsule outline sits on the Slider's own background, not a separate wrapping view --
        // it draws a fixed-height pill centred within whatever bounds the Slider view ends up
        // with, so it stays aligned with Slider's own (also vertically-centred) track regardless
        // of the view's actual measured height.
        slider.background = sliderCapsuleDrawable(context, accent)
        // The capsule's focus glow uses BlurMaskFilter, which silently no-ops on a hardware
        // layer -- without this the ring would still appear on focus, just as a crisp unblurred
        // outline instead of a soft glow.
        slider.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    // No fill: lets the workspace's designed background (and its "M" watermark) show through
    // behind the card, same reasoning as CrossoverDashboardBuilder's dashboardPanel and
    // crossoverBandCard. A live-visualizer view inside a styled card (ParametricEqSurface,
    // CompressorSurface) still paints its own solid background every frame regardless -- only
    // the card's own body becomes see-through.
    fun styleCard(card: MaterialCardView) {
        card.radius = dp(card.context, 7).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = dp(card.context, 1)
        card.strokeColor = inactiveStroke
        card.setCardBackgroundColor(Color.TRANSPARENT)
    }

    fun styleSwitch(toggle: SwitchCompat) {
        // null clears any tint list so the custom drawables' own colors show through unfiltered
        // -- SwitchCompat otherwise applies thumbTintList/trackTintList as a color filter on top.
        toggle.thumbTintList = null
        toggle.trackTintList = null
        toggle.thumbDrawable = glassSwitchThumbDrawable(toggle.context)
        toggle.trackDrawable = glassSwitchTrackDrawable(toggle.context)
    }

    /**
     * PEQ scope chips inherit Material's app colorPrimary by default. Give selected chips the
     * same BMW light-blue language as the sliders/nav, while preserving dark inactive chips.
     */
    private fun styleChip(chip: Chip) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        )
        chip.chipBackgroundColor = ColorStateList(
            states,
            intArrayOf(selectedSurface, Color.rgb(28, 35, 43), inactiveSurface),
        )
        chip.chipStrokeColor = ColorStateList(
            states,
            intArrayOf(LIGHT_BLUE, Color.rgb(83, 96, 109), inactiveStroke),
        )
        chip.chipStrokeWidth = dp(chip.context, 1).toFloat()
        chip.setTextColor(
            ColorStateList(
                states,
                intArrayOf(Color.WHITE, Color.WHITE, inactiveText),
            )
        )
        chip.chipIconTint = ColorStateList(
            states,
            intArrayOf(LIGHT_BLUE, LIGHT_BLUE, Color.rgb(172, 184, 195)),
        )
        chip.checkedIconTint = ColorStateList.valueOf(LIGHT_BLUE)
    }

    /**
     * Only recolor buttons that are genuine selection controls (Graph/List, Low/Mid band,
     * filter/channel segmented controls). Ordinary Add/Done/Cancel/action buttons keep their
     * existing Material treatment.
     */
    private fun styleCheckableButton(button: MaterialButton) {
        // state_focused is listed before the checked/pressed states: a D-pad/rotary controller
        // (e.g. a car's iDrive wheel) moving Android focus onto an already-checked button (the
        // currently active Graph/List or Low/Mid selection) should still show the focus ring, not
        // get silently swallowed by the checked color -- ColorStateList picks the first state
        // array in the list that fully matches, so focused+checked must resolve to the focused
        // entry, not the checked one further down.
        val states = arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        )
        button.backgroundTintList = ColorStateList(
            states,
            intArrayOf(selectedSurface, selectedSurface, Color.rgb(28, 35, 43), inactiveSurface),
        )
        button.strokeColor = ColorStateList(
            states,
            intArrayOf(LIGHT_BLUE_BRIGHT, LIGHT_BLUE, Color.rgb(83, 96, 109), inactiveStroke),
        )
        // strokeWidth is a single Int on MaterialButton, not stateful like the ColorStateLists
        // above -- focus is distinguished by the brighter LIGHT_BLUE_BRIGHT stroke color instead.
        button.strokeWidth = dp(button.context, 1)
        button.setTextColor(
            ColorStateList(
                states,
                intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE, inactiveText),
            )
        )
        button.iconTint = ColorStateList(
            states,
            intArrayOf(LIGHT_BLUE_BRIGHT, LIGHT_BLUE, LIGHT_BLUE, Color.rgb(172, 184, 195)),
        )
    }

    internal fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).roundToInt()

    // Fractional dp -> px, for sub-pixel spec values (2.5dp track height, 1.5dp capsule gap,
    // etc.) that would round away to nothing (or the wrong whole number) through the Int dp()
    // above -- returned as Float since callers here need sub-pixel precision for Paint geometry,
    // not an Int pixel count.
    private fun dpF(context: Context, value: Float) = value * context.resources.displayMetrics.density

    /** Linear per-channel blend of [from] toward [to] by [t] (0 = [from], 1 = [to]). */
    internal fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt(),
    )
}
