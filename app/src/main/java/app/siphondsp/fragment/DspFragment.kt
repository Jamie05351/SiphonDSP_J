package app.siphondsp.fragment

import android.animation.LayoutTransition
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch
import app.siphondsp.R
import app.siphondsp.compose.controls.HomeFaceplate
import app.siphondsp.compose.controls.HomeTile
import app.siphondsp.compose.controls.HomeTileKind
import app.siphondsp.compose.screens.HomeCrossoverGraph
import app.siphondsp.compose.screens.HomePeqGraph
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.databinding.FragmentDspBinding
import app.siphondsp.databinding.FragmentDspPageSettingsBinding
import app.siphondsp.databinding.FragmentDspPageShortcutsBinding
import app.siphondsp.utils.Constants
import app.siphondsp.utils.preferences.Preferences
import app.siphondsp.view.StaticPagerAdapter
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

class DspFragment : Fragment() {
    private val prefsVar: Preferences.Var by inject()

    private lateinit var binding: FragmentDspBinding
    private lateinit var shortcutsBinding: FragmentDspPageShortcutsBinding
    private lateinit var settingsBinding: FragmentDspPageSettingsBinding
    private var updateNoticeOnClick: (() -> Unit)? = null
    private var updateNoticeOnCloseClick: (() -> Unit)? = null
    private var powerState: Boolean = false

