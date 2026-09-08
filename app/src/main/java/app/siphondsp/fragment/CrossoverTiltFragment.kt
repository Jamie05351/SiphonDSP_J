package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.compose.screens.CrossoversPageScreen
import app.siphondsp.compose.screens.MonoBassScreen
import app.siphondsp.compose.screens.TonalityTiltScreen
import app.siphondsp.view.DspPager

/**
 * Crossovers & Tilt workspace -- a [DspPager] of three Compose pages:
 * - [CrossoversPageScreen] -- the read-only CrossoverHandoffSurface graph over the Lowpass /
 *   Highpass / Subsonic / Mid-align rows, plus a deep link to the full All-pass screen.
 * - [TonalityTiltScreen] -- Tilt amount / pivot.
 * - [MonoBassScreen] -- Mono Bass frequency / blend / makeup.
 *
 * All three read/write the same `NativeBmwDspValues` indices via `BmwDspState` and broadcast the
 * same way, so this fragment is just a `DspPager` host (COMPOSE_MIGRATION_ROADMAP.md Phase 4 +
 * follow-up).
 */
class CrossoverTiltFragment : Fragment() {
    private lateinit var container: FrameLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        this.container = FrameLayout(requireContext())
        rebuild()
        return this.container
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every resume so a fresh set of ComposeViews (and their BmwDspState) pick up
        // edits made elsewhere while this screen was stopped.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        container.removeAllViews()
        container.addView(
            DspPager.build(
                ctx,
                listOf(
                    ComposeView(ctx).apply { setContent { CrossoversPageScreen() } },
                    ComposeView(ctx).apply { setContent { TonalityTiltScreen() } },
                    ComposeView(ctx).apply { setContent { MonoBassScreen() } },
                ),
                toggleContainer = requireActivity().findViewById(R.id.dsp_page_toggle_slot),
            ),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }
}
