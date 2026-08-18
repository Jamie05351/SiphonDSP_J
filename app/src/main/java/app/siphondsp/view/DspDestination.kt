package app.siphondsp.view

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    // Declaration order is display order (used directly by DspCrossNavBar.populate()) --
    // matches the main tile grid's PEQ, Gains, Xovers, Compressor, Routing order
    // (fragment_dsp_page_shortcuts.xml) rather than an arbitrary/functional grouping, so the
    // sidebar doesn't present a different sequence than the page the user navigated in from.
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    ROUTING(R.string.action_routing, R.string.sidebar_label_routing, R.drawable.ic_twotone_route_24dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ROUTING),
}

object DspCrossNavBar {
    // ~1.463:1 width:height at the sidebar's 120dp column width, measured directly off the
    // sidebar_tile_selected/unselected art (490x335 shared crop) -- these are noticeably
    // wider-than-tall pills, not tall rectangles.
    private const val TILE_HEIGHT_DP = 82

    fun populate(
        activity: FragmentActivity,
        container: LinearLayout,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        // CENTER, not CENTER_HORIZONTAL: the tile stack's total height is usually shorter than
        // the sidebar column, so top-aligning (the old behavior) left the whole group pinned high
        // with empty space below it. Centering vertically anchors the middle tile near the
        // column's own center instead, so the group doesn't read as too high or too low.
        container.gravity = Gravity.CENTER
        // No background here: the panel behind the whole sidebar is painted once on the shared
        // dsp_sidebar parent by DspWorkspaceActivity.setUpWorkspaceSidebarActions().

        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }

        destinations.forEachIndexed { index, destination ->
            // Small fixed gap before every tile except the first.
            if (index > 0) {
                container.addView(View(activity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(2)))
            }

            val selected = destination == current

            // The whole tile body (rounded shape, gloss sweep, and both accent bars) is baked
            // into one of two source images now -- sidebar_tile_selected/unselected -- rather
            // than composed from a programmatic gradient plus separately-driven accent-bar
            // drawables, so it matches the reference art directly instead of approximating it.
            //
            // Plain FrameLayout, not MaterialCardView: MaterialCardView kept painting a solid
            // dark rectangle (~rgb(20,25,32), a Material3 default surface/state-layer tint) behind
            // its content even with cardElevation=0 and setCardBackgroundColor(TRANSPARENT) --
            // visible as a hard-edged box around the tile's actual (smaller, padded) artwork. A
            // plain ViewGroup has no such built-in surface painting, so nothing shows through the
            // art's transparent margin except the sidebar's own background, as intended.
            val card = FrameLayout(activity).apply {
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                if (selected) {
                    isClickable = false
                } else {
                    isClickable = true
                    val rippleAttr = android.util.TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true)
                    foreground = ContextCompat.getDrawable(activity, rippleAttr.resourceId)
                    setOnClickListener {
                        if (!canNavigate()) return@setOnClickListener
                        val intent = Intent(activity, destination.activityClass.java)
                        destination.workspaceMode?.let { intent.putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
                        activity.startActivity(intent)
                        activity.finish()
                    }
                }
            }

            val tileArt = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageResource(if (selected) R.drawable.sidebar_tile_selected else R.drawable.sidebar_tile_unselected)
            }
            card.addView(tileArt, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Both accent bars are already part of tileArt -- this row only needs to clear
                // their painted width so the icon/label don't sit on top of them.
                setPadding(activity.dp(12), 0, activity.dp(3), 0)
            }

            val icon = ImageView(activity).apply {
                setImageDrawable(ContextCompat.getDrawable(activity, destination.icon))
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            content.addView(
                icon,
                LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)).apply { leftMargin = activity.dp(8) },
            )

            val label = TextView(activity).apply {
                text = activity.getString(destination.sidebarLabelRes)
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 11f
                // Wraps instead of ellipsizing: at this column width even the pre-shortened
                // labels ("Comp", "Xover") don't reliably fit on one line once the icon and
                // accent bar are accounted for, and a mid-word "Co..." reads as broken UI.
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = activity.dp(6)
                    rightMargin = activity.dp(4)
                },
            )

            card.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            container.addView(
                card,
                // Fixed height, not weighted/stretched to fill the column: the reference tiles
                // are noticeably wider than tall (~1.463:1 at this 120dp column width, measured
                // directly off the reference art), not the taller-than-wide shape stretching to
                // fill produced. Any leftover column space splits evenly above/below the group
                // instead, via container.gravity = CENTER above.
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(TILE_HEIGHT_DP)),
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
