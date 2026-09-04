package app.siphondsp.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.roundToInt

/**
 * Wraps a fixed set of pre-built page views in a [ViewPager2] with a numbered page-toggle strip,
 * top-right -- for the DSP workspace screens that page their sections (Gains & Delay, Crossovers
 * & Tilt, Compressor, All-pass; the Parametric EQ screen builds its own pager and is deliberately
 * not routed through here). Tap a number to jump; horizontal swipe still works. Selected box's
 * border and number light green (the same neon as the ON/OFF switch), no fill -- unselected boxes
 * and numbers are greyed. Hand-rolled to match the rest of this dashboard chrome.
 *
 * [toggleContainer], when given, is where the toggle row docks instead of the pager's own root --
 * callers pass the activity's `R.id.dsp_page_toggle_slot` (see activity_parametric_eq.xml) so the
 * numbers sit in their own row above the tri-colour stripe, not inside the scrolling content.
 * null keeps the toggle pinned atop the pager itself, same as before.
 */
object DspPager {
    fun build(
        context: Context,
        pages: List<View>,
        onPageSelected: (Int) -> Unit = {},
        toggleContainer: ViewGroup? = null,
    ): View {
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

        if (pages.size > 1) {
            val boxes = pages.indices.map { index ->
                TextView(context).apply {
                    text = (index + 1).toString()
                    gravity = Gravity.CENTER
                    textSize = 11f
                    includeFontPadding = false
                    isSelected = index == 0
                    applyToggleBoxStyle(context, this)
                    setOnClickListener { viewPager.currentItem = index }
                }
            }
            val toggleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(dp(context, 6), dp(context, 4), dp(context, 8), dp(context, 4))
                boxes.forEach { box ->
                    addView(box, LinearLayout.LayoutParams(dp(context, 26), dp(context, 22)).apply {
                        marginStart = dp(context, 4)
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
            if (toggleContainer != null) {
                // The slot itself is wrap_content-width (see activity_parametric_eq.xml) so it
                // doesn't add to the toolbar's own fixed height -- match its width here too
                // (not MATCH_PARENT) so the two don't fight over sizing the row.
                toggleContainer.removeAllViews()
                toggleContainer.addView(toggleRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                root.addView(toggleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        root.addView(viewPager, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun applyToggleBoxStyle(context: Context, box: TextView) {
        val selected = box.isSelected
        box.background = GradientDrawable().apply {
            cornerRadius = dp(context, 5).toFloat()
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
