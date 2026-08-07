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
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt
import kotlin.reflect.KClass

enum class DspDestination(
    @StringRes val labelRes: Int,
    @DrawableRes val icon: Int,
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
) {
    ROUTING(R.string.action_routing, R.drawable.ic_twotone_route_24dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ROUTING),
    GAINS_DELAY(R.string.action_gain_limiter, R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    COMPRESSOR(R.string.action_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
}

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

        val compact = container.layoutParams?.width?.let { it in 1..activity.dp(72) } == true
        val activeColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimary, Color.rgb(63, 174, 229))
        val activeText = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val inactiveText = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val outlinedButtonStyle = if (compact)
            com.google.android.material.R.attr.materialIconButtonOutlinedStyle
        else
            com.google.android.material.R.attr.materialButtonOutlinedStyle

        val destinations = if (compact) DspDestination.entries.filter { it != current } else DspDestination.entries
        destinations.forEach { destination ->
            val selected = destination == current
            val button = MaterialButton(activity, null, outlinedButtonStyle).apply {
                icon = ContextCompat.getDrawable(activity, destination.icon)
                contentDescription = activity.getString(destination.labelRes)
                insetTop = 0
                insetBottom = 0
                cornerRadius = activity.dp(7)
                if (compact) {
                    text = ""
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    setPadding(0, 0, 0, 0)
                } else {
                    text = activity.getString(destination.labelRes)
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = activity.dp(10)
                    isAllCaps = false
                }
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
                        val intent = Intent(activity, destination.activityClass.java)
                        destination.workspaceMode?.let { intent.putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
                        activity.startActivity(intent)
                        activity.finish()
                    }
                }
            }
            container.addView(
                button,
                if (compact) {
                    LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
                        bottomMargin = activity.dp(8)
                    }
                } else {
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(46)).apply {
                        bottomMargin = activity.dp(7)
                    }
                },
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
