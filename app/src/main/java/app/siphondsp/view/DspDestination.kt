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
import kotlin.math.roundToInt
import kotlin.reflect.KClass

enum class DspDestination(
    @StringRes val labelRes: Int,
    @DrawableRes val icon: Int,
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
    val showInPrimaryNav: Boolean = true,
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
        container.gravity = Gravity.CENTER_HORIZONTAL
        // No background here: the panel behind the whole sidebar is painted once on the shared
        // dsp_sidebar parent by DspWorkspaceActivity.setUpWorkspaceSidebarActions().

        val compact = container.layoutParams?.width?.let { it in 1..activity.dp(72) } == true
        val outlinedButtonStyle = com.google.android.material.R.attr.materialIconButtonOutlinedStyle
        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }

        destinations.forEachIndexed { index, destination ->
            // Flexible spacer before every tile except the first, so the tiles distribute evenly
            // across the sidebar's full height (dsp_cross_nav is match_parent) instead of
            // clustering at the top with empty space left below the last one.
            if (index > 0) {
                container.addView(View(activity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }

            val selected = destination == current
            val button = MaterialButton(activity, null, outlinedButtonStyle).apply {
                icon = ContextCompat.getDrawable(activity, destination.icon)
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                insetTop = 0
                insetBottom = 0
                cornerRadius = activity.dp(6)
                text = if (compact) "" else activity.getString(destination.labelRes)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = if (compact) 0 else activity.dp(10)
                isAllCaps = false
                if (compact) setPadding(0, 0, 0, 0)

                if (selected) {
                    // Custom background (fill + brighter border + bottom glow) replaces
                    // Material's own stroke/corner shape handling entirely, so the glow's
                    // BlurMaskFilter actually renders -- that requires a software layer, since
                    // BlurMaskFilter has no effect on hardware-accelerated views.
                    background = BmwDashboardSkin.litTileDrawable(activity)
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setTextColor(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
                    iconTint = ColorStateList.valueOf(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
                    isClickable = false
                } else {
                    backgroundTintList = ColorStateList.valueOf(Color.argb(110, 12, 16, 21))
                    strokeWidth = activity.dp(1)
                    strokeColor = ColorStateList.valueOf(Color.rgb(62, 70, 79))
                    setTextColor(Color.rgb(211, 217, 223))
                    iconTint = ColorStateList.valueOf(Color.rgb(194, 202, 210))
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
                    LinearLayout.LayoutParams(activity.dp(46), activity.dp(46))
                } else {
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(46))
                },
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
