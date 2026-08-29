package app.siphondsp.view

import android.content.Intent
import android.view.Gravity
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
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
    val showInPrimaryNav: Boolean = true,
) {
    // Declaration order is display order (used directly by DspCrossNavBar.populate()) --
    // matches the main tile grid's PEQ, Gains, Xovers, Compressor, All-pass order
    // (fragment_dsp_page_shortcuts.xml) rather than an arbitrary/functional grouping, so the
    // sidebar doesn't present a different sequence than the page the user navigated in from.
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    // 5th tile: the per-output all-pass screen (MODE_ALLPASS, OutputAllPassFragment). Was the
    // routing-matrix editor historically; that screen is gone (the matrix itself still runs in
    // the native chain). The Measurements / routing rows live in the Settings page's inline
    // card (NativeBmwDspCardFragment).
    ALLPASS(R.string.action_allpass, R.string.action_allpass, R.drawable.ic_twotone_route_24dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ALLPASS),
}

object DspCrossNavBar {
    // The whole 5-tile bar -- each tile's own icon, plus which single tile reads as "selected"
    // (a cyan glow border, with that tile's icon recolored to match) -- is baked entirely into
    // one of 5 source images (drawable-nodpi/sidebar_bar_*.png), picked by [current]. populate()
    // draws no icon/label of its own; it only lays an invisible click-target/focus-ring row over
    // each tile's measured bounds.
    //
    // The art is 5 free-standing rounded tiles on a transparent canvas -- no wrapping panel, no
    // background fill -- rendered from the jdsp_*_selected.svg vector sources (panel + black
    // backdrop stripped) at 1200px wide and cropped to the tile column, giving one shared
    // 591x2909 canvas with byte-identical tile positions across all 5. Shown FIT_CENTER (aspect
    // preserved, never stretched to the column). [rowWeights] are the 11 relative bands measured
    // off that canvas -- top margin, tile1, gap1, tile2, gap2, tile3, gap3, tile4, gap4, tile5,
    // bottom margin -- applied to the art's *displayed* height (post-FIT_CENTER), not the column
    // height, so the click targets track the tiles regardless of the column's size on device.
    private class BarArt(@DrawableRes val res: Int, val rowWeights: IntArray)

    private val ROW_WEIGHTS = intArrayOf(46, 534, 40, 534, 40, 534, 40, 534, 40, 534, 35)

    private fun barArt(current: DspDestination): BarArt = when (current) {
        DspDestination.PARAMETRIC_EQ -> BarArt(R.drawable.sidebar_bar_peq, ROW_WEIGHTS)
        DspDestination.GAINS_DELAY -> BarArt(R.drawable.sidebar_bar_gains, ROW_WEIGHTS)
        DspDestination.CROSSOVER_TILT -> BarArt(R.drawable.sidebar_bar_xover, ROW_WEIGHTS)
        DspDestination.COMPRESSOR -> BarArt(R.drawable.sidebar_bar_compressor, ROW_WEIGHTS)
        DspDestination.ALLPASS -> BarArt(R.drawable.sidebar_bar_allpass, ROW_WEIGHTS)
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
        container.background = null
        val art = barArt(current)

        // The art and the click-target rows share one FrameLayout so the rows can sit *on top of*
        // the art rather than stacked after it. The art is FIT_CENTER (aspect-preserving) so it is
        // never stretched to the column -- it scales to whichever of width/height runs out first
        // and centres in the rest.
        val stack = FrameLayout(activity)
        container.addView(
            stack,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT),
        )

        val artView = ImageView(activity).apply {
            setImageDrawable(ContextCompat.getDrawable(activity, art.res))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stack.addView(
            artView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        // Sized/positioned to the art's displayed rect in the post() below, not left MATCH_PARENT,
        // so a tap in the column's empty margin above/below/beside the art doesn't hit a tile row.
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(rows, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }
        val weights = art.rowWeights
        val children = mutableListOf<WeightedChild>()

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
        // once the art has a real displayed rect (post, not before), each child's height is set
        // explicitly from the *cumulative* weight fraction rounded to a pixel boundary -- the
        // running sum is always exact, so no drift can accumulate regardless of position.
        val totalWeight = weights.sum()
        stack.post {
            val drawable = artView.drawable ?: return@post
            val iw = drawable.intrinsicWidth
            val ih = drawable.intrinsicHeight
            if (iw <= 0 || ih <= 0 || artView.width <= 0 || artView.height <= 0) return@post
            // FIT_CENTER: uniform scale to the tighter of the two axes, then centre in the rest.
            val scale = minOf(artView.width.toFloat() / iw, artView.height.toFloat() / ih)
            val dispW = (iw * scale).toInt()
            val dispH = (ih * scale).toInt()
            if (dispH <= 0) return@post
            (rows.layoutParams as FrameLayout.LayoutParams).apply {
                width = dispW
                height = dispH
                gravity = Gravity.CENTER
            }
            var cumulative = 0
            var previousBoundary = 0
            children.forEach { child ->
                cumulative += weights[child.weightIndex]
                val boundary = (dispH.toLong() * cumulative / totalWeight).toInt()
                (child.view.layoutParams as LinearLayout.LayoutParams).height = boundary - previousBoundary
                previousBoundary = boundary
            }
            rows.requestLayout()
        }

        container.visibility = View.VISIBLE
    }
}
