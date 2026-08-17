package app.siphondsp.fragment

import android.animation.LayoutTransition
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import app.siphondsp.view.StaticPagerAdapter
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

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
        binding.dspPager.adapter = StaticPagerAdapter(
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

        // Primary BMW DSP shortcuts. Each card's background is one of the pre-built tile images
        // set directly in the XML, with a text label view drawn on top -- only the click targets
        // are wired here. These open the same activities the old bottom bar icons did; Routing
        // is a new entry that only used to be reachable via the DSP workspace sidebar. Settings
        // moved back to the bottom bar's gear icon, so there's no System tile anymore.
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
        shortcutsBinding.cardShortcutRouting.setOnClickListener {
            startActivity(
                Intent(requireContext(), CrossoverTiltActivity::class.java)
                    .putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_ROUTING),
            )
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
