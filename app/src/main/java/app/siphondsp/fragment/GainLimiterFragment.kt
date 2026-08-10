package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspSwipePager
import kotlin.math.roundToInt

/** Dedicated Gains & Delay workspace using horizontally swipable internal pages. */
class GainLimiterFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val host = container ?: LinearLayout(requireContext())
        val values = NativeBmwDspValues.load(requireContext())

        fun lowPair(field: Int) = intArrayOf(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, field),
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, field),
        )
        fun midPair(field: Int) = intArrayOf(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, field),
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, field),
        )
        fun page(build: CrossoverDashboardBuilder.() -> Unit): View {
            val root = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }
            val builder = CrossoverDashboardBuilder(requireContext(), root, values) { updated ->
                NativeBmwDspValues.save(requireContext(), updated)
                NativeBmwDspValues.broadcast(requireContext(), updated)
            }
            builder.dashboardPanel(getString(R.string.action_gain_limiter), null, build)
            return root
        }

        return DspSwipePager.create(
            host,
            listOf(
                {
                    page {
                        sectionHeader(getString(R.string.bmw_dsp_gain_structure))
                        addSliderRow(getString(R.string.bmw_dsp_headroom), NativeBmwDspValues.INDEX_HEADROOM, -12f, 0f, 1f, "dB")
                        addSliderRow(getString(R.string.bmw_dsp_low_gain_l), NativeBmwDspValues.INDEX_LOW_GAIN_L, -6f, 0f, .1f, "dB")
                        addSliderRow(getString(R.string.bmw_dsp_low_gain_r), NativeBmwDspValues.INDEX_LOW_GAIN_R, -6f, 0f, .1f, "dB")
                        addSliderRow(getString(R.string.bmw_dsp_mid_gain_l), NativeBmwDspValues.INDEX_MID_GAIN_L, -6f, 0f, .1f, "dB")
                        addSliderRow(getString(R.string.bmw_dsp_mid_gain_r), NativeBmwDspValues.INDEX_MID_GAIN_R, -6f, 0f, .1f, "dB")
                    }
                },
                {
                    page {
                        sectionHeader(getString(R.string.bmw_dsp_delay_polarity))
                        addSegmentedSwitchRow(
                            getString(R.string.bmw_dsp_invert_low_polarity),
                            null,
                            NativeBmwDspValues.INDEX_LOW_INVERT,
                            offLabel = "Normal",
                            onLabel = "Invert",
                            mirrorIndices = lowPair(NativeBmwDspValues.FIELD_INVERT),
                        )
                        addSegmentedSwitchRow(
                            getString(R.string.bmw_dsp_invert_mid_polarity),
                            null,
                            NativeBmwDspValues.INDEX_MID_INVERT,
                            offLabel = "Normal",
                            onLabel = "Invert",
                            mirrorIndices = midPair(NativeBmwDspValues.FIELD_INVERT),
                        )
                        addSliderRow(getString(R.string.bmw_dsp_mid_delay_l), NativeBmwDspValues.INDEX_MID_DELAY_L, 0f, 2.8f, .01f, "ms")
                        addSliderRow(getString(R.string.bmw_dsp_mid_delay_r), NativeBmwDspValues.INDEX_MID_DELAY_R, 0f, 2.8f, .01f, "ms")
                        addSliderRow(getString(R.string.bmw_dsp_low_delay_l), NativeBmwDspValues.INDEX_LOW_DELAY_L, 0f, 2.8f, .01f, "ms")
                        addSliderRow(getString(R.string.bmw_dsp_low_delay_r), NativeBmwDspValues.INDEX_LOW_DELAY_R, 0f, 2.8f, .01f, "ms")
                    }
                },
            ),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
