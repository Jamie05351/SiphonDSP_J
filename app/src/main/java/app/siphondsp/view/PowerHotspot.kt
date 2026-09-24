package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.siphondsp.R

/**
 * Invisible touch target over the power button drawn in the front-page artwork. Carries the same
 * toggle API the old bottom-bar power FAB had ([isToggled], [toggleOnClick], the click listener),
 * so MainActivity's power logic is unchanged.
 *
 * Head unit: the backdrop is the DSP-off art (grey button); while on, this view paints
 * `dsp_home_power_on` -- the same patch cut from the DSP-on art (purple button + glow) -- over
 * its whole bounds, which HomeArt's `power_btn` rect sets to exactly that crop. No LED dot there
 * (the view is hidden). Phone: unchanged -- paints a green power symbol over the art's white one
 * and lights [linkedLed] (the small dot under the button).
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

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = RectF()

    /** Head unit only: the DSP-on patch of the artwork, drawn over the off-art while on. */
    private val onArt: Drawable? =
        if (context.isHeadUnitDisplay()) ContextCompat.getDrawable(context, R.drawable.dsp_home_power_on) else null

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
        if (onArt != null) {
            if (isToggled) {
                onArt.setBounds(0, 0, width, height)
                onArt.draw(canvas)
            }
            return
        }
        if (!isToggled) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = width * 0.21f
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        // Soft halo first (wide, faint), then the crisp symbol on top -- no blur filter, which
        // isn't reliable under the head unit's software renderer.
        drawSymbol(canvas, cx, cy, radius, width * 0.11f, 0x2239FF14)
        drawSymbol(canvas, cx, cy, radius, width * 0.075f, 0x5539FF14)
        drawSymbol(canvas, cx, cy, radius, width * 0.05f, BmwDashboardSkin.TOGGLE_ON_GREEN)
    }

    private fun drawSymbol(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float, color: Int) {
        ringPaint.strokeWidth = stroke
        ringPaint.color = color
        // Ring open at the top, plus the vertical bar through the gap.
        canvas.drawArc(arc, -60f, 300f, false, ringPaint)
        canvas.drawLine(cx, cy - radius * 1.25f, cx, cy - radius * 0.1f, ringPaint)
    }
}
