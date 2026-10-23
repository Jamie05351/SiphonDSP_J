package app.siphondsp.fragment

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.siphondsp.R
import androidx.compose.ui.geometry.Rect
import app.siphondsp.compose.home.GlobalStage
import app.siphondsp.compose.home.HomeEngineState
import androidx.activity.OnBackPressedCallback
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.siphondsp.compose.launcher.LauncherActions
import app.siphondsp.compose.launcher.LauncherHome
import app.siphondsp.compose.launcher.LauncherOverlay
import app.siphondsp.launcher.AppEntry
import app.siphondsp.launcher.CarFunction
import app.siphondsp.launcher.HomeSlots
import app.siphondsp.launcher.InstalledApps
import app.siphondsp.launcher.Launch
import app.siphondsp.launcher.SlotTarget
import app.siphondsp.compose.home.HomeStage
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.OutputActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.databinding.FragmentDspBinding
import app.siphondsp.databinding.FragmentDspPageSettingsBinding
import app.siphondsp.databinding.FragmentDspPageShortcutsBinding
import app.siphondsp.model.MasterVolume
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.preferences.Preferences
import app.siphondsp.view.HomeLevelFeed
import app.siphondsp.view.LevelReadout
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

    /**
     * The DSP tile being opened: its glow is lit, and every other front-page tap is ignored, from the
     * tap until this page stops (the DSP screen covers it) or the launch is cancelled. Null otherwise.
     */
    private var openingTile by mutableStateOf<HomeStage?>(null)

    /** The launch waiting out the glow flash; cancelled if the user goes anywhere else first. */
    private var pendingOpen: Job? = null

    /** Whether the signal chain is raised out of the DSP bar. */
    private var chainRaised by mutableStateOf(false)

    /** The app drawer or car menu, when one is open over the home screen. */
    private var overlay by mutableStateOf<LauncherOverlay>(LauncherOverlay.None)

    /** The held output levels, about 5 a second, for the numbers: headroom and held peaks. */
    private var levelReadout by mutableStateOf(LevelReadout.SILENT)

    /** The same levels every frame, for the LED rows; read only while drawing. */
    private var levelFrame by mutableStateOf(LevelReadout.SILENT)

    /** Feeds [levelReadout] while the front page is live; see [updateLevelFeed]. */
    private var levelFeed: HomeLevelFeed? = null
    private var resumed = false
    private var homePageActive = true

    /** Whether the front page is on screen, so its tile loops run; see [updateLevelFeed]. */
    private var homeLive by mutableStateOf(false)

    /** The stages, chips and meter ceiling, from the saved DSP values; see [refreshValues]. */
    private var engineState by mutableStateOf(HomeEngineState.OFF)

    /**
     * Re-reads [engineState] whenever settings change underneath the
     * front page while it stays resumed: a preset or backup loaded, a Revert from the overflow
     * menu, or any DSP edit.
     */
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshValues(intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES))
        }
    }

    /** MainActivity's real power state. Off until the processor service reports it running. */
    private var powerOn by mutableStateOf(false)

    /**
     * Called with the artwork front page's horizontal offset in px as the pager moves it (0 =
     * settled on it, +-page width = fully off screen), so the activity-level overlay laid over
     * that art can move with it instead of staying put mid-swipe.
     */
    var onHomePageOffset: ((Float) -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onMoreClick: ((View) -> Unit)? = null
    /** The power node was tapped; MainActivity owns the engine and reports back via [setPowerState]. */
    var onPowerClick: (() -> Unit)? = null

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
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
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
        resumed = true
        // Coming back from a DSP screen; in-place changes arrive via settingsReceiver.
        refreshValues(null)
        // Re-assert the current page after a restore, where onPageSelected doesn't fire.
        onPageSelectedInternal(binding.dspPager.currentItem)
        // Posted: after a restore the pager may not be laid out yet, and a 0 width would put the
        // overlay back over the settings page.
        binding.dspPager.post { reportHomePageOffset(binding.dspPager.currentItem * binding.dspPager.width) }
    }

    override fun onPause() {
        super.onPause()
        // Something else is coming to the front (a GLOBAL STAGES cell, Settings, another app): a
        // tile launch still waiting out its flash must not open over it.
        cancelPendingOpen()
        resumed = false
        updateLevelFeed()
    }

    override fun onDestroyView() {
        levelFeed?.running = false
        levelFeed = null
        requireContext().unregisterLocalReceiver(settingsReceiver)
        super.onDestroyView()
    }

    override fun onStop() {
        super.onStop()
        // The DSP screen (or whatever else) now covers this page, so taps can't reach it: release
        // the guard. The glow fades out as the page comes back.
        openingTile = null
    }

    private fun onPageSelectedInternal(position: Int) {
        // The artwork page stays attached while off screen, so live/polled home widgets need to
        // stop work when the settings page is selected.
        val active = position == 0
        homePageActive = active
        updateLevelFeed()
        if (!active) cancelPendingOpen()
    }

    /**
     * Runs the level feed (and so the analyzer) and the tiles' loops only while the front page is
     * resumed and the pager is on the artwork page: it stays attached and "visible" when swiped
     * away or covered.
     */
    private fun updateLevelFeed() {
        homeLive = resumed && homePageActive
        levelFeed?.running = homeLive
    }

    /** [scrolledPx] is how far the pager has scrolled past the artwork page, in reading order. */
    private fun reportHomePageOffset(scrolledPx: Int) {
        val rtl = binding.dspPager.layoutDirection == View.LAYOUT_DIRECTION_RTL
        onHomePageOffset?.invoke(if (rtl) scrolledPx.toFloat() else -scrolledPx.toFloat())
    }

    private fun setUpShortcutsPage() {
        val slots = HomeSlots.get(requireContext())
        val installed = InstalledApps.get(requireContext())
        shortcutsBinding.homeScreen.setHomeContent {
            val apps by installed.apps.collectAsState()
            val slotList by slots.slots.collectAsState()
            LaunchedEffect(apps) { apps?.let(slots::fillDefaults) }
            val carFunctions by produceState(emptyList<CarFunction>(), apps) {
                val context = requireContext().applicationContext
                repeat(CAR_CHECK_TRIES) { attempt ->
                    value = withContext(Dispatchers.IO) { CarFunction.entries.filter { it.isAvailable(context) } }
                    // The commands need wits_pms, which can start after the home screen at boot.
                    if (value.any { it.command != null } || attempt == CAR_CHECK_TRIES - 1) return@produceState
                    delay(CAR_CHECK_RETRY_MS)
                }
            }
            LauncherHome(
                engine = engineState,
                powered = powerOn,
                readout = { levelReadout },
                meterLevels = { levelFrame },
                apps = apps,
                slots = slotList,
                carFunctions = carFunctions,
                expanded = chainRaised,
                onExpandedChange = ::raiseChain,
                overlay = overlay,
                onOverlayChange = ::showOverlay,
                openingStage = openingTile,
                animateTiles = homeLive && chainRaised,
                actions = launcherActions,
            )
        }
        levelFeed = HomeLevelFeed(onFrame = { levelFrame = it }, onReadout = { levelReadout = it })
        requireContext().registerLocalReceiver(
            settingsReceiver,
            IntentFilter().apply {
                addAction(Constants.ACTION_PRESET_LOADED)
                addAction(Constants.ACTION_BACKUP_RESTORED)
                addAction(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
            },
        )
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

    private fun raiseChain(raised: Boolean) {
        chainRaised = raised
        updatePagerInput()
    }

    private fun showOverlay(next: LauncherOverlay) {
        overlay = next
        updatePagerInput()
    }

    /** The pager's swipe to the settings page is off while anything is raised over the home screen. */
    private fun updatePagerInput() {
        if (::binding.isInitialized) binding.dspPager.isUserInputEnabled = !chainRaised && overlay == LauncherOverlay.None
    }

    /**
     * The Home button (MENU, short press) while already home: back to the plain home screen, with
     * the chain lowered and any drawer or menu closed.
     */
    fun onHomePressed() {
        cancelPendingOpen()
        showOverlay(LauncherOverlay.None)
        raiseChain(false)
        if (::binding.isInitialized) binding.dspPager.setCurrentItem(0, false)
    }

    /**
     * Back closes whatever is raised, then returns from the settings page. On the plain home screen
     * it does nothing while J_DSP is the home screen (see [isHome]: there is nowhere to go back to);
     * opened from another launcher, it closes as before.
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                overlay != LauncherOverlay.None -> showOverlay(LauncherOverlay.None)
                chainRaised -> raiseChain(false)
                binding.dspPager.currentItem != 0 -> binding.dspPager.currentItem = 0
                !isHome() -> requireActivity().finish()
            }
        }
    }

    /**
     * Whether this is the home screen: J_DSP is the default home app, or no default is chosen yet
     * (Android's chooser resolves, from the "android" package), when J_DSP may be what Home opens.
     */
    private fun isHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = requireContext().packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return current == null || current == requireContext().packageName || current == "android"
    }

    private val launcherActions = object : LauncherActions {
        override fun togglePower() { if (openingTile == null) onPowerClick?.invoke() }
        override fun openStage(stage: HomeStage) = this@DspFragment.openStage(stage)
        override fun openGlobal(stage: GlobalStage) = this@DspFragment.openGlobal(stage)
        override fun dspSettings() { if (openingTile == null) onSettingsClick?.invoke() }
        override fun more(bounds: Rect) = openMore(bounds)
        // The DSP master volume: same path as the steering-wheel buttons, without the toast (the
        // level is on screen); the saved value comes back through settingsReceiver.
        override fun volumeStep(steps: Int) { MasterVolume.step(requireContext(), steps) }
        override fun toggleMute() { MasterVolume.setMuted(requireContext(), null) }
        override fun launchApp(app: AppEntry) = Launch.component(requireContext(), app.component)
        override fun runCar(function: CarFunction) = function.run(requireContext())
        override fun assignSlot(index: Int, target: SlotTarget) = HomeSlots.get(requireContext()).set(index, target)
        override fun swapSlots(a: Int, b: Int) = HomeSlots.get(requireContext()).swap(a, b)
        override fun appInfo(app: AppEntry) {
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + app.component.packageName).toUri())
            Launch.start(requireContext(), details)
        }
        override fun androidSettings() { Launch.start(requireContext(), Intent(Settings.ACTION_SETTINGS)) }
        override fun homeAppSettings() { Launch.start(requireContext(), Intent(Settings.ACTION_HOME_SETTINGS)) }
        override fun homeFull() { Toast.makeText(requireContext(), R.string.launcher_home_full, Toast.LENGTH_LONG).show() }
    }

    /**
     * Opens a DSP screen from its tile in the raised chain: the tile's glow flashes on for
     * [TILE_FLASH_MS] so the tap reads as confirmed, then the screen opens. With animations turned
     * off in system settings it opens at once.
     *
     * Other tile, chip, power, Settings and More taps are ignored from the tap until this page
     * stops (see [openingTile]), not just during the flash, so a quick second tap can't open a
     * second screen underneath. Leaving the page before the screen opens (swiping to the settings
     * page, or anything pausing it) cancels the launch, so the DSP screen never opens over where
     * the user went.
     */
    private fun openStage(stage: HomeStage) {
        if (openingTile != null) return
        val intent = when (stage) {
            HomeStage.PEQ -> Intent(requireContext(), ParametricEqualizerActivity::class.java)
            HomeStage.ALIGNMENT -> Intent(requireContext(), GainLimiterActivity::class.java)
            HomeStage.XOVERS -> Intent(requireContext(), CrossoverTiltActivity::class.java)
            HomeStage.DYNAMICS -> Intent(requireContext(), NativeBmwCompressorActivity::class.java)
            HomeStage.OUTPUT -> Intent(requireContext(), OutputActivity::class.java)
        }
        openingTile = stage
        pendingOpen = viewLifecycleOwner.lifecycleScope.launch {
            if (ValueAnimator.areAnimatorsEnabled()) delay(TILE_FLASH_MS)
            startActivity(intent)
        }
    }

    /** A global stage's chip: opens the screen that owns the stage, as the workspace toolbar does. */
    private fun openGlobal(stage: GlobalStage) {
        if (openingTile != null) return
        val intent = when (stage) {
            GlobalStage.TILT -> Intent(requireContext(), CrossoverTiltActivity::class.java)
                .putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_CROSSOVER)
            GlobalStage.MBC -> Intent(requireContext(), NativeBmwCompressorActivity::class.java)
            // The master limiter lives on Output; ALLPASS opens the Alignment pager on its page.
            GlobalStage.LIMITER -> Intent(requireContext(), OutputActivity::class.java)
            GlobalStage.ALLPASS -> Intent(requireContext(), GainLimiterActivity::class.java)
                .putExtra(GainLimiterActivity.EXTRA_PAGE, GainLimiterFragment.PAGE_ALLPASS)
        }
        startActivity(intent)
    }

    /** Opens MainActivity's overflow menu, anchored on the More button ([bounds]). */
    private fun openMore(bounds: Rect) {
        if (openingTile != null) return
        val anchor = shortcutsBinding.homeMoreAnchor
        anchor.translationX = bounds.left
        anchor.translationY = bounds.bottom
        onMoreClick?.invoke(anchor)
    }

    /** Drops a tile launch that has not opened yet (still in its flash), and the glow with it. */
    private fun cancelPendingOpen() {
        if (pendingOpen?.isActive == true) {
            pendingOpen?.cancel()
            openingTile = null
        }
        pendingOpen = null
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

    /**
     * Re-reads what the front page shows from settings (stage pills, global chips, the meter's
     * ceiling). From [fromBroadcast] or the saved values.
     */
    private fun refreshValues(fromBroadcast: FloatArray?) {
        val values = fromBroadcast?.takeIf { it.size == NativeBmwDspValues.SIZE }
            ?: NativeBmwDspValues.load(requireContext())
        engineState = HomeEngineState.from(values)
    }

    /**
     * Keeps the front page in step with MainActivity's real power state. [HomeScreen] animates the
     * change itself: the power node, signal path, meter and chips fade between lit and bypassed.
     */
    fun setPowerState(on: Boolean) {
        powerOn = on
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
        private const val TILE_FLASH_MS = 80L
        private const val CAR_CHECK_TRIES = 6
        private const val CAR_CHECK_RETRY_MS = 5_000L
        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}
