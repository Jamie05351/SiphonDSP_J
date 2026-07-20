package app.siphondsp.fragment.settings

import android.os.Bundle
import androidx.preference.*
import app.siphondsp.R
import app.siphondsp.model.preference.AppTheme
import app.siphondsp.model.preference.ThemeMode
import app.siphondsp.preference.ThemesPreference
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.showColorPickerAlert
import app.siphondsp.utils.extensions.isDynamicColorAvailable
import app.siphondsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject

class SettingsAppearanceFragment : SettingsBaseFragment() {

    private val preferences: Preferences.App by inject()

    private val themeMode by lazy { findPreference<ListPreference>(getString(R.string.key_appearance_theme_mode)) }
    private val amoledMode by lazy { findPreference<Preference>(getString(R.string.key_appearance_pure_black)) }
    private val appTheme by lazy { findPreference<ThemesPreference>(getString(R.string.key_appearance_app_theme)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_appearance_preferences, rootKey)

        val appThemes = AppTheme.values().filter {
            val dynamicFilter = if (it == AppTheme.MONET || it == AppTheme.CUSTOM) {
                isDynamicColorAvailable
            } else {
                true
            }
            it.titleResId != null && dynamicFilter
        }
        appTheme?.entries = appThemes
        appTheme?.onThemeClick = { theme ->
            if (theme == AppTheme.CUSTOM) {
                showCustomColorPicker()
                true
            } else {
                false
            }
        }

        themeMode?.setOnPreferenceChangeListener { _, _ ->
            updateViewStates()
            true
        }
        updateViewStates()

        savedInstanceState?.let {
            appTheme?.lastScrollPosition = it.getInt(STATE_THEMES_SCROLL_POSITION, 0)
        }
    }

    private fun showCustomColorPicker() {
        val currentColor = preferences.get<Int>(R.string.key_appearance_custom_color)
        requireContext().showColorPickerAlert(
            layoutInflater,
            R.string.custom_theme_color_title,
            currentColor,
        ) { color ->
            color ?: return@showColorPickerAlert
            preferences.set(R.string.key_appearance_custom_color, color)
            appTheme?.refreshPreviews()
            appTheme?.value = AppTheme.CUSTOM.name
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        appTheme?.let {
            outState.putInt(STATE_THEMES_SCROLL_POSITION, it.lastScrollPosition ?: 0)
        }
        super.onSaveInstanceState(outState)
    }

    private fun updateViewStates(){
        amoledMode?.isVisible = themeMode?.value?.toIntOrNull()?.let { ThemeMode.fromInt(it) } != ThemeMode.Light
    }

    companion object {
        private const val STATE_THEMES_SCROLL_POSITION = "stateThemesScrollPosition"

        fun newInstance(): SettingsAppearanceFragment {
            return SettingsAppearanceFragment()
        }
    }
}