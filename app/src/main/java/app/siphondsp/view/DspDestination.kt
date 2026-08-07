package app.siphondsp.view

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.activity.RoutingActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/** The five main BMW DSP workspaces shown permanently in the landscape side rail. */
enum class DspDestination(
    @StringRes val labelRes: Int,
    @DrawableRes val icon: Int,
    val activityClass: KClass<out AppCompatActivity>,
) {
    ROUTING(R.string.action_routing, R.drawable.ic_twotone_route_24dp, RoutingActivity::class),
    GAINS_DELAY(R.string.action_gain_limiter, R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    COMPRESSOR(R.string.action_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class),
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
}

/**
 * BMW-style side rail. All destinations remain visible so the user can see where they are;
 * the current screen is highlighted and non-clickable. Labeled 48dp rows are deliberately
 * much easier to hit on the 1280x480 head unit than edge-mounted icon-only buttons.
 */
object DspCrossNavBar {
    fun populate(
        activity: FragmentActivity,
        container: LinearLayout,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.TOP

        val activeColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimary, Color.rgb(63, 174, 229))
        val activeText = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val inactiveText = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val outlinedButtonStyle = com.google.android.material.R.attr.materialButtonOutlinedStyle

        DspDestination.entries.forEach { destination ->
            val selected = destination == current
            container.addView(
                MaterialButton(activity, null, outlinedButtonStyle).apply {
                    icon = ContextCompat.getDrawable(activity, destination.icon)
                    text = activity.getString(destination.labelRes)
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = activity.dp(10)
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = activity.dp(7)
                    isAllCaps = false
                    contentDescription = text
                    if (selected) {
                        backgroundTintList = ColorStateList.valueOf(activeColor)
                        strokeColor = ColorStateList.valueOf(activeColor)
                        setTextColor(activeText)
                        iconTint = ColorStateList.valueOf(activeText)
                        isClickable = false
                    } else {
                        setTextColor(inactiveText)
                        setOnClickListener {
                            if (!canNavigate()) return@setOnClickListener
                            activity.startActivity(Intent(activity, destination.activityClass.java))
                            activity.finish()
                        }
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(46)).apply {
                    bottomMargin = activity.dp(7)
                },
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
