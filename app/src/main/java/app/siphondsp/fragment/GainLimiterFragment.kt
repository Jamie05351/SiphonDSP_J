package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.compose.screens.CompressorDriverPage
import app.siphondsp.compose.screens.GainsDelayScreen
import app.siphondsp.compose.screens.HeadroomOutputScreen

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
 * `BmwDspState`. Phase 11.1: hosted directly by Compose's own `HorizontalPager` instead of the
 * View-based `DspPager` -- each page already self-refreshes via `rememberBmwDspState`, so the old
 * per-resume rebuild was redundant (see COMPOSE_MIGRATION_ROADMAP.md Phases 5-6, 11).
 *
 * The `PagerState` is owned by [GainLimiterActivity], not `remember`ed here, so
 * `DspPagerArrows` (hosted on the toolbar line) can drive the same pager -- swiping (off a slider)
 * still works too, the arrows are the reliable path when a swipe would start on one (see
 * `DspPagerArrows`'s doc for why Compose can't arbitrate that automatically the way the old
 * `DspPager` + `PagerChildSwipeGate` did).
 */
class GainLimiterFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val pagerState = (requireActivity() as GainLimiterActivity).pagerState
        setContent { GainLimiterPager(pagerState) }
    }

    companion object {
        const val PAGE_COUNT = 3
    }
}

@Composable
private fun GainLimiterPager(pagerState: PagerState) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> GainsDelayScreen()
            1 -> HeadroomOutputScreen()
            else -> CompressorDriverPage()
        }
    }
}
