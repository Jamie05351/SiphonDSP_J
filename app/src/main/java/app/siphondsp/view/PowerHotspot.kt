package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
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
 * The backdrop is the DSP-off art (grey button); while on, this view paints the same patch cut
 * from the DSP-on art (purple button + glow) over its whole bounds, which HomeArt's `power_btn`
 * rect sets to exactly that crop: `dsp_home_power_on` on the head unit, `dsp_home_power_on_phone`
 * on a phone. No LED dot on either (the view is hidden).
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

    /** The DSP-on patch of this device's artwork, drawn over the off-art while on. */
    private val onArt: Drawable? = ContextCompat.getDrawable(
        context,
        if (context.isHeadUnitDisplay()) R.drawable.dsp_home_power_on else R.drawable.dsp_home_power_on_phone,
    )

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
        if (isToggled && onArt != null) {
            onArt.setBounds(0, 0, width, height)
            onArt.draw(canvas)
        }
    }
}
