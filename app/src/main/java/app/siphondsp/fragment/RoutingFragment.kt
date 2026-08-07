package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import kotlin.math.roundToInt

class RoutingFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val values = NativeBmwDspValues.load(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        val builder = CrossoverDashboardBuilder(requireContext(), root, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        builder.sectionCard("Output routing") {
            addSliderRow("Low Left → Front Left", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
            addSliderRow("Low Left → Front Right", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
            addSliderRow("Low Right → Front Left", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
            addSliderRow("Low Right → Front Right", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
            addSliderRow("Mid Left → Front Left", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
            addSliderRow("Mid Left → Front Right", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
            addSliderRow("Mid Right → Front Left", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
            addSliderRow("Mid Right → Front Right", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
        }
        return NestedScrollView(requireContext()).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
