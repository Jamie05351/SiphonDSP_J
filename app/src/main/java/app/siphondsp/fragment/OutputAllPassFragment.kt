package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import app.siphondsp.compose.screens.OutputAllPassScreen
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspPager

/** Output all-pass workspace: the two cascaded all-pass filter sections per physical output.
 *  Each output is a swipe page ([OutputAllPassScreen], Compose -- see
 *  COMPOSE_MIGRATION_ROADMAP.md Phase 9), colour-coded Low=blue / Mid=yellow the same way Gains
 *  & Delay and Crossovers & Tilt are, so which output you're on reads at a glance. `DspPager`
 *  (a View) hosts the four.
 *
 *  (This used to be a `CrossoverDashboardBuilder` page; `NativeBmwDspCardFragment` still renders
 *  the same all-pass slots in the plain-preferences style for its other home, the Settings
 *  page's inline card.) */
class OutputAllPassFragment : Fragment() {
    private lateinit var container: FrameLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        this.container = FrameLayout(requireContext())
        rebuild()
        return this.container
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every resume so a fresh ComposeView (and its BmwDspState) picks up edits
        // made elsewhere while this screen was stopped.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        fun page(output: Int, title: String, bandColor: Int, sliderColor: Int): View =
            ComposeView(requireContext()).apply {
                setContent { OutputAllPassScreen(output, title, bandColor, sliderColor) }
            }

        container.removeAllViews()
        container.addView(
            DspPager.build(
                requireContext(),
                listOf(
                    page(NativeBmwDspValues.OUTPUT_LOW_LEFT, "Left Low", BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR),
                    page(NativeBmwDspValues.OUTPUT_LOW_RIGHT, "Right Low", BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR),
                    page(NativeBmwDspValues.OUTPUT_MID_LEFT, "Left Mid", BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR),
                    page(NativeBmwDspValues.OUTPUT_MID_RIGHT, "Right Mid", BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR),
                ),
            ),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }
}
