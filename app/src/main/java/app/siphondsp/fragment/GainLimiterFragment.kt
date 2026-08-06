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
import app.siphondsp.view.BmwControlBuilder
import kotlin.math.roundToInt

/** Dedicated Gains & Delay screen, including output routing. */
class GainLimiterFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val scroll = NestedScrollView(requireContext()).apply { isFillViewport = true }
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val values = NativeBmwDspValues.load(requireContext())
        val builder = BmwControlBuilder(requireContext(), root, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        builder.sectionCard(getString(R.string.bmw_dsp_gain_structure)) {
            addSliderRow(getString(R.string.bmw_dsp_headroom), NativeBmwDspValues.INDEX_HEADROOM, -12f, 0f, 1f, "dB")
            addSliderRow(getString(R.string.bmw_dsp_low_gain_l), NativeBmwDspValues.INDEX_LOW_GAIN_L, -6f, 0f, .1f, "dB")
            addSliderRow(getString(R.string.bmw_dsp_low_gain_r), NativeBmwDspValues.INDEX_LOW_GAIN_R, -6f, 0f, .1f, "dB")
            addSliderRow(getString(R.string.bmw_dsp_mid_gain_l), NativeBmwDspValues.INDEX_MID_GAIN_L, -6f, 0f, .1f, "dB")
            addSliderRow(getString(R.string.bmw_dsp_mid_gain_r), NativeBmwDspValues.INDEX_MID_GAIN_R, -6f, 0f, .1f, "dB")
        }
        builder.sectionCard(getString(R.string.bmw_dsp_delay_polarity)) {
            addSwitchRow(getString(R.string.bmw_dsp_invert_low_polarity), null, NativeBmwDspValues.INDEX_LOW_INVERT)
            addSwitchRow(getString(R.string.bmw_dsp_invert_mid_polarity), null, NativeBmwDspValues.INDEX_MID_INVERT)
            addSliderRow(getString(R.string.bmw_dsp_mid_delay_l), NativeBmwDspValues.INDEX_MID_DELAY_L, 0f, 2.8f, .01f, "ms")
            addSliderRow(getString(R.string.bmw_dsp_mid_delay_r), NativeBmwDspValues.INDEX_MID_DELAY_R, 0f, 2.8f, .01f, "ms")
            addSliderRow(getString(R.string.bmw_dsp_low_delay_l), NativeBmwDspValues.INDEX_LOW_DELAY_L, 0f, 2.8f, .01f, "ms")
            addSliderRow(getString(R.string.bmw_dsp_low_delay_r), NativeBmwDspValues.INDEX_LOW_DELAY_R, 0f, 2.8f, .01f, "ms")
        }
        builder.sectionCard("Output routing") {
            addRoutingSliderRow("Low Left", "Front Left", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT)
            addRoutingSliderRow("Low Left", "Front Right", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT)
            addRoutingSliderRow("Low Right", "Front Left", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT)
            addRoutingSliderRow("Low Right", "Front Right", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT)
            addRoutingSliderRow("Mid Left", "Front Left", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_LEFT)
            addRoutingSliderRow("Mid Left", "Front Right", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_RIGHT)
            addRoutingSliderRow("Mid Right", "Front Left", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_LEFT)
            addRoutingSliderRow("Mid Right", "Front Right", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT)
            addActionRow(
                "Reset to stereo defaults",
                "Unity same-side routing; cross-channel contributions zero",
            ) {
                NativeBmwDspValues.resetRoutingToDefaults(values)
                NativeBmwDspValues.save(requireContext(), values)
                NativeBmwDspValues.broadcast(requireContext(), values)
                parentFragmentManager.beginTransaction()
                    .detach(this@GainLimiterFragment)
                    .attach(this@GainLimiterFragment)
                    .commit()
            }
        }

        return scroll
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
