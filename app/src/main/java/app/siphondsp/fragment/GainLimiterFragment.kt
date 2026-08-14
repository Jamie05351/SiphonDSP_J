package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import kotlin.math.roundToInt

/**
 * Dedicated Gains & Delay workspace using the shared BMW dashboard skin. Swipes between two
 * pages -- Gain Structure and Delay & Polarity -- at the same section boundaries the single
 * scrolling panel used to have, so nothing about the content changed, only how it's paged.
 */
class GainLimiterFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val values = NativeBmwDspValues.load(requireContext())
        val onChanged: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        fun lowPair(field: Int) = intArrayOf(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, field),
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, field),
        )
        fun midPair(field: Int) = intArrayOf(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, field),
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, field),
        )

        fun page(build: CrossoverDashboardBuilder.() -> Unit): View {
            val pageRoot = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }
            CrossoverDashboardBuilder(requireContext(), pageRoot, values, onChanged).build()
            return NestedScrollView(requireContext()).apply {
                isFillViewport = true
                addView(pageRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val gainStructurePage = page {
            dashboardPanel(getString(R.string.bmw_dsp_gain_structure), "Per-band level") {
                addSliderRow(getString(R.string.bmw_dsp_headroom), NativeBmwDspValues.INDEX_HEADROOM, -12f, 0f, 1f, "dB")
                addSliderRow(getString(R.string.bmw_dsp_low_gain_l), NativeBmwDspValues.INDEX_LOW_GAIN_L, -6f, 0f, .1f, "dB")
                addSliderRow(getString(R.string.bmw_dsp_low_gain_r), NativeBmwDspValues.INDEX_LOW_GAIN_R, -6f, 0f, .1f, "dB")
                addSliderRow(getString(R.string.bmw_dsp_mid_gain_l), NativeBmwDspValues.INDEX_MID_GAIN_L, -6f, 0f, .1f, "dB")
                addSliderRow(getString(R.string.bmw_dsp_mid_gain_r), NativeBmwDspValues.INDEX_MID_GAIN_R, -6f, 0f, .1f, "dB")
            }
        }

        val delayPolarityPage = page {
            dashboardPanel(getString(R.string.bmw_dsp_delay_polarity), "Delay and polarity") {
                addDelayDiagramSection(
                    getString(R.string.bmw_dsp_invert_low_polarity), NativeBmwDspValues.INDEX_LOW_INVERT, lowPair(NativeBmwDspValues.FIELD_INVERT),
                    getString(R.string.bmw_dsp_invert_mid_polarity), NativeBmwDspValues.INDEX_MID_INVERT, midPair(NativeBmwDspValues.FIELD_INVERT),
                    getString(R.string.bmw_dsp_mid_delay_l), NativeBmwDspValues.INDEX_MID_DELAY_L,
                    getString(R.string.bmw_dsp_mid_delay_r), NativeBmwDspValues.INDEX_MID_DELAY_R,
                    getString(R.string.bmw_dsp_low_delay_l), NativeBmwDspValues.INDEX_LOW_DELAY_L,
                    getString(R.string.bmw_dsp_low_delay_r), NativeBmwDspValues.INDEX_LOW_DELAY_R,
                    0f, 2.8f, "ms",
                )
            }
        }

        return DspPager.build(requireContext(), listOf(gainStructurePage, delayPolarityPage))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
