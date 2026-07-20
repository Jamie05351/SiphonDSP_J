package app.siphondsp.activity

import android.app.ActivityManager
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import app.siphondsp.MainApplication
import app.siphondsp.R
import app.siphondsp.delegates.ThemingDelegate
import app.siphondsp.delegates.ThemingDelegateImpl
import app.siphondsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

abstract class BaseActivity :
    AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    KoinComponent,
    ThemingDelegate by ThemingDelegateImpl() {

    /* Preferences */
    protected val prefsApp: Preferences.App by inject()
    protected val prefsVar: Preferences.Var by inject()

    protected open val disableAppTheme = false

    protected val app
        get() = application as MainApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        if(!disableAppTheme)
            applyAppTheme(this)
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        // Exclude from recents if enabled
        getSystemService<ActivityManager>()?.appTasks?.takeIf { it.isNotEmpty() }?.forEach {
            it.setExcludeFromRecents(prefsApp.get<Boolean>(R.string.key_exclude_app_from_recents))
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if(key == getString(R.string.key_appearance_pure_black) ||
            key == getString(R.string.key_appearance_app_theme) ||
            key == getString(R.string.key_appearance_custom_color)) {
            if(!disableAppTheme)
                ActivityCompat.recreate(this)
        }
    }
}
