package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import app.siphondsp.compose.screens.CompressorDriverPage
import app.siphondsp.compose.screens.GainsDelayScreen
import app.siphondsp.compose.screens.HeadroomOutputScreen
import app.siphondsp.view.DspPager

/**
 * Dedicated Gains & Delay workspace. Swipes between three pages, all Compose:
 * - [GainsDelayScreen] -- the car/speaker diagram with per-channel Delay, Polarity and Gain
 *   cards (the Left Low card also carries the global Link L/R Delay toggle).
 * - [HeadroomOutputScreen] -- Headroom, the post-gain L/R sliders and the master limiter
 *   (enable + threshold + a live GR meter).
 * - [CompressorDriverPage] -- the per-bus brick-wall limiters (Low bus / Mid bus), moved here
 *   from the compressor pager so every limiter stage lives on one screen.
 *
 * All three read/write the same `NativeBmwDspValues` indices and broadcast the same way via
 * `BmwDspState`, so this fragment is now just a `DspPager` host (see
 * COMPOSE_MIGRATION_ROADMAP.md Phases 5-6).
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
        // Rebuilt on every resume so a fresh ComposeView (and its BmwDspState) picks up edits
        // made elsewhere while this screen was stopped.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        val diagramPage: View = ComposeView(requireContext()).apply {
            setContent { GainsDelayScreen() }
        }
        val outputPage: View = ComposeView(requireContext()).apply {
            setContent { HeadroomOutputScreen() }
        }
        val busLimiterPage: View = ComposeView(requireContext()).apply {
            setContent { CompressorDriverPage() }
        }

        container.removeAllViews()
        container.addView(
            DspPager.build(
                requireContext(),
                listOf(diagramPage, outputPage, busLimiterPage),
            ),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }
}
