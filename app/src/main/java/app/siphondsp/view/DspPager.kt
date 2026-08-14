package app.siphondsp.view

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import app.siphondsp.R
import kotlin.math.roundToInt

/**
 * Wraps a fixed set of pre-built page views in a [ViewPager2] plus a small dot indicator below
 * it, for the DSP workspace screens that page their sections instead of scrolling through them
 * all at once (Gains & Delay, Crossovers & Tilt, Compressor). Hand-rolled rather than a
 * TabLayout, matching how the rest of this dashboard chrome is built directly in code.
 */
object DspPager {
    fun build(context: Context, pages: List<View>, onPageSelected: (Int) -> Unit = {}): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ViewPager2 requires every page to explicitly declare match_parent/match_parent --
        // programmatically-built pages (unlike XML-inflated ones) have no LayoutParams at all
        // until something sets them, which throws "Pages must fill the whole ViewPager2" the
        // instant the page is measured. Enforce it here so callers don't have to remember.
        pages.forEach { page ->
            page.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val viewPager = ViewPager2(context).apply {
            adapter = StaticPagerAdapter(pages)
        }
        root.addView(viewPager, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (pages.size > 1) {
            val dots = pages.indices.map { index ->
                View(context).apply {
                    isSelected = index == 0
                    background = ContextCompat.getDrawable(context, R.drawable.selector_dot_indicator)
                    setOnClickListener { viewPager.currentItem = index }
                }
            }
            val dotsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 6), 0, dp(context, 8))
                dots.forEach { dot ->
                    addView(dot, LinearLayout.LayoutParams(dp(context, 8), dp(context, 8)).apply {
                        marginStart = dp(context, 4)
                        marginEnd = dp(context, 4)
                    })
                }
            }
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    dots.forEachIndexed { index, dot -> dot.isSelected = index == position }
                    onPageSelected(position)
                }
            })
            root.addView(dotsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        return root
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
