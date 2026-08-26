package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import kotlin.math.roundToInt

/** Output all-pass workspace: the "Measurements / routing" toggles plus the two cascaded
 *  all-pass filter sections per output, rebuilt in the same glass-panel BMW dashboard style as
 *  every other workspace page (see CrossoverDashboardBuilder) rather than a plain
 *  PreferenceFragmentCompat list -- this used to be NativeBmwDspCardFragment's job, but that
 *  fragment is still needed as-is for its other home, the Settings page's inline card
 *  (DspFragment), which intentionally keeps the plain-preferences look shared by its neighbouring
 *  cards (Output Control, EQ, DDC, etc.), so it was left alone rather than restyled in place. */
class OutputAllPassFragment : Fragment() {
    private lateinit var container: FrameLayout

    // Which output's two all-pass sections are currently shown below -- purely a UI selection,
    // not itself a stored DSP value, so it lives here rather than in the values array (see
    // NativeBmwDspValues.INDEX_ALL_PASS's per-output layout). Resets to Low Left each time this
    // screen is (re)entered, same as any other unsaved UI-only selection in this app.
    private var selectedOutput = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        this.container = FrameLayout(requireContext())
        rebuild()
        return this.container
    }

    override fun onResume() {
        super.onResume()
        // Same reasoning as RoutingFragment: rebuild from disk on every resume so edits made
        // elsewhere (the Settings page's own inline NativeBmwDspCardFragment editing this same
        // data, a restored preset/profile/backup) aren't silently overwritten by a stale snapshot.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        val values = NativeBmwDspValues.load(requireContext())
        val onChanged: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }

        val pageRoot = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }

        CrossoverDashboardBuilder(requireContext(), pageRoot, values, onChanged).apply {
            sectionCard("Measurements / routing") {
                addSegmentedSwitchRow("LPF passthrough", null, NativeBmwDspValues.INDEX_LPF_PASS)
                addSegmentedSwitchRow("HPF passthrough", null, NativeBmwDspValues.INDEX_HPF_PASS)
                addSegmentedSwitchRow(
                    "Mute low band", null, NativeBmwDspValues.INDEX_LOW_MUTE,
                    mirrorIndices = intArrayOf(
                        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_MUTE),
                        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_MUTE),
                    ),
                )
                addSegmentedSwitchRow(
                    "Mute mid band", null, NativeBmwDspValues.INDEX_MID_MUTE,
                    mirrorIndices = intArrayOf(
                        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_MUTE),
                        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_MUTE),
                    ),
                )
                addDropdownRow("Channel isolation", NativeBmwDspValues.INDEX_CHANNEL_MUTE, listOf("Both" to 0f, "Mute L" to 1f, "Mute R" to 2f))
                addDropdownRow("Measurement mute", NativeBmwDspValues.INDEX_MEASUREMENT_MUTE, listOf("Off" to 0f, "Mute low" to 1f, "Mute mid" to 2f))
            }

            sectionCard(
                "Output all-pass",
                "Two cascaded all-pass filter sections per output, for phase/time alignment between bands",
            ) {
                addDropdownRow("Output channel", OUTPUT_CHANNEL_LABELS, { selectedOutput }) { picked ->
                    selectedOutput = picked
                    rebuild()
                }

                repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
                    val base = NativeBmwDspValues.INDEX_ALL_PASS +
                        (selectedOutput * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
                    sectionHeader("Section ${section + 1}")
                    addSegmentedSwitchRow("Enabled", null, base)
                    addDropdownRow("Type", base + 1, listOf("First order" to 1f, "Second order" to 2f))
                    addSliderRow("Frequency", base + 2, 20f, 20000f, 1f, "Hz")
                    addSliderRow("Q", base + 3, 0.1f, 30f, 0.01f, "")
                }
            }
        }

        container.removeAllViews()
        container.addView(
            NestedScrollView(requireContext()).apply {
                isFillViewport = true
                addView(pageRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private val OUTPUT_CHANNEL_LABELS = listOf("Low Left", "Low Right", "Mid Left", "Mid Right")
    }
}
