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
 * Dedicated "Gain & Limiter" screen: BMW per-band/channel gains only.
 * The limiter and post gain already live in Output Control -- no duplicate
 * surface for either here (post gain L/R dropped in favor of that single
 * existing global control).
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

        return scroll
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
