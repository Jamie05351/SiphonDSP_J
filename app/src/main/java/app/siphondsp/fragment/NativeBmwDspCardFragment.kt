package app.siphondsp.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import app.siphondsp.R
import app.siphondsp.adapter.RoundedRipplePreferenceGroupAdapter
import app.siphondsp.preference.SwitchPreferenceGroup
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast

/** Inline, expandable main DSP-page entry point for the native BMW processor. */
class NativeBmwDspCardFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var menuPreferences: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MENU_PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.dsp_native_bmw_preferences, rootKey)

        menuPreferences = requireContext().getSharedPreferences(MENU_PREFS, Context.MODE_PRIVATE)
        val enabled = loadValues()[0] >= .5f
        findPreference<SwitchPreferenceGroup>(KEY_ENABLE)?.setValue(enabled)

        findPreference<Preference>(KEY_OPEN)?.setOnPreferenceClickListener {
            if (parentFragmentManager.findFragmentByTag(NativeBmwDspBottomSheet.TAG) == null) {
                NativeBmwDspBottomSheet().show(parentFragmentManager, NativeBmwDspBottomSheet.TAG)
            }
            true
        }
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
        if (key != KEY_ENABLE) return
        val values = loadValues()
        values[0] = if (menuPreferences.getBoolean(KEY_ENABLE, true)) 1f else 0f
        requireContext().getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(NATIVE_VALUES_KEY, values.joinToString(","))
            .apply()
        requireContext().sendLocalBroadcast(
            Intent(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
                .putExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES, values)
        )
    }

    private fun loadValues(): FloatArray {
        val saved = requireContext().getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
            .getString(NATIVE_VALUES_KEY, null)
        val parsed = saved?.split(',')?.mapNotNull(String::toFloatOrNull)?.toFloatArray()
        return if (parsed?.size == NativeBmwDspBottomSheet.DEFAULTS.size) {
            parsed
        } else {
            NativeBmwDspBottomSheet.DEFAULTS.copyOf()
        }
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
        private const val KEY_ENABLE = "native_bmw_dsp_enable"
        private const val KEY_OPEN = "open_native_bmw_dsp"

        fun newInstance() = NativeBmwDspCardFragment()
    }
}
