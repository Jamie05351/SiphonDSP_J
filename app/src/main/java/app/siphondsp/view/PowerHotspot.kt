package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The front page's power button: touch target and picture in one. Carries the same toggle API the
 * old bottom-bar power FAB had ([isToggled], [toggleOnClick], the click listener), so MainActivity's
 * power logic is unchanged.
 *
 * It draws the whole button within its bounds (HomeArt's `power_btn` rect, which includes the
 * glow): a matte grey face in a matte grey bezel, with a glowing power symbol that is always lit:
 * purple while on (the signal chain's purple), red while off, so the engine state reads at a glance;
 * the front page's tiles and live data also grey out while off. No baked art is involved. No LED dot (the view is hidden).
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

        val glow = if (on) GlowOn else GlowOff
        val symbol = if (on) SymbolOn else SymbolOff
        // The button itself; the rest of the rect is room for the symbol's glow.
        val b = r * 0.72f

        // Matte grey bezel, a little lighter at the top.
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(cx, cy - b, cx, cy + b, BezelTop, BezelBottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, b, paint)
        // Matte grey face, set just inside the bezel.
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(cx, cy - b, cx, cy + b, FaceTop, FaceBottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, b * 0.82f, paint)

        // Power symbol: an arc open at the top and a stem, over one soft glow of its colour.
        val symbolRadius = b * 0.42f
        arcBounds.set(cx - symbolRadius, cy - symbolRadius, cx + symbolRadius, cy + symbolRadius)
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = glow.withAlpha(0.25f); paint.strokeWidth = b * 0.2f
        drawSymbol(canvas, cx, cy, symbolRadius)
        paint.color = symbol; paint.strokeWidth = b * 0.085f
        drawSymbol(canvas, cx, cy, symbolRadius)
    }

    private fun drawSymbol(canvas: Canvas, cx: Float, cy: Float, symbolRadius: Float) {
        canvas.drawArc(arcBounds, -50f, 280f, false, paint)
        canvas.drawLine(cx, cy - symbolRadius * 1.2f, cx, cy - symbolRadius * 0.15f, paint)
    }

    private companion object {
        // On: the signal chain's purple. Off: BmwDashboardSkin.M_RED.
        const val GlowOn = 0xFFB14DFF.toInt()
        const val GlowOff = 0xFFE32B3B.toInt()
        const val SymbolOn = 0xFFECA3FC.toInt()
        const val SymbolOff = 0xFFFFB8BE.toInt()
        const val BezelTop = 0xFF6A6A70.toInt()
        const val BezelBottom = 0xFF2C2C31.toInt()
        const val FaceTop = 0xFF4E4E55.toInt()
        const val FaceBottom = 0xFF2A2A2F.toInt()
        fun Int.withAlpha(a: Float): Int = Color.argb((a * 255f).toInt(), Color.red(this), Color.green(this), Color.blue(this))
    }
}
