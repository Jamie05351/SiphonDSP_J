package app.siphondsp.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.roundToInt

/**
 * Wraps a fixed set of pre-built page views in a [ViewPager2] with a small dot page indicator --
 * for the DSP workspace screens that page their sections (Gains & Delay, Crossovers & Tilt,
 * Compressor, All-pass; the Parametric EQ screen builds its own pager and is deliberately not
 * routed through here). Tap a dot to jump; horizontal swipe still works.
 *
 * The dots float directly over the bottom of the page (a [FrameLayout] overlay, not a footer that
 * reserves its own row) and paint no card/box behind them -- just the dot itself, same neon green
 * as the ON/OFF switch when selected, dim when not. Each dot's actual touch target is bigger than
 * its drawn circle (an [InsetDrawable] wrapping a small [GradientDrawable] oval), so tapping is
 * still comfortable without the visible footprint looking like a button. Previously a numbered
 * strip that lived first in the top toolbar, then a dedicated footer row -- both read as an
 * unwanted opaque bar sitting on top of the workspace chrome.
 */
object DspPager {
    fun build(
        context: Context,
        pages: List<View>,
        onPageSelected: (Int) -> Unit = {},
    ): View {
        // Each page is wrapped in a PagerChildSwipeGate: a quick horizontal flick that lands on a
        // slider row turns the page instead of nudging (and committing) the slider, while a slow,
        // deliberate horizontal drag still adjusts the slider. The gate also satisfies ViewPager2's
        // requirement that every page explicitly fill it -- programmatically-built pages have no
        // LayoutParams until something sets them, which otherwise throws "Pages must fill the whole
        // ViewPager2" the instant the page is measured.
        val gatedPages = pages.map { page -> PagerChildSwipeGate.wrap(context, page) }

        val viewPager = ViewPager2(context).apply {
            adapter = StaticPagerAdapter(gatedPages)
        }

        val root = FrameLayout(context)
        root.addView(viewPager, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        if (pages.size > 1) {
            val dots = pages.indices.map { index ->
                View(context).apply {
                    isSelected = index == 0
                    // Explicit -- a View is only clickable once a listener is attached, and the
                    // hit target should not depend on that ordering.
                    isClickable = true
                    isFocusable = true
                    background = dotDrawable(context, selected = isSelected)
                    setOnClickListener { viewPager.setCurrentItem(index, true) }
                }
            }
            val dotsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // Wrap-content, not full width: this row only owns the small area right around
                // the dots, so it can't shadow-block taps anywhere else on the page. isClickable
                // absorbs a tap landing in the narrow gap between two dots, so it doesn't fall
                // through to whatever's on the page underneath.
                isClickable = true
                dots.forEach { dot ->
                    addView(dot, LinearLayout.LayoutParams(dp(context, TOUCH_TARGET_DP), dp(context, TOUCH_TARGET_DP)))
                }
            }
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    dots.forEachIndexed { index, dot ->
                        val selected = index == position
                        dot.isSelected = selected
                        dot.background = dotDrawable(context, selected)
                    }
                    onPageSelected(position)
                }
            })
            root.addView(
                dotsRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = dp(context, 10) },
            )
        }

        return root
    }

    /** A plain filled circle, no stroke/card -- [InsetDrawable] centers it inside the larger
     *  [TOUCH_TARGET_DP] touch target the dot View itself uses. */
    private fun dotDrawable(context: Context, selected: Boolean): InsetDrawable {
        val diameterDp = if (selected) SELECTED_DOT_DP else DOT_DP
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (selected) BmwDashboardSkin.TOGGLE_ON_GREEN else UNSELECTED_DOT)
        }
        val inset = (dp(context, TOUCH_TARGET_DP) - dp(context, diameterDp)) / 2
        return InsetDrawable(oval, inset)
    }

    private const val TOUCH_TARGET_DP = 40
    private const val DOT_DP = 7
    private const val SELECTED_DOT_DP = 9

    // Translucent so it reads as a dim marker, not a filled UI element competing with the page.
    private val UNSELECTED_DOT = Color.argb(0x8A, 0xB2, 0xBB, 0xC6)

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
