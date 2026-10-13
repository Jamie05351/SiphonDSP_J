package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import app.siphondsp.compose.screens.HeadroomOutputScreen

/**
 * The Output workspace's one page, [HeadroomOutputScreen]: headroom, the post gains, the master
 * limiter and the master volume -- the end of the chain. It was the GAINS page of the Gains &
 * Delay pager until the menus were regrouped (Figma "Menus · PROPOSED", frame 5).
 */
class OutputFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { HeadroomOutputScreen() }
    }
}
