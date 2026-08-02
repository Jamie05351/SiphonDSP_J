package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwControlBuilder
import kotlin.math.roundToInt

/**
 * Dedicated "Gains & Delay" screen: BMW per-band/channel gains, plus delay and polarity
 * (merged in from the former standalone Delay & Polarity destination to free up a bottom-nav
 * slot for Crossovers & Tilt). The limiter and post gain already live in Output Control -- no
 * duplicate surface for either here (post gain L/R dropped in favor of that single existing
 * global control).
 */
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
        builder.sectionCard("Gain structure") {
            addSliderRow("Headroom", NativeBmwDspValues.INDEX_HEADROOM, -12f, 0f, 1f, "dB")
            addSliderRow("Low gain L", NativeBmwDspValues.INDEX_LOW_GAIN_L, -6f, 0f, .1f, "dB")
            addSliderRow("Low gain R", NativeBmwDspValues.INDEX_LOW_GAIN_R, -6f, 0f, .1f, "dB")
            addSliderRow("Mid gain L", NativeBmwDspValues.INDEX_MID_GAIN_L, -6f, 0f, .1f, "dB")
            addSliderRow("Mid gain R", NativeBmwDspValues.INDEX_MID_GAIN_R, -6f, 0f, .1f, "dB")
        }
        builder.sectionCard("Delay / polarity") {
            addSwitchRow("Invert low polarity", null, NativeBmwDspValues.INDEX_LOW_INVERT)
            addSwitchRow("Invert mid polarity", null, NativeBmwDspValues.INDEX_MID_INVERT)
            addSliderRow("Mid delay L", NativeBmwDspValues.INDEX_MID_DELAY_L, 0f, 2.8f, .01f, "ms")
            addSliderRow("Mid delay R", NativeBmwDspValues.INDEX_MID_DELAY_R, 0f, 2.8f, .01f, "ms")
            addSliderRow("Low delay L", NativeBmwDspValues.INDEX_LOW_DELAY_L, 0f, 2.8f, .01f, "ms")
            addSliderRow("Low delay R", NativeBmwDspValues.INDEX_LOW_DELAY_R, 0f, 2.8f, .01f, "ms")
        }

        return scroll
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
