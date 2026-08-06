package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwControlBuilder
import app.siphondsp.view.BmwControlBuilder.ChoiceOption
import kotlin.math.roundToInt

/** Inline, expandable controls for the native BMW processor: measurements/routing switches,
 *  the output routing matrix, and per-channel all-pass sections. Embedded at R.id.card_bmw_dsp,
 *  already inside a MaterialCardView from fragment_dsp.xml (see DspFragment.kt) -- rendered with
 *  BmwControlBuilder.flatSection so it doesn't nest a second card inside that one.
 *
 *  Formerly a PreferenceFragmentCompat over dsp_native_bmw_preferences.xml with a large bridging
 *  layer translating SharedPreferences-backed widgets to/from the NativeBmwDspValues FloatArray.
 *  Rebuilt directly on BmwControlBuilder (same as GainLimiterFragment/CrossoverTiltFragment) so
 *  it shares their switch/slider styling and edits [values] directly with no bridging needed. */
class NativeBmwDspCardFragment : Fragment() {
    private var selectedAllPassChannel = 0
    private lateinit var allPassContainer: LinearLayout
    private lateinit var routingContainer: LinearLayout
    private lateinit var values: FloatArray

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
        }

        values = NativeBmwDspValues.load(requireContext())
        val builder = BmwControlBuilder(requireContext(), root, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }

        builder.flatSection("Measurements / routing") {
            addSwitchRow("LPF passthrough", null, NativeBmwDspValues.INDEX_LPF_PASS)
            addSwitchRow("HPF passthrough", null, NativeBmwDspValues.INDEX_HPF_PASS)
            addChoiceRow(
                "Channel isolation", null, NativeBmwDspValues.INDEX_CHANNEL_MUTE,
                listOf(ChoiceOption("Both", 0f), ChoiceOption("Mute L", 1f), ChoiceOption("Mute R", 2f)),
            )
            addChoiceRow(
                "Measurement mute", null, NativeBmwDspValues.INDEX_MEASUREMENT_MUTE,
                listOf(ChoiceOption("Off", 0f), ChoiceOption("Mute low", 1f), ChoiceOption("Mute mid", 2f)),
            )
        }

        builder.flatSection("Output routing") {
            routingContainer = subContainer { renderRouting() }
            addActionRow(
                "Reset to stereo defaults",
                "Unity same-side routing; cross-channel contributions zero",
            ) {
                NativeBmwDspValues.resetRoutingToDefaults(values)
                NativeBmwDspValues.save(requireContext(), values)
                NativeBmwDspValues.broadcast(requireContext(), values)
                rebuildInto(routingContainer) { renderRouting() }
            }
        }

        builder.flatSection("Output all-pass") {
            addChoiceRow(
                "Output channel", null,
                listOf(
                    ChoiceOption("Low Left", 0f), ChoiceOption("Low Right", 1f),
                    ChoiceOption("Mid Left", 2f), ChoiceOption("Mid Right", 3f),
                ),
                current = { selectedAllPassChannel.toFloat() },
            ) { chosen ->
                selectedAllPassChannel = chosen.toInt()
                rebuildInto(allPassContainer) { renderAllPass() }
            }
            allPassContainer = subContainer { renderAllPass() }
        }

        return root
    }

    private fun BmwControlBuilder.renderRouting() {
        addSliderRow("Low Left · Front Left", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
        addSliderRow("Low Left · Front Right", NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
        addSliderRow("Low Right · Front Left", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
        addSliderRow("Low Right · Front Right", NativeBmwDspValues.INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
        addSliderRow("Mid Left · Front Left", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
        addSliderRow("Mid Left · Front Right", NativeBmwDspValues.INDEX_ROUTE_MID_LEFT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
        addSliderRow("Mid Right · Front Left", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_LEFT, -2f, 2f, .01f, "%", 100f)
        addSliderRow("Mid Right · Front Right", NativeBmwDspValues.INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT, -2f, 2f, .01f, "%", 100f)
    }

    private fun BmwControlBuilder.renderAllPass() {
        val channelBase = NativeBmwDspValues.INDEX_ALL_PASS +
            selectedAllPassChannel * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
        for (section in 0 until NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) {
            val base = channelBase + section * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
            addSwitchRow("Section ${section + 1}", null, base)
            addChoiceRow(
                "Section ${section + 1} type", null, base + 1,
                listOf(ChoiceOption("First order", 1f), ChoiceOption("Second order", 2f)),
            )
            addSliderRow("Section ${section + 1} frequency (Hz)", base + 2, 20f, 20000f, 1f, "Hz")
            addSliderRow("Section ${section + 1} Q × 100", base + 3, .10f, 30f, .01f, "", 100f)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        fun newInstance() = NativeBmwDspCardFragment()
    }
}
