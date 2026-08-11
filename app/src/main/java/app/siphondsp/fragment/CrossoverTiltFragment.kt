package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import kotlin.math.roundToInt

/**
 * Dedicated Crossovers & Tilt screen using one continuous BMW-style dashboard panel.
 * The visible Low/Mid controls stay linked while mirroring into independent L/R runtime config.
 */
class CrossoverTiltFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = NestedScrollView(requireContext()).apply {
        isFillViewport = true

        val values = NativeBmwDspValues.load(requireContext())
        val page = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }

        val builder = CrossoverDashboardBuilder(requireContext(), page, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }

        fun lowPair(field: Int) = intArrayOf(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, field),
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, field),
        )
        fun midPair(field: Int) = intArrayOf(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, field),
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, field),
        )

        builder.dashboardPanel(
            title = getString(R.string.action_crossover_tilt),
            subtitle = "Configure crossover, tilt and mono-bass behaviour",
        ) {
            sectionHeader(getString(R.string.bmw_dsp_crossovers))
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_subsonic_lr4),
                null,
                NativeBmwDspValues.INDEX_SUBSONIC_ENABLED,
                mirrorIndices = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_ENABLED),
            )
            addSliderRow(
                getString(R.string.bmw_dsp_subsonic_freq),
                NativeBmwDspValues.INDEX_SUBSONIC_FREQ,
                20f, 60f, 1f, "Hz",
                mirrorIndices = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_FREQ),
            )
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_mute_low_band),
                null,
                NativeBmwDspValues.INDEX_LOW_MUTE,
                mirrorIndices = lowPair(NativeBmwDspValues.FIELD_MUTE),
            )
            addSegmentedSwitchRow(
                getString(R.string.bmw_dsp_mute_mid_band),
                null,
                NativeBmwDspValues.INDEX_MID_MUTE,
                mirrorIndices = midPair(NativeBmwDspValues.FIELD_MUTE),
            )
            addCrossoverBandPair(
                low = CrossoverDashboardBuilder.CrossoverBandSpec(
                    title = "LOW PASS",
                    freqIndex = NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ,
                    freqMin = 80f,
                    freqMax = 200f,
                    curveDrawableRes = R.drawable.ic_crossover_lowpass_curve,
                    freqMirrorIndices = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                    // BW3 (3rd-order Butterworth) = 18dB/oct, LR4 (4th-order Linkwitz-Riley) = 24dB/oct.
                    slopeIndex = NativeBmwDspValues.INDEX_LOW_LR4,
                    slopeMirrorIndices = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_LR4),
                    slopeOptions = listOf("18 dB/Oct" to 0f, "24 dB/Oct" to 1f),
                ),
                // The mid-band highpass has no topology switch at the native layer -- it's a
                // fixed-order filter -- so its slope reads as a fixed label, not a real dropdown.
                high = CrossoverDashboardBuilder.CrossoverBandSpec(
                    title = "HIGH PASS",
                    freqIndex = NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ,
                    freqMin = 80f,
                    freqMax = 200f,
                    curveDrawableRes = R.drawable.ic_crossover_highpass_curve,
                    freqMirrorIndices = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                    fixedSlopeLabel = "24 dB/Oct",
                ),
            )

            sectionHeader(getString(R.string.bmw_dsp_tilt_section))
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

            sectionHeader(getString(R.string.bmw_dsp_mono_bass))
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

        addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
