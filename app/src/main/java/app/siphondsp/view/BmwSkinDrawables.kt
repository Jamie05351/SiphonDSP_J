package app.siphondsp.view

import android.content.Context
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
// Every design token these drawables read, the dp()/blend() helpers and the two bitmap loaders
// stay on BmwDashboardSkin -- widened private -> internal for this -- and are pulled in wholesale.
import app.siphondsp.view.BmwDashboardSkin.blend
import app.siphondsp.view.BmwDashboardSkin.dp
import app.siphondsp.view.BmwDashboardSkin.loadPlainMetalBitmap
import app.siphondsp.view.BmwDashboardSkin.loadWorkspaceBackgroundBitmap
import kotlin.math.roundToInt

/*
 * The 11 Canvas [Drawable]s behind BmwDashboardSkin's factory functions, lifted verbatim out of
 * that object so its ~500 lines of colour tokens, factories and view-styling read without ~830
 * lines of pixel geometry in between. Every design token / helper / bitmap loader they use stays
 * on BmwDashboardSkin (widened private -> internal) and is imported here; the public accent
 * constants (LIGHT_BLUE_BRIGHT, SLIDER_DEFAULT_COLOR, SELECTED_TILE_BRIGHTNESS) are still read as
 * BmwDashboardSkin.<name>. No behaviour change.
 */

/**
 * Renders the DSP workspace's designed background image: a center-cropped cover fill,
 * independent of the container's own aspect ratio.
 */
internal class PhotoBrushedMetalDrawable(context: Context) : Drawable() {
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
internal class MetalTileDrawable(context: Context, brightness: Float, private val strokeColor: Int? = null) : Drawable() {
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
 * border, a top-edge highlight and bottom-edge shadow line for the emboss, a small darker
 * inset "detail panel" centred within it, and three short accent grip ticks above and below
 * that panel) -- the master art's compact thumb, whose body matches the 18dp housing height
 * exactly and stands clear of the now-slim 6.5dp active fill, so it still reads as the
 * grabbable control without overhanging the capsule the way the earlier taller thumb did.
 */

internal class SliderThumbDrawable(private val context: Context, private val accentColor: Int? = null) : Drawable() {
    private val density = context.resources.displayMetrics.density
    // Border/highlight/shadow stay the neutral metal colors regardless of [accentColor] --
    // just the face gradient AND the inset centre panel recolor. The inset panel is the
    // biggest flat area at this thumb's actual rendered size (the gradient face is mostly a
    // thin margin around it), so leaving it grey read as "a grey handle with a colored rim"
    // instead of a colored handle -- coloring it too is what actually makes the whole thumb
    // read as blue/yellow at a glance.
    private val gradientTop = accentColor?.let { blend(it, Color.WHITE, 0.35f) } ?: BmwDashboardSkin.thumbGradientTop
    private val gradientCenter = accentColor ?: BmwDashboardSkin.thumbGradientCenter
    private val gradientBottom = accentColor?.let { blend(it, Color.BLACK, 0.45f) } ?: BmwDashboardSkin.thumbGradientBottom
    private val insetColor = accentColor?.let { blend(it, Color.BLACK, 0.25f) } ?: BmwDashboardSkin.SLIDER_THUMB_INSET_FILL_COLOR
    private val cornerRadius = BmwDashboardSkin.SLIDER_THUMB_CORNER_RADIUS_DP * density
    private val borderWidth = BmwDashboardSkin.SLIDER_THUMB_BORDER_WIDTH_DP * density
    private val insetMargin = BmwDashboardSkin.SLIDER_THUMB_INSET_MARGIN_DP * density
    private val insetCornerRadius = BmwDashboardSkin.SLIDER_THUMB_INSET_CORNER_RADIUS_DP * density
    private val insetBorderWidth = BmwDashboardSkin.SLIDER_THUMB_INSET_BORDER_WIDTH_DP * density
    private val edgeLineWidth = density // 1dp highlight/shadow lines

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = BmwDashboardSkin.SLIDER_THUMB_BORDER_COLOR
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BmwDashboardSkin.SLIDER_THUMB_HIGHLIGHT_COLOR }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BmwDashboardSkin.SLIDER_THUMB_SHADOW_COLOR }
    private val insetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = insetColor }
    private val insetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = insetBorderWidth
        color = BmwDashboardSkin.SLIDER_THUMB_INSET_BORDER_COLOR
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BmwDashboardSkin.SLIDER_THUMB_TICK_STROKE_WIDTH_DP * density
        strokeCap = Paint.Cap.ROUND
        color = accentColor ?: BmwDashboardSkin.SLIDER_THUMB_BORDER_COLOR
    }
    private val tickLength = BmwDashboardSkin.SLIDER_THUMB_TICK_LENGTH_DP * density
    private val tickSpacing = BmwDashboardSkin.SLIDER_THUMB_TICK_SPACING_DP * density

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

            // Grip ticks: BmwDashboardSkin.SLIDER_THUMB_TICK_COUNT short verticals centred in the band above
            // the inset slot, mirrored in the band below it. Skipped if either band is too
            // shallow to seat a full-length tick with a hair of breathing room.
            val cx = bodyRect.centerX()
            val topBandMid = (bodyRect.top + insetRect.top) / 2f
            val bottomBandMid = (insetRect.bottom + bodyRect.bottom) / 2f
            if (insetRect.top - bodyRect.top >= tickLength + density) {
                val firstX = cx - tickSpacing * (BmwDashboardSkin.SLIDER_THUMB_TICK_COUNT - 1) / 2f
                for (i in 0 until BmwDashboardSkin.SLIDER_THUMB_TICK_COUNT) {
                    val x = firstX + i * tickSpacing
                    canvas.drawLine(x, topBandMid - tickLength / 2f, x, topBandMid + tickLength / 2f, tickPaint)
                    canvas.drawLine(x, bottomBandMid - tickLength / 2f, x, bottomBandMid + tickLength / 2f, tickPaint)
                }
            }
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
 * The pill-shaped outline wrapping a slider's track: a fixed [BmwDashboardSkin.SLIDER_CAPSULE_HEIGHT_DP]-tall
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
 * etc.) -- every other slider keeps the default BmwDashboardSkin.SLIDER_DEFAULT_COLOR border. The focus
 * glow/ring stay BmwDashboardSkin.LIGHT_BLUE_BRIGHT regardless: that's a focus-state indicator, not a band
 * identity. Also draws the recessed groove + thin inner highlight line inset within the
 * capsule -- the master art's own bezel layers -- as an 8.5dp channel for Slider's slim 6.5dp
 * (see SLIDER_TRACK_HEIGHT_DP) native track to sit inside of; the groove's own fill color is
 * what shows through as the "unfilled" look, since the native track's inactive tint is fully
 * transparent (see SLIDER_TRACK_INACTIVE_COLOR).
 */
