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

/** Dedicated "Delay & Polarity" screen: BMW low/mid channel delay and polarity invert. */
class DelayPolarityFragment : Fragment() {
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
