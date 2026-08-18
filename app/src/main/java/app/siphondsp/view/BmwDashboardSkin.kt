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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
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
    // Mono Bass, Routing, Compressor) -- long side horizontal, like a physical fader cap, with
    // the same brushed-metal grain as the workspace panels. Replaces the previously divergent
    // circular "chrome ball" (CrossoverDashboardBuilder.createRoundThumb) and tall rectangular
    // "ingot" (the old styleSlider()) thumbs.
    const val SLIDER_THUMB_WIDTH_DP = 28
    const val SLIDER_THUMB_HEIGHT_DP = 14
    // Gunmetal grey, not light aluminum -- reads as a distinct handle against the track's own
    // black base rather than blending into it.
    private val thumbFillTop = Color.rgb(94, 100, 108)
    private val thumbFillBottom = Color.rgb(52, 56, 62)
    private val thumbStroke = Color.rgb(30, 33, 37)

    fun sliderThumbDrawable(context: Context): Drawable = BrushedThumbDrawable(context)

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
     * Appends a plain, non-clickable View sized to fill [root] with the logo drawable as its
     * *background*, added as [root]'s last child so it paints after every other child (cards
     * included) in the same z-order pass a regular background/child would use.
     *
     * This is deliberately not `root.foreground` -- that was tried first, but `View.foreground`
     * on an Activity's `android.R.id.content` view turned out to get its very first (and, absent
     * another size change, only) bounds update during a transient relayout pass -- observed via
     * logging to land mid-activity-transition with a bogus height of -1 -- and then never
     * corrected, leaving the drawable permanently invisible. A plain child view goes through the
     * exact same layout/measure path this root's own `background` already reliably uses (that one
     * visibly repaints correctly on every resize), sidestepping ForegroundInfo's separate and
     * apparently timing-sensitive bounds bookkeeping entirely.
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
        parent.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        slider.trackHeight = dp(context, 6)
        slider.thumbWidth = dp(context, SLIDER_THUMB_WIDTH_DP)
        slider.thumbHeight = dp(context, SLIDER_THUMB_HEIGHT_DP)
        slider.setTrackActiveTintList(ColorStateList.valueOf(LIGHT_BLUE))
        slider.setTrackInactiveTintList(ColorStateList.valueOf(Color.BLACK))
        slider.setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
        slider.setCustomThumbDrawable(sliderThumbDrawable(context))
        applyTrackOutline(slider)
    }

    /**
     * Material's Slider only exposes a flat inactive-track tint, no stroke API, so the thin white
     * border around the black base is drawn as a separate stroke-only rounded-rect behind the
     * slider, inset from the slider's own (thumb-halo-padded) full height down to roughly the
     * track band itself.
     */
    fun applyTrackOutline(slider: Slider) {
        val context = slider.context
        val outline = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 3).toFloat()
            setStroke(dp(context, 1), Color.WHITE)
            setColor(Color.TRANSPARENT)
        }
        val verticalInset = dp(context, SLIDER_THUMB_HEIGHT_DP)
        slider.background = InsetDrawable(outline, 0, verticalInset, 0, verticalInset)
    }

    fun styleCard(card: MaterialCardView) {
        card.radius = dp(card.context, 7).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = dp(card.context, 1)
        card.strokeColor = inactiveStroke
        card.setCardBackgroundColor(Color.argb(215, 18, 23, 29))
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
     * Renders the real photographed/rendered brushed-metal background: a center-cropped, tiled
     * plain-metal fill covering the whole bounds, with the tri-colour M-stripe pinned to a fixed
     * height along the top edge and the "M" logo watermark pinned at a fixed size to the
     * bottom-right corner -- both independent of the container's own aspect ratio. A whole-image
     * center-crop was tried first, but on a container much taller than the source photo (e.g. the
     * Gains & Delay card, which stacks several rows) the crop had to zoom in so far to cover the
     * height that the stripe and logo -- both fixed pixel features near the source's edges -- were
     * scaled out of frame entirely. Pinning them as separate fixed-size overlays avoids that.
     */
    private class PhotoBrushedMetalDrawable(context: Context) : Drawable() {
        private val fillBitmap = loadPlainMetalBitmap(context)
        private val density = context.resources.displayMetrics.density

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            shader = BitmapShader(fillBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        // Drawn as 3 solid blocks, not a photo crop or a blended gradient -- the source stripe is
        // itself 3 hard-edged flat colors (confirmed by sampling it), so this reproduces it exactly
        // without depending on any raster asset's pixel layout.
        private val stripeCyanPaint = Paint().apply { color = Color.rgb(0, 147, 255) }
        private val stripePurplePaint = Paint().apply { color = Color.rgb(72, 21, 111) }
        private val stripeRedPaint = Paint().apply { color = Color.rgb(255, 4, 12) }
        private val fillMatrix = Matrix()
        private val stripeRect = RectF()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val scale = maxOf(bounds.width().toFloat() / fillBitmap.width, bounds.height().toFloat() / fillBitmap.height)
            val dx = (bounds.width() - fillBitmap.width * scale) / 2f
            val dy = (bounds.height() - fillBitmap.height * scale) / 2f
            fillMatrix.setScale(scale, scale)
            fillMatrix.postTranslate(bounds.left + dx, bounds.top + dy)
            (fillPaint.shader as BitmapShader).setLocalMatrix(fillMatrix)

            val stripeHeight = (STRIPE_HEIGHT_DP * density).coerceAtMost(bounds.height().toFloat())
            stripeRect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + stripeHeight)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(bounds, fillPaint)
            val cyanEnd = stripeRect.left + stripeRect.width() * 0.355f
            val purpleEnd = stripeRect.left + stripeRect.width() * 0.70f
            canvas.drawRect(stripeRect.left, stripeRect.top, cyanEnd, stripeRect.bottom, stripeCyanPaint)
            canvas.drawRect(cyanEnd, stripeRect.top, purpleEnd, stripeRect.bottom, stripePurplePaint)
            canvas.drawRect(purpleEnd, stripeRect.top, stripeRect.right, stripeRect.bottom, stripeRedPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            stripeCyanPaint.alpha = alpha
            stripePurplePaint.alpha = alpha
            stripeRedPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

        companion object {
            private const val STRIPE_HEIGHT_DP = 4
        }
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
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
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
     * The unified slider thumb: a gunmetal-grey brushed rectangle, long side horizontal, rounded
     * and stroked like a real control rather than a flat panel.
     */
    private class BrushedThumbDrawable(private val context: Context) : Drawable() {
        private val cornerRadius = dp(context, 2).toFloat()
        private val strokeWidthPx = dp(context, 1).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = thumbStroke
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val rect = RectF(b)

            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.left.toFloat(), b.bottom.toFloat(),
                intArrayOf(thumbFillTop, thumbFillBottom),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            paint.shader = null

            var y = b.top
            var index = 0
            while (y < b.bottom) {
                val alpha = if (index % 2 == 0) 55 else 24
                linePaint.color = Color.argb(alpha, 150, 156, 164)
                canvas.drawLine(b.left.toFloat(), y.toFloat(), b.right.toFloat(), y.toFloat(), linePaint)
                y += 2
                index++
            }

            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                intArrayOf(Color.argb(90, 255, 255, 255), Color.TRANSPARENT, Color.argb(40, 0, 0, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            paint.shader = null

            canvas.drawRoundRect(
                RectF(
                    b.left + strokeWidthPx / 2f, b.top + strokeWidthPx / 2f,
                    b.right - strokeWidthPx / 2f, b.bottom - strokeWidthPx / 2f,
                ),
                cornerRadius, cornerRadius, strokePaint,
            )
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

        // Drawable.getConstantState() returns null unless a subclass provides one.
        // BaseSlider.setCustomThumbDrawable() calls getConstantState().newDrawable() internally
        // (to make per-thumb-index copies), so without this it NPEs the instant a Slider using
        // this thumb is created -- GradientDrawable (the old thumb) has this built in already,
        // which is why that path never hit it.
        private val constantState = object : ConstantState() {
            override fun newDrawable(): Drawable = BrushedThumbDrawable(context)
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
