package app.siphondsp.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.roundToInt

/**
 * Wraps a fixed set of pre-built page views in a [ViewPager2] with a numbered page-toggle strip
 * docked as a footer below the pages -- for the DSP workspace screens that page their sections
 * (Gains & Delay, Crossovers & Tilt, Compressor, All-pass; the Parametric EQ screen builds its own
 * pager and is deliberately not routed through here). Tap a number to jump; horizontal swipe still
 * works. Selected box's border and number light green (the same neon as the ON/OFF switch), no
 * fill -- unselected boxes and numbers are greyed. Hand-rolled to match the rest of this dashboard
 * chrome.
 *
 * The strip used to dock in the top toolbar (`R.id.dsp_page_toggle_slot`, since removed) -- top
 * edge of a 1280x480 head-unit screen turned out to be an awkward reach and a fiddly tap target
 * with real fingers. A bottom footer, same width as the content column, is both easier to reach
 * and gives the boxes more room than the 36dp toolbar band ever allowed.
 */
object DspPager {
    fun build(
        context: Context,
        pages: List<View>,
        onPageSelected: (Int) -> Unit = {},
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

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

        root.addView(viewPager, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (pages.size > 1) {
            val boxes = pages.indices.map { index ->
                TextView(context).apply {
                    text = (index + 1).toString()
                    gravity = Gravity.CENTER
                    textSize = 15f
                    includeFontPadding = false
                    isSelected = index == 0
                    // Explicit -- a TextView is only clickable once a listener is attached, and the
                    // hit target should not depend on that ordering.
                    isClickable = true
                    isFocusable = true
                    applyToggleBoxStyle(context, this)
                    setOnClickListener { viewPager.setCurrentItem(index, true) }
                }
            }
            val toggleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
                // Own the whole footer so a tap that lands in the gap between two numbers is
                // absorbed here rather than falling through to the page behind it.
                isClickable = true
                boxes.forEach { box ->
                    // A real footer row (not squeezed into the old 36dp toolbar band), so the
                    // boxes clear Android's 48dp minimum touch target -- was 46x30 with an 18dp
                    // gap when this lived in the toolbar.
                    addView(box, LinearLayout.LayoutParams(dp(context, 52), dp(context, 48)).apply {
                        marginStart = dp(context, 20)
                    })
                }
            }
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    boxes.forEachIndexed { index, box ->
                        box.isSelected = index == position
                        applyToggleBoxStyle(context, box)
                    }
                    onPageSelected(position)
                }
            })
            root.addView(toggleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        return root
    }

    private fun applyToggleBoxStyle(context: Context, box: TextView) {
        val selected = box.isSelected
        box.background = GradientDrawable().apply {
            cornerRadius = dp(context, 6).toFloat()
            // No fill on the selected box any more -- just its border and number light green;
            // the workspace background shows through, same as unselected (which was never filled
            // solid either).
            setColor(if (selected) Color.TRANSPARENT else UNSELECTED_FILL)
            setStroke(
                dp(context, 1),
                if (selected) BmwDashboardSkin.TOGGLE_ON_GREEN else UNSELECTED_STROKE,
            )
        }
        box.setTextColor(if (selected) BmwDashboardSkin.TOGGLE_ON_GREEN else UNSELECTED_TEXT)
        box.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    // 50% opacity so the photo background reads through the box -- glassier than a near-opaque fill.
    private val UNSELECTED_FILL = Color.argb(0x80, 0x10, 0x12, 0x16)
    private val UNSELECTED_STROKE = Color.argb(0x66, 0x8A, 0x93, 0x9E)
    private val UNSELECTED_TEXT = Color.argb(0xB0, 0x9A, 0xA1, 0xAB)

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
