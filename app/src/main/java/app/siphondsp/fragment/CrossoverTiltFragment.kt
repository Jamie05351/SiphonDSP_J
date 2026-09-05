package app.siphondsp.fragment

import android.graphics.Color
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
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import kotlin.math.roundToInt

/**
 * Dedicated Crossovers & Tilt screen using the shared BMW dashboard skin. Swipes between three
 * pages -- Crossovers, Tilt, and Mono Bass -- at the same section boundaries the single
 * scrolling panel used to have, so nothing about the content changed, only how it's paged.
 * The visible Low/Mid controls stay linked while mirroring into independent L/R runtime config.
 * (A fourth Pultec-style bass EQ page briefly lived here between Tilt and Mono Bass; it was
 * unused and has been removed -- its config slots have since been reclaimed, see
 * NativeBmwDspProcessor.h's kConfigSize comment.)
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
                setPadding(dp(4), dp(0), dp(4), dp(8))
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
                // Single page-title-weight header (18f, no divider) covering the whole page --
                // subsonic, both crossovers and the mid LPF -- so the mid LPF slider clears the
                // fold without scrolling on the head unit. The old "Subsonic Protection" /
                // "Crossovers" split cost a header's worth of vertical space for little gain.
                sectionHeader("Crossovers", accentColor = Color.WHITE, textSize = 18f, showDivider = false)
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
                // Both crossovers are LR4-only now (the 18dB/oct option was removed -- the
                // mid-band highpass was already secretly LR4-only at the native layer, so this
                // just makes the low-band one match instead of exposing a slope choice for it).
                addSliderRow(
                    "Lowpass freq (LR4)", NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ,
                    80f, 200f, 1f, "Hz",
                    mirrorIndices = lowPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                    accentColor = BmwDashboardSkin.LIGHT_BLUE,
                    sliderAccentColor = BmwDashboardSkin.SLIDER_LOW_BAND_COLOR,
                )
                addSliderRow(
                    "Highpass freq (LR4)", NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ,
                    80f, 200f, 1f, "Hz",
                    mirrorIndices = midPair(NativeBmwDspValues.FIELD_CROSSOVER_FREQ),
                    accentColor = BmwDashboardSkin.MID_BAND_YELLOW,
                    sliderAccentColor = BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
                )
                // Independent mid-band lowpass -- a second, decoupled corner above the highpass
                // (native slots 141/142, applied only to the Mid outputs). Rolls the mid off
                // below a passive tweeter to tame overlap comb-filtering. Same switch+slider
                // pattern as the Subsonic block above.
                addSegmentedSwitchRow(
                    "Mid lowpass (LR4)",
                    null,
                    NativeBmwDspValues.INDEX_MID_LPF_ENABLED,
                )
                addSliderRow(
                    "Mid lowpass freq", NativeBmwDspValues.INDEX_MID_LPF_FREQ,
                    1500f, 8000f, 50f, "Hz",
                    accentColor = BmwDashboardSkin.MID_BAND_YELLOW,
                    sliderAccentColor = BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
                )
            }
        }

        val tiltPage = page {
            // Plain white title, no subtitle -- matches the Crossovers page's own "Crossovers"
            // header format rather than the old tint-and-blurb style.
            dashboardPanel(getString(R.string.bmw_dsp_tilt_section), null) {
                addSegmentedSwitchRow(
                    getString(R.string.bmw_dsp_tilt_active),
                    null,
                    NativeBmwDspValues.INDEX_TILT_ENABLED,
                )
                addSliderRow(
                    getString(R.string.bmw_dsp_tilt_amount),
                    NativeBmwDspValues.INDEX_TILT_AMOUNT,
                    -6f, 6f, .1f, "dB",
                    accentColor = BmwDashboardSkin.SLIDER_TILT_COLOR,
                    sliderAccentColor = BmwDashboardSkin.SLIDER_TILT_COLOR,
                )
                addSliderRow(
                    getString(R.string.bmw_dsp_tilt_pivot),
                    NativeBmwDspValues.INDEX_TILT_FREQ,
                    200f, 2000f, 1f, "Hz",
                    accentColor = BmwDashboardSkin.SLIDER_TILT_COLOR,
                    sliderAccentColor = BmwDashboardSkin.SLIDER_TILT_COLOR,
                )
            }
        }

        val monoBassPage = page {
            // No subtitle -- matches the Crossovers/Tilt pages' own header format.
            dashboardPanel(getString(R.string.bmw_dsp_mono_bass), null) {
                addSegmentedSwitchRow(
                    getString(R.string.bmw_dsp_mono_bass_active),
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
            DspPager.build(
                requireContext(),
                listOf(crossoversPage, tiltPage, monoBassPage),
                toggleContainer = requireActivity().findViewById(R.id.dsp_page_toggle_slot),
            ),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
