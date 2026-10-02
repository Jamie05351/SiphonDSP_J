package app.siphondsp.fragment

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import app.siphondsp.compose.controls.HomeFaceplate
import app.siphondsp.compose.controls.HomeLevelReadout
import app.siphondsp.compose.controls.HomeTile
import app.siphondsp.compose.controls.HomeTileKind
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.databinding.FragmentDspBinding
import app.siphondsp.databinding.FragmentDspPageSettingsBinding
import app.siphondsp.databinding.FragmentDspPageShortcutsBinding
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.preferences.Preferences
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
    private var openingTile by mutableStateOf<HomeTileKind?>(null)

    /** The launch waiting out the glow flash; cancelled if the user goes anywhere else first. */
    private var pendingOpen: Job? = null

    /** The top screen's level readout, fed by the LED meter while the front page is live. */
    private var levelReadout by mutableStateOf(LevelReadout.SILENT)

    /** The limiter threshold while the limiter is on, else null; see [refreshLimiter]. */
    private var limiterDb by mutableStateOf<Float?>(null)

    /**
     * Re-reads the limiter whenever settings change underneath the front page while it stays
     * resumed: a preset or backup loaded, a Revert from the overflow menu, or any DSP edit.
     */
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshLimiter(intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES))
        }
    }

    /** MainActivity's real power state. Off until the processor service reports it running. */
    private var powerOn = false

    /** How "on" the front page looks: 1 in full colour, 0 greyed out (see [setPowerLook]). */
    private var powerLook = 1f
    private var powerAnimator: ValueAnimator? = null

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
        // Coming back from the limiter's screen; in-place changes arrive via settingsReceiver.
        refreshLimiter(null)
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
    }

    override fun onDestroyView() {
        requireContext().unregisterLocalReceiver(settingsReceiver)
        powerAnimator?.cancel()
        powerAnimator = null
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
        shortcutsBinding.homeLevelBars.pageActive = active
        if (!active) cancelPendingOpen()
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
        shortcutsBinding.homeLevelReadout.setHomeContent { HomeLevelReadout(levelReadout, limiterDb) }
        shortcutsBinding.homeLevelBars.onReadout = { levelReadout = it }
        requireContext().registerLocalReceiver(
            settingsReceiver,
            IntentFilter().apply {
                addAction(Constants.ACTION_PRESET_LOADED)
                addAction(Constants.ACTION_BACKUP_RESTORED)
                addAction(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
            },
        )
        setPowerLook(if (powerOn) 1f else 0f)
        shortcutsBinding.cardShortcutPeq.setHomeContent { HomeTile(HomeTileKind.PEQ, selected = openingTile == HomeTileKind.PEQ) }
        shortcutsBinding.cardShortcutGainsDelay.setHomeContent { HomeTile(HomeTileKind.GAINS, selected = openingTile == HomeTileKind.GAINS) }
        shortcutsBinding.cardShortcutCrossovers.setHomeContent { HomeTile(HomeTileKind.XOVERS, selected = openingTile == HomeTileKind.XOVERS) }
        shortcutsBinding.cardShortcutCompressor.setHomeContent { HomeTile(HomeTileKind.COMPRESSOR, selected = openingTile == HomeTileKind.COMPRESSOR) }
        shortcutsBinding.cardShortcutAllpass.setHomeContent { HomeTile(HomeTileKind.ALLPASS, selected = openingTile == HomeTileKind.ALLPASS) }
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

        // Seven primary home actions. The first five open DSP workspaces; Settings and More
        // delegate to MainActivity so its existing settings/overflow behaviour remains the single
        // source of truth. The power button remains activity-owned because it controls the engine.
        shortcutsBinding.cardShortcutPeq.setOnClickListener { tile ->
            openFromTile(HomeTileKind.PEQ, tile, Intent(requireContext(), ParametricEqualizerActivity::class.java))
        }
        shortcutsBinding.cardShortcutGainsDelay.setOnClickListener { tile ->
            openFromTile(HomeTileKind.GAINS, tile, Intent(requireContext(), GainLimiterActivity::class.java))
        }
        shortcutsBinding.cardShortcutCompressor.setOnClickListener { tile ->
            openFromTile(HomeTileKind.COMPRESSOR, tile, Intent(requireContext(), NativeBmwCompressorActivity::class.java))
        }
        shortcutsBinding.cardShortcutCrossovers.setOnClickListener { tile ->
            openFromTile(HomeTileKind.XOVERS, tile, Intent(requireContext(), CrossoverTiltActivity::class.java))
        }
        shortcutsBinding.cardShortcutAllpass.setOnClickListener { tile ->
            openFromTile(
                HomeTileKind.ALLPASS,
                tile,
                Intent(requireContext(), CrossoverTiltActivity::class.java)
                    .putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_ALLPASS),
            )
        }
        shortcutsBinding.cardShortcutSettings.setOnClickListener {
            if (openingTile == null) onSettingsClick?.invoke()
        }
        shortcutsBinding.homeStages.canOpen = { openingTile == null }
        shortcutsBinding.cardShortcutMore.setOnClickListener { anchor ->
            if (openingTile == null) onMoreClick?.invoke(anchor)
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

    /**
     * Opens a DSP screen from its front-page tile: the tile's glow flashes on for [TILE_FLASH_MS]
     * so the tap reads as confirmed, then the screen zooms open out of the tile's own rect. With
     * animations turned off in system settings there is no flash wait, and the platform skips the
     * zoom.
     *
     * Other tile, Settings, More and GLOBAL STAGES taps are ignored from the tap until this page
     * stops (see [openingTile]), not just during the flash, so a quick second tap can't open a
     * second screen underneath. Leaving the page during the flash (swiping to the settings page,
     * or anything pausing it) cancels the launch, so the DSP screen never opens over where the
     * user went.
     */
    private fun openFromTile(kind: HomeTileKind, tile: View, intent: Intent) {
        if (openingTile != null) return
        openingTile = kind
        pendingOpen = viewLifecycleOwner.lifecycleScope.launch {
            if (ValueAnimator.areAnimatorsEnabled()) delay(TILE_FLASH_MS)
            val zoom = ActivityOptions.makeScaleUpAnimation(tile, 0, 0, tile.width, tile.height)
            startActivity(intent, zoom.toBundle())
        }
    }

    /** Drops a tile launch that is still waiting out its flash, and the tile's glow with it. */
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

    /** The limiter threshold while it is on, else null, from [fromBroadcast] or the saved values. */
    private fun refreshLimiter(fromBroadcast: FloatArray?) {
        val values = fromBroadcast?.takeIf { it.size == NativeBmwDspValues.SIZE }
            ?: NativeBmwDspValues.load(requireContext())
        limiterDb = values[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD]
            .takeIf { values[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED] >= 0.5f }
    }

    /**
     * Keeps the front page in step with MainActivity's real power state: while the DSP is off the
     * live data (the LED meter, GLOBAL STAGES and the level readout) fades to grey, so it is obvious
     * at a glance. The stage cells stay tappable. The tiles, Settings and More keep their colour.
     */
    fun setPowerState(on: Boolean) {
        if (on == powerOn) return
        powerOn = on
        if (!::shortcutsBinding.isInitialized || view == null) return
        val target = if (on) 1f else 0f
        powerAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setPowerLook(target)
            return
        }
        powerAnimator = ValueAnimator.ofFloat(powerLook, target).apply {
            duration = POWER_FADE_MS
            addUpdateListener { setPowerLook(it.animatedValue as Float) }
            start()
        }
    }

    /**
     * [look] 1 draws the powered views normally; below that each is drawn through a hardware layer
     * whose paint drains its colour (saturation [look]) and fades it towards [OFF_ALPHA].
     */
    private fun setPowerLook(look: Float) {
        powerLook = look
        val views = with(shortcutsBinding) { listOf(homeLevelBars, homeStages, homeLevelReadout) }
        if (look >= 1f) {
            views.forEach {
                it.setLayerType(View.LAYER_TYPE_NONE, null)
                it.alpha = 1f
            }
            return
        }
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(look) })
        }
        views.forEach {
            if (it.layerType == View.LAYER_TYPE_HARDWARE) {
                it.setLayerPaint(paint)
            } else {
                it.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            }
            it.alpha = OFF_ALPHA + (1f - OFF_ALPHA) * look
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
        private const val TILE_FLASH_MS = 150L
        private const val POWER_FADE_MS = 250L
        /** How visible the live data stays while the DSP is off: dimmed, but still readable. */
        private const val OFF_ALPHA = 0.45f

        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}
