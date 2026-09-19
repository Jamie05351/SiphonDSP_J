package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Green glow drawn over the small LED dot under the artwork's power button. Lit while
 * [isActivated] (driven by [PowerHotspot.linkedLed]); transparent otherwise, so the artwork's own
 * unlit dot shows through. Not touchable.
 */
class PowerLed @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        if (!isActivated) return
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        paint.color = 0x2239FF14
        canvas.drawCircle(cx, cy, r * 1.9f, paint)
        paint.color = 0x5539FF14
        canvas.drawCircle(cx, cy, r * 1.3f, paint)
        paint.color = BmwDashboardSkin.TOGGLE_ON_GREEN
        canvas.drawCircle(cx, cy, r * 0.8f, paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
