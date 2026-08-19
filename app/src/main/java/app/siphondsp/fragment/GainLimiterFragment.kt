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
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import kotlin.math.roundToInt

/**
 * Dedicated Gains & Delay workspace using the shared BMW dashboard skin. Swipes between two
 * pages: the car/speaker diagram with per-channel Delay and Polarity cards, and a Gain page
 * (Headroom plus the 4 channel gains) as full horizontal sliders in the same style as every
 * other DSP workspace slider -- gain used to live as a mini-slider inside each channel card and
 * Headroom as a vertical fader docked to the page header, both replaced by this dedicated page.
 */
class GainLimiterFragment : Fragment() {
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
        // by this screen's stale snapshot the next time a control here is touched.
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
                addView(pageRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val diagramPage = page {
            // Blank title and subtitle: the toolbar above already shows "Gains & Delay", and the
            // subtitle wasn't telling the user anything the page itself doesn't already -- both
            // just cost rows of vertical space this page can't spare.
            dashboardPanel("", null) {
                val midLeft = addChannelCard(
                    title = "Left Mid",
                    accentColor = BmwDashboardSkin.LIGHT_BLUE,
                    delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_L, delayMin = 0f, delayMax = 2.8f,
                    polarityIndex = NativeBmwDspValues.INDEX_MID_INVERT, polarityMirror = midPair(NativeBmwDspValues.FIELD_INVERT),
                    onPolarityChanged = ::rebuild,
                )
                val lowLeft = addChannelCard(
                    title = "Left Low",
                    accentColor = TEAL,
                    delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_L, delayMin = 0f, delayMax = 2.8f,
                    polarityIndex = NativeBmwDspValues.INDEX_LOW_INVERT, polarityMirror = lowPair(NativeBmwDspValues.FIELD_INVERT),
                    onPolarityChanged = ::rebuild,
                )
                val midRight = addChannelCard(
                    title = "Right Mid",
                    accentColor = ORANGE,
                    delayIndex = NativeBmwDspValues.INDEX_MID_DELAY_R, delayMin = 0f, delayMax = 2.8f,
                    polarityIndex = NativeBmwDspValues.INDEX_MID_INVERT, polarityMirror = midPair(NativeBmwDspValues.FIELD_INVERT),
                    onPolarityChanged = ::rebuild,
                )
                val lowRight = addChannelCard(
                    title = "Right Low",
                    accentColor = BmwDashboardSkin.M_RED,
                    delayIndex = NativeBmwDspValues.INDEX_LOW_DELAY_R, delayMin = 0f, delayMax = 2.8f,
                    polarityIndex = NativeBmwDspValues.INDEX_LOW_INVERT, polarityMirror = lowPair(NativeBmwDspValues.FIELD_INVERT),
                    onPolarityChanged = ::rebuild,
                )
                addChannelDiagramSection(
                    midLeft, lowLeft, midRight, lowRight,
                    midLeftColor = BmwDashboardSkin.LIGHT_BLUE, lowLeftColor = TEAL,
                    midRightColor = ORANGE, lowRightColor = BmwDashboardSkin.M_RED,
                )
            }
        }

        val gainPage = page {
            dashboardPanel("Gain", null) {
                addSliderRow(
                    getString(R.string.bmw_dsp_headroom), NativeBmwDspValues.INDEX_HEADROOM, -12f, 0f, 1f, "dB",
                )
                addSliderRow(
                    "Left Mid", NativeBmwDspValues.INDEX_MID_GAIN_L, -6f, 0f, .5f, "dB",
                )
                addSliderRow(
                    "Right Mid", NativeBmwDspValues.INDEX_MID_GAIN_R, -6f, 0f, .5f, "dB",
                )
                addSliderRow(
                    "Left Low", NativeBmwDspValues.INDEX_LOW_GAIN_L, -6f, 0f, .5f, "dB",
                )
                addSliderRow(
                    "Right Low", NativeBmwDspValues.INDEX_LOW_GAIN_R, -6f, 0f, .5f, "dB",
                )
            }
        }

        container.removeAllViews()
        container.addView(
            DspPager.build(requireContext(), listOf(diagramPage, gainPage)),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private val TEAL = android.graphics.Color.rgb(38, 198, 218)
        private val ORANGE = android.graphics.Color.rgb(255, 152, 0)
    }
}
