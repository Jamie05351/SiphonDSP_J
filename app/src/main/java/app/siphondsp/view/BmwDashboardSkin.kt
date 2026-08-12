package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.Drawable.ConstantState
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
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
    const val PANEL_TOP = 0xFF20262D.toInt()
    const val PANEL_BOTTOM = 0xFF101419.toInt()
    // Near-black for the sidebar panel -- deliberately not a gradient, unlike the workspace
    // background it sits in front of, so the sidebar itself reads as a distinct, well-defined
    // solid fixture rather than blending into the content behind it.
    const val SIDEBAR_GUNMETAL = 0xFF0B0D0F.toInt()

    private val inactiveSurface = Color.rgb(18, 23, 29)
    private val inactiveStroke = Color.rgb(61, 71, 82)
    private val inactiveText = Color.rgb(211, 217, 223)
    private val selectedSurface = Color.rgb(24, 69, 101)

    fun brushedPanelDrawable(): Drawable = BrushedMetalDrawable()

    /**
     * Solid, near-black sidebar background with an always-lit accent border (the same blue as a
     * tile's own border when it's the active one), rather than the brushed-gradient look the
     * rest of the workspace chrome uses.
     */
    fun sidebarPanelDrawable(context: Context): Drawable = GradientDrawable().apply {
        setColor(SIDEBAR_GUNMETAL)
        setStroke(dp(context, 1), LIGHT_BLUE)
    }

    /**
     * The sidebar's active-tile background: same neutral fill as an inactive tile, a brighter
     * accent border, and a soft glow concentrated near the bottom edge, as if lit from beneath.
     * Uses a real [BlurMaskFilter] blur rather than layered fake-blur shapes, which requires the
     * View this is set on to render via a software layer -- BlurMaskFilter has no effect on
     * hardware-accelerated layers. See DspCrossNavBar.populate(), which sets that layer type.
     */
    fun litTileDrawable(context: Context): Drawable = LitTileGlowDrawable(context)

    // Single slider thumb shared by every DSP workspace slider (Gains/Delay, Crossovers & Tilt,
    // Mono Bass, Routing, Compressor) -- long side horizontal, like a physical fader cap, with
    // the same brushed-metal grain as the workspace panels. Replaces the previously divergent
    // circular "chrome ball" (CrossoverDashboardBuilder.createRoundThumb) and tall rectangular
    // "ingot" (the old styleSlider()) thumbs.
    const val SLIDER_THUMB_WIDTH_DP = 28
    const val SLIDER_THUMB_HEIGHT_DP = 14
    private val thumbFillTop = Color.rgb(238, 240, 243)
    private val thumbFillBottom = Color.rgb(176, 182, 190)
    private val thumbStroke = Color.rgb(110, 116, 123)

    fun sliderThumbDrawable(context: Context): Drawable = BrushedThumbDrawable(context)

    fun addMAccent(parent: LinearLayout) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(LIGHT_BLUE, M_BLUE, M_RED).forEach { colour ->
            row.addView(View(context).apply { setBackgroundColor(colour) }, LinearLayout.LayoutParams(dp(context, 6), dp(context, 18)).apply {
                marginEnd = dp(context, 2)
            })
        }
        parent.addView(row)
    }

    fun styleWorkspace(root: View) {
        root.background = brushedPanelDrawable()
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
        root.background = brushedPanelDrawable()
    }

    /** Apply automotive chrome to existing XML-driven cards and controls without touching behavior. */
    fun styleTree(root: View) {
        when (root) {
            is MaterialCardView -> {
                root.radius = dp(root.context, 7).toFloat()
                root.cardElevation = 0f
                root.strokeWidth = dp(root.context, 1)
                root.strokeColor = inactiveStroke
                root.setCardBackgroundColor(Color.argb(215, 18, 23, 29))
            }
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

    // Public so callers that build/inflate sliders onto pages ViewPager2 may not have attached
    // yet (e.g. NativeBmwCompressorFragment's off-screen pager pages) can style them directly
    // at creation time, instead of relying on styleTree's later recursive walk to reach them.
    fun styleSlider(slider: Slider) {
        val context = slider.context
        slider.trackHeight = dp(context, 6)
        slider.thumbWidth = dp(context, SLIDER_THUMB_WIDTH_DP)
        slider.thumbHeight = dp(context, SLIDER_THUMB_HEIGHT_DP)
        slider.setTrackActiveTintList(ColorStateList.valueOf(LIGHT_BLUE))
        slider.setTrackInactiveTintList(ColorStateList.valueOf(Color.rgb(31, 35, 41)))
        slider.setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
        slider.setCustomThumbDrawable(sliderThumbDrawable(context))
    }

    private fun styleSwitch(toggle: SwitchCompat) {
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

    private class BrushedMetalDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }

        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                intArrayOf(PANEL_TOP, 0xFF171C22.toInt(), PANEL_BOTTOM),
                floatArrayOf(0f, .45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, paint)
            paint.shader = null

            var y = b.top
            var index = 0
            while (y < b.bottom) {
                val alpha = if (index % 3 == 0) 22 else 10
                linePaint.color = Color.argb(alpha, 205, 215, 225)
                canvas.drawLine(b.left.toFloat(), y.toFloat(), b.right.toFloat(), y.toFloat(), linePaint)
                y += 3
                index++
            }

            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                intArrayOf(Color.argb(34, 255, 255, 255), Color.TRANSPARENT, Color.argb(28, 0, 0, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    /**
     * The unified slider thumb: a light brushed-aluminum rectangle, long side horizontal. Same
     * scan-line-grain + sheen technique as [BrushedMetalDrawable], but on a light base (dark
     * grain lines instead of light ones) since this sits on top of dark tracks/panels rather
     * than being one itself, and rounded/stroked like a real control instead of a flat panel.
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
                val alpha = if (index % 2 == 0) 40 else 18
                linePaint.color = Color.argb(alpha, 90, 96, 104)
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

    private class LitTileGlowDrawable(private val context: Context) : Drawable() {
        private val cornerRadius = dp(context, 6).toFloat()
        private val strokeWidthPx = dp(context, 2).toFloat()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Same neutral fill DspCrossNavBar.populate() uses for every tile, selected or not --
            // the glow/border are what set this tile apart, not a different background color.
            color = Color.argb(110, 12, 16, 21)
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = LIGHT_BLUE_BRIGHT
            alpha = 150
            maskFilter = BlurMaskFilter(dp(context, 6).toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = LIGHT_BLUE_BRIGHT
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRoundRect(RectF(b), cornerRadius, cornerRadius, fillPaint)

            // Glow: a soft blurred blob confined to the bottom ~45% of the tile, as if a light
            // source sits just beneath it. Kept inside the tile's own bounds rather than
            // overflowing past them, since the sidebar's LinearLayout clips its children.
            val inset = dp(context, 3).toFloat()
            val glowTop = b.top + b.height() * 0.55f
            canvas.drawRoundRect(
                RectF(b.left + inset, glowTop, b.right - inset, b.bottom - inset),
                cornerRadius, cornerRadius, glowPaint,
            )

            val strokeInset = strokeWidthPx / 2f
            canvas.drawRoundRect(
                RectF(
                    b.left + strokeInset, b.top + strokeInset,
                    b.right - strokeInset, b.bottom - strokeInset,
                ),
                cornerRadius, cornerRadius, strokePaint,
            )
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        // Same reasoning as BrushedThumbDrawable above: no ConstantState by default, and while
        // a plain View background doesn't strictly need one, MaterialButton's internal handling
        // can still query it -- cheap to provide, so we do.
        private val constantState = object : ConstantState() {
            override fun newDrawable(): Drawable = LitTileGlowDrawable(context)
            override fun getChangingConfigurations(): Int = 0
        }

        override fun getConstantState(): ConstantState = constantState
    }
}
