package app.siphondsp.view

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
    @DrawableRes val backdrop: Int,
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
    val showInPrimaryNav: Boolean = true,
) {
    // Declaration order is display order (used directly by DspCrossNavBar.populate()) --
    // matches the main tile grid's PEQ, Gains, Xovers, Compressor, All-pass order
    // (fragment_dsp_page_shortcuts.xml) rather than an arbitrary/functional grouping, so the
    // sidebar doesn't present a different sequence than the page the user navigated in from.
    //
    // `backdrop` is a full-screen, per-destination piece of art (rail housing, tile buttons, lit
    // selection glow and background all baked in together) -- hand-authored per destination, not
    // generated. Replaces the single shared dsp_workspace_field.png + live-drawn
    // WorkspaceSidebarView overlay (both removed).
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, R.drawable.dsp_workspace_backdrop_peq, ParametricEqualizerActivity::class),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, R.drawable.dsp_workspace_backdrop_gains, GainLimiterActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, R.drawable.dsp_workspace_backdrop_xover, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, R.drawable.dsp_workspace_backdrop_compressor, NativeBmwCompressorActivity::class),
    // 5th tile: the per-output all-pass screen (MODE_ALLPASS, OutputAllPassFragment). Was the
    // routing-matrix editor historically; that screen is gone (the matrix itself still runs in
    // the native chain). The Measurements / routing rows live in the Settings page's inline
    // card (NativeBmwDspCardFragment).
    ALLPASS(R.string.action_allpass, R.string.action_allpass, R.drawable.ic_twotone_route_24dp, R.drawable.dsp_workspace_backdrop_allpass, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ALLPASS),
}

object DspCrossNavBar {
    // The rail -- rounded glass panel, 5 inlaid tiles, each tile's icon, and the lit selection
    // glow on the current screen's tile -- is baked into each destination's own full-screen
    // backdrop image (DspDestination.backdrop, set on R.id.dsp_workspace_backdrop below). A
    // different backdrop swaps in per destination; nothing is drawn live over it.
    //
    // populate() sets that backdrop image and lays an invisible click-target/focus-ring row over
    // each tile's measured bounds within dsp_sidebar's reserved column (see that column's own
    // comment in activity_parametric_eq.xml).

    // Rail geometry: rail top margin 16, five 80-unit tiles with 12-unit inter-tile gaps, 16
    // bottom margin -- 16 + 5*80 + 4*12 + 16 = 480. populate() applies these as cumulative
    // fractions of dsp_sidebar's real measured height (so only the ratios matter), matching the
    // tile layout baked into each destination's backdrop art. Tune both together if the
    // click-targets drift off the tiles.
    private val ROW_WEIGHTS = intArrayOf(16, 80, 12, 80, 12, 80, 12, 80, 12, 80, 16)

    private class WeightedChild(val view: View, val weightIndex: Int)

    fun populate(
        activity: FragmentActivity,
        container: LinearLayout,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.background = null

        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(
            rows,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT),
        )

        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }
        val weights = ROW_WEIGHTS
        val children = mutableListOf<WeightedChild>()

        // The rail visual: swap in this destination's own backdrop (housing + tiles + lit glow
        // all baked in), rather than moving a live-drawn selection over a shared image.
        activity.findViewById<ImageView>(R.id.dsp_workspace_backdrop)?.setImageResource(current.backdrop)

        fun addSpacer(weightIndex: Int) {
            val spacer = View(activity)
            rows.addView(spacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            children += WeightedChild(spacer, weightIndex)
        }

        addSpacer(0) // top margin, before the first tile

        destinations.forEachIndexed { index, destination ->
            val selected = destination == current

            // Plain FrameLayout, not MaterialCardView: MaterialCardView kept painting a solid
            // dark rectangle (~rgb(20,25,32), a Material3 default surface/state-layer tint) behind
            // its content even with cardElevation=0 and setCardBackgroundColor(TRANSPARENT) --
            // visible as a hard-edged box over the backdrop underneath. A plain ViewGroup has no
            // such built-in surface painting, so the backdrop shows through untouched.
            val row = FrameLayout(activity).apply {
                contentDescription = activity.getString(destination.labelRes)
                tooltipText = activity.getString(destination.labelRes)
                if (selected) {
                    isClickable = false
                    // Already carries its own selected-state look via the backdrop itself, so it's
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
                        // Rail navigation is a clean cut, not a transition: picking another DSP
                        // menu from the sidebar should just swap the screen. The platform default
                        // slides the new activity in from the right (and the old one out left),
                        // which reads like a page swipe -- and swiping is reserved for paging
                        // *within* a menu. FLAG_ACTIVITY_NO_ANIMATION suppresses both the enter
                        // and exit animation for this launch; overridePendingTransition(0, 0)
                        // covers the finishing activity on API levels where the flag alone leaves
                        // a close animation.
                        val intent = Intent(activity, destination.activityClass.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        destination.workspaceMode?.let { intent.putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
                        activity.startActivity(intent)
                        @Suppress("DEPRECATION")
                        activity.overridePendingTransition(0, 0)
                        activity.finish()
                        @Suppress("DEPRECATION")
                        activity.overridePendingTransition(0, 0)
                    }
                }
            }

            // No icon/label overlay: the backdrop's own tile already carries its icon (and, for
            // the current destination, its lit glow) baked in -- this row exists purely as the
            // invisible click target / focus-ring host over that tile, per its measured bounds.
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            // Tile weight is at an odd index (1, 3, 5, 7, 9); the gap that follows it (2, 4, 6, 8)
            // sits between this tile and the next, so it's only added while a next tile remains.
            children += WeightedChild(row, index * 2 + 1)
            if (index < destinations.lastIndex) addSpacer(index * 2 + 2)
        }

        addSpacer(10) // bottom margin, after the last tile

        // Every child was added above with height=0 (a LinearLayout.LayoutParams default), not a
        // weight -- weights are wrong here because LinearLayout rounds each weighted child's
        // share to a whole pixel *independently*, and those small per-child rounding errors
        // compound down 11 children (top margin, 5 tiles, 4 gaps, bottom margin), so the click
        // targets drift out from under their tiles the further down the bar they sit. Instead,
        // once the container has a real measured height (post, not before), each child's height
        // is set explicitly from the *cumulative* weight fraction rounded to a pixel boundary --
        // the running sum is always exact, so no drift can accumulate regardless of position.
        // ROW_WEIGHTS sums to exactly 417 -- this container's own height on the mdpi device the
        // backdrop art was authored for -- so there the boundaries land pixel-exact on the art's
        // own tile geometry; on any other content-area height it scales proportionally instead.
        val totalWeight = weights.sum()
        container.post {
            val h = container.height
            if (h <= 0) return@post
            var cumulative = 0
            var previousBoundary = 0
            children.forEach { child ->
                cumulative += weights[child.weightIndex]
                val boundary = (h.toLong() * cumulative / totalWeight).toInt()
                (child.view.layoutParams as LinearLayout.LayoutParams).height = boundary - previousBoundary
                previousBoundary = boundary
            }
            rows.requestLayout()
        }

        container.visibility = View.VISIBLE
    }
}
