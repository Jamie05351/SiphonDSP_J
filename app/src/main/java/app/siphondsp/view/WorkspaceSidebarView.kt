package app.siphondsp.view

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.siphondsp.R

/**
 * Overlay for the DSP workspace rail. The rounded glass panel and its five inlaid tiles come from
 * the full-screen backdrop ([R.drawable.dsp_workspace_field], one neutral image for every
 * screen); this view draws, on top of it, each tile's icon and the cyan neon selection on
 * whichever tile is the current screen.
 *
 * It replaced five near-but-not-quite-identical `dsp_workspace_backdrop_*.png` exports (one per
 * destination, each with a different tile pre-lit) -- cycling screens showed a subtly different
 * rail. Now the rail is one image + this overlay: identical everywhere, only [selectedIndex]
 * moves.
 *
 * Tile geometry mirrors [DspCrossNavBar]'s `ROW_WEIGHTS` -- a 480-unit rail (16 top margin, five
 * 80-unit tiles, 12-unit gaps) scaled to the view's real height -- so this and the invisible
 * click-targets `DspCrossNavBar.populate()` lays over the rail stay registered.
 */
class WorkspaceSidebarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 0-based index of the selected tile among the primary-nav destinations; -1 = none. */
    var selectedIndex: Int = -1
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Icons in primary-nav order: PEQ, Gains & Delay, Crossover & Tilt, Compressor, All-pass.
    private val icons = intArrayOf(
        R.drawable.wsicon_peq,
        R.drawable.wsicon_gains,
        R.drawable.wsicon_xover,
        R.drawable.wsicon_compressor,
        R.drawable.wsicon_allpass,
    ).map { BitmapFactory.decodeResource(resources, it) }

    // 480-unit rail space. DspCrossNavBar.ROW_WEIGHTS is 16/80/12; the rendered art's tiles
    // measure a touch lower and taller (top ~20, height ~82, gap ~10), so the selection hugs
    // them with these. The click-targets still use ROW_WEIGHTS -- a few px of slack there is
    // fine, they're invisible.
    private val railUnits = 480f
    private val railTopMargin = 20f
    private val tileUnit = 82f
    private val tileGap = 10f
    private val artWidth = 124f
    private val iconBoxDp = 54f

    private val cyan = 0xFF5AD6EC.toInt()
    private val cyanSoft = 0xFF33C6E4.toInt()
    private val cyanFilter = PorterDuffColorFilter(cyan, PorterDuff.Mode.SRC_IN)

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = cyanSoft
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = cyan
    }
    private val src = Rect()
    private val dstF = RectF()
    private val tile = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val scaleY = h / railUnits
        val scaleX = w / artWidth
        val box = dp(iconBoxDp)

        for (i in icons.indices) {
            val top = (railTopMargin + i * (tileUnit + tileGap)) * scaleY
            val cy = top + tileUnit * scaleY / 2f
            val cx = (artWidth / 2f) * scaleX
            val selected = i == selectedIndex

            if (selected) {
                // The art's tiles sit ~8px in from the 124px column edges; use plain dp insets.
                tile.set(dp(8f), top, w - dp(8f), top + tileUnit * scaleY)
                val r = dp(10f)
                glowPaint.strokeWidth = dp(3f)
                glowPaint.alpha = 140
                glowPaint.maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(tile, r, r, glowPaint)
                glowPaint.maskFilter = null
                borderPaint.strokeWidth = dp(2.2f)
                canvas.drawRoundRect(
                    RectF(tile).apply { inset(dp(1.1f), dp(1.1f)) }, r, r, borderPaint,
                )
            }

            val bmp = icons.getOrNull(i) ?: continue
            src.set(0, 0, bmp.width, bmp.height)
            val fit = minOf(box / bmp.width, box / bmp.height)
            val dw = bmp.width * fit
            val dh = bmp.height * fit
            dstF.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
            iconPaint.colorFilter = if (selected) cyanFilter else null
            canvas.drawBitmap(bmp, src, dstF, iconPaint)
        }
    }
}
