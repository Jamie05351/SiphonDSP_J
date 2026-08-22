package app.siphondsp.view

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
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
    // The whole 5-tile bar -- glass tile shapes, each tile's own icon, gloss sweep, AND which
    // single tile reads as "selected" (lit cyan glow) -- is baked entirely into one of 5
    // whole-bar source images, picked by [current]. populate() draws no icon/label of its own; it
    // only lays an invisible click-target/focus-ring row over each tile's measured bounds.
    //
    // The 5 tiles are NOT an even one-fifth-each split of the image -- there's a top margin, a
    // bottom margin, and small gaps between tiles. [rowWeights] holds the *measured* pixel
    // boundaries (sampled by locating each destination's own lit tile in its source image, since
    // that tile's glow is the one clearly-detectable edge per image; the 5 renders share the same
    // template layout closely enough that these boundaries are reused for all 5 destinations) as
    // 11 relative weights: top margin, tile1, gap1, tile2, gap2, tile3, gap3, tile4, gap4, tile5,
    // bottom margin. LinearLayout weights are proportions, not absolute values, so passing the raw
    // measured pixel heights as weights reproduces the exact source proportions regardless of the
    // sidebar's actual on-screen height on any given device.
    private class BarArt(@DrawableRes val res: Int, val rowWeights: IntArray)

    private val ROW_WEIGHTS = intArrayOf(38, 142, 9, 140, 13, 131, 15, 126, 16, 126, 144)

    private fun barArt(current: DspDestination): BarArt = when (current) {
        DspDestination.PARAMETRIC_EQ -> BarArt(R.drawable.sidebar_bar_peq, ROW_WEIGHTS)
        DspDestination.GAINS_DELAY -> BarArt(R.drawable.sidebar_bar_gains, ROW_WEIGHTS)
        DspDestination.CROSSOVER_TILT -> BarArt(R.drawable.sidebar_bar_xover, ROW_WEIGHTS)
        DspDestination.COMPRESSOR -> BarArt(R.drawable.sidebar_bar_compressor, ROW_WEIGHTS)
        DspDestination.ROUTING -> BarArt(R.drawable.sidebar_bar_routing, ROW_WEIGHTS)
    }

    private class WeightedChild(val view: View, val weightIndex: Int)

    fun populate(
        activity: FragmentActivity,
        container: LinearLayout,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        val art = barArt(current)
        // A plain resource-referenced bitmap's default gravity is FILL, so this stretches to
        // cover the container exactly like the old per-tile ImageView(FIT_XY) did -- View.background
        // always paints behind every child added below regardless of container type.
        container.background = ContextCompat.getDrawable(activity, art.res)

        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }
        val weights = art.rowWeights
        val children = mutableListOf<WeightedChild>()

        fun addSpacer(weightIndex: Int) {
            val spacer = View(activity)
            container.addView(spacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            children += WeightedChild(spacer, weightIndex)
        }

        addSpacer(0) // top margin, before the first tile

        destinations.forEachIndexed { index, destination ->
            val selected = destination == current

            // Plain FrameLayout, not MaterialCardView: MaterialCardView kept painting a solid
            // dark rectangle (~rgb(20,25,32), a Material3 default surface/state-layer tint) behind
            // its content even with cardElevation=0 and setCardBackgroundColor(TRANSPARENT) --
            // visible as a hard-edged box over the bar art underneath. A plain ViewGroup has no
            // such built-in surface painting, so the bar art shows through untouched.
            val row = FrameLayout(activity).apply {
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                if (selected) {
                    isClickable = false
                    // Already carries its own selected-state look via the bar art itself, so it's
                    // left out of D-pad/rotary traversal rather than showing a redundant focus ring.
                    isFocusable = false
                } else {
                    isClickable = true
                    isFocusable = true
                    val rippleAttr = android.util.TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true)
                    foreground = ContextCompat.getDrawable(activity, rippleAttr.resourceId)
                    // Transparent except when this row has Android focus (see
                    // BmwDashboardSkin.sidebarTileFocusRingDrawable) -- makes a hardware rotary
                    // controller's current position visible, since it moves focus without any
                    // touch/ripple feedback ever firing.
                    background = BmwDashboardSkin.sidebarTileFocusRingDrawable(activity)
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setOnClickListener {
                        if (!canNavigate()) return@setOnClickListener
                        val intent = Intent(activity, destination.activityClass.java)
                        destination.workspaceMode?.let { intent.putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
                        activity.startActivity(intent)
                        activity.finish()
                    }
                }
            }

            // No icon/label overlay: the bar art's own tile already carries its icon (and, for
            // the current destination, its lit glow) baked in -- this row exists purely as the
            // invisible click target / focus-ring host over that tile, per its measured bounds.
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            // Tile weight is at an odd index (1, 3, 5, 7, 9); the gap that follows it (2, 4, 6, 8)
            // sits between this tile and the next, so it's only added while a next tile remains.
            children += WeightedChild(row, index * 2 + 1)
            if (index < destinations.lastIndex) addSpacer(index * 2 + 2)
        }

        addSpacer(10) // bottom margin, after the last tile

        // Every child was added above with height=0 (a LinearLayout.LayoutParams default), not a
        // weight -- weights are wrong here because LinearLayout rounds each weighted child's
        // share to a whole pixel *independently*, and those small per-child rounding errors
        // compound down 11 children (top margin, 5 tiles, 4 gaps, bottom margin), so icon/label
        // content drifted visibly higher within its tile the further down the bar it sat. Instead,
        // once the container has a real measured height (post, not before), each child's height is
        // set explicitly from the *cumulative* weight fraction rounded to a pixel boundary -- the
        // running sum is always exact, so no drift can accumulate regardless of position.
        val totalWeight = weights.sum()
        container.post {
            val totalHeight = container.height
            if (totalHeight <= 0) return@post
            var cumulative = 0
            var previousBoundary = 0
            children.forEach { child ->
                cumulative += weights[child.weightIndex]
                val boundary = (totalHeight.toLong() * cumulative / totalWeight).toInt()
                (child.view.layoutParams as LinearLayout.LayoutParams).height = boundary - previousBoundary
                previousBoundary = boundary
            }
            container.requestLayout()
        }

        container.visibility = View.VISIBLE
    }
}
