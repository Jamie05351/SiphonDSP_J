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

        binding.translationNotice.setOnCloseClickListener(::hideTranslationNotice)
        binding.translationNotice.setOnRootClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/project/siphondsp")))
            hideTranslationNotice()
        }

        binding.updateNotice.setOnCloseClickListener {
            updateNoticeOnCloseClick?.invoke()
        }
        binding.updateNotice.setOnRootClickListener {
            updateNoticeOnClick?.invoke()
        }

        // Give the primary DSP destinations the large image-backed dashboard treatment used on
        // the head-unit concept. The XML keeps the stable IDs/click targets; only the visual
        // contents are replaced here so navigation and DSP behavior remain untouched.
        decorateDestinationCard(
            binding.cardShortcutPeq,
            R.drawable.card_peq_e60,
            R.drawable.ic_twotone_peq_sliders_28dp,
            "PEQ",
        )
        decorateDestinationCard(
            binding.cardShortcutGainsDelay,
            R.drawable.card_gains_delay_e60,
            R.drawable.ic_twotone_gain_knob_28dp,
            "GAINS / DELAY",
        )
        decorateDestinationCard(
            binding.cardShortcutCompressor,
            R.drawable.card_compressor_e60,
            R.drawable.ic_twotone_compressor_pulse_28dp,
            "COMPRESSOR",
        )
        decorateDestinationCard(
            binding.cardShortcutCrossovers,
            R.drawable.card_crossovers_e60,
            R.drawable.ic_twotone_crossover_tilt_28dp,
            "CROSSOVERS",
        )

        // Primary BMW DSP shortcuts. These open the same activities as the existing nav/menu
        // actions; the cards are only an additional main-menu entry point.
        binding.cardShortcutPeq.setOnClickListener {
            startActivity(Intent(requireContext(), ParametricEqualizerActivity::class.java))
        }
        binding.cardShortcutGainsDelay.setOnClickListener {
            startActivity(Intent(requireContext(), GainLimiterActivity::class.java))
        }
        binding.cardShortcutCompressor.setOnClickListener {
            startActivity(Intent(requireContext(), NativeBmwCompressorActivity::class.java))
        }
        binding.cardShortcutCrossovers.setOnClickListener {
            startActivity(Intent(requireContext(), CrossoverTiltActivity::class.java))
        }

        // Should show notice?
        Timber.e(Locale.getDefault().language.toString())
        binding.translationNotice.isVisible =
           prefsVar.get<Long>(R.string.key_snooze_translation_notice) < (System.currentTimeMillis() / 1000L) &&
                    !Locale.getDefault().language.equals("en")
        binding.updateNotice.isVisible = false

        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        binding.cardContainer.layoutTransition = transition

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

        return binding.root
    }

    private fun decorateDestinationCard(
        card: MaterialCardView,
        backgroundRes: Int,
        iconRes: Int,
        label: String,
    ) {
        card.layoutParams = card.layoutParams.apply {
            width = dp(250)
            // Keep the dashboard cards proportional to the actual display rather than assuming
            // that a dp value maps directly to the head unit's physical pixels. A 480px-tall
            // display gets cards around 278px high.
            height = (resources.displayMetrics.heightPixels * 0.58f).roundToInt()
        }
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
                (binding.cardDeviceProfiles.parent as ViewGroup).isVisible =
                    prefsApp.get<Boolean>(R.string.key_device_profiles_enable)
            }
        }
    }

    private fun hideTranslationNotice() {
        binding.translationNotice.isVisible = false
        // Set timer +1y
        prefsVar.set<Long>(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 31536000L)
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).roundToInt()

    fun setUpdateCardVisible(visible: Boolean) {
        binding.updateNotice.isVisible = visible
    }

    fun setUpdateCardTitle(title: String) {
        binding.updateNotice.titleText = title
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

    companion object {
        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}
