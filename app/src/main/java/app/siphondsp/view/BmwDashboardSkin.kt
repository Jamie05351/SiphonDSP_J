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

    @Volatile private var logoBitmap: Bitmap? = null

    // The "M" logo watermark: a dedicated asset with real alpha transparency around the mark
    // (not a rectangular crop out of a photo with an opaque background), so it draws cleanly over
    // any fill color/brightness instead of showing its own crop bounds as a visible box -- drawn
    // at a fixed size anchored to the background's bottom-right corner (see PhotoBrushedMetalDrawable).
    private fun loadLogoBitmap(context: Context): Bitmap {
        logoBitmap?.let { return it }
        synchronized(this) {
            logoBitmap?.let { return it }
            val decoded = BitmapFactory.decodeResource(context.applicationContext.resources, R.drawable.bmw_m_logo)
            logoBitmap = decoded
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

    // Single slider thumb shared by every DSP workspace slider (Gains/Delay, Crossovers & Tilt,
    // Mono Bass, Routing, Compressor). Exact dimensions/colours below were specified explicitly
    // by the user after a full breakdown was shared back to them -- see BmwDashboardSkin's
    // slider-dimension summary in session history for the element-by-element reference; do not
    // re-derive these from the original screenshot, they're deliberate overrides of it.
    const val SLIDER_THUMB_WIDTH_DP = 12
    const val SLIDER_THUMB_HEIGHT_DP = 20
    private const val SLIDER_THUMB_CORNER_RADIUS_DP = 7
    const val SLIDER_TRACK_HEIGHT_DP = 8
    // Public so CrossoverDashboardBuilder's own inline slider setups (which don't go through
    // styleSlider()) can use the exact same colours instead of drifting from them over time.
    val SLIDER_TRACK_ACTIVE_COLOR = LIGHT_BLUE
    val SLIDER_TRACK_INACTIVE_COLOR = Color.rgb(96, 100, 106)
    // Thumb fill is a vertical gradient of LIGHT_BLUE: lighter at top for a glossy highlight,
    // LIGHT_BLUE itself at the bottom.
    private val thumbGradientTop = Color.rgb(144, 211, 241)
    private val thumbGradientBottom = LIGHT_BLUE

    fun sliderThumbDrawable(context: Context): Drawable = SliderThumbDrawable(context)

    fun styleWorkspace(root: View) {
        root.background = brushedPanelDrawable(root.context)
        addLogoOverlay(root)
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
        addLogoOverlay(root)
    }

    private const val LOGO_OVERLAY_TAG = "bmw_logo_overlay"

    /**
     * Inserts a plain, non-clickable View sized to fill [root] with the logo drawable as its
     * *background*, as [root]'s FIRST child -- so it paints immediately after the plain
     * background fill but before every real child (toolbar, sidebar, cards, sliders, text), i.e.
     * behind all of them rather than on top. An earlier version appended this as the *last*
     * child instead, which drew it over every control -- sliders and buttons underneath the
     * logo's fixed corner became visually obscured, which is the opposite of what a watermark
     * should do.
     *
     * This is a separate overlay view rather than baking the logo into [PhotoBrushedMetalDrawable]
     * itself (which is what `root.background` is) only because `root.background` on
     * `android.R.id.content` is set once from a context where the callers differ (some call this
     * before the fragment/content tree exists); `View.foreground` was tried too, but on an
     * Activity's `android.R.id.content` view its bounds update landed once, during a transient
     * relayout pass, with a logged bogus height of -1, and never corrected -- so it never rendered.
     * A plain child view goes through the exact same layout/measure path this root's own
     * `background` already reliably uses, sidestepping both problems.
     */
    private fun addLogoOverlay(root: View) {
        val parent = root as? ViewGroup ?: return
        (parent.findViewWithTag<View>(LOGO_OVERLAY_TAG))?.let { parent.removeView(it) }
        val overlay = View(root.context).apply {
            tag = LOGO_OVERLAY_TAG
            isClickable = false
            isFocusable = false
            background = LogoWatermarkDrawable(root.context)
        }
        parent.addView(overlay, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
    fun styleSlider(slider: Slider) {
        val context = slider.context
        slider.trackHeight = dp(context, SLIDER_TRACK_HEIGHT_DP)
        slider.thumbWidth = dp(context, SLIDER_THUMB_WIDTH_DP)
        slider.thumbHeight = dp(context, SLIDER_THUMB_HEIGHT_DP)
        slider.setTrackActiveTintList(ColorStateList.valueOf(SLIDER_TRACK_ACTIVE_COLOR))
        slider.setTrackInactiveTintList(ColorStateList.valueOf(SLIDER_TRACK_INACTIVE_COLOR))
        slider.setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
        slider.setCustomThumbDrawable(sliderThumbDrawable(context))
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
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        )
        button.backgroundTintList = ColorStateList(
            states,
            intArrayOf(selectedSurface, Color.rgb(28, 35, 43), inactiveSurface),
        )
        button.strokeColor = ColorStateList(
            states,
            intArrayOf(LIGHT_BLUE, Color.rgb(83, 96, 109), inactiveStroke),
        )
        button.strokeWidth = dp(button.context, 1)
        button.setTextColor(
            ColorStateList(
                states,
                intArrayOf(Color.WHITE, Color.WHITE, inactiveText),
            )
        )
        button.iconTint = ColorStateList(
            states,
            intArrayOf(LIGHT_BLUE, LIGHT_BLUE, Color.rgb(172, 184, 195)),
        )
    }

    private fun checkedColours(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked),
    )

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).roundToInt()

    /**
     * Renders the DSP workspace's designed background image: a center-cropped cover fill,
     * independent of the container's own aspect ratio. The "M" logo watermark is deliberately NOT
     * drawn by this class -- it's a separate overlay (see [addLogoOverlay]) pinned in front of
     * every card, so it stays a single full-screen-scale badge rather than being baked into the
     * background image itself.
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

    /**
     * The "M" logo watermark, pinned at a fixed size to the bottom-right corner -- set as an
     * overlay child view's `background` (see [addLogoOverlay]), not part of
     * [PhotoBrushedMetalDrawable]'s `background`, so it always draws on top of every card instead
     * of behind them. It used to be baked into the workspace root's own background drawable,
     * which put it behind cards' own stroke borders -- wherever a card's rounded corner happened
     * to fall near the fixed bottom-right position, that border cut across the logo, reading as
     * an unintended box/frame around it.
     */
    private class LogoWatermarkDrawable(context: Context) : Drawable() {
        private val logoBitmap = loadLogoBitmap(context)
        private val density = context.resources.displayMetrics.density
        // 50% transparent -- this now sits behind every control, not on top of them, but should
        // still read as a subtle watermark rather than a fully-opaque badge competing with content.
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; alpha = 128 }
        private val logoRect = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val logoAspect = logoBitmap.height.toFloat() / logoBitmap.width
            val logoMargin = LOGO_MARGIN_DP * density
            val logoWidth = (LOGO_WIDTH_DP * density).coerceAtMost((bounds.width() - logoMargin * 2).coerceAtLeast(0f))
            val logoHeight = logoWidth * logoAspect
            logoRect.set(
                bounds.right - logoMargin - logoWidth, bounds.bottom - logoMargin - logoHeight,
                bounds.right - logoMargin, bounds.bottom - logoMargin,
            )
        }

        override fun draw(canvas: Canvas) {
            if (!logoRect.isEmpty) canvas.drawBitmap(logoBitmap, null, logoRect, bitmapPaint)
        }

        override fun setAlpha(alpha: Int) {
            bitmapPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            bitmapPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        companion object {
            // Sized for rendering once at full-screen scale (dashboardPanel cards no longer paint
            // their own independent copy of this background, so this is the only copy on screen).
            private const val LOGO_WIDTH_DP = 160
            private const val LOGO_MARGIN_DP = 16
        }
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
     * The unified slider thumb: a slim vertical bar filled with a top-lit LIGHT_BLUE gradient.
     * No stroke -- with the capsule outline and track already both blue, an extra grey border
     * here just added visual clutter without adding legibility.
     */
    private class SliderThumbDrawable(private val context: Context) : Drawable() {
        private val cornerRadius = dp(context, SLIDER_THUMB_CORNER_RADIUS_DP).toFloat()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.height() <= 0) return
            fillPaint.shader = LinearGradient(
                0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
                thumbGradientTop, thumbGradientBottom,
                Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(RectF(bounds), cornerRadius, cornerRadius, fillPaint)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

        // Drawable.getConstantState() returns null unless a subclass provides one.
        // BaseSlider.setCustomThumbDrawable() calls getConstantState().newDrawable() internally
        // (to make per-thumb-index copies), so without this it NPEs the instant a Slider using
        // this thumb is created -- GradientDrawable (the old thumb) has this built in already,
        // which is why that path never hit it.
        private val constantState = object : ConstantState() {
            override fun newDrawable(): Drawable = SliderThumbDrawable(context)
            override fun getChangingConfigurations(): Int = 0
        }

        override fun getConstantState(): ConstantState = constantState
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
}
