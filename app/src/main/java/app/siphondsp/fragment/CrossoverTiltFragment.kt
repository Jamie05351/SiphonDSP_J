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

/** Dedicated "Crossovers & Tilt" screen: BMW subsonic/crossover filters and the post-sum
 *  tonality tilt, moved out of the Parametric EQ graph's cramped side panel so their numeric
 *  fields get full-screen room, same as Gains and Delay/Polarity. The live graph on the
 *  Parametric EQ screen still renders these curves directly from the same underlying
 *  NativeBmwDspValues array; this page is only for editing the numbers. */
class CrossoverTiltFragment : Fragment() {
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
        builder.sectionCard("Crossovers") {
            addSwitchRow("Subsonic BW2", "12 dB/oct Butterworth protection", NativeBmwDspValues.INDEX_SUBSONIC_ENABLED)
            addSliderRow("Subsonic freq", NativeBmwDspValues.INDEX_SUBSONIC_FREQ, 20f, 60f, 1f, "Hz")
            addSwitchRow("Mute low band", null, NativeBmwDspValues.INDEX_LOW_MUTE)
            addSliderRow("Low LPF freq", NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f, 200f, 1f, "Hz")
            addSwitchRow("LR4 topology", "Off uses BW3 (3rd-order) instead of LR4 (4th-order)", NativeBmwDspValues.INDEX_LOW_LR4)
            addSwitchRow("Mute mid band", null, NativeBmwDspValues.INDEX_MID_MUTE)
            addSliderRow("Mid HPF freq", NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 80f, 200f, 1f, "Hz")
        }
        builder.sectionCard("Post-sum tonality tilt") {
            addSwitchRow("Tilt active", "Broad tonal balance after the bands rejoin", NativeBmwDspValues.INDEX_TILT_ENABLED)
            addSliderRow("Tilt amount", NativeBmwDspValues.INDEX_TILT_AMOUNT, -6f, 6f, .1f, "dB")
            addSliderRow("Tilt pivot", NativeBmwDspValues.INDEX_TILT_FREQ, 200f, 2000f, 1f, "Hz")
        }

        return scroll
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
