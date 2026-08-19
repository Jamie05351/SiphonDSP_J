package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import app.siphondsp.view.FilterResponseCurveView
import kotlin.math.roundToInt

/**
 * Dedicated Crossovers & Tilt screen using the shared BMW dashboard skin. Swipes between three
 * pages -- Crossovers, Tilt, and Mono Bass -- at the same section boundaries the single
 * scrolling panel used to have, so nothing about the content changed, only how it's paged.
 * The visible Low/Mid controls stay linked while mirroring into independent L/R runtime config.
 */
class CrossoverTiltFragment : Fragment() {
    private lateinit var container: FrameLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        this.container = FrameLayout(requireContext())
        rebuild()
        return this.container
    }

    override fun onResume() {
        super.onResume()
        // NativeBmwDspValues is loaded once into a local array and captured by closures below;
        // rebuild from disk on every resume so edits made elsewhere aren't silently overwritten
        // by this screen's stale snapshot the next time a slider here is touched.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        val values = NativeBmwDspValues.load(requireContext())
        val onChanged: (FloatArray) -> Unit = { updated ->
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

        fun page(build: CrossoverDashboardBuilder.() -> Unit): View {
            val pageRoot = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }
            CrossoverDashboardBuilder(requireContext(), pageRoot, values, onChanged).build()
            return NestedScrollView(requireContext()).apply {
                // Deliberately NOT isFillViewport=true: stretching a shorter-than-viewport page
                // (eg. Tilt's 3 rows, Mono Bass's 4) to fill the remaining height corrupts
                // LinearLayout's measure pass for addSegmentedSwitchRow's MATCH_PARENT control
                // slot and addSliderRow's weighted spacer, silently dropping every row after the
                // first slider that follows a switch (confirmed: their views ARE added to the
                // tree, they just never get measured/laid out). Only pages whose natural content
                // already exceeds the viewport (eg. Gain Structure's 5 sliders) were unaffected.
                addView(pageRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val crossoversPage = page {
            // Blank title/subtitle: the toolbar already shows "Crossovers & Tilt", so repeating
            // it here just wastes vertical space (same reasoning Gains & Delay's page uses).
            dashboardPanel("", null) {
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
                addCrossoverBandPair(
                    low = CrossoverDashboardBuilder.CrossoverBandSpec(
                        title = "LOW PASS",
                        freqIndex = NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ,
                        freqMin = 80f,
                        freqMax = 200f,
                        curveKind = FilterResponseCurveView.Kind.LOW_PASS,
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
                        curveKind = FilterResponseCurveView.Kind.HIGH_PASS,
                        freqMirrorIndices = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                        fixedSlopeLabel = "24 dB/Oct",
                    ),
                )
            }
        }

        val tiltPage = page {
            dashboardPanel(getString(R.string.bmw_dsp_tilt_section), "Spectral tilt around a pivot frequency") {
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
        }

        val monoBassPage = page {
            dashboardPanel(getString(R.string.bmw_dsp_mono_bass), "Sum low frequencies to mono below a crossover") {
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
        }

        container.removeAllViews()
        container.addView(
            DspPager.build(requireContext(), listOf(crossoversPage, tiltPage, monoBassPage)),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
