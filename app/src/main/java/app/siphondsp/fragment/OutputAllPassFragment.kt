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
import app.siphondsp.compose.screens.OutputAllPassScreen
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin

/** Output all-pass workspace: the two cascaded all-pass filter sections per physical output.
 *  Each output is a swipe page ([OutputAllPassScreen], Compose -- see
 *  COMPOSE_MIGRATION_ROADMAP.md Phase 9), colour-coded Low=blue / Mid=yellow the same way Gains
 *  & Delay and Crossovers & Tilt are, so which output you're on reads at a glance.
 *
 *  Phase 11.1: hosted directly by Compose's own `HorizontalPager` (see `ParametricEqScreen` for
 *  the precedent) instead of the View-based `DspPager` -- each page already self-refreshes via
 *  `rememberBmwDspState` (resume reload + live broadcast), so the old "destroy and rebuild every
 *  ComposeView on onResume" dance was fully redundant, not load-bearing.
 *
 *  The `PagerState` is owned by [CrossoverTiltActivity] (shared with [CrossoverTiltFragment], its
 *  other possible content), not `remember`ed here, so `DspPagerArrows` on the toolbar line can
 *  drive it -- swiping starting on one of this screen's freq/Q sliders can't reliably page on its
 *  own in Compose (see `DspPagerArrows`'s doc).
 *
 *  (This used to be a `CrossoverDashboardBuilder` page.) */
class OutputAllPassFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val pagerState = (requireActivity() as CrossoverTiltActivity).pagerState
        setContent { OutputAllPassPager(pagerState) }
    }

    companion object {
        const val PAGE_COUNT = 4
    }
}

@Composable
private fun OutputAllPassPager(pagerState: PagerState) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> OutputAllPassScreen(NativeBmwDspValues.OUTPUT_LOW_LEFT, "Left Low", BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR)
            1 -> OutputAllPassScreen(NativeBmwDspValues.OUTPUT_LOW_RIGHT, "Right Low", BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR)
            2 -> OutputAllPassScreen(NativeBmwDspValues.OUTPUT_MID_LEFT, "Left Mid", BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR)
            else -> OutputAllPassScreen(NativeBmwDspValues.OUTPUT_MID_RIGHT, "Right Mid", BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR)
        }
    }
}
