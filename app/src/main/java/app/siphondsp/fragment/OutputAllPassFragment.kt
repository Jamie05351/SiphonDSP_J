package app.siphondsp.fragment

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspSwipePager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlin.math.roundToInt

/**
 * Output all-pass editor for the dedicated DSP workspace.
 *
 * Each exact output gets its own horizontally swipable page. This replaces the old output-picker
 * plus vertically scrolling preference list while continuing to edit the same NativeBmwDspValues
 * all-pass block used by the native processor.
 */
class OutputAllPassFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val values = NativeBmwDspValues.load(requireContext())
        val host = FrameLayout(requireContext())
        val persist: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        val names = arrayOf("LOW LEFT", "LOW RIGHT", "MID LEFT", "MID RIGHT")

        val pages = names.indices.map { output ->
            {
                val root = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(2), dp(4), dp(4))
                }
                val builder = CrossoverDashboardBuilder(requireContext(), root, values, persist)
                val previous = if (output > 0) names[output - 1] else null
                val next = if (output < names.lastIndex) names[output + 1] else null
                val hint = buildString {
                    if (previous != null) append("Swipe right for $previous")
                    if (previous != null && next != null) append(" · ")
                    if (next != null) append("Swipe left for $next")
                }
                builder.dashboardPanel("OUTPUT ALL-PASS · ${names[output]}", hint) {
                    repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
                        val base = NativeBmwDspValues.INDEX_ALL_PASS +
                            (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) *
                            NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
                        sectionHeader("SECTION ${section + 1}")
                        addSegmentedSwitchRow("Active", null, base)
                        addCustomView(orderRow(values, base + 1, persist), topMarginDp = 2, bottomMarginDp = 2)
                        addSliderRow("Frequency", base + 2, 20f, 20_000f, 1f, "Hz")
                        // The old preference stored Q × 100 over 10..3000; this is the exact
                        // equivalent 0.10..30.00 range in the float array consumed by native DSP.
                        addSliderRow("Q", base + 3, .10f, 30f, .01f, "")
                    }
                }
                root
            }
        }
        host.addView(DspSwipePager.create(host, pages))
        return host
    }

    private fun orderRow(
        values: FloatArray,
        index: Int,
        persist: (FloatArray) -> Unit,
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(42)
        }
        row.addView(TextView(requireContext()).apply {
            text = "Order"
            textSize = 13f
            setTextColor(Color.rgb(231, 235, 239))
        }, LinearLayout.LayoutParams(dp(205), ViewGroup.LayoutParams.WRAP_CONTENT))

        val group = MaterialButtonToggleGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        fun button(label: String) = MaterialButton(
            requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            id = View.generateViewId()
            text = label
            textSize = 10.5f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(3)
            strokeWidth = dp(1)
            backgroundTintList = ColorStateList(states, intArrayOf(Color.rgb(28, 70, 107), Color.rgb(20, 23, 28)))
            strokeColor = ColorStateList(states, intArrayOf(BmwDashboardSkin.LIGHT_BLUE, Color.rgb(67, 73, 82)))
            setTextColor(ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(225, 230, 235))))
        }
        val first = button("1st order")
        val second = button("2nd order")
        group.addView(first, LinearLayout.LayoutParams(dp(86), dp(34)))
        group.addView(second, LinearLayout.LayoutParams(dp(86), dp(34)))
        group.check(if (values[index].toInt() == 1) first.id else second.id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            values[index] = if (checkedId == first.id) 1f else 2f
            persist(values)
        }
        row.addView(group, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })
        return row
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
