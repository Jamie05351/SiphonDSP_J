package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Invisible touch target over the power button drawn in the front-page artwork. Carries the same
 * toggle API the old bottom-bar power FAB had ([isToggled], [toggleOnClick], the click listener),
 * so MainActivity's power logic is unchanged.
 *
 * Head unit: the art draws only the dark disc (its icon is erased); this view paints the whole
 * indicator -- the disc's outline ring and the power symbol -- neon green while on, red while
 * off, each with a faint halo. No LED dot there (the view is hidden). Phone: unchanged -- paints
 * a green power symbol over the art's white one and lights [linkedLed] (the small dot under the
 * button).
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

    private val artIcon = context.isHeadUnitDisplay()

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

    private fun drawArtIndicator(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val on = isToggled
        val core = if (on) BmwDashboardSkin.TOGGLE_ON_GREEN else INDICATOR_OFF_RED
        val halo = core and 0x00FFFFFF
        // The hotspot box is the art's disc; the ring sits just inside its edge.
        val ringR = width * 0.5f - width * 0.032f
        arc.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        // Wide faint strokes first, crisp ring on top -- no blur filter, which isn't reliable
        // under the head unit's software renderer.
        ringPaint.color = halo or 0x28000000
        ringPaint.strokeWidth = width * 0.116f
        canvas.drawArc(arc, 0f, 360f, false, ringPaint)
        ringPaint.color = halo or 0x5A000000
        ringPaint.strokeWidth = width * 0.074f
        canvas.drawArc(arc, 0f, 360f, false, ringPaint)
        ringPaint.color = core
        ringPaint.strokeWidth = width * 0.042f
        canvas.drawArc(arc, 0f, 360f, false, ringPaint)

        // Power symbol, sized/placed where the art's own icon used to sit (its centre is a touch
        // right of and below the disc's).
        val sx = cx + width * 0.014f
        val sy = cy + width * 0.022f
        val sr = width * 0.176f
        arc.set(sx - sr, sy - sr, sx + sr, sy + sr)
        drawSymbol(canvas, sx, sy, sr, width * 0.098f, halo or 0x28000000)
        drawSymbol(canvas, sx, sy, sr, width * 0.08f, halo or 0x5A000000)
        drawSymbol(canvas, sx, sy, sr, width * 0.063f, core)
    }

    override fun onDraw(canvas: Canvas) {
        if (artIcon) {
            drawArtIndicator(canvas)
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

    private companion object {
        const val INDICATOR_OFF_RED = 0xFFFF2A2A.toInt()
    }
}
