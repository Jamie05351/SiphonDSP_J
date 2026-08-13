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
    ROUTING(R.string.action_routing, R.drawable.ic_tile_routing, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ROUTING),
    GAINS_DELAY(R.string.action_gain_limiter, R.drawable.ic_tile_gains_delay, GainLimiterActivity::class),
    COMPRESSOR(R.string.action_compressor, R.drawable.ic_tile_compressor, NativeBmwCompressorActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.drawable.ic_tile_crossover, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.drawable.ic_tile_eq, ParametricEqualizerActivity::class),
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
        container.setPadding(0, 0, 0, 0)
        container.clipToPadding = false
        container.clipChildren = false
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
                iconSize = activity.dp(60)
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                insetTop = 0
                insetBottom = 0
                insetLeft = 0
                insetRight = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                cornerRadius = activity.dp(6)
                text = if (compact) "" else activity.getString(destination.labelRes)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = 0
                isAllCaps = false

                // Keep each tile's supplied artwork completely unchanged. Selection is indicated
                // only by the outline: the active tile gets the BMW cyan/blue border while
                // inactive tiles keep a white border. Do not tint the bitmap artwork itself.
                backgroundTintList = ColorStateList.valueOf(Color.argb(110, 12, 16, 21))
                strokeWidth = activity.dp(if (selected) 2 else 1)
                strokeColor = ColorStateList.valueOf(
                    if (selected) BmwDashboardSkin.LIGHT_BLUE_BRIGHT else Color.WHITE
                )
                setTextColor(if (selected) BmwDashboardSkin.LIGHT_BLUE_BRIGHT else Color.rgb(211, 217, 223))
                iconTint = null

                if (selected) {
                    isClickable = false
                } else {
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
                    LinearLayout.LayoutParams(activity.dp(60), activity.dp(60))
                } else {
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(60))
                },
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
