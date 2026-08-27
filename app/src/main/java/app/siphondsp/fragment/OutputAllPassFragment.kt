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
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CrossoverDashboardBuilder
import app.siphondsp.view.DspPager
import kotlin.math.roundToInt

/** Output all-pass workspace: the two cascaded all-pass filter sections per output, rebuilt in
 *  the same glass-panel BMW dashboard style as every other workspace page (see
 *  CrossoverDashboardBuilder) rather than a plain PreferenceFragmentCompat list -- this used to
 *  be NativeBmwDspCardFragment's job, but that fragment is still needed as-is for its other home,
 *  the Settings page's inline card (DspFragment), which intentionally keeps the plain-preferences
 *  look shared by its neighbouring cards (Output Control, EQ, DDC, etc.), so it was left alone
 *  rather than restyled in place.
 *
 *  This workspace originally also carried a "Measurements / routing" page (LPF/HPF passthrough,
 *  band mutes, channel isolation) copied out of that same Settings card -- removed again, since
 *  that data already has a home there and didn't need a second, colour-coded copy the way the
 *  all-pass sections themselves did (those had no other proper home; Settings' card is the only
 *  other place that edits them too, just in its plain list style, not duplicated further here).
 *
 *  Paged like every other multi-section workspace here (Gains & Delay, Crossovers & Tilt,
 *  Compressor) instead of one page sharing all 4 outputs behind a dropdown selector that swapped
 *  the two all-pass sections shown below it -- that was the one screen in this app not following
 *  the swipe-page pattern, and cycling through 4 outputs on a single dropdown meant losing your
 *  scroll position every time. Each output now gets its own page, colour-coded the same Low=blue/
 *  Mid=yellow way Gains & Delay and Crossovers & Tilt already are, so which output you're on reads
 *  at a glance instead of off a dropdown label. */
class OutputAllPassFragment : Fragment() {
    private lateinit var container: FrameLayout

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

        fun page(build: CrossoverDashboardBuilder.() -> Unit): View {
            val pageRoot = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }
            CrossoverDashboardBuilder(requireContext(), pageRoot, values, onChanged).build()
            return NestedScrollView(requireContext()).apply {
                // Deliberately NOT isFillViewport=true -- see CrossoverTiltFragment's identical
                // page() helper for why: stretching a shorter-than-viewport page corrupts
                // LinearLayout's measure pass for addSegmentedSwitchRow's MATCH_PARENT control
                // slot and addSliderRow's weighted spacer, silently dropping rows after the first
                // slider that follows a switch -- exactly the Enabled-switch-then-Frequency/Q-
                // sliders shape every all-pass page below has.
                addView(pageRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        // One page per physical output -- title, section-header, and both sliders all take the
        // same band color, matching how Gains & Delay's channel cards and Crossovers & Tilt's
        // frequency sliders are already colour-coded. The Enabled switch and Type dropdown stay
        // neutral, same convention Crossovers & Tilt's own switches/dropdowns already follow.
        fun outputPage(title: String, output: Int, bandColor: Int, sliderColor: Int): View = page {
            dashboardPanel(
                title,
                "Two cascaded all-pass filter sections, for phase/time alignment between bands",
                titleColor = bandColor,
            ) {
                repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
                    val base = NativeBmwDspValues.INDEX_ALL_PASS +
                        (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
                    sectionHeader("Section ${section + 1}", accentColor = bandColor)
                    addSegmentedSwitchRow("Enabled", null, base, accentColor = bandColor)
                    addDropdownRow("Type", base + 1, listOf("First order" to 1f, "Second order" to 2f))
                    // 20Hz-20kHz (the full audio range) made this slider nearly unusable -- one
                    // finger-width of drag covered thousands of Hz. All-pass sections here exist
                    // for phase/time alignment near a crossover (both bands' crossover freq slider
                    // is 80-200Hz, see CrossoverTiltFragment), so 20-1000Hz keeps that region --
                    // plus real headroom above it -- comfortably spread across the slider's width.
                    addSliderRow("Frequency", base + 2, 20f, 1000f, 1f, "Hz", accentColor = bandColor, sliderAccentColor = sliderColor)
                    addSliderRow("Q", base + 3, 0.1f, 30f, 0.01f, "", accentColor = bandColor, sliderAccentColor = sliderColor)
                }
            }
        }

        container.removeAllViews()
        container.addView(
            DspPager.build(
                requireContext(),
                listOf(
                    outputPage("Left Low", NativeBmwDspValues.OUTPUT_LOW_LEFT, BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR),
                    outputPage("Right Low", NativeBmwDspValues.OUTPUT_LOW_RIGHT, BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR),
                    outputPage("Left Mid", NativeBmwDspValues.OUTPUT_MID_LEFT, BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR),
                    outputPage("Right Mid", NativeBmwDspValues.OUTPUT_MID_RIGHT, BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR),
                ),
            ),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
