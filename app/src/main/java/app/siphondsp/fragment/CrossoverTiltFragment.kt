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
import kotlin.math.roundToInt

/**
 * Dedicated Crossovers & Tilt screen.
 *
 * Uses one vertical dashboard column on all orientations so the controls read as one
 * continuous workspace instead of a busy two-pane layout.
 */
class CrossoverTiltFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val values = NativeBmwDspValues.load(requireContext())

        val page = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val builder = CrossoverDashboardBuilder(requireContext(), page, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }

        builder.sectionCard(getString(R.string.bmw_dsp_crossovers)) {
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_subsonic_bw2),
                null,
                NativeBmwDspValues.INDEX_SUBSONIC_ENABLED,
            )
            addSliderRow(
                getString(R.string.bmw_dsp_subsonic_freq),
                NativeBmwDspValues.INDEX_SUBSONIC_FREQ,
                20f, 60f, 1f, "Hz",
            )
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_mute_low_band),
                null,
                NativeBmwDspValues.INDEX_LOW_MUTE,
            )
            addSliderRow(
                getString(R.string.bmw_dsp_low_lpf_freq),
                NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ,
                80f, 200f, 1f, "Hz",
            )
            addSegmentedSwitchRow(
                "Low topology",
                null,
                NativeBmwDspValues.INDEX_LOW_LR4,
                offLabel = "BW3",
                onLabel = "LR4",
            )
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_mute_mid_band),
                null,
                NativeBmwDspValues.INDEX_MID_MUTE,
            )
            addSliderRow(
                getString(R.string.bmw_dsp_mid_hpf_freq),
                NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ,
                80f, 200f, 1f, "Hz",
            )
        }

        builder.sectionCard(getString(R.string.bmw_dsp_tilt_section)) {
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_tilt_active),
                null,
                NativeBmwDspValues.INDEX_TILT_ENABLED,
            )
            addSliderRow(
                getString(R.string.bmw_dsp_tilt_amount),
                NativeBmwDspValues.INDEX_TILT_AMOUNT,
                -6f, 6f, .1f, "dB",
            )
            addSliderRow(
                getString(R.string.bmw_dsp_tilt_pivot),
                NativeBmwDspValues.INDEX_TILT_FREQ,
                200f, 2000f, 1f, "Hz",
            )
        }

        builder.sectionCard(getString(R.string.bmw_dsp_mono_bass)) {
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_mono_bass),
                null,
                NativeBmwDspValues.INDEX_MONO_BASS_ENABLED,
            )
            addSliderRow(
                getString(R.string.bmw_dsp_mono_bass_freq),
                NativeBmwDspValues.INDEX_MONO_BASS_FREQ,
                40f, 120f, 1f, "Hz",
            )
            addSliderRow(
                getString(R.string.bmw_dsp_mono_bass_blend),
                NativeBmwDspValues.INDEX_MONO_BASS_BLEND,
                0f, 100f, 1f, "%",
            )
            addSliderRow(
                getString(R.string.bmw_dsp_mono_bass_makeup),
                NativeBmwDspValues.INDEX_MONO_BASS_MAKEUP,
                -6f, 6f, .1f, "dB",
            )
        }

        return NestedScrollView(requireContext()).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
