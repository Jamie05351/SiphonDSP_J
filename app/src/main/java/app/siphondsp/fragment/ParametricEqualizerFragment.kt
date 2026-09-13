package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.compose.screens.ParametricEqScreen
import app.siphondsp.compose.theme.BmwDspTheme

/**
 * The Parametric EQ workspace host. Everything is Compose now — [ParametricEqScreen] (roadmap
 * Phase 10, redesigned since to just the graph/list swipe pager) assembles the graph and the
 * filter list. The scope switch and action-chip row live on the toolbar line instead, hosted
 * directly by [ParametricEqualizerActivity] (`PeqToolbarActions`) -- this fragment reads that
 * activity's shared `peqStateHolder` rather than creating its own, so the two stay in sync.
 *
 * `fragment_parametric_eq.xml` (and its `layout-land` variant) and every portrait-only code
 * path (`collapsePreview`, the weighted-chain layout, `cards_pager`) are gone — portrait is dead
 * weight on the 1280×480 head unit. `activity_parametric_eq.xml` (the rail + toolbar chrome)
 * is untouched.
 */
class ParametricEqualizerFragment : Fragment() {

    /** Guard for `ParametricEqualizerActivity`'s `DspCrossNavBar.populate()`: every PEQ edit
     *  commits instantly, so there is never an unsaved edit to block a screen switch on. */
    fun canSwitchDspScreens(): Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val holder = (requireActivity() as ParametricEqualizerActivity).peqStateHolder
        setContent {
            BmwDspTheme {
                ParametricEqScreen(holder = holder)
            }
        }
    }

    companion object {
        fun newInstance() = ParametricEqualizerFragment()
    }
}