internal class SliderCapsuleDrawable(context: Context, accentColor: Int? = null) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val capsuleHeight = BmwDashboardSkin.SLIDER_CAPSULE_HEIGHT_DP * density
    private val borderWidth = BmwDashboardSkin.SLIDER_CAPSULE_BORDER_WIDTH_DP * density
    private val cornerRadius = capsuleHeight / 2f
    private val grooveMargin = BmwDashboardSkin.SLIDER_GROOVE_MARGIN_DP * density
    private val grooveStrokeWidth = BmwDashboardSkin.SLIDER_GROOVE_STROKE_WIDTH_DP * density
    private val grooveHighlightMargin = BmwDashboardSkin.SLIDER_GROOVE_HIGHLIGHT_MARGIN_DP * density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BmwDashboardSkin.SLIDER_BOX_BACKGROUND_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = accentColor ?: BmwDashboardSkin.SLIDER_DEFAULT_COLOR
    }
    private val grooveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BmwDashboardSkin.SLIDER_GROOVE_FILL_COLOR }
    private val grooveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = grooveStrokeWidth
        color = BmwDashboardSkin.SLIDER_GROOVE_STROKE_COLOR
    }
    private val grooveHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = BmwDashboardSkin.SLIDER_GROOVE_HIGHLIGHT_COLOR
    }
    private val focusGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = BmwDashboardSkin.LIGHT_BLUE_BRIGHT
        maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = BmwDashboardSkin.LIGHT_BLUE_BRIGHT
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

/** See [glassSwitchTrackDrawable]. Shared glass shell for both positions, except the outer
 *  shell border, which is stateful on state_checked: neon green when on, neon red when off. */
