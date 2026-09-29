package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The front page's power button: touch target and picture in one. Carries the same toggle API the
 * old bottom-bar power FAB had ([isToggled], [toggleOnClick], the click listener), so MainActivity's
 * power logic is unchanged.
 *
 * It draws the whole button within its bounds (HomeArt's `power_btn` rect, which includes the glow):
 * a metal collar around a dark well with a power symbol, grey while off, and with a violet neon ring
 * and halo while on. No baked art is involved. No LED dot (the view is hidden).
 */
class PowerHotspot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface OnToggleClickListener {
        fun onClick()
    }

    private var clickListener: OnToggleClickListener? = null

    /** The LED dot view to light together with the symbol. */
    var linkedLed: View? = null
        set(value) {
            field = value
            value?.isActivated = isToggled
        }

    var toggleOnClick = true

    var isToggled = false
        set(value) {
            if (field == value) return
            field = value
            isSelected = value
            linkedLed?.isActivated = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBounds = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            clickListener?.onClick()
            if (toggleOnClick) isToggled = !isToggled
        }
    }

    fun setOnToggleClickListener(listener: OnToggleClickListener) {
        clickListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (r <= 0f) return
        val on = isToggled

        // Halo, only while on.
        if (on) {
            paint.reset(); paint.isAntiAlias = true
            paint.shader = RadialGradient(cx, cy, r, Ring.withAlpha(0.55f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        // Metal collar (light top-left, dark bottom-right, like the tile bezels).
        paint.reset(); paint.isAntiAlias = true
        paint.shader = SweepGradient(cx, cy, CollarColors, CollarStops)
        canvas.drawCircle(cx, cy, r * 0.78f, paint)
        // Dark well, with a faint glow while on.
        paint.reset(); paint.isAntiAlias = true
        paint.color = 0xFF050508.toInt()
        canvas.drawCircle(cx, cy, r * 0.68f, paint)
        if (on) {
            paint.shader = RadialGradient(cx, cy, r * 0.68f, Ring.withAlpha(0.30f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 0.68f, paint)
        }
        // Neon ring.
        val ring = if (on) Ring else 0xFF2A2A3A.toInt()
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        if (on) {
            paint.color = Ring.withAlpha(0.25f); paint.strokeWidth = r * 0.14f
            canvas.drawCircle(cx, cy, r * 0.6f, paint)
        }
        paint.color = ring; paint.strokeWidth = r * 0.035f
        canvas.drawCircle(cx, cy, r * 0.6f, paint)
        // Power symbol: an arc open at the top, and a stem.
        val symbolRadius = r * 0.3f
        val symbol = if (on) 0xFFB8AEFF.toInt() else 0xFF3A3A4A.toInt()
        arcBounds.set(cx - symbolRadius, cy - symbolRadius, cx + symbolRadius, cy + symbolRadius)
        paint.strokeCap = Paint.Cap.ROUND
        if (on) {
            paint.color = Ring.withAlpha(0.30f); paint.strokeWidth = r * 0.165f
            canvas.drawArc(arcBounds, -55f, 290f, false, paint)
        }
        paint.color = symbol; paint.strokeWidth = r * 0.055f
        canvas.drawArc(arcBounds, -55f, 290f, false, paint)
        canvas.drawLine(cx, cy - symbolRadius * 1.15f, cx, cy - symbolRadius * 0.10f, paint)
    }

    private companion object {
        const val Ring = 0xFF3A22FF.toInt()
        val CollarColors = intArrayOf(
            0xFF2A282A.toInt(), 0xFFC8C6C8.toInt(), 0xFF383638.toInt(), 0xFF9A9799.toInt(),
            0xFF232123.toInt(), 0xFFD0CED0.toInt(), 0xFF2A282A.toInt(),
        )
        val CollarStops = floatArrayOf(0f, 0.15f, 0.30f, 0.50f, 0.70f, 0.85f, 1f)
        fun Int.withAlpha(a: Float): Int = Color.argb((a * 255f).toInt(), Color.red(this), Color.green(this), Color.blue(this))
    }
}
