package app.siphondsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import app.siphondsp.R
import app.siphondsp.preference.MaterialSeekbarPreference
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject

class SettingsAudioFormatFragment : SettingsBaseFragment() {

    private val encoding by lazy { findPreference<ListPreference>(getString(R.string.key_audioformat_encoding)) }
    private val bufferSize by lazy { findPreference<MaterialSeekbarPreference>(getString(R.string.key_audioformat_buffersize)) }

    private val preferences: Preferences.App by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_audio_format_preferences, rootKey)

        bufferSize?.setDefaultValue(preferences.getDefault<Float>(R.string.key_audioformat_buffersize))
        bufferSize?.setOnPreferenceChangeListener { _, newValue ->
            if((newValue as Float) <= 1024){
                requireContext().toast(R.string.audio_format_buffer_size_warning_low_value, false)
            }
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
        encoding?.setOnPreferenceChangeListener { _, _ ->
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
    }

    companion object {
        fun newInstance(): SettingsAudioFormatFragment {
            return SettingsAudioFormatFragment()
        }
    }
}
