package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.compose.screens.CompressorDriverPage
import app.siphondsp.compose.screens.GainsBand
import app.siphondsp.compose.screens.GainsDelayScreen
import app.siphondsp.compose.screens.HeadroomOutputScreen
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

/**
 * Dedicated Gains & Delay workspace. Swipes between five pages, all Compose:
 * - [GainsDelayScreen] x3 -- one per crossover band, High / Mid / Low (top of the speaker stack
 *   first): that band's Left/Right Delay, Polarity and Gain cards, the stage alignment and the
 *   global stereo link. Each band page swaps in its own car backdrop ([GainsBand.backdrop]).
 * - [HeadroomOutputScreen] -- Headroom, the post-gain L/R sliders and the master limiter
 *   (enable + threshold + a live GR meter).
 * - [CompressorDriverPage] -- the per-bus brick-wall limiters (Low bus / Mid bus), moved here
 *   from the compressor pager so every limiter stage lives on one screen.
 * The last two show the destination's plain backdrop.
 *
 * All pages read/write the same `NativeBmwDspValues` indices and broadcast the same way via
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
        /** One page per [GainsBand], then Output and Bus limiters. */
        val PAGE_COUNT = GainsBand.entries.size + 2
    }
}

@Composable
private fun GainLimiterPager(pagerState: PagerState) {
    val activity = LocalContext.current as FragmentActivity
    // currentPage flips at the swipe's halfway point, so the art changes with the page it belongs to.
    LaunchedEffect(pagerState.currentPage) {
        val band = GainsBand.entries.getOrNull(pagerState.currentPage)
        if (band != null) {
            DspCrossNavBar.showBackdrop(activity, band.backdrop, band.backdropPhone)
        } else {
            DspCrossNavBar.showBackdrop(activity, DspDestination.GAINS_DELAY.backdrop, DspDestination.GAINS_DELAY.backdropPhone)
        }
    }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val band = GainsBand.entries.getOrNull(page)
        when {
            band != null -> GainsDelayScreen(band)
            page == GainsBand.entries.size -> HeadroomOutputScreen()
            else -> CompressorDriverPage()
        }
    }
}