internal class GlassSwitchTrackDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val intrinsicW = (BmwDashboardSkin.GLASS_SWITCH_TRACK_WIDTH_DP * density).roundToInt()
    private val intrinsicH = (BmwDashboardSkin.GLASS_SWITCH_TRACK_HEIGHT_DP * density).roundToInt()
    private val borderWidth = BmwDashboardSkin.GLASS_SWITCH_TRACK_BORDER_WIDTH_DP * density
    private var checked = false
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = BmwDashboardSkin.GLASS_SWITCH_OFF_COLOR
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth * 0.75f
        color = BmwDashboardSkin.GLASS_SWITCH_TRACK_RIM_COLOR
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val sheenPath = android.graphics.Path()
    private val clipPath = android.graphics.Path()

    override fun getIntrinsicWidth() = intrinsicW
    override fun getIntrinsicHeight() = intrinsicH
    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val wasChecked = checked
        checked = state.contains(android.R.attr.state_checked)
        if (checked != wasChecked) {
            shellBorderPaint.color = if (checked) BmwDashboardSkin.GLASS_SWITCH_ON_COLOR else BmwDashboardSkin.GLASS_SWITCH_OFF_COLOR
            invalidateSelf()
        }
        return checked != wasChecked
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        trackRect.set(bounds)
        trackRect.inset(borderWidth / 2f, borderWidth / 2f)
        if (trackRect.isEmpty) return
        val corner = trackRect.height() / 2f
        fillPaint.shader = LinearGradient(
            trackRect.left, trackRect.top, trackRect.right, trackRect.bottom,
            intArrayOf(BmwDashboardSkin.GLASS_SWITCH_TRACK_FILL_TOP, BmwDashboardSkin.GLASS_SWITCH_TRACK_FILL_MID, BmwDashboardSkin.GLASS_SWITCH_TRACK_FILL_BOTTOM),
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
            BmwDashboardSkin.GLASS_SWITCH_TRACK_SHEEN_NEAR, BmwDashboardSkin.GLASS_SWITCH_TRACK_SHEEN_FAR, Shader.TileMode.CLAMP,
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

/** See [glassSwitchThumbDrawable]. Stateful on state_checked: a glowing neon-green sphere when
 *  on, a glowing neon-red sphere when off -- same glass-sphere treatment either way (radial
 *  fill, blurred outer glow, crisp ring, top highlight arc), only the hue changes, so the
 *  switch reads as a live red/green status light rather than lit-vs-dead. */
internal class GlassSwitchThumbDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val intrinsicSize = (BmwDashboardSkin.GLASS_SWITCH_THUMB_SIZE_DP * density).roundToInt()
    private val glowWidth = 3f * density
    private val borderWidth = density
    private var checked = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = borderWidth }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = glowWidth
        maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        color = BmwDashboardSkin.GLASS_SWITCH_THUMB_HIGHLIGHT
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
        val near = if (checked) BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_FILL_NEAR else BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_FILL_NEAR
        val far = if (checked) BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_FILL_FAR else BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_FILL_FAR
        fillPaint.shader = RadialGradient(
            circleRect.centerX(), circleRect.top + circleRect.height() * 0.42f, circleRect.width() / 2f,
            near, far, Shader.TileMode.CLAMP,
        )
        borderPaint.color = if (checked) BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_BORDER else BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_BORDER
        glowPaint.color = if (checked) BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_GLOW else BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_GLOW
        ringPaint.color = if (checked) BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_RING else BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_RING
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
        canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r + glowWidth / 2f, glowPaint)
        canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, fillPaint)
        canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, ringPaint)
        canvas.drawCircle(circleRect.centerX(), circleRect.centerY(), r, borderPaint)
        canvas.drawPath(highlightPath, highlightPaint)
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** See [glassSegmentTrackDrawable]. The dark capsule shell a NORMAL/INVERT toggle group sits
 *  in -- shared, unaffected by which segment is checked. */
