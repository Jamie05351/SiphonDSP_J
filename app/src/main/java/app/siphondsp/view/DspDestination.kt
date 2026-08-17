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
import com.google.android.material.card.MaterialCardView
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
    // Sampled from the brushed-metal reference photos' outer border stroke.
    private val SIDEBAR_BORDER_COLOR = Color.rgb(0x90, 0xA3, 0xAC)

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

            // Brushed-metal panel + a left accent bar that lights up solid blue when selected,
            // replacing the old flat-tinted-button look. Icon and label stay white in both
            // states now -- only the accent bar's image changes -- since that's the whole
            // selected/unselected signal in the reference photos this was designed from.
            val card = MaterialCardView(activity).apply {
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                radius = activity.dp(6).toFloat()
                strokeWidth = activity.dp(1)
                setStrokeColor(ColorStateList.valueOf(SIDEBAR_BORDER_COLOR))
                cardElevation = 0f
                // The default Material3 outlined-card content padding was eating into the
                // already-tight 120dp column, leaving barely any room for the label before it
                // had to ellipsize -- this tile draws all the way to its own stroke instead.
                setContentPadding(0, 0, 0, 0)
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

            val texture = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.sidebar_tile_texture)
            }
            card.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            // The texture's bright horizontal sheen band washes out the white icon/label
            // wherever it lands -- a flat dark scrim underneath the content keeps contrast
            // consistent across the whole tile instead of only where the sheen happens to be dim.
            val scrim = View(activity).apply {
                setBackgroundColor(Color.argb(140, 0, 0, 0))
            }
            card.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val accentBar = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageResource(if (selected) R.drawable.sidebar_accent_bar_on else R.drawable.sidebar_accent_bar_off)
            }
            content.addView(accentBar, LinearLayout.LayoutParams(activity.dp(12), LinearLayout.LayoutParams.MATCH_PARENT))

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
                textSize = 13f
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
