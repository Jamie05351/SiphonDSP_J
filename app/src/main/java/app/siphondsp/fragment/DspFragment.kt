package app.siphondsp.fragment

import android.animation.LayoutTransition
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.databinding.FragmentDspBinding
import app.siphondsp.databinding.FragmentDspPageSettingsBinding
import app.siphondsp.databinding.FragmentDspPageShortcutsBinding
import app.siphondsp.utils.Constants
import app.siphondsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

class DspFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefsApp: Preferences.App by inject()
    private val prefsVar: Preferences.Var by inject()

    private lateinit var binding: FragmentDspBinding
    private lateinit var shortcutsBinding: FragmentDspPageShortcutsBinding
    private lateinit var settingsBinding: FragmentDspPageSettingsBinding
    private var updateNoticeOnClick: (() -> Unit)? = null
    private var updateNoticeOnCloseClick: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

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
        binding.dspPager.adapter = DspPagerAdapter(
            pages = listOf(shortcutsBinding.root, settingsBinding.root),
            onPageAttached = { position -> if (position == 1) setUpSettingsPage() },
        )

        return binding.root
    }

    private fun setUpShortcutsPage() {
        shortcutsBinding.translationNotice.setOnCloseClickListener(::hideTranslationNotice)
        shortcutsBinding.translationNotice.setOnRootClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/project/siphondsp")))
            hideTranslationNotice()
        }

        shortcutsBinding.updateNotice.setOnCloseClickListener {
            updateNoticeOnCloseClick?.invoke()
        }
        shortcutsBinding.updateNotice.setOnRootClickListener {
            updateNoticeOnClick?.invoke()
        }

        // Give the primary DSP destinations the large image-backed dashboard treatment used on
        // the head-unit concept. The XML keeps the stable IDs/click targets; only the visual
        // contents are replaced here so navigation and DSP behavior remain untouched.
        decorateDestinationCard(
            shortcutsBinding.cardShortcutPeq,
            R.drawable.card_peq_e60,
            R.drawable.ic_twotone_peq_sliders_28dp,
            "PEQ",
        )
        decorateDestinationCard(
            shortcutsBinding.cardShortcutGainsDelay,
            R.drawable.card_gains_delay_e60,
            R.drawable.ic_twotone_gain_knob_28dp,
            "GAINS / DELAY",
        )
        decorateDestinationCard(
            shortcutsBinding.cardShortcutCompressor,
            R.drawable.card_compressor_e60,
            R.drawable.ic_twotone_compressor_pulse_28dp,
            "COMPRESSOR",
        )
        decorateDestinationCard(
            shortcutsBinding.cardShortcutCrossovers,
            R.drawable.card_crossovers_e60,
            R.drawable.ic_twotone_crossover_tilt_28dp,
            "CROSSOVERS",
        )

        // Primary BMW DSP shortcuts. These open the same activities as the existing nav/menu
        // actions; the cards are only an additional main-menu entry point.
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

    private fun setUpSettingsPage() {
        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        settingsBinding.cardContainer.layoutTransition = transition

        childFragmentManager.beginTransaction()
            .replace(R.id.card_device_profiles, DeviceProfilesCardFragment.newInstance())
            .replace(
                R.id.card_output_control, PreferenceGroupFragment.newInstance(Constants.PREF_OUTPUT,
                    R.xml.dsp_output_control_preferences
                ))
            .replace(
                R.id.card_eq, PreferenceGroupFragment.newInstance(Constants.PREF_EQ,
                    R.xml.dsp_equalizer_preferences
                ))
            .replace(R.id.card_bmw_dsp, NativeBmwDspCardFragment.newInstance())
            .replace(
                R.id.card_ddc, PreferenceGroupFragment.newInstance(Constants.PREF_DDC,
                    R.xml.dsp_ddc_preferences
                ))
            .replace(
                R.id.card_convolver, PreferenceGroupFragment.newInstance(Constants.PREF_CONVOLVER,
                    R.xml.dsp_convolver_preferences
                ))
            .replace(
                R.id.card_liveprog, PreferenceGroupFragment.newInstance(Constants.PREF_LIVEPROG,
                    R.xml.dsp_liveprog_preferences
                ))
            .replace(
                R.id.card_stereowide, PreferenceGroupFragment.newInstance(Constants.PREF_STEREOWIDE,
                    R.xml.dsp_stereowide_preferences
                ))
            .commit()

        // Load initial preferences
        arrayOf(R.string.key_device_profiles_enable).forEach {
            onSharedPreferenceChanged(null, getString(it))
        }
    }

    private fun decorateDestinationCard(
        card: MaterialCardView,
        backgroundRes: Int,
        iconRes: Int,
        label: String,
    ) {
        card.removeAllViews()

        val frame = FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        frame.addView(ImageView(requireContext()).apply {
            setImageResource(backgroundRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        frame.addView(View(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_dsp_destination_overlay)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        frame.addView(ImageView(requireContext()).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            contentDescription = label
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER).apply {
            topMargin = -dp(18)
        })

        frame.addView(TextView(requireContext()).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (label == "PEQ") 19f else 17f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            setShadowLayer(5f, 0f, 2f, Color.BLACK)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(48),
            Gravity.BOTTOM,
        ).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
            bottomMargin = dp(8)
        })

        card.addView(frame)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            getString(R.string.key_device_profiles_enable) -> {
                (settingsBinding.cardDeviceProfiles.parent as ViewGroup).isVisible =
                    prefsApp.get<Boolean>(R.string.key_device_profiles_enable)
            }
        }
    }

    private fun hideTranslationNotice() {
        shortcutsBinding.translationNotice.isVisible = false
        // Set timer +1y
        prefsVar.set<Long>(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 31536000L)
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).roundToInt()

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
        CoroutineScope(Dispatchers.Main).launch {
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

    /**
     * Wraps a fixed set of pre-inflated page views (one per position) for [binding.dspPager].
     * There are only ever two static pages here, so this skips Fragment-per-page machinery and
     * just hands ViewPager2's RecyclerView the already-built view for each position.
     */
    private class DspPagerAdapter(
        private val pages: List<View>,
        private val onPageAttached: (position: Int) -> Unit,
    ) : RecyclerView.Adapter<DspPagerAdapter.PageViewHolder>() {
        private val attachedPositions = mutableSetOf<Int>()

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemCount() = pages.size

        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PageViewHolder(pages[viewType])

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {}

        override fun onViewAttachedToWindow(holder: PageViewHolder) {
            super.onViewAttachedToWindow(holder)
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION && attachedPositions.add(position)) {
                onPageAttached(position)
            }
        }
    }

    companion object {
        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}
