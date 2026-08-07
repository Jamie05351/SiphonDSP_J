package app.siphondsp.fragment

import android.content.res.Configuration
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
 * This branch intentionally prototypes the BMW dashboard treatment on this screen only.
 * The underlying NativeBmwDspValues indices, persistence and broadcasts are unchanged.
 */
class CrossoverTiltFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val values = NativeBmwDspValues.load(requireContext())

        val page = LinearLayout(requireContext()).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }

        val leftColumn = createColumn()
        val rightColumn = createColumn()

        val leftBuilder = CrossoverDashboardBuilder(requireContext(), leftColumn, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        leftBuilder.sectionCard(
            getString(R.string.bmw_dsp_crossovers),
            "Filter routing and crossover hand-off",
        ) {
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_subsonic_bw2),
                getString(R.string.bmw_dsp_subsonic_bw2_subtitle),
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
                getString(R.string.bmw_dsp_lr4_topology),
                getString(R.string.bmw_dsp_lr4_topology_subtitle),
                NativeBmwDspValues.INDEX_LOW_LR4,
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

        val rightBuilder = CrossoverDashboardBuilder(requireContext(), rightColumn, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        rightBuilder.sectionCard(
            getString(R.string.bmw_dsp_tilt_section),
            "Post-sum tonal balance",
        ) {
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_tilt_active),
                getString(R.string.bmw_dsp_tilt_active_subtitle),
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
        rightBuilder.sectionCard(
            getString(R.string.bmw_dsp_mono_bass),
            "Low-frequency stereo management",
        ) {
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_mono_bass),
                getString(R.string.bmw_dsp_mono_bass_subtitle),
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

        if (landscape) {
            page.addView(leftColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.08f).apply {
                marginEnd = dp(6)
            })
            page.addView(rightColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .92f).apply {
                marginStart = dp(6)
            })
        } else {
            page.addView(leftColumn)
            page.addView(rightColumn)
        }

        return NestedScrollView(requireContext()).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun createColumn() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
