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
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.compose.screens.CrossoversPageScreen
import app.siphondsp.compose.screens.TonalityTiltScreen

/**
 * Crossovers & Tilt workspace -- two Compose pages:
 * - [CrossoversPageScreen] -- the read-only CrossoverHandoffSurface graph over the Lowpass /
 *   Highpass / Subsonic / Mid-align rows, plus a deep link to the full All-pass screen.
 * - [TonalityTiltScreen] -- Tilt amount / pivot.
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
        const val PAGE_COUNT = 2
    }
}

@Composable
private fun CrossoverTiltPager(pagerState: PagerState) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> CrossoversPageScreen()
            else -> TonalityTiltScreen()
        }
    }
}
