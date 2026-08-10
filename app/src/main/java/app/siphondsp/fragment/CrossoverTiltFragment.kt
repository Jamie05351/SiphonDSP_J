package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspSwipePager
import kotlin.math.roundToInt

/**
 * Crossovers & Tilt is split into internal horizontal pages so the head-unit layout never needs
 * a vertical scroll. The outer DSP navigation rail remains owned by the workspace activity.
 */
class CrossoverTiltFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val values = NativeBmwDspValues.load(requireContext())
        val host = FrameLayout(requireContext())
        val persist: (FloatArray) -> Unit = { updated ->
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

        val pager = DspSwipePager.create(host, listOf(
            {
                page { builder ->
                    builder.dashboardPanel("CROSSOVERS", "Swipe left for Tilt and Mono Bass") {
                        sectionHeader(getString(R.string.bmw_dsp_crossovers))
                        addSegmentedSwitchRow(
                            getString(R.string.bmw_dsp_subsonic_lr4), null,
                            NativeBmwDspValues.INDEX_SUBSONIC_ENABLED,
                            mirrorIndices = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_ENABLED),
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_subsonic_freq),
                            NativeBmwDspValues.INDEX_SUBSONIC_FREQ, 20f, 60f, 1f, "Hz",
                            mirrorIndices = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_FREQ),
                        )
                        addSegmentedSwitchRow(
                            getString(R.string.bmw_dsp_mute_low_band), null,
                            NativeBmwDspValues.INDEX_LOW_MUTE,
                            mirrorIndices = lowPair(NativeBmwDspValues.FIELD_MUTE),
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_low_lpf_freq),
                            NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f, 200f, 1f, "Hz",
                            mirrorIndices = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                        )
                        addSegmentedSwitchRow(
                            "Low topology", null, NativeBmwDspValues.INDEX_LOW_LR4,
                            offLabel = "BW3", onLabel = "LR4",
                            mirrorIndices = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_LR4),
                        )
                        addSegmentedSwitchRow(
                            getString(R.string.bmw_dsp_mute_mid_band), null,
                            NativeBmwDspValues.INDEX_MID_MUTE,
                            mirrorIndices = midPair(NativeBmwDspValues.FIELD_MUTE),
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_mid_hpf_freq),
                            NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 80f, 200f, 1f, "Hz",
                            mirrorIndices = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                        )
                    }
                }(values, persist)
            },
            {
                page { builder ->
                    builder.dashboardPanel("TILT", "Swipe right for Crossovers or left for Mono Bass") {
                        sectionHeader(getString(R.string.bmw_dsp_tilt_section))
                        addSegmentedSwitchRow(
                            getString(R.string.bmw_dsp_tilt_active), null,
                            NativeBmwDspValues.INDEX_TILT_ENABLED,
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_tilt_amount),
                            NativeBmwDspValues.INDEX_TILT_AMOUNT, -6f, 6f, .1f, "dB",
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_tilt_pivot),
                            NativeBmwDspValues.INDEX_TILT_FREQ, 200f, 2000f, 1f, "Hz",
                        )
                    }
                }(values, persist)
            },
            {
                page { builder ->
                    builder.dashboardPanel("MONO BASS", "Swipe right for Tilt") {
                        sectionHeader(getString(R.string.bmw_dsp_mono_bass))
                        addSegmentedSwitchRow(
                            getString(R.string.bmw_dsp_mono_bass), null,
                            NativeBmwDspValues.INDEX_MONO_BASS_ENABLED,
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_mono_bass_freq),
                            NativeBmwDspValues.INDEX_MONO_BASS_FREQ, 40f, 120f, 1f, "Hz",
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_mono_bass_blend),
                            NativeBmwDspValues.INDEX_MONO_BASS_BLEND, 0f, 100f, 1f, "%",
                        )
                        addSliderRow(
                            getString(R.string.bmw_dsp_mono_bass_makeup),
                            NativeBmwDspValues.INDEX_MONO_BASS_MAKEUP, -6f, 6f, .1f, "dB",
                        )
                    }
                }(values, persist)
            },
        ))
        host.addView(pager)
        return host
    }

    private fun page(
        build: (CrossoverDashboardBuilder) -> Unit,
    ): (FloatArray, (FloatArray) -> Unit) -> View = { values, persist ->
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
            build(CrossoverDashboardBuilder(requireContext(), this, values, persist))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
