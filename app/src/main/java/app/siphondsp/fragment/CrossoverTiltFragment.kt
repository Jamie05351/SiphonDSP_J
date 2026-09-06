package app.siphondsp.fragment

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.CrossoverHandoffSurface
import app.siphondsp.view.DspPager
import kotlin.math.roundToInt

/**
 * Dedicated Crossovers & Tilt screen using the shared BMW dashboard skin. Swipes between three
 * pages -- Crossover, Tilt, and Mono Bass. Page 1 is the interactive [CrossoverHandoffSurface]
 * (draggable Low / Mid HPF / Mid LPF corners over the live low/mid/sum response, with a flat-sum
 * readout) plus the two rows that don't belong on the graph -- Subsonic and one linked Mid
 * all-pass alignment control -- and a deep link to the full per-output All-pass screen. The
 * visible Low/Mid controls stay linked while mirroring into independent L/R runtime config.
 * All four panels render in `lean` mode (no card, thin header) so the head unit's fold isn't
 * eaten by chrome.
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
        val peqState = BmwPeqState.load(requireContext())
        val onChanged: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }

        // Subsonic still mirrors onto the two Low outputs' own config block; the crossover
        // corners themselves are written by CrossoverHandoffSurface now, not from here.
        fun lowPair(field: Int) = intArrayOf(
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, field),
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, field),
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

        // Section-1 (index 0) all-pass block for each Mid output: [enabled, order, freq, Q].
        // Linked L/R below -- the frequency handle is what aligns the Mid branch's phase through
        // the handoff; per-output / section 2 / Q stay on the full All-pass screen.
        val midLeftAllPassBase = NativeBmwDspValues.INDEX_ALL_PASS +
            (NativeBmwDspValues.OUTPUT_MID_LEFT * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) *
            NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
        val midRightAllPassBase = NativeBmwDspValues.INDEX_ALL_PASS +
            (NativeBmwDspValues.OUTPUT_MID_RIGHT * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) *
            NativeBmwDspValues.ALL_PASS_SECTION_WIDTH

        val crossoversPage = page {
            // One "Crossover" object: an interactive Low/Mid handoff graph with three draggable
            // corners (Low, Mid HPF, Mid LPF) and a live flat-sum readout, replacing the old
            // Subsonic + Lowpass/Highpass/Mid-LPF slider stack. Only the rows that don't belong
            // on the graph stay below it -- Subsonic, and the one linked Mid all-pass alignment
            // control -- plus a deep link to the full per-output All-pass screen.
            dashboardPanel("", null, lean = true) {
                val surface = CrossoverHandoffSurface(requireContext()).apply {
                    bind(values, peqState)
                    onEdit = onChanged
                }
                addCustomView(surface, topMarginDp = 2, bottomMarginDp = 2)

                // Fixed readout, not a selector: both corners are LR4 (24 dB/oct). The mid
                // highpass was already LR4-only natively; the low band was matched to it.
                sectionHeader("LR4 · 24 dB/oct", accentColor = Color.rgb(150, 158, 168), textSize = 11f, showDivider = false)

                addSliderRow(
                    getString(R.string.bmw_dsp_subsonic_freq),
                    NativeBmwDspValues.INDEX_SUBSONIC_FREQ,
                    20f, 60f, 1f, "Hz",
                    mirrorIndices = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_FREQ),
                    toggleIndex = NativeBmwDspValues.INDEX_SUBSONIC_ENABLED,
                    toggleMirrorIndices = lowPair(NativeBmwDspValues.FIELD_SUBSONIC_ENABLED),
                )
                addSliderRow(
                    "Mid align (all-pass)", midLeftAllPassBase + 2,
                    20f, 1000f, 1f, "Hz",
                    mirrorIndices = intArrayOf(midRightAllPassBase + 2),
                    accentColor = BmwDashboardSkin.MID_BAND_YELLOW,
                    sliderAccentColor = BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
                    toggleIndex = midLeftAllPassBase,
                    toggleMirrorIndices = intArrayOf(midRightAllPassBase),
                )
                addCustomView(
                    TextView(requireContext()).apply {
                        text = "Open full All-pass ›"
                        textSize = 12f
                        setTextColor(BmwDashboardSkin.LIGHT_BLUE)
                        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(4), dp(6), dp(4), dp(2))
                        setOnClickListener {
                            startActivity(
                                Intent(requireContext(), CrossoverTiltActivity::class.java)
                                    .putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_ALLPASS),
                            )
                        }
                    },
                    topMarginDp = 0, bottomMarginDp = 2,
                )
            }
        }

        val tiltPage = page {
            // Plain white title, no subtitle -- matches the Crossovers page's own "Crossovers"
            // header format rather than the old tint-and-blurb style.
            dashboardPanel(
                getString(R.string.bmw_dsp_tilt_section), null,
                toggleIndex = NativeBmwDspValues.INDEX_TILT_ENABLED,
                topContentGapDp = 40,
                lean = true,
            ) {
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
            dashboardPanel(
                getString(R.string.bmw_dsp_mono_bass), null,
                toggleIndex = NativeBmwDspValues.INDEX_MONO_BASS_ENABLED,
                topContentGapDp = 40,
                lean = true,
            ) {
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
