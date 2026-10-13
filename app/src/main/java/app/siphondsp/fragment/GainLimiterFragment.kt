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
import app.siphondsp.compose.screens.AllPassPage
import app.siphondsp.compose.screens.GainsDelayScreen
import app.siphondsp.compose.screens.SpeakerAlignScreen
import app.siphondsp.compose.screens.VirtualCentreScreen
import kotlinx.coroutines.launch

/**
 * The Alignment workspace (was Gains & Delay): everything that lines the drivers up in time and
 * phase. Swipes between four pages, all Compose:
 * - [GainsDelayScreen] -- DELAY: the tuning controls for one crossover band at a time (High / Mid
 *   / Low tabs): Left/Right Delay, Gain and Polarity, and the global stereo link.
 * - [SpeakerAlignScreen] -- ALIGN: time-alignment setup: the live car map, the seat target, all
 *   six drivers' measured path distances and the alignment they imply, and "Apply to delays".
 * - [AllPassPage] -- ALLPASS: the per-output all-pass sections, one output at a time.
 * - [VirtualCentreScreen] -- CENTRE: the virtual centre (two-seat imaging).
 *
 * The Output page and the bus limiters that used to be here are now the Output workspace and the
 * Dynamics pager's LIMITERS page (Figma "Menus · PROPOSED").
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
        /** Delay, Align, All-pass, Centre; the finder's four segments map 1:1. */
        const val PAGE_COUNT = 4
        const val PAGE_ALLPASS = 2
    }
}

@Composable
private fun GainLimiterPager(pagerState: PagerState) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> GainsDelayScreen()
            1 -> SpeakerAlignScreen()
            GainLimiterFragment.PAGE_ALLPASS -> AllPassPage()
            else -> VirtualCentreScreen()
        }
    }
}

/** Head unit's DELAY | ALIGN | ALLPASS | CENTRE finder for this pager. */
@Composable
fun GainLimiterPageFinder(pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    ArtPagerFinder(
        pagerState = pagerState,
        labels = listOf("DELAY", "ALIGN", "ALLPASS", "CENTRE"),
        frac = WorkspaceArt.finder4,
        selected = pagerState.currentPage,
        onSelect = { page -> scope.launch { pagerState.scrollToPage(page) } },
    )
}
