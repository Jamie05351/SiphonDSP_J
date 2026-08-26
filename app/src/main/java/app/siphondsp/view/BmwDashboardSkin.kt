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
    // Crossovers & Tilt, Mono Bass, Routing, Compressor). Exact dimensions/colours below were
    // specified explicitly by the user as a full pixel spec (row layout, boxed title/value,
    // capsule outline, 3d thumb) -- do not re-derive these from a screenshot, they're deliberate
    // overrides of it. The track's own 0.5dp specular-highlight/shade sub-strips from that spec
    // are NOT implemented: Slider only exposes trackHeight/trackColorActive/trackColorInactive,
    // not a pluggable track drawable, so painting sub-pixel bevel strips over the real track would
    // need a Slider subclass reverse-engineering BaseSlider's internal padding/thumb-radius inset
    // math to find the exact active/inactive split each frame -- a real (if small) fidelity risk
    // for a barely-visible detail, so left out of this pass rather than guessed at.
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

    fun glassBoxDrawable(context: Context, showBorder: Boolean = true): Drawable = GlassBoxDrawable(context, showBorder)

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

    const val SLIDER_TRACK_HEIGHT_DP = 2.5f
    private const val SLIDER_CAPSULE_HEIGHT_DP = 10f
    private const val SLIDER_CAPSULE_BORDER_WIDTH_DP = 2f
    // Public so CrossoverDashboardBuilder's own inline slider setups (which don't go through
    // styleSlider()) can use the exact same colours instead of drifting from them over time.
    val SLIDER_TRACK_ACTIVE_COLOR = LIGHT_BLUE
    // Same colour as the capsule fill -- the unfilled portion of the track is meant to
    // disappear into the capsule background, so only the active (blue) progress reads visually.
    val SLIDER_TRACK_INACTIVE_COLOR = Color.rgb(0x10, 0x13, 0x18)
    val SLIDER_HALO_COLOR = Color.argb(42, 70, 181, 232)
    const val SLIDER_HALO_RADIUS_DP = 4

    // [accentColor], when given, replaces the thumb's neutral grey 3-stop gradient with one
    // derived from that color (lit/mid/dark shades of it) instead -- used to color-code the
    // Low-band (blue) / Mid-band (yellow) sliders on the Crossovers and Gains & Delay pages, so
    // those specific rows read as a distinct colored knob rather than the plain metal one every
    // other slider still uses.
    fun sliderThumbDrawable(context: Context, accentColor: Int? = null): Drawable = SliderThumbDrawable(context, accentColor)
    fun sliderCapsuleDrawable(context: Context): Drawable = SliderCapsuleDrawable(context)

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
        slider.trackHeight = dpF(context, SLIDER_TRACK_HEIGHT_DP).roundToInt()
        slider.thumbWidth = dp(context, SLIDER_THUMB_WIDTH_DP)
        slider.thumbHeight = dp(context, SLIDER_THUMB_HEIGHT_DP)
        slider.haloRadius = dp(context, SLIDER_HALO_RADIUS_DP)
        // Track stays the standard blue regardless of [accentColor] -- only the handle itself
        // recolors, per an explicit correction: an earlier pass also tinted the track, which
        // read as "the whole slider changed color" rather than "this one has a colored handle."
        slider.setTrackActiveTintList(ColorStateList.valueOf(SLIDER_TRACK_ACTIVE_COLOR))
        slider.setTrackInactiveTintList(ColorStateList.valueOf(SLIDER_TRACK_INACTIVE_COLOR))
        slider.setHaloTintList(ColorStateList.valueOf(SLIDER_HALO_COLOR))
        slider.setCustomThumbDrawable(sliderThumbDrawable(context, accentColor))
        // The capsule outline sits on the Slider's own background, not a separate wrapping view --
        // it draws a fixed-height pill centred within whatever bounds the Slider view ends up
        // with, so it stays aligned with Slider's own (also vertically-centred) track regardless
        // of the view's actual measured height.
        slider.background = sliderCapsuleDrawable(context)
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
        toggle.thumbTintList = checkedColours(LIGHT_BLUE, Color.rgb(170, 177, 184))
        toggle.trackTintList = checkedColours(Color.rgb(32, 78, 111), Color.rgb(45, 50, 57))
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

    private fun checkedColours(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked),
    )

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
     */
    private class SliderCapsuleDrawable(context: Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val capsuleHeight = SLIDER_CAPSULE_HEIGHT_DP * density
        private val borderWidth = SLIDER_CAPSULE_BORDER_WIDTH_DP * density
        private val cornerRadius = capsuleHeight / 2f
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDER_BOX_BACKGROUND_COLOR }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = LIGHT_BLUE
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
        }

        override fun draw(canvas: Canvas) {
            if (capsuleRect.isEmpty) return
            canvas.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, fillPaint)
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

    /** See [glassBoxDrawable]. */
    private class GlassBoxDrawable(context: Context, private val showBorder: Boolean) : Drawable() {
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