internal class GlassSegmentTrackDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val borderWidth = BmwDashboardSkin.GLASS_SEGMENT_BORDER_WIDTH_DP * density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = BmwDashboardSkin.GLASS_SEGMENT_TRACK_BORDER_COLOR
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth * 0.6f
        color = BmwDashboardSkin.GLASS_SEGMENT_TRACK_RIM_COLOR
    }
    private val trackRect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        trackRect.set(bounds)
        trackRect.inset(borderWidth / 2f, borderWidth / 2f)
        if (trackRect.isEmpty) return
        fillPaint.shader = LinearGradient(
            trackRect.left, trackRect.top, trackRect.right, trackRect.bottom,
            BmwDashboardSkin.GLASS_SEGMENT_TRACK_FILL_NEAR, BmwDashboardSkin.GLASS_SEGMENT_TRACK_FILL_FAR, Shader.TileMode.CLAMP,
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
internal class GlassSegmentDrawable(context: Context, private val accentColor: Int? = null) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val borderWidth = BmwDashboardSkin.GLASS_SEGMENT_BORDER_WIDTH_DP * density
    private val glowWidth = BmwDashboardSkin.GLASS_SEGMENT_GLOW_WIDTH_DP * density
    private var checked = false
    // Same recoloring technique as GlassSwitchThumbDrawable: derive lit/mid/dark shades of
    // accentColor for the fill gradient, and preserve each fixed color's own alpha for the
    // (semi-transparent) glow.
    private val fillNear = accentColor?.let { blend(it, Color.WHITE, 0.25f) } ?: BmwDashboardSkin.GLASS_SEGMENT_FILL_NEAR
    private val fillMid = accentColor ?: BmwDashboardSkin.GLASS_SEGMENT_FILL_MID
    private val fillFar = accentColor?.let { blend(it, Color.BLACK, 0.25f) } ?: BmwDashboardSkin.GLASS_SEGMENT_FILL_FAR
    private val borderColor = accentColor ?: BmwDashboardSkin.GLASS_SEGMENT_BORDER_COLOR
    private val glowColor = accentColor?.let {
        Color.argb(Color.alpha(BmwDashboardSkin.GLASS_SEGMENT_GLOW_COLOR), Color.red(it), Color.green(it), Color.blue(it))
    } ?: BmwDashboardSkin.GLASS_SEGMENT_GLOW_COLOR
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
            BmwDashboardSkin.GLASS_SEGMENT_SHEEN_NEAR, BmwDashboardSkin.GLASS_SEGMENT_SHEEN_FAR, Shader.TileMode.CLAMP,
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
internal class GlassBoxDrawable(context: Context, private val showBorder: Boolean, private val accentColor: Int? = null) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val cornerRadius = BmwDashboardSkin.GLASS_BOX_CORNER_RADIUS_DP * density
    private val strokeWidth = BmwDashboardSkin.GLASS_BOX_STROKE_WIDTH_DP * density
    private val edgeLineWidth = density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GlassBoxDrawable.strokeWidth
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BmwDashboardSkin.GLASS_BOX_TOP_GLINT_COLOR }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BmwDashboardSkin.GLASS_BOX_BOTTOM_GLINT_COLOR }
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
            intArrayOf(BmwDashboardSkin.GLASS_FILL_TOP, BmwDashboardSkin.GLASS_FILL_MID, BmwDashboardSkin.GLASS_FILL_BOTTOM),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )
        // accentColor, when given, tints the border to that flat colour instead of the neutral
        // glass rim (a non-null shader would otherwise win over Paint.color, so it's cleared).
        if (accentColor != null) {
            strokePaint.shader = null
            strokePaint.color = accentColor
        } else {
            strokePaint.color = Color.WHITE
            strokePaint.shader = LinearGradient(
                boxRect.left, boxRect.top, boxRect.left, boxRect.bottom,
                intArrayOf(BmwDashboardSkin.GLASS_RIM_TOP, BmwDashboardSkin.GLASS_RIM_MID, BmwDashboardSkin.GLASS_RIM_BOTTOM),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }

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
            BmwDashboardSkin.GLASS_SHEEN_NEAR, BmwDashboardSkin.GLASS_SHEEN_FAR, Shader.TileMode.CLAMP,
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
internal class IlluminatedTileDrawable(context: Context) : Drawable() {
    private val corner = dp(context, 6).toFloat()
    private val strokeWidthPx = dp(context, 2).toFloat()
    private val metalBitmap = loadPlainMetalBitmap(context)
    private val fillRect = RectF()
    private val matrix = Matrix()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        shader = BitmapShader(metalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setScale(BmwDashboardSkin.SELECTED_TILE_BRIGHTNESS, BmwDashboardSkin.SELECTED_TILE_BRIGHTNESS, BmwDashboardSkin.SELECTED_TILE_BRIGHTNESS, 1f)
        })
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.LIGHT_BLUE_BRIGHT
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        maskFilter = BlurMaskFilter(dp(context, 5).toFloat(), BlurMaskFilter.Blur.NORMAL)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.LIGHT_BLUE_BRIGHT
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
internal class TileFocusRingDrawable(context: Context) : Drawable() {
    private val corner = dp(context, 6).toFloat()
    private val strokeWidthPx = dp(context, 2).toFloat()
    private val strokeRect = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.LIGHT_BLUE_BRIGHT
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        maskFilter = BlurMaskFilter(dp(context, 5).toFloat(), BlurMaskFilter.Blur.NORMAL)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.LIGHT_BLUE_BRIGHT
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
