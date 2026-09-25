package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.compose.screens.CrossoverGraphMode
import app.siphondsp.compose.screens.CrossoverLowMidPage
import app.siphondsp.compose.screens.CrossoverMidHighPage
import app.siphondsp.compose.screens.TonalityTiltScreen

/**
 * Crossovers & Tilt workspace -- three Compose pages:
 * - [CrossoverLowMidPage] -- the response graph over the Low lowpass / Mid highpass / Subsonic
 *   rows.
 * - [CrossoverMidHighPage] -- the same graph under the master 3-way switch, over the Mid/High
 *   corner and Mid-align rows, plus a deep link to the full All-pass screen.
 * - [TonalityTiltScreen] -- Tilt amount / pivot.
 * The two crossover pages share one graph-mode choice, held here.
 *
 * Both read/write the same `NativeBmwDspValues` indices via `BmwDspState` and broadcast the same
 * way. Phase 11.1: hosted directly by Compose's own `HorizontalPager` instead of the View-based
 * `DspPager` -- each page already self-refreshes via `rememberBmwDspState`, so the old per-resume
 * rebuild was redundant (COMPOSE_MIGRATION_ROADMAP.md Phase 4 + Phase 11).
 *
 * The `PagerState` is owned by [CrossoverTiltActivity] (shared with [OutputAllPassFragment], its
 * other possible content), not `remember`ed here, so `DspPagerArrows` on the toolbar line can
 * drive it -- see that composable's doc for why a swipe starting on one of this screen's sliders
 * can't reliably page on its own in Compose.
 */
class CrossoverTiltFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val pagerState = (requireActivity() as CrossoverTiltActivity).pagerState
        setContent { CrossoverTiltPager(pagerState) }
    }

    companion object {
        const val PAGE_COUNT = 3
    }
}

@Composable
private fun CrossoverTiltPager(pagerState: PagerState) {
    var graphMode by rememberSaveable { mutableStateOf(CrossoverGraphMode.MAGNITUDE) }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> CrossoverLowMidPage(graphMode, { graphMode = it })
            1 -> CrossoverMidHighPage(graphMode, { graphMode = it })
            else -> TonalityTiltScreen()
        }
    }
}
