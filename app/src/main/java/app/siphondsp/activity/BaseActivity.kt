package app.siphondsp.activity

import android.app.ActivityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import app.siphondsp.MainApplication
import app.siphondsp.R
import app.siphondsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

abstract class BaseActivity : AppCompatActivity(), KoinComponent {

    /* Preferences */
    protected val prefsApp: Preferences.App by inject()
    protected val prefsVar: Preferences.Var by inject()

    protected val app
        get() = application as MainApplication

    override fun onResume() {
        super.onResume()

        // Exclude from recents if enabled
        getSystemService<ActivityManager>()?.appTasks?.takeIf { it.isNotEmpty() }?.forEach {
            it.setExcludeFromRecents(prefsApp.get<Boolean>(R.string.key_exclude_app_from_recents))
        }
    }
}
