package app.siphondsp.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import app.siphondsp.R
import app.siphondsp.adapter.RoundedRipplePreferenceGroupAdapter
import app.siphondsp.preference.MaterialSeekbarPreference
import app.siphondsp.preference.NativeBmwDspResponsePreference
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast

/** Inline, expandable controls for the native BMW processor. */
class NativeBmwDspCardFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var menuPreferences: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MENU_PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

        menuPreferences = requireContext().getSharedPreferences(MENU_PREFS, Context.MODE_PRIVATE)
        val values = loadValues()
        writeValuesToMenu(values)
        setPreferencesFromResource(R.xml.dsp_native_bmw_preferences, rootKey)
        configureFractionalSteps()
        updateResponseVisualiser(values)
    }

    private fun configureFractionalSteps() {
        STEP_TENTH_KEYS.forEach { key ->
            findPreference<MaterialSeekbarPreference>(key)?.setSeekBarIncrement(.1f)
        }
        STEP_HUNDREDTH_KEYS.forEach { key ->
            findPreference<MaterialSeekbarPreference>(key)?.setSeekBarIncrement(.01f)
        }
    }

    private fun updateResponseVisualiser(values: FloatArray) {
        findPreference<NativeBmwDspResponsePreference>(KEY_LIVE_RESPONSE)?.setValues(values)
    }

    override fun onStart() {
        super.onStart()
        menuPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        menuPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val index = KEY_TO_INDEX[key] ?: return
        val values = loadValues()
        values[index] = when {
            index in BOOLEAN_INDEXES -> if (menuPreferences.getBoolean(key, false)) 1f else 0f
            index in LIST_INDEXES -> menuPreferences.getString(key, "0")?.toFloatOrNull() ?: 0f
            else -> menuPreferences.getFloat(key, values[index])
        }
        saveAndApply(values)
    }

    private fun writeValuesToMenu(values: FloatArray) {
        val editor = menuPreferences.edit()
        KEY_TO_INDEX.forEach { (key, index) ->
            when {
                index in BOOLEAN_INDEXES -> editor.putBoolean(key, values[index] >= .5f)
                index in LIST_INDEXES -> editor.putString(key, values[index].toInt().toString())
                else -> editor.putFloat(key, values[index])
            }
        }
        editor.apply()
    }

    private fun saveAndApply(values: FloatArray) {
        requireContext().getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(NATIVE_VALUES_KEY, values.joinToString(",")).apply()
        updateResponseVisualiser(values)
        requireContext().sendLocalBroadcast(
            Intent(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
                .putExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES, values)
        )
    }

    private fun loadValues(): FloatArray {
        val saved = requireContext().getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
            .getString(NATIVE_VALUES_KEY, null)
        val parsed = saved?.split(',')?.mapNotNull(String::toFloatOrNull)?.toFloatArray()
        return if (parsed?.size == NativeBmwDspBottomSheet.DEFAULTS.size) parsed
        else NativeBmwDspBottomSheet.DEFAULTS.copyOf()
    }

    override fun onCreateRecyclerView(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
        itemAnimator = null
        isNestedScrollingEnabled = false
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        RoundedRipplePreferenceGroupAdapter(preferenceScreen)

    companion object {
        private const val MENU_PREFS = "native_bmw_dsp_menu"
        private const val NATIVE_PREFS = "native_bmw_dsp"
        private const val NATIVE_VALUES_KEY = "values"
        private const val KEY_LIVE_RESPONSE = "bmw_live_response"

        private val BOOLEAN_INDEXES = setOf(0, 1, 2, 12, 14, 17, 19, 20, 25, 28)
        private val LIST_INDEXES = setOf(3, 4, 16)
        private val STEP_TENTH_KEYS = setOf(
            "bmw_low_gain_l",
            "bmw_low_gain_r",
            "bmw_mid_gain_l",
            "bmw_mid_gain_r",
            "bmw_post_gain_l",
            "bmw_post_gain_r",
            "bmw_tilt_amount",
            "bmw_comp_ratio",
            "bmw_comp_makeup",
        )
        private val STEP_HUNDREDTH_KEYS = setOf(
            "bmw_mid_delay_l",
            "bmw_mid_delay_r",
            "bmw_low_delay_l",
            "bmw_low_delay_r",
        )
        private val KEY_TO_INDEX = linkedMapOf(
            "native_bmw_dsp_enable" to 0,
            "bmw_lpf_passthrough" to 1,
            "bmw_hpf_passthrough" to 2,
            "bmw_channel_isolation" to 3,
            "bmw_measurement_mute" to 4,
            "bmw_headroom" to 5,
            "bmw_low_gain_l" to 6,
            "bmw_low_gain_r" to 7,
            "bmw_mid_gain_l" to 8,
            "bmw_mid_gain_r" to 9,
            "bmw_post_gain_l" to 10,
            "bmw_post_gain_r" to 11,
            "bmw_subsonic_enable" to 12,
            "bmw_subsonic_freq" to 13,
            "bmw_mute_low" to 14,
            "bmw_low_lpf" to 15,
            "bmw_low_topology" to 16,
            "bmw_mute_mid" to 17,
            "bmw_mid_hpf" to 18,
            "bmw_invert_low" to 19,
            "bmw_invert_mid" to 20,
            "bmw_mid_delay_l" to 21,
            "bmw_mid_delay_r" to 22,
            "bmw_low_delay_l" to 23,
            "bmw_low_delay_r" to 24,
            "bmw_tilt_enable" to 25,
            "bmw_tilt_amount" to 26,
            "bmw_tilt_pivot" to 27,
            "bmw_comp_enable" to 28,
            "bmw_comp_threshold" to 29,
            "bmw_comp_ratio" to 30,
            "bmw_comp_knee" to 31,
            "bmw_comp_attack" to 32,
            "bmw_comp_release" to 33,
            "bmw_comp_makeup" to 34,
        )

        fun newInstance() = NativeBmwDspCardFragment()
    }
}
