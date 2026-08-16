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
    @StringRes val sidebarLabelRes: Int,
    @DrawableRes val icon: Int,
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
    val showInPrimaryNav: Boolean = true,
) {
    ROUTING(R.string.action_routing, R.string.sidebar_label_routing, R.drawable.ic_twotone_route_24dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ROUTING),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
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

        val outlinedButtonStyle = com.google.android.material.R.attr.materialIconButtonOutlinedStyle
        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }

        destinations.forEachIndexed { index, destination ->
            // Small fixed gap before every tile except the first -- tiles themselves are weighted
            // to fill the column's full height (see the addView below), so this is just breathing
            // room between them, not a flexible spacer soaking up leftover space the way a huge gap
            // between small fixed-height tiles used to.
            if (index > 0) {
                container.addView(View(activity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(8)))
            }

            val selected = destination == current
            val button = MaterialButton(activity, null, outlinedButtonStyle).apply {
                icon = ContextCompat.getDrawable(activity, destination.icon)
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                insetTop = 0
                insetBottom = 0
                cornerRadius = activity.dp(6)
                text = activity.getString(destination.sidebarLabelRes)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 12f
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = activity.dp(8)
                iconSize = activity.dp(30)
                isAllCaps = false

                if (selected) {
                    // Darker brushed-metal fill with a blurred glow ring around the border, and a
                    // BMW-blue illuminated icon/label to match -- BlurMaskFilter has no
                    // hardware-accelerated path, so the button needs a software layer for the
                    // glow to actually render.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    background = BmwDashboardSkin.litTileDrawable(activity)
                    // MaterialButton tints ANY background drawable (custom ones included) with
                    // whatever backgroundTintList it has -- here inherited from the outlined-button
                    // style -- via SRC_IN, which was silently replacing the custom drawable's own
                    // colors with a flat dark tint. Clearing it lets it render unmodified.
                    backgroundTintList = null
                    setTextColor(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
                    iconTint = ColorStateList.valueOf(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
                    isClickable = false
                } else {
                    // Lighter brushed-metal fill (vs. the selected tile's darker one) so the
                    // selected tile visibly pops; white border/icon keep unselected tiles clearly
                    // visible instead of getting lost against the metal texture.
                    background = BmwDashboardSkin.metalTileDrawable(activity, darkened = false)
                    backgroundTintList = null
                    strokeWidth = activity.dp(1)
                    strokeColor = ColorStateList.valueOf(Color.WHITE)
                    setTextColor(Color.WHITE)
                    iconTint = ColorStateList.valueOf(Color.WHITE)
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
                // Weighted (0 height + weight) rather than a fixed dp height, so the tiles
                // themselves expand to fill the column's available height -- the gaps between
                // them come only from the small fixed spacer above, not from the tiles staying
                // small while empty space collects around them.
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
