package app.siphondsp.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import app.siphondsp.view.MbcBandGrMeter
import kotlin.math.roundToInt

/**
 * Dedicated Gains & Delay workspace using the shared BMW dashboard skin. Swipes between two
 * pages: the car/speaker diagram with per-channel Delay, Polarity and Gain cards (the Left Low
 * card also carries the global Link L/R Delay toggle), and an Output page with Headroom, the
 * post-gain L/R sliders and the master limiter (enable + threshold + a live GR meter).
 */
class GainLimiterFragment : Fragment() {
    private lateinit var container: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var limiterMeter: MbcBandGrMeter? = null

    private val meterTick = object : Runnable {
        override fun run() {
            RootlessAudioProcessorService.nativeBmwMasterLimiterMeter()?.let { m ->
                limiterMeter?.setGainReductionDb(m[0])
            }
            handler.postDelayed(this, 33L)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(meterTick)
    }

    override fun onStop() {
        handler.removeCallbacks(meterTick)
        super.onStop()
    }

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
        // by this screen's stale snapshot the next time a control here is touched.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        val values = NativeBmwDspValues.load(requireContext())
        val onChanged: (FloatArray) -> Unit = { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        fun page(build: CrossoverDashboardBuilder.() -> Unit): View {
            val pageRoot = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }
            CrossoverDashboardBuilder(requireContext(), pageRoot, values, onChanged).build()
            return NestedScrollView(requireContext()).apply {
                addView(pageRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val diagramPage = page {
            // Blank title and subtitle: the toolbar above already shows "Gains & Delay", and the
            // subtitle wasn't telling the user anything the page itself doesn't already -- both
            // just cost rows of vertical space this page can't spare.
            dashboardPanel("", null) {
                val linked = values[NativeBmwDspValues.INDEX_DELAY_LINKED] >= .5f
                val midLeft = addChannelCard(
                    title = "Left Mid",
                    accentColor = BmwDashboardSkin.MID_BAND_YELLOW,
                    strokeColor = BmwDashboardSkin.MID_BAND_YELLOW,
                    delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_L, delayMin = 0f, delayMax = 2.8f,
                    gainIndex = NativeBmwDspValues.INDEX_MID_GAIN_L, gainMin = -6f, gainMax = 0f,
                    gainSliderAccentColor = BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
                    // Independent per physical driver, not per band -- a real reversed-polarity
                    // fault can land on just one driver (see NativeBmwDspProcessor.cpp's own
                    // "Deliberate final-output swap" comment for a documented example of exactly
                    // that class of real-world wiring fault on this vehicle), and a shared,
                    // mirrored Low/Mid-wide toggle can't compensate for that: flipping both sides
                    // of a band together leaves their phase relative to *each other* unchanged.
                    // Each card now owns its own FIELD_INVERT slot with no mirrorIndices.
                    polarityIndex = NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_INVERT),
                    polarityMirror = intArrayOf(),
                    delayLinkedIndex = if (linked) NativeBmwDspValues.INDEX_MID_DELAY_R else null,
                    onDelayChanged = ::rebuild,
                )
                val lowLeft = addChannelCard(
                    title = "Left Low",
                    accentColor = BmwDashboardSkin.LIGHT_BLUE,
                    strokeColor = BmwDashboardSkin.M_BLUE,
                    delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_L, delayMin = 0f, delayMax = 2.8f,
                    gainIndex = NativeBmwDspValues.INDEX_LOW_GAIN_L, gainMin = -6f, gainMax = 0f,
                    gainSliderAccentColor = BmwDashboardSkin.SLIDER_LOW_BAND_COLOR,
                    polarityIndex = NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_INVERT),
                    polarityMirror = intArrayOf(),
                    delayLinkedIndex = if (linked) NativeBmwDspValues.INDEX_LOW_DELAY_R else null,
                    onDelayChanged = ::rebuild,
                    // The global Link L/R Delay toggle lives here now, at the bottom of the
                    // left-hand Low card, rather than as its own header row above the diagram.
                    delayLinkToggleIndex = NativeBmwDspValues.INDEX_DELAY_LINKED,
                    onDelayLinkToggled = ::rebuild,
                )
                val midRight = addChannelCard(
                    title = "Right Mid",
                    accentColor = BmwDashboardSkin.MID_BAND_YELLOW,
                    strokeColor = BmwDashboardSkin.MID_BAND_YELLOW,
                    delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_R, delayMin = 0f, delayMax = 2.8f,
                    gainIndex = NativeBmwDspValues.INDEX_MID_GAIN_R, gainMin = -6f, gainMax = 0f,
                    gainSliderAccentColor = BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
                    polarityIndex = NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_INVERT),
                    polarityMirror = intArrayOf(),
                    delayLinkedIndex = if (linked) NativeBmwDspValues.INDEX_MID_DELAY_L else null,
                    onDelayChanged = ::rebuild,
                )
                val lowRight = addChannelCard(
                    title = "Right Low",
                    accentColor = BmwDashboardSkin.LIGHT_BLUE,
                    strokeColor = BmwDashboardSkin.M_BLUE,
                    delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_R, delayMin = 0f, delayMax = 2.8f,
                    gainIndex = NativeBmwDspValues.INDEX_LOW_GAIN_R, gainMin = -6f, gainMax = 0f,
                    gainSliderAccentColor = BmwDashboardSkin.SLIDER_LOW_BAND_COLOR,
                    polarityIndex = NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_INVERT),
                    polarityMirror = intArrayOf(),
                    delayLinkedIndex = if (linked) NativeBmwDspValues.INDEX_LOW_DELAY_L else null,
                    onDelayChanged = ::rebuild,
                )
                addChannelDiagramSection(midLeft, lowLeft, midRight, lowRight)
            }
        }

        val limiterGrMeter = MbcBandGrMeter(requireContext()).also { limiterMeter = it }
        val outputPage = page {
            dashboardPanel("Output", null) {
                addSliderRow(
                    getString(R.string.bmw_dsp_headroom), NativeBmwDspValues.INDEX_HEADROOM, -12f, 0f, 1f, "dB",
                    sliderAccentColor = BmwDashboardSkin.SLIDER_DEFAULT_COLOR,
                )
                addSliderRow(
                    "Post gain L", NativeBmwDspValues.INDEX_POST_GAIN_L, -6f, 6f, .5f, "dB",
                    accentColor = BmwDashboardSkin.M_GREEN,
                    sliderAccentColor = BmwDashboardSkin.M_GREEN,
                )
                addSliderRow(
                    "Post gain R", NativeBmwDspValues.INDEX_POST_GAIN_R, -6f, 6f, .5f, "dB",
                    accentColor = BmwDashboardSkin.M_GREEN,
                    sliderAccentColor = BmwDashboardSkin.M_GREEN,
                )
                sectionHeader("Limiter", BmwDashboardSkin.M_BLUE)
                addSegmentedSwitchRow(
                    "Limiter",
                    "Brick-wall ceiling on the summed output. Off = true bypass.",
                    NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED,
                )
                addSliderRow(
                    "Threshold", NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD, -12f, 0f, .5f, "dB",
                    accentColor = BmwDashboardSkin.M_BLUE,
                    sliderAccentColor = BmwDashboardSkin.M_BLUE,
                )
                addCustomView(limiterGrMeter)
            }
        }

        container.removeAllViews()
        container.addView(
            DspPager.build(requireContext(), listOf(diagramPage, outputPage)),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
