package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
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
 * shadow and glow): a matte charcoal dome in a matte grey bezel, with a glowing power symbol that
 * is always lit: purple while on (the output meter's purple), red while off, so the engine state
 * reads at a glance. No baked art is involved. No LED dot (the view is hidden).
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
        // The button itself; the rest of the rect is room for its shadow and the symbol's glow.
        val b = r * 0.72f

        // Soft shadow the button casts on the plate, a little below it.
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(
            cx, cy + b * 0.06f, b * 1.12f,
            intArrayOf(ShadowColor, ShadowColor, Color.TRANSPARENT), floatArrayOf(0f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy + b * 0.06f, b * 1.12f, paint)
        // Matte grey bezel, lit from the top-left.
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(cx - b, cy - b, cx + b, cy + b, BezelColors, BezelStops, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, b, paint)
        // The dark gap between bezel and face, with a faint hairline of the glow colour in it.
        paint.reset(); paint.isAntiAlias = true
        paint.color = 0xFF141418.toInt()
        canvas.drawCircle(cx, cy, b * 0.86f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = glow.withAlpha(0.45f); paint.strokeWidth = b * 0.02f
        canvas.drawCircle(cx, cy, b * 0.845f, paint)
        // Matte grey dome face: lighter towards the top-left, darker at the bottom-right.
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(cx - b * 0.3f, cy - b * 0.35f, b * 1.3f, FaceColors, null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, b * 0.81f, paint)

        // Power symbol: an arc open at the top, and a stem, glowing.
        val symbolRadius = b * 0.42f
        arcBounds.set(cx - symbolRadius, cy - symbolRadius, cx + symbolRadius, cy + symbolRadius)
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for ((alpha, stroke) in SymbolGlow) {
            paint.color = glow.withAlpha(alpha); paint.strokeWidth = b * stroke
            drawSymbol(canvas, cx, cy, symbolRadius)
        }
        paint.color = symbol; paint.strokeWidth = b * 0.085f
        drawSymbol(canvas, cx, cy, symbolRadius)
    }

    private fun drawSymbol(canvas: Canvas, cx: Float, cy: Float, symbolRadius: Float) {
        canvas.drawArc(arcBounds, -50f, 280f, false, paint)
        canvas.drawLine(cx, cy - symbolRadius * 1.2f, cx, cy - symbolRadius * 0.15f, paint)
    }

    private companion object {
        // On: the output meter's purple. Off: BmwDashboardSkin.M_RED.
        const val GlowOn = 0xFFB14DFF.toInt()
        const val GlowOff = 0xFFE32B3B.toInt()
        const val SymbolOn = 0xFFECA3FC.toInt()
        const val SymbolOff = 0xFFFFB8BE.toInt()
        val BezelColors = intArrayOf(0xFF74747A.toInt(), 0xFF4A4A50.toInt(), 0xFF2E2E33.toInt(), 0xFF222226.toInt())
        val BezelStops = floatArrayOf(0f, 0.35f, 0.7f, 1f)
        val FaceColors = intArrayOf(0xFF5C5C63.toInt(), 0xFF3A3A40.toInt(), 0xFF26252A.toInt())
        const val ShadowColor = 0x99000000.toInt()
        // Glow passes under the symbol's core stroke: (alpha, stroke width as a fraction of the
        // button radius).
        val SymbolGlow = listOf(0.12f to 0.30f, 0.30f to 0.17f)
        fun Int.withAlpha(a: Float): Int = Color.argb((a * 255f).toInt(), Color.red(this), Color.green(this), Color.blue(this))
    }
}
