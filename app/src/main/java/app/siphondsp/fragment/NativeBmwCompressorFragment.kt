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
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.compose.screens.CompressorBandPage
import app.siphondsp.compose.screens.CompressorVisualiserPage
import app.siphondsp.model.NativeBmwDspValues

/**
 * The pre-crossover multiband compressor screen: Compose pages for the
 * [CompressorVisualiserPage] (the `CompressorSurface` visualiser + the MBC enable / dry-wet Mix
 * master strip) and one [CompressorBandPage] per MBC band (enable, stereo link, a live GR meter,
 * and the threshold / ratio / knee / attack / release / makeup sliders). The per-bus brick-wall
 * limiters (`CompressorDriverPage`) now live on the Gains & Delay pager. See
 * COMPOSE_MIGRATION_ROADMAP.md Phase 7.
 *
 * Every control writes into the shared `NativeBmwDspValues` array via `BmwDspState` and
 * broadcasts the same way; the legacy per-output compressor this screen used to edit is retired
 * and force-disabled on load (`NativeBmwDspValues.migrateDisableLegacyCompressorIfNeeded`, run
 * inside `NativeBmwDspValues.load()` itself, so `rememberBmwDspState`'s resume reload already
 * covers it -- no explicit rebuild needed).
 *
 * Phase 11.1: hosted directly by Compose's own `HorizontalPager` instead of the View-based
 * `DspPager`; `fragment_native_bmw_compressor.xml` (a single passthrough `FrameLayout`) is gone,
 * this fragment's root is the `ComposeView` itself.
 *
 * The `PagerState` is owned by [NativeBmwCompressorActivity], not `remember`ed here, so
 * `DspPagerArrows` on the toolbar line can drive it -- swiping starting on one of a band page's
 * sliders can't reliably page on its own in Compose (see `DspPagerArrows`'s doc).
 */
class NativeBmwCompressorFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val pagerState = (requireActivity() as NativeBmwCompressorActivity).pagerState
        setContent { NativeBmwCompressorPager(pagerState) }
    }

    companion object {
        val PAGE_COUNT = 1 + NativeBmwDspValues.MBC_BAND_COUNT
    }
}

@Composable
private fun NativeBmwCompressorPager(pagerState: PagerState) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 0) CompressorVisualiserPage() else CompressorBandPage(page - 1)
    }
}
