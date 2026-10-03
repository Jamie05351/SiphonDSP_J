package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.compose.controls.ArtPagerFinder
import app.siphondsp.compose.controls.WorkspaceArt
import app.siphondsp.compose.screens.CompressorDriverPage
import app.siphondsp.compose.screens.GainsDelayScreen
import app.siphondsp.compose.screens.HeadroomOutputScreen
import app.siphondsp.compose.screens.SpeakerAlignScreen
import app.siphondsp.compose.screens.VirtualCentreScreen
import kotlinx.coroutines.launch

/**
 * Dedicated Gains & Delay workspace. Swipes between four pages, all Compose:
 * - [GainsDelayScreen] -- the tuning controls for one crossover band at a time (High / Mid / Low
 *   tabs): Left/Right Delay, Gain, Polarity and Stage Alignment, and the global stereo link.
 * - [SpeakerAlignScreen] -- time-alignment setup: the live car map, the seat target, all six
 *   drivers' measured path distances and the alignment they imply, and "Apply to delays". Split
 *   off the Delay page, which was too cramped with both on it.
 * - [HeadroomOutputScreen] -- Headroom, the post-gain L/R sliders and the master limiter
 *   (enable + threshold + a live GR meter).
 * - [CompressorDriverPage] -- the per-bus brick-wall limiters (Low bus / Mid bus), moved here
 *   from the compressor pager so every limiter stage lives on one screen.
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
        /** Delay, Align, Centre, Output, then Bus limiters; the finder's five segments map 1:1. */
        const val PAGE_COUNT = 5
    }
}

@Composable
private fun GainLimiterPager(pagerState: PagerState) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> GainsDelayScreen()
            1 -> SpeakerAlignScreen()
            2 -> VirtualCentreScreen()
            3 -> HeadroomOutputScreen()
            else -> CompressorDriverPage()
        }
    }
}

/**
 * Head unit's DELAY | ALIGN | CENTRE | GAINS | LIMITERS finder for this pager: DELAY the band
 * controls, ALIGN the speaker map and time alignment, CENTRE the virtual centre (two-seat
 * imaging), GAINS the Output page and LIMITERS the bus limiters.
 */
@Composable
fun GainLimiterPageFinder(pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    ArtPagerFinder(
        pagerState = pagerState,
        labels = listOf("DELAY", "ALIGN", "CENTRE", "GAINS", "LIMITERS"),
        frac = WorkspaceArt.finder5,
        selected = pagerState.currentPage,
        onSelect = { page -> scope.launch { pagerState.scrollToPage(page) } },
    )
}
