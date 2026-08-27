package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.Drawable.ConstantState
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
    private const val SELECTED_TILE_BRIGHTNESS = 0.55f

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
    private fun loadPlainMetalBitmap(context: Context): Bitmap {
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
    private fun loadWorkspaceBackgroundBitmap(context: Context): Bitmap {
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
    // slider_{dark_blue,light_blue,purple,yellow}.xml reference art: a chunky recessed groove
    // (outer capsule shell + inset bezel + thin highlight line) with the fill and thumb both
    // tinted per accent. The groove/bezel layers are painted onto the Slider's own `background`
    // (SliderCapsuleDrawable, below); the actual active/inactive fill stays Slider's real native
    // track (just made much taller and recolored via trackColorActive/trackColorInactive) rather
    // than hand-painted, deliberately -- Slider only exposes trackHeight/trackColorActive/
    // trackColorInactive, not a pluggable track drawable, so repainting the live value-fill
    // ourselves would need a Slider subclass reverse-engineering BaseSlider's internal
    // padding/thumb-radius inset math to find the exact active/inactive split each frame, and any
    // drift from that math would visibly desync the fill's edge from the real thumb position. The
    // reference's own gradient gets approximated as a single lightened solid for the same reason
    // (trackColorActive only accepts a flat tint, not a Shader) -- reads correctly at the size
    // this actually renders at, so not worth the alignment risk. Dimensions below are starting
    // numbers picked by eye to match the reference's proportions against this app's existing row
    // heights, same as every other pixel spec in this codebase, meant to be refined on-device.
    const val SLIDER_ROW_MIN_HEIGHT_DP = 28
    // Used only by CrossoverDashboardBuilder's own slider rows (Gains & Delay, Crossovers & Tilt,
    // Routing) -- Compressor's XML rows hardcode their own marginStart directly in
    // page_compressor_band.xml (currently 0dp) rather than referencing this constant, so the two
    // can differ without touching each other. Safe regardless of title-box width now: every title
    // box in a group is sized to match its longest sibling (see CrossoverDashboardBuilder's
    // pendingTitleBoxes and NativeBmwCompressorFragment.resizeTitleBoxesToLongest()), so this gap
    // no longer needs to be 0 to avoid the "different title widths -> different slider lengths"
    // problem that briefly motivated zeroing it.
    const val SLIDER_TITLE_GAP_DP = 75
    const val SLIDER_VALUE_GAP_DP = 24
    const val SLIDER_TITLE_HEIGHT_DP = 40
    const val SLIDER_VALUE_HEIGHT_DP = 24
    const val SLIDER_VALUE_MIN_WIDTH_DP = 64
    // Still referenced by SliderCapsuleDrawable below (the slider track's own capsule fill),
    // independent of the value/title box family.
    private val SLIDER_BOX_BACKGROUND_COLOR = Color.rgb(0x10, 0x13, 0x18)

    // "Glass box" value/title box background: recreates the user-supplied sidebar glass-panel
    // artwork (glass_panel_1536x768.xml) at 50% overall opacity, so every value/title box across
    // the app's DSP workspaces (Gains & Delay, Crossovers & Tilt, Routing) reads as the same
    // fixture as the sidebar. Every color below is that vector's own color with its alpha channel
    // halved. Built as a Canvas Drawable rather than reused directly as a VectorDrawable: that
    // source vector has a fixed 1536x768 viewport with hardcoded pixel corner/rim geometry, and
    // stretching it non-uniformly to fit boxes this small and this varied in aspect ratio would
    // squash the rounded corners exactly like the sidebar bar-art squish bug this app already hit
    // once. Drawing with a RectF keeps the corner radius correct at any size.
    private const val GLASS_BOX_CORNER_RADIUS_DP = 5f
    private const val GLASS_BOX_STROKE_WIDTH_DP = 1.25f
    private val GLASS_FILL_TOP = Color.argb(0x80, 0x11, 0x13, 0x16)
    private val GLASS_FILL_MID = Color.argb(0x80, 0x08, 0x0A, 0x0C)
    private val GLASS_FILL_BOTTOM = Color.argb(0x80, 0x02, 0x02, 0x03)
    private val GLASS_RIM_TOP = Color.argb(0x6B, 0xD4, 0xD6, 0xD8)
    private val GLASS_RIM_MID = Color.argb(0x4A, 0x34, 0x38, 0x3D)
    private val GLASS_RIM_BOTTOM = Color.argb(0x5E, 0xD9, 0xDB, 0xDD)
    private val GLASS_SHEEN_NEAR = Color.argb(0x37, 0xFF, 0xFF, 0xFF)
    private val GLASS_SHEEN_FAR = Color.argb(0x00, 0xFF, 0xFF, 0xFF)
    private val GLASS_BOX_TOP_GLINT_COLOR = Color.argb(0x47, 0xFF, 0xFF, 0xFF)
    private val GLASS_BOX_BOTTOM_GLINT_COLOR = Color.argb(0x39, 0xDE, 0xDE, 0xDE)

    // accentColor, when given, tints the box's border to that exact color instead of the default
    // neutral glass rim -- used to match a colour-coded slider row's title/value boxes to that
    // row's own band colour (Low=blue/Mid=yellow) rather than leaving them neutral while the
    // slider itself is colored.
    fun glassBoxDrawable(context: Context, showBorder: Boolean = true, accentColor: Int? = null): Drawable =
        GlassBoxDrawable(context, showBorder, accentColor)

    const val SLIDER_THUMB_WIDTH_DP = 36
    const val SLIDER_THUMB_HEIGHT_DP = 24
    private const val SLIDER_THUMB_CORNER_RADIUS_DP = 5f
    private const val SLIDER_THUMB_BORDER_WIDTH_DP = 1f
    private val SLIDER_THUMB_BORDER_COLOR = Color.rgb(0x9A, 0x9D, 0xA1)
    // 3-stop vertical gradient: lit grey at top, mid grey centre, dark grey at bottom.
    private val thumbGradientTop = Color.rgb(0x77, 0x7B, 0x80)
    private val thumbGradientCenter = Color.rgb(0x51, 0x55, 0x5A)
    private val thumbGradientBottom = Color.rgb(0x32, 0x35, 0x3A)
    private val SLIDER_THUMB_HIGHLIGHT_COLOR = Color.argb(153, 0xC5, 0xC8, 0xCB) // 60%
    private val SLIDER_THUMB_SHADOW_COLOR = Color.argb(140, 0, 0, 0) // 55%
    private const val SLIDER_THUMB_INSET_MARGIN_DP = 3f
    private const val SLIDER_THUMB_INSET_CORNER_RADIUS_DP = 3f
    private val SLIDER_THUMB_INSET_FILL_COLOR = Color.rgb(0x25, 0x28, 0x2D)
    private val SLIDER_THUMB_INSET_BORDER_COLOR = Color.rgb(0x11, 0x13, 0x17)
    private const val SLIDER_THUMB_INSET_BORDER_WIDTH_DP = 1f

    // Per-band slider accent, taken directly from the dedicated
    // slider_{dark_blue,light_blue,purple,yellow}.xml art's own capsule-outline stroke color --
    // Low=dark blue, Mid=yellow, Headroom=purple, everything else=light blue (the default, when
    // no accentColor is passed). Distinct from LIGHT_BLUE/MID_BAND_YELLOW (used for title
    // text/PEQ curves/diagram lines elsewhere): those stay as they are, this is the sliders' own
    // accent palette so a slider's thumb+capsule+fill can use its own exact hue without
    // recoloring anything else on the row.
    const val SLIDER_LOW_BAND_COLOR = 0xFF1F4F9A.toInt()
    const val SLIDER_MID_BAND_COLOR = 0xFFFFB000.toInt()
    const val SLIDER_HEADROOM_COLOR = 0xFF8B56FF.toInt()
    const val SLIDER_DEFAULT_COLOR = 0xFF63C7FF.toInt()

    const val SLIDER_TRACK_HEIGHT_DP = 15f
    private const val SLIDER_CAPSULE_HEIGHT_DP = 21f
    private const val SLIDER_CAPSULE_BORDER_WIDTH_DP = 1.5f
    // The groove sits inset within the outer capsule shell (see the reference art's own
    // 15pt-radius shell vs. 8pt-radius groove, at the same relative proportion here), and gets
    // its own thin inner highlight line 1dp further in again.
    private const val SLIDER_GROOVE_MARGIN_DP = 3.5f
    private const val SLIDER_GROOVE_STROKE_WIDTH_DP = 1f
    private const val SLIDER_GROOVE_HIGHLIGHT_MARGIN_DP = 1.5f
    private val SLIDER_GROOVE_FILL_COLOR = Color.rgb(0x1B, 0x1B, 0x1D)
    private val SLIDER_GROOVE_STROKE_COLOR = Color.rgb(0x31, 0x31, 0x34)
    private val SLIDER_GROOVE_HIGHLIGHT_COLOR = Color.rgb(0x26, 0x26, 0x28)
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
    // panel language as the slider capsule/glass box above. Only an ON state was supplied; the OFF
    // thumb below (flat neutral grey, no glow) is extrapolated to match the existing app's grey-
    // when-off convention rather than invented from nothing.
    private const val GLASS_SWITCH_TRACK_WIDTH_DP = 50f
    private const val GLASS_SWITCH_TRACK_HEIGHT_DP = 26f
    private const val GLASS_SWITCH_TRACK_BORDER_WIDTH_DP = 1.3f
    private val GLASS_SWITCH_SHELL_BORDER_COLOR = Color.rgb(0x2A, 0x2A, 0x2F)
    private val GLASS_SWITCH_TRACK_FILL_TOP = Color.rgb(0x11, 0x11, 0x11)
    private val GLASS_SWITCH_TRACK_FILL_MID = Color.rgb(0x09, 0x09, 0x09)
    private val GLASS_SWITCH_TRACK_FILL_BOTTOM = Color.BLACK
    private val GLASS_SWITCH_TRACK_RIM_COLOR = Color.argb(0x6B, 0xF5, 0xF5, 0xF5)
    private val GLASS_SWITCH_TRACK_SHEEN_NEAR = Color.argb(0x61, 0xFF, 0xFF, 0xFF)
    private val GLASS_SWITCH_TRACK_SHEEN_FAR = Color.argb(0x00, 0xFF, 0xFF, 0xFF)

    private const val GLASS_SWITCH_THUMB_SIZE_DP = 22f
    private val GLASS_SWITCH_THUMB_ON_FILL_NEAR = Color.rgb(0x27, 0xBD, 0xF4)
    private val GLASS_SWITCH_THUMB_ON_FILL_FAR = Color.rgb(0x1E, 0xB1, 0xE7)
    private val GLASS_SWITCH_THUMB_ON_BORDER = Color.argb(0x59, 0xDD, 0xF7, 0xFF)
    private val GLASS_SWITCH_THUMB_ON_GLOW = Color.argb(0xA0, 0x25, 0xC2, 0xFF)
    private val GLASS_SWITCH_THUMB_ON_RING = Color.argb(0xF5, 0x25, 0xC2, 0xFF)
    private val GLASS_SWITCH_THUMB_HIGHLIGHT = Color.argb(0x6B, 0xFF, 0xFF, 0xFF)
    private val GLASS_SWITCH_THUMB_OFF_FILL = Color.rgb(0xAA, 0xB1, 0xB8)
    private val GLASS_SWITCH_THUMB_OFF_BORDER = Color.argb(0x50, 0xFF, 0xFF, 0xFF)

    fun glassSwitchTrackDrawable(context: Context): Drawable = GlassSwitchTrackDrawable(context)
    // accentColor recolors just the thumb's ON-state glow/fill/border/ring (the track's own fill
    // is state-independent -- see GlassSwitchTrackDrawable -- so it stays neutral either way) --
    // used to color-code a switch row's toggle to match the rest of a colour-coded row (eg. the
    // All-pass workspace's per-output Enabled switch).
    fun glassSwitchThumbDrawable(context: Context, accentColor: Int? = null): Drawable = GlassSwitchThumbDrawable(context, accentColor)

    // NORMAL/INVERT segmented polarity toggle (buildMiniToggleRow), recreated from the dedicated
    // glass_normal_invert_toggle.xml art -- a fully-rounded stadium capsule rather than
    // segmentButton's previous small-corner-radius rectangle.
    private const val GLASS_SEGMENT_BORDER_WIDTH_DP = 1.3f
    private const val GLASS_SEGMENT_GLOW_WIDTH_DP = 3f
    private val GLASS_SEGMENT_TRACK_BORDER_COLOR = Color.rgb(0x3E, 0x43, 0x52)
    private val GLASS_SEGMENT_TRACK_RIM_COLOR = Color.argb(0x29, 0xF0, 0xF0, 0xF0)
    private val GLASS_SEGMENT_TRACK_FILL_NEAR = Color.rgb(0x0B, 0x0C, 0x10)
    private val GLASS_SEGMENT_TRACK_FILL_FAR = Color.BLACK
    private val GLASS_SEGMENT_FILL_NEAR = Color.rgb(0x4A, 0xA9, 0xD8)
    private val GLASS_SEGMENT_FILL_MID = Color.rgb(0x0D, 0x5D, 0x85)
    private val GLASS_SEGMENT_FILL_FAR = Color.rgb(0x0A, 0x4F, 0x73)
    private val GLASS_SEGMENT_BORDER_COLOR = Color.rgb(0x31, 0xD2, 0xFF)
    private val GLASS_SEGMENT_GLOW_COLOR = Color.argb(0x50, 0x31, 0xD2, 0xFF)
    private val GLASS_SEGMENT_SHEEN_NEAR = Color.argb(0x54, 0xFF, 0xFF, 0xFF)
    private val GLASS_SEGMENT_SHEEN_FAR = Color.argb(0x00, 0xFF, 0xFF, 0xFF)

    fun glassSegmentTrackDrawable(context: Context): Drawable = GlassSegmentTrackDrawable(context)
    // accentColor recolors the selected segment's gradient fill/glow/border -- same purpose as
    // glassSwitchThumbDrawable's accentColor, for the two-way (eg. NORMAL/INVERT) toggle variant.
    fun glassSegmentDrawable(context: Context, accentColor: Int? = null): Drawable = GlassSegmentDrawable(context, accentColor)

    fun styleWorkspace(root: View) {
        root.background = brushedPanelDrawable(root.context)
        styleTree(root)
    }

    /**
     * Background-only half of [styleWorkspace], for workspaces (Gains/Delay, Crossovers &amp; Tilt)
     * whose content is built by [CrossoverDashboardBuilder], which already styles its own cards
     * and sliders. Running the full [styleTree] walk there would overwrite that styling with the
     * generic XML-card/slider skin -- this just paints the continuous panel background behind
     * the toolbar/sidebar/content seam, without touching anything inside the content tree.
     */
    fun paintWorkspaceBackground(root: View) {
        root.background = brushedPanelDrawable(root.context)
    }

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
        // The active fill now tints per [accent] too (a lightened blend, approximating the
        // reference art's gradient -- see the class doc above), not just the handle -- this
        // reverses an earlier explicit correction that kept the track a fixed blue regardless of
        // accent, because the new slider_{dark_blue,light_blue,purple,yellow}.xml reference art
        // unambiguously shows the whole fill tinted, not just the thumb.
        slider.setTrackActiveTintList(ColorStateList.valueOf(blend(accent, Color.WHITE, 0.2f)))
        slider.setTrackInactiveTintList(ColorStateList.valueOf(SLIDER_TRACK_INACTIVE_COLOR))
        slider.setHaloTintList(ColorStateList.valueOf(SLIDER_HALO_COLOR))
        // [accent] (resolved above, never null) is passed rather than the raw nullable
        // [accentColor] -- every slider now gets a colored thumb+track+capsule, defaulting to
        // SLIDER_DEFAULT_COLOR's light blue, matching how all 4 of the new reference art's own
        // variants are colored presets with no neutral-grey option among them.
        slider.setCustomThumbDrawable(sliderThumbDrawable(context, accent))
        // The capsule outline sits on the Slider's own background, not a separate wrapping view --
        // it draws a fixed-height pill centred within whatever bounds the Slider view ends up
        // with, so it stays aligned with Slider's own (also vertically-centred) track regardless
        // of the view's actual measured height.
        slider.background = sliderCapsuleDrawable(context, accent)
        // The capsule's focus glow uses BlurMaskFilter, which silently no-ops on a hardware
        // layer -- without this the ring would still appear on focus, just as a crisp unblurred
        // outline instead of a soft glow. Matches the existing LAYER_TYPE_SOFTWARE usage for the
        // same reason in NativeBmwCompressorView/CompressorGrTraceView.
        slider.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    // No fill: lets the workspace's designed background (and its "M" watermark) show through
    // behind the card, same reasoning as CrossoverDashboardBuilder's dashboardPanel and
    // crossoverBandCard. A live-visualizer view inside a styled card (ParametricEqSurface,
    // NativeBmwCompressorView, CompressorGrTraceView) still paints its own solid background every
    // frame regardless -- only the card's own body becomes see-through.
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

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).roundToInt()

    // Fractional dp -> px, for sub-pixel spec values (2.5dp track height, 1.5dp capsule gap,
    // etc.) that would round away to nothing (or the wrong whole number) through the Int dp()
    // above -- returned as Float since callers here need sub-pixel precision for Paint geometry,
    // not an Int pixel count.
    private fun dpF(context: Context, value: Float) = value * context.resources.displayMetrics.density

    /**
     * Renders the DSP workspace's designed background image: a center-cropped cover fill,
     * independent of the container's own aspect ratio.
     */
    private class PhotoBrushedMetalDrawable(context: Context) : Drawable() {
        private val fillBitmap = loadWorkspaceBackgroundBitmap(context)

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            shader = BitmapShader(fillBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        private val fillMatrix = Matrix()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val scale = maxOf(bounds.width().toFloat() / fillBitmap.width, bounds.height().toFloat() / fillBitmap.height)
            val dx = (bounds.width() - fillBitmap.width * scale) / 2f
            val dy = (bounds.height() - fillBitmap.height * scale) / 2f
            fillMatrix.setScale(scale, scale)
            fillMatrix.postTranslate(bounds.left + dx, bounds.top + dy)
            (fillPaint.shader as BitmapShader).setLocalMatrix(fillMatrix)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(bounds, fillPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    /** Plain brushed-metal fill for small sidebar tiles -- see [metalTileDrawable]. Draws its own
     *  optional stroke ([strokeColor]) rather than relying on MaterialButton's built-in stroke:
     *  assigning a custom `background` drawable to a MaterialButton replaces its whole internal
     *  background stack (fill + stroke together), so `MaterialButton.strokeColor` silently stops
     *  drawing anything once `background` is overridden like this -- the stroke has to be part of
     *  this drawable itself. */
    private class MetalTileDrawable(context: Context, brightness: Float, private val strokeColor: Int? = null) : Drawable() {
        private val bitmap = loadPlainMetalBitmap(context)
        private val cornerRadiusPx = dp(context, 6).toFloat()
        private val strokeWidthPx = dp(context, 1).toFloat()
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }
        private val matrix = Matrix()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            rect.set(bounds)
            if (strokeColor != null) rect.inset(strokeWidthPx / 2f, strokeWidthPx / 2f)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val scale = maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
            val dx = (bounds.width() - bitmap.width * scale) / 2f
            val dy = (bounds.height() - bitmap.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(bounds.left + dx, bounds.top + dy)
            (paint.shader as BitmapShader).setLocalMatrix(matrix)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
            if (strokeColor != null) {
                strokePaint.color = strokeColor
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, strokePaint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { /* fixed brightness filter owns this slot */ }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * The unified slider thumb: a 3d-lit grey block (top/centre/bottom gradient, a thin outer
     * border, a top-edge highlight and bottom-edge shadow line for the emboss, and a small darker
     * inset "detail panel" centred within it) -- taller than the capsule it sits in and the track
     * it rides on, so it always reads as the grabbable control rather than blending into either.
     */
    /** Linear per-channel blend of [from] toward [to] by [t] (0 = [from], 1 = [to]). */
    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt(),
    )

    private class SliderThumbDrawable(private val context: Context, private val accentColor: Int? = null) : Drawable() {
        private val density = context.resources.displayMetrics.density
        // Border/highlight/shadow stay the neutral metal colors regardless of [accentColor] --
        // just the face gradient AND the inset centre panel recolor. The inset panel is the
        // biggest flat area at this thumb's actual rendered size (the gradient face is mostly a
        // thin margin around it), so leaving it grey read as "a grey handle with a colored rim"
        // instead of a colored handle -- coloring it too is what actually makes the whole thumb
        // read as blue/yellow at a glance.
        private val gradientTop = accentColor?.let { blend(it, Color.WHITE, 0.35f) } ?: thumbGradientTop
        private val gradientCenter = accentColor ?: thumbGradientCenter
        private val gradientBottom = accentColor?.let { blend(it, Color.BLACK, 0.45f) } ?: thumbGradientBottom
        private val insetColor = accentColor?.let { blend(it, Color.BLACK, 0.25f) } ?: SLIDER_THUMB_INSET_FILL_COLOR
        private val cornerRadius = SLIDER_THUMB_CORNER_RADIUS_DP * density
        private val borderWidth = SLIDER_THUMB_BORDER_WIDTH_DP * density
        private val insetMargin = SLIDER_THUMB_INSET_MARGIN_DP * density
        private val insetCornerRadius = SLIDER_THUMB_INSET_CORNER_RADIUS_DP * density
        private val insetBorderWidth = SLIDER_THUMB_INSET_BORDER_WIDTH_DP * density
        private val edgeLineWidth = density // 1dp highlight/shadow lines

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = SLIDER_THUMB_BORDER_COLOR
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDER_THUMB_HIGHLIGHT_COLOR }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDER_THUMB_SHADOW_COLOR }
        private val insetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = insetColor }
        private val insetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = insetBorderWidth
            color = SLIDER_THUMB_INSET_BORDER_COLOR
        }

        private val bodyRect = RectF()
        private val insetRect = RectF()
        private val clipPath = android.graphics.Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.height() <= 0) return
            bodyRect.set(bounds)
            bodyRect.inset(borderWidth / 2f, borderWidth / 2f)
            fillPaint.shader = LinearGradient(
                0f, bodyRect.top, 0f, bodyRect.bottom,
                intArrayOf(gradientTop, gradientCenter, gradientBottom),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            clipPath.reset()
            clipPath.addRoundRect(bodyRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
            insetRect.set(bodyRect)
            insetRect.inset(insetMargin, insetMargin)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(bodyRect, cornerRadius, cornerRadius, fillPaint)

            // Emboss: a thin lit line along the top edge, a thin dark line along the bottom edge,
            // both clipped to the thumb's own rounded silhouette so they don't spill past it.
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(bodyRect.left, bodyRect.top, bodyRect.right, bodyRect.top + edgeLineWidth, highlightPaint)
            canvas.drawRect(bodyRect.left, bodyRect.bottom - edgeLineWidth, bodyRect.right, bodyRect.bottom, shadowPaint)
            canvas.restore()

            canvas.drawRoundRect(bodyRect, cornerRadius, cornerRadius, borderPaint)

            if (!insetRect.isEmpty) {
                canvas.drawRoundRect(insetRect, insetCornerRadius, insetCornerRadius, insetFillPaint)
                canvas.drawRoundRect(insetRect, insetCornerRadius, insetCornerRadius, insetBorderPaint)
            }
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        // No-op, deliberately: BaseSlider applies its theme's default thumbTintList to whatever
        // drawable setCustomThumbDrawable() is given, by computing a PorterDuff ColorFilter from
        // it and calling setColorFilter() -- which, if honored, silently flattens this drawable's
        // own gradientTop/Center/Bottom (grey by default, or the accent color) to one flat theme
        // color. There's no public API to clear BaseSlider's thumbTintList from the outside
        // (setThumbTintList() requires non-null), so this drawable instead just refuses any
        // externally-imposed tint -- it's fully self-colored already, it never needs one.
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

        // Drawable.getConstantState() returns null unless a subclass provides one.
        // BaseSlider.setCustomThumbDrawable() calls getConstantState().newDrawable() internally
        // (to make per-thumb-index copies), so without this it NPEs the instant a Slider using
        // this thumb is created -- GradientDrawable (the old thumb) has this built in already,
        // which is why that path never hit it. newDrawable() MUST forward accentColor -- it used
        // to construct a plain SliderThumbDrawable(context), silently reconstructing every accented
        // thumb back to the default grey gradient the instant BaseSlider asked for its "real" copy.
        private val constantState = object : ConstantState() {
            override fun newDrawable(): Drawable = SliderThumbDrawable(context, accentColor)
            override fun getChangingConfigurations(): Int = 0
        }

        override fun getConstantState(): ConstantState = constantState
    }

    /**
     * The pill-shaped outline wrapping a slider's track: a fixed [SLIDER_CAPSULE_HEIGHT_DP]-tall
     * capsule centred vertically within whatever bounds the host Slider view actually ends up
     * with (which is usually taller, since Material enforces its own minimum touch-target height)
     * -- set as the Slider's own `background`, so it always shares the exact same vertical centre
     * as Slider's own internally-drawn track, no separate alignment bookkeeping needed. The
     * capsule interior is left transparent (just the border+fill drawn) so Slider's own track and
     * thumb paint on top of it undisturbed.
     *
     * [isStateful]/[onStateChange] make this react to the Slider's own focused state -- Android
     * propagates a View's state (focused, pressed, etc.) to its `background` drawable
     * automatically, no listener needed. A hardware D-pad/rotary controller (e.g. a car's iDrive
     * wheel wired in through a CAN-to-Android adapter) moves Android focus between views the same
     * way pressing Tab does, but without ever touching the screen -- so touch-driven feedback
     * (the halo, a drag) never appears; this glow is the only visual sign of where that input
     * currently is.
     *
     * [accentColor], when given, recolors just the unfocused border (see SLIDER_LOW_BAND_COLOR
     * etc.) -- every other slider keeps the default SLIDER_DEFAULT_COLOR border. The focus
     * glow/ring stay LIGHT_BLUE_BRIGHT regardless: that's a focus-state indicator, not a band
     * identity. Also draws the recessed groove + thin inner highlight line inset within the
     * capsule -- the reference art's own bezel layers -- so Slider's real (now much taller, see
     * SLIDER_TRACK_HEIGHT_DP) native track has a visible channel to sit inside of; the groove's
     * own fill color is what shows through as the "unfilled" look, since the native track's
     * inactive tint is fully transparent (see SLIDER_TRACK_INACTIVE_COLOR).
     */
    private class SliderCapsuleDrawable(context: Context, accentColor: Int? = null) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val capsuleHeight = SLIDER_CAPSULE_HEIGHT_DP * density
        private val borderWidth = SLIDER_CAPSULE_BORDER_WIDTH_DP * density
        private val cornerRadius = capsuleHeight / 2f
        private val grooveMargin = SLIDER_GROOVE_MARGIN_DP * density
        private val grooveStrokeWidth = SLIDER_GROOVE_STROKE_WIDTH_DP * density
        private val grooveHighlightMargin = SLIDER_GROOVE_HIGHLIGHT_MARGIN_DP * density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDER_BOX_BACKGROUND_COLOR }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = accentColor ?: SLIDER_DEFAULT_COLOR
        }
        private val grooveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDER_GROOVE_FILL_COLOR }
        private val grooveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = grooveStrokeWidth
            color = SLIDER_GROOVE_STROKE_COLOR
        }
        private val grooveHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = SLIDER_GROOVE_HIGHLIGHT_COLOR
        }
        private val focusGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = LIGHT_BLUE_BRIGHT
            maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = LIGHT_BLUE_BRIGHT
        }
        private val capsuleRect = RectF()
        private val grooveRect = RectF()
        private val grooveHighlightRect = RectF()
        private var focused = false

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val wasFocused = focused
            focused = state.contains(android.R.attr.state_focused)
            if (focused != wasFocused) invalidateSelf()
            return focused != wasFocused
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val centerY = bounds.top + bounds.height() / 2f
            capsuleRect.set(
                bounds.left + borderWidth / 2f, centerY - capsuleHeight / 2f + borderWidth / 2f,
                bounds.right - borderWidth / 2f, centerY + capsuleHeight / 2f - borderWidth / 2f,
            )
            grooveRect.set(capsuleRect)
            grooveRect.inset(grooveMargin, grooveMargin)
            grooveHighlightRect.set(grooveRect)
            grooveHighlightRect.inset(grooveHighlightMargin, grooveHighlightMargin)
        }

        override fun draw(canvas: Canvas) {
            if (capsuleRect.isEmpty) return
            canvas.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, fillPaint)
            if (!grooveRect.isEmpty) {
                val grooveRadius = grooveRect.height() / 2f
                canvas.drawRoundRect(grooveRect, grooveRadius, grooveRadius, grooveFillPaint)
                canvas.drawRoundRect(grooveRect, grooveRadius, grooveRadius, grooveStrokePaint)
                if (!grooveHighlightRect.isEmpty) {
                    val highlightRadius = grooveHighlightRect.height() / 2f
                    canvas.drawRoundRect(grooveHighlightRect, highlightRadius, highlightRadius, grooveHighlightPaint)
                }
            }
            if (focused) {
                canvas.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, focusGlowPaint)
                canvas.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, focusRingPaint)
            } else {
                canvas.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, borderPaint)
            }
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; borderPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** See [glassSwitchTrackDrawable]. Shared shell for both on and off -- the source art only
     *  shows one combined track, independent of thumb position. */
    private class GlassSwitchTrackDrawable(context: Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val intrinsicW = (GLASS_SWITCH_TRACK_WIDTH_DP * density).roundToInt()
        private val intrinsicH = (GLASS_SWITCH_TRACK_HEIGHT_DP * density).roundToInt()
        private val borderWidth = GLASS_SWITCH_TRACK_BORDER_WIDTH_DP * density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shellBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = GLASS_SWITCH_SHELL_BORDER_COLOR
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth * 0.75f
            color = GLASS_SWITCH_TRACK_RIM_COLOR
        }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackRect = RectF()
        private val sheenPath = android.graphics.Path()
        private val clipPath = android.graphics.Path()

        override fun getIntrinsicWidth() = intrinsicW
        override fun getIntrinsicHeight() = intrinsicH

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            trackRect.set(bounds)
            trackRect.inset(borderWidth / 2f, borderWidth / 2f)
            if (trackRect.isEmpty) return
            val corner = trackRect.height() / 2f
            fillPaint.shader = LinearGradient(
                trackRect.left, trackRect.top, trackRect.right, trackRect.bottom,
                intArrayOf(GLASS_SWITCH_TRACK_FILL_TOP, GLASS_SWITCH_TRACK_FILL_MID, GLASS_SWITCH_TRACK_FILL_BOTTOM),
                floatArrayOf(0f, 0.28f, 1f), Shader.TileMode.CLAMP,
            )
            clipPath.reset()
            clipPath.addRoundRect(trackRect, corner, corner, android.graphics.Path.Direction.CW)

            // A static diagonal glass sheen over the track's left third -- same placement/spirit
            // as the source art's own highlight, independent of thumb position.
            val w = trackRect.width()
            sheenPath.reset()
            sheenPath.moveTo(trackRect.left + w * 0.06f, trackRect.top)
            sheenPath.lineTo(trackRect.left + w * 0.30f, trackRect.top)
            sheenPath.lineTo(trackRect.left + w * 0.20f, trackRect.bottom)
            sheenPath.lineTo(trackRect.left, trackRect.bottom)
            sheenPath.close()
            sheenPaint.shader = LinearGradient(
                trackRect.left + w * 0.06f, trackRect.top, trackRect.left + w * 0.20f, trackRect.bottom,
                GLASS_SWITCH_TRACK_SHEEN_NEAR, GLASS_SWITCH_TRACK_SHEEN_FAR, Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            if (trackRect.isEmpty) return
            val corner = trackRect.height() / 2f
            canvas.drawRoundRect(trackRect, corner, corner, fillPaint)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawPath(sheenPath, sheenPaint)
            canvas.restore()
            canvas.drawRoundRect(trackRect, corner, corner, rimPaint)
            canvas.drawRoundRect(trackRect, corner, corner, shellBorderPaint)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** See [glassSwitchThumbDrawable]. Stateful on state_checked: glowing radial-blue sphere when
     *  on (matching the source art exactly), a flat neutral-grey circle when off -- the source art
     *  only supplied an "on" state, so off is extrapolated to match the app's existing grey-when-
     *  off convention rather than invented from nothing. */
    private class GlassSwitchThumbDrawable(context: Context, private val accentColor: Int? = null) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val intrinsicSize = (GLASS_SWITCH_THUMB_SIZE_DP * density).roundToInt()
        private val glowWidth = 3f * density
        private val borderWidth = density
        private var checked = false

        // accentColor swaps this thumb's fixed ON-state blue for that color, preserving each
        // original color's own alpha (glow/border/ring are semi-transparent by design; recoloring
        // via [blend] toward the accent -- same technique SliderThumbDrawable already uses --
        // instead of a flat replacement would otherwise lose that translucency).
        private fun tinted(original: Int) = accentColor?.let {
            Color.argb(Color.alpha(original), Color.red(it), Color.green(it), Color.blue(it))
        } ?: original
        private val onFillNear = accentColor?.let { blend(it, Color.WHITE, 0.3f) } ?: GLASS_SWITCH_THUMB_ON_FILL_NEAR
        private val onFillFar = accentColor ?: GLASS_SWITCH_THUMB_ON_FILL_FAR
        private val onBorder = tinted(GLASS_SWITCH_THUMB_ON_BORDER)
        private val onGlow = tinted(GLASS_SWITCH_THUMB_ON_GLOW)
        private val onRing = tinted(GLASS_SWITCH_THUMB_ON_RING)

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = borderWidth }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glowWidth
            color = onGlow
            maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = onRing
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
            color = GLASS_SWITCH_THUMB_HIGHLIGHT
        }
        private val circleRect = RectF()
        private val highlightPath = android.graphics.Path()

        override fun getIntrinsicWidth() = intrinsicSize
        override fun getIntrinsicHeight() = intrinsicSize
        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val wasChecked = checked
            checked = state.contains(android.R.attr.state_checked)
            if (checked != wasChecked) updatePaints()
            return checked != wasChecked
        }

        private fun updatePaints() {
            if (circleRect.isEmpty) return
            if (checked) {
                fillPaint.shader = RadialGradient(
                    circleRect.centerX(), circleRect.top + circleRect.height() * 0.42f, circleRect.width() / 2f,
                    onFillNear, onFillFar, Shader.TileMode.CLAMP,
                )
                borderPaint.color = onBorder
            } else {
                fillPaint.shader = null
                fillPaint.color = GLASS_SWITCH_THUMB_OFF_FILL
                borderPaint.color = GLASS_SWITCH_THUMB_OFF_BORDER
            }
            invalidateSelf()
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val inset = glowWidth
            circleRect.set(bounds)
            circleRect.inset(inset, inset)
            if (circleRect.isEmpty) return
            updatePaints()
            val r = circleRect.width() / 2f
            highlightPath.reset()
            highlightPath.addArc(
                RectF(circleRect.centerX() - r * 0.55f, circleRect.top + r * 0.15f, circleRect.centerX() + r * 0.55f, circleRect.top + r * 1.1f),
                200f, 70f,
            )
        }

        override fun draw(canvas: Canvas) {
            if (circleRect.isEmpty) return
            val r = circleRect.width() / 2f
            if (checked) {
                canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r + glowWidth / 2f, glowPaint)
                canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, fillPaint)
                canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, ringPaint)
                canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, borderPaint)
                canvas.drawPath(highlightPath, highlightPaint)
            } else {
                canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, fillPaint)
                canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, borderPaint)
            }
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** See [glassSegmentTrackDrawable]. The dark capsule shell a NORMAL/INVERT toggle group sits
     *  in -- shared, unaffected by which segment is checked. */
    private class GlassSegmentTrackDrawable(context: Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val borderWidth = GLASS_SEGMENT_BORDER_WIDTH_DP * density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = GLASS_SEGMENT_TRACK_BORDER_COLOR
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth * 0.6f
            color = GLASS_SEGMENT_TRACK_RIM_COLOR
        }
        private val trackRect = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            trackRect.set(bounds)
            trackRect.inset(borderWidth / 2f, borderWidth / 2f)
            if (trackRect.isEmpty) return
            fillPaint.shader = LinearGradient(
                trackRect.left, trackRect.top, trackRect.right, trackRect.bottom,
                GLASS_SEGMENT_TRACK_FILL_NEAR, GLASS_SEGMENT_TRACK_FILL_FAR, Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            if (trackRect.isEmpty) return
            val corner = trackRect.height() / 2f
            canvas.drawRoundRect(trackRect, corner, corner, fillPaint)
            canvas.drawRoundRect(trackRect, corner, corner, rimPaint)
            canvas.drawRoundRect(trackRect, corner, corner, borderPaint)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** See [glassSegmentDrawable]. Stateful on state_selected: fully transparent when unselected
     *  (the track drawable's own dark shell shows through), a glowing gradient pill with a
     *  diagonal glass sheen when selected -- matches the source art's selected-segment treatment
     *  exactly. */
    private class GlassSegmentDrawable(context: Context, private val accentColor: Int? = null) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val borderWidth = GLASS_SEGMENT_BORDER_WIDTH_DP * density
        private val glowWidth = GLASS_SEGMENT_GLOW_WIDTH_DP * density
        private var checked = false
        // Same recoloring technique as GlassSwitchThumbDrawable: derive lit/mid/dark shades of
        // accentColor for the fill gradient, and preserve each fixed color's own alpha for the
        // (semi-transparent) glow.
        private val fillNear = accentColor?.let { blend(it, Color.WHITE, 0.25f) } ?: GLASS_SEGMENT_FILL_NEAR
        private val fillMid = accentColor ?: GLASS_SEGMENT_FILL_MID
        private val fillFar = accentColor?.let { blend(it, Color.BLACK, 0.25f) } ?: GLASS_SEGMENT_FILL_FAR
        private val borderColor = accentColor ?: GLASS_SEGMENT_BORDER_COLOR
        private val glowColor = accentColor?.let {
            Color.argb(Color.alpha(GLASS_SEGMENT_GLOW_COLOR), Color.red(it), Color.green(it), Color.blue(it))
        } ?: GLASS_SEGMENT_GLOW_COLOR
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = borderColor
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glowWidth
            color = glowColor
            maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val segRect = RectF()
        private val sheenPath = android.graphics.Path()
        private val clipPath = android.graphics.Path()

        override fun isStateful() = true

        // Keyed off state_selected (View.isSelected), not state_checked -- this backs a plain
        // TextView (see CrossoverDashboardBuilder.glassSegmentView), not a MaterialButton. A
        // MaterialButton was tried first and rejected: MaterialButtonToggleGroup throws
        // IllegalStateException ("Attempted to get ShapeAppearance from a MaterialButton which has
        // an overwritten background") the moment a child's .background is set directly, which this
        // drawable's gradient+glow rendering requires.
        override fun onStateChange(state: IntArray): Boolean {
            val wasChecked = checked
            checked = state.contains(android.R.attr.state_selected)
            if (checked != wasChecked) invalidateSelf()
            return checked != wasChecked
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            segRect.set(bounds)
            segRect.inset(borderWidth / 2f, borderWidth / 2f)
            if (segRect.isEmpty) return
            fillPaint.shader = LinearGradient(
                segRect.left, segRect.top, segRect.right, segRect.bottom,
                intArrayOf(fillNear, fillMid, fillFar),
                floatArrayOf(0f, 0.18f, 1f), Shader.TileMode.CLAMP,
            )
            val corner = segRect.height() / 2f
            clipPath.reset()
            clipPath.addRoundRect(segRect, corner, corner, android.graphics.Path.Direction.CW)
            val w = segRect.width()
            sheenPath.reset()
            sheenPath.moveTo(segRect.left + w * 0.12f, segRect.top)
            sheenPath.lineTo(segRect.left + w * 0.42f, segRect.top)
            sheenPath.lineTo(segRect.left + w * 0.30f, segRect.bottom)
            sheenPath.lineTo(segRect.left, segRect.bottom)
            sheenPath.close()
            sheenPaint.shader = LinearGradient(
                segRect.left + w * 0.12f, segRect.top, segRect.left + w * 0.30f, segRect.bottom,
                GLASS_SEGMENT_SHEEN_NEAR, GLASS_SEGMENT_SHEEN_FAR, Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            if (!checked || segRect.isEmpty) return
            val corner = segRect.height() / 2f
            canvas.drawRoundRect(segRect, corner, corner, glowPaint)
            canvas.drawRoundRect(segRect, corner, corner, fillPaint)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawPath(sheenPath, sheenPaint)
            canvas.restore()
            canvas.drawRoundRect(segRect, corner, corner, borderPaint)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** See [glassBoxDrawable]. */
    private class GlassBoxDrawable(context: Context, private val showBorder: Boolean, private val accentColor: Int? = null) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val cornerRadius = GLASS_BOX_CORNER_RADIUS_DP * density
        private val strokeWidth = GLASS_BOX_STROKE_WIDTH_DP * density
        private val edgeLineWidth = density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = this@GlassBoxDrawable.strokeWidth
        }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_BOX_TOP_GLINT_COLOR }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_BOX_BOTTOM_GLINT_COLOR }
        private val boxRect = RectF()
        private val clipPath = android.graphics.Path()
        private val sheenPath = android.graphics.Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            boxRect.set(bounds)
            if (showBorder) boxRect.inset(strokeWidth / 2f, strokeWidth / 2f)
            if (boxRect.isEmpty) return
            clipPath.reset()
            clipPath.addRoundRect(boxRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)

            fillPaint.shader = LinearGradient(
                boxRect.left, boxRect.top, boxRect.right, boxRect.bottom,
                intArrayOf(GLASS_FILL_TOP, GLASS_FILL_MID, GLASS_FILL_BOTTOM),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
            )
            strokePaint.shader = LinearGradient(
                boxRect.left, boxRect.top, boxRect.left, boxRect.bottom,
                intArrayOf(GLASS_RIM_TOP, GLASS_RIM_MID, GLASS_RIM_BOTTOM),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )

            // Diagonal light sweep in the top-left corner -- same placement as the source art's own
            // "glass body" highlight quad, the one cue that reads as glass rather than a flat box.
            sheenPath.reset()
            sheenPath.moveTo(boxRect.left, boxRect.top)
            sheenPath.lineTo(boxRect.left + boxRect.width() * 0.55f, boxRect.top)
            sheenPath.lineTo(boxRect.left + boxRect.width() * 0.22f, boxRect.bottom)
            sheenPath.lineTo(boxRect.left, boxRect.bottom)
            sheenPath.close()
            sheenPaint.shader = LinearGradient(
                boxRect.left, boxRect.top, boxRect.left + boxRect.width() * 0.5f, boxRect.bottom,
                GLASS_SHEEN_NEAR, GLASS_SHEEN_FAR, Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            if (boxRect.isEmpty) return
            canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, fillPaint)

            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawPath(sheenPath, sheenPaint)
            canvas.drawRect(boxRect.left, boxRect.top, boxRect.right, boxRect.top + edgeLineWidth, highlightPaint)
            canvas.drawRect(boxRect.left, boxRect.bottom - edgeLineWidth, boxRect.right, boxRect.bottom, shadowPaint)
            canvas.restore()

            if (showBorder) canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, strokePaint)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * The sidebar's selected-tile background: a darker brushed-metal fill (vs. the lighter fill
     * unselected tiles get, via [metalTileDrawable]) so the tile itself visibly "pops", plus a
     * blurred glow ring drawn behind a crisp bright stroke so the border visibly radiates instead
     * of just being outlined.
     */
    private class IlluminatedTileDrawable(context: Context) : Drawable() {
        private val corner = dp(context, 6).toFloat()
        private val strokeWidthPx = dp(context, 2).toFloat()
        private val metalBitmap = loadPlainMetalBitmap(context)
        private val fillRect = RectF()
        private val matrix = Matrix()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            shader = BitmapShader(metalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setScale(SELECTED_TILE_BRIGHTNESS, SELECTED_TILE_BRIGHTNESS, SELECTED_TILE_BRIGHTNESS, 1f)
            })
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIGHT_BLUE_BRIGHT
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            maskFilter = BlurMaskFilter(dp(context, 5).toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIGHT_BLUE_BRIGHT
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            fillRect.set(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val scale = maxOf(bounds.width().toFloat() / metalBitmap.width, bounds.height().toFloat() / metalBitmap.height)
            val dx = (bounds.width() - metalBitmap.width * scale) / 2f
            val dy = (bounds.height() - metalBitmap.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(bounds.left + dx, bounds.top + dy)
            (fillPaint.shader as BitmapShader).setLocalMatrix(matrix)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(fillRect, corner, corner, fillPaint)

            val strokeRect = RectF(bounds).apply {
                inset(strokeWidthPx / 2f, strokeWidthPx / 2f)
            }
            canvas.drawRoundRect(strokeRect, corner, corner, glowPaint)
            canvas.drawRoundRect(strokeRect, corner, corner, strokePaint)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { /* fixed brightness filter owns this slot */ }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** See [sidebarTileFocusRingDrawable]. */
    private class TileFocusRingDrawable(context: Context) : Drawable() {
        private val corner = dp(context, 6).toFloat()
        private val strokeWidthPx = dp(context, 2).toFloat()
        private val strokeRect = RectF()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIGHT_BLUE_BRIGHT
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            maskFilter = BlurMaskFilter(dp(context, 5).toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIGHT_BLUE_BRIGHT
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }
        private var focused = false

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val wasFocused = focused
            focused = state.contains(android.R.attr.state_focused)
            if (focused != wasFocused) invalidateSelf()
            return focused != wasFocused
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            strokeRect.set(bounds)
            strokeRect.inset(strokeWidthPx / 2f, strokeWidthPx / 2f)
        }

        override fun draw(canvas: Canvas) {
            if (!focused || strokeRect.isEmpty) return
            canvas.drawRoundRect(strokeRect, corner, corner, glowPaint)
            canvas.drawRoundRect(strokeRect, corner, corner, strokePaint)
        }

        override fun setAlpha(alpha: Int) { glowPaint.alpha = alpha; strokePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            glowPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