    /**
     * Called with the artwork front page's horizontal offset in px as the pager moves it (0 =
     * settled on it, +-page width = fully off screen), so the activity-level overlay laid over
     * that art can move with it instead of staying put mid-swipe.
     */
    var onHomePageOffset: ((Float) -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onMoreClick: ((View) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDspBinding.inflate(layoutInflater, container, false)
        shortcutsBinding = FragmentDspPageShortcutsBinding.inflate(layoutInflater, binding.dspPager, false)
        settingsBinding = FragmentDspPageSettingsBinding.inflate(layoutInflater, binding.dspPager, false)

        setUpShortcutsPage()

        // The settings page hosts child fragments through FragmentContainerView, which needs its
        // container ids to already be resolvable in the live view tree when the transaction runs.
        // ViewPager2 only actually attaches a page's view once that page is laid out, so the
        // child-fragment transaction is deferred until the page view is attached to the window
        // rather than run eagerly here.
        binding.dspPager.offscreenPageLimit = 1
        binding.dspPager.adapter = StaticPagerAdapter(
            pages = listOf(shortcutsBinding.root, settingsBinding.root),
            onPageAttached = { position -> if (position == 1) setUpSettingsPage() },
        )
        binding.dspPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageSelectedInternal(position)
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                reportHomePageOffset(position * binding.dspPager.width + positionOffsetPixels)
            }
        })

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Re-assert the current page after a restore, where onPageSelected doesn't fire.
        onPageSelectedInternal(binding.dspPager.currentItem)
        // Posted: after a restore the pager may not be laid out yet, and a 0 width would put the
        // overlay back over the settings page.
        binding.dspPager.post { reportHomePageOffset(binding.dspPager.currentItem * binding.dspPager.width) }
    }

    private fun onPageSelectedInternal(position: Int) {
        // The artwork page stays attached while off screen, so live/polled home widgets need to
        // stop work when the settings page is selected.
        val active = position == 0
        shortcutsBinding.homeLevelBars.pageActive = active
        shortcutsBinding.homeDashboardStatus.pageActive = active
    }

    /** [scrolledPx] is how far the pager has scrolled past the artwork page, in reading order. */
    private fun reportHomePageOffset(scrolledPx: Int) {
        val rtl = binding.dspPager.layoutDirection == View.LAYOUT_DIRECTION_RTL
        onHomePageOffset?.invoke(if (rtl) scrolledPx.toFloat() else -scrolledPx.toFloat())
    }

    private fun setUpShortcutsPage() {
        // The faceplate and the seven tiles are Compose. HomeFaceplate and HomeArtLayout both pick
        // the head-unit or phone rect set from isHeadUnitDisplay(), so a phone needs no swap.
        shortcutsBinding.homeBackdrop.setHomeContent { HomeFaceplate() }
        shortcutsBinding.cardShortcutPeq.setHomeContent { HomeTile(HomeTileKind.PEQ) }
        shortcutsBinding.cardShortcutGainsDelay.setHomeContent { HomeTile(HomeTileKind.GAINS) }
        shortcutsBinding.cardShortcutCrossovers.setHomeContent { HomeTile(HomeTileKind.XOVERS) }
        shortcutsBinding.cardShortcutCompressor.setHomeContent { HomeTile(HomeTileKind.COMPRESSOR) }
        shortcutsBinding.cardShortcutAllpass.setHomeContent { HomeTile(HomeTileKind.ALLPASS) }
        shortcutsBinding.cardShortcutSettings.setHomeContent { HomeTile(HomeTileKind.SETTINGS) }
        shortcutsBinding.cardShortcutMore.setHomeContent { HomeTile(HomeTileKind.MORE) }
        shortcutsBinding.translationNotice.setOnCloseClickListener(::hideTranslationNotice)
        shortcutsBinding.translationNotice.setOnRootClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://crowdin.com/project/siphondsp".toUri()))
            hideTranslationNotice()
        }

        shortcutsBinding.updateNotice.setOnCloseClickListener {
            updateNoticeOnCloseClick?.invoke()
        }
        shortcutsBinding.updateNotice.setOnRootClickListener {
            updateNoticeOnClick?.invoke()
        }

        // Top-left display: read-only crossover response. Top-centre: read-only PEQ response.
        // Both are static snapshots of current settings (no analyser/spectrum animation) and are
        // disposed with the fragment view rather than the window.
        shortcutsBinding.homeCrossoverGraph.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        shortcutsBinding.homeCrossoverGraph.setContent { HomeCrossoverGraph() }

        shortcutsBinding.homePeqGraph.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        shortcutsBinding.homePeqGraph.setContent { HomePeqGraph() }
        shortcutsBinding.homeDashboardStatus.powerOn = powerState

        // Seven primary home actions. The first five open DSP workspaces; Settings and More
        // delegate to MainActivity so its existing settings/overflow behaviour remains the single
        // source of truth. The power button remains activity-owned because it controls the engine.
        shortcutsBinding.cardShortcutPeq.setOnClickListener {
            startActivity(Intent(requireContext(), ParametricEqualizerActivity::class.java))
        }
        shortcutsBinding.cardShortcutGainsDelay.setOnClickListener {
            startActivity(Intent(requireContext(), GainLimiterActivity::class.java))
        }
        shortcutsBinding.cardShortcutCompressor.setOnClickListener {
            startActivity(Intent(requireContext(), NativeBmwCompressorActivity::class.java))
        }
        shortcutsBinding.cardShortcutCrossovers.setOnClickListener {
            startActivity(Intent(requireContext(), CrossoverTiltActivity::class.java))
        }
        shortcutsBinding.cardShortcutAllpass.setOnClickListener {
            startActivity(
                Intent(requireContext(), CrossoverTiltActivity::class.java)
                    .putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_ALLPASS),
            )
        }
        shortcutsBinding.cardShortcutSettings.setOnClickListener {
            onSettingsClick?.invoke()
        }
        shortcutsBinding.cardShortcutMore.setOnClickListener { anchor ->
            onMoreClick?.invoke(anchor)
        }
        // Should show notice?
        Timber.e(Locale.getDefault().language.toString())
        shortcutsBinding.translationNotice.isVisible =
           prefsVar.get<Long>(R.string.key_snooze_translation_notice) < (System.currentTimeMillis() / 1000L) &&
                    !Locale.getDefault().language.equals("en")
        shortcutsBinding.updateNotice.isVisible = false

        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        shortcutsBinding.pageShortcutsRoot.layoutTransition = transition
    }

    /** Fragment-hosted ComposeViews are disposed with the fragment view, not the window. */
    private fun ComposeView.setHomeContent(content: @Composable () -> Unit) {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { BmwDspTheme { content() } }
    }

    private fun setUpSettingsPage() {
        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        settingsBinding.cardContainer.layoutTransition = transition

        childFragmentManager.beginTransaction()
            .replace(
                R.id.card_output_control, PreferenceGroupFragment.newInstance(Constants.PREF_OUTPUT,
                    R.xml.dsp_output_control_preferences
                ))
            .replace(
                R.id.card_convolver, PreferenceGroupFragment.newInstance(Constants.PREF_CONVOLVER,
                    R.xml.dsp_convolver_preferences
                ))
            .commit()
    }

    private fun hideTranslationNotice() {
        shortcutsBinding.translationNotice.isVisible = false
        // Set timer +1y
        prefsVar.set<Long>(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 31536000L)
    }

    /** Keeps the live DSP-status cell in lockstep with MainActivity's real power state. */
    fun setPowerState(on: Boolean) {
        powerState = on
        if (::shortcutsBinding.isInitialized) {
            shortcutsBinding.homeDashboardStatus.powerOn = on
        }
    }

    fun setUpdateCardVisible(visible: Boolean) {
        shortcutsBinding.updateNotice.isVisible = visible
    }

    fun setUpdateCardTitle(title: String) {
        shortcutsBinding.updateNotice.titleText = title
    }

    fun setUpdateCardOnClick(onClick: () -> Unit) {
        updateNoticeOnClick = onClick
    }

    fun setUpdateCardOnCloseClick(onClick: () -> Unit) {
        updateNoticeOnCloseClick = onClick
    }

    fun restartFragment(id: Int, newFragment: Fragment) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                childFragmentManager.beginTransaction()
                    .replace(id, newFragment)
                    .commitAllowingStateLoss()
            }
            catch(ex: IllegalStateException) {
                Timber.e("Failed to restart fragment")
                Timber.i(ex)
            }
        }
    }

    companion object {
        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}
