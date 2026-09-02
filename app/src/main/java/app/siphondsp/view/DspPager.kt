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
 * Wraps a fixed set of pre-built page views in a [ViewPager2] with a numbered page-toggle strip
 * pinned top-right, directly under the tri-colour stripe -- for the DSP workspace screens that
 * page their sections (Gains & Delay, Crossovers & Tilt, Compressor, All-pass; the Parametric EQ
 * screen builds its own pager and is deliberately not routed through here). Tap a number to jump;
 * horizontal swipe still works. Selected box lights green (the same neon as the ON/OFF switch),
 * its number stays white; unselected boxes and numbers are greyed. Hand-rolled to match the rest
 * of this dashboard chrome.
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
            root.addView(toggleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        root.addView(viewPager, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun applyToggleBoxStyle(context: Context, box: TextView) {
        val selected = box.isSelected
        box.background = GradientDrawable().apply {
            cornerRadius = dp(context, 5).toFloat()
            setColor(if (selected) TOGGLE_ON_GREEN_FILL else UNSELECTED_FILL)
            setStroke(
                dp(context, 1),
                if (selected) BmwDashboardSkin.TOGGLE_ON_GREEN else UNSELECTED_STROKE,
            )
        }
        box.setTextColor(if (selected) Color.WHITE else UNSELECTED_TEXT)
        // White numeral on neon green needs a hair of shadow to stay legible.
        if (selected) box.setShadowLayer(dp(context, 2).toFloat(), 0f, 0f, Color.argb(0xAA, 0, 0, 0))
        else box.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    private val TOGGLE_ON_GREEN_FILL = Color.argb(0xF0, 0x39, 0xFF, 0x14)
    private val UNSELECTED_FILL = Color.argb(0x66, 0x10, 0x12, 0x16)
    private val UNSELECTED_STROKE = Color.argb(0x66, 0x8A, 0x93, 0x9E)
    private val UNSELECTED_TEXT = Color.argb(0xB0, 0x9A, 0xA1, 0xAB)

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
