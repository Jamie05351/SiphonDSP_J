package app.siphondsp.activity

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import app.siphondsp.R
import app.siphondsp.compose.screens.AppCompatibilityScreen
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.room.BlockedApp
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.extensions.CompatExtensions.getParcelableAs
import app.siphondsp.utils.extensions.ContextExtensions.getAppIcon
import app.siphondsp.utils.extensions.ContextExtensions.getAppNameFromUidSafe
import app.siphondsp.utils.extensions.ContextExtensions.getPackageNameFromUid
import app.siphondsp.utils.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Opened from the "incompatible app" notification ([AppCompatibilityScreen]): retry the capture,
 * or exclude the app that broke it and restart.
 */
class AppCompatibilityActivity : BaseActivity() {

    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uid = intent.getIntExtra(RootlessAudioProcessorService.EXTRA_APP_UID, -1)
        if (uid < 0) {
            finish()
            return
        }
        val internalCall = intent.getBooleanExtra(RootlessAudioProcessorService.EXTRA_APP_COMPAT_INTERNAL_CALL, false)
        val projection = intent.extras?.getParcelableAs<Intent>(RootlessAudioProcessorService.EXTRA_MEDIA_PROJECTION_DATA)

        getSystemService<NotificationManager>()?.cancel(Notifications.ID_SERVICE_APPCOMPAT)

        val appPackage = getPackageNameFromUid(uid) ?: getString(R.string.app_compat_unknown_pkg_name)
        val appName = getAppNameFromUidSafe(uid)
        val icon = getAppIcon(appPackage) ?: getAppIcon("android")

        fun restartAndClose(afterMs: Long) {
            busy = true
            // Not the activity's scope: being recreated during the wait must not cancel the
            // engine restart. The app context outlives this instance.
            val appContext = applicationContext
            CoroutineScope(Dispatchers.Main).launch {
                delay(afterMs)
                if (projection != null && SdkCheck.isQ) RootlessAudioProcessorService.start(appContext, projection)
                delay(CLOSE_DELAY_MS)
                if (internalCall) {
                    appContext.startActivity(Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            }
        }

        setContent {
            BmwDspTheme {
                AppCompatibilityScreen(
                    appName = appName,
                    packageName = appPackage,
                    icon = icon,
                    busy = busy,
                    onOpenApp = { packageManager.getLaunchIntentForPackage(appPackage)?.let { startActivity(it) } },
                    onRetry = {
                        Timber.d("Requesting retry")
                        restartAndClose(afterMs = 0L)
                    },
                    onExclude = {
                        Timber.d("Requesting exclude of $appPackage")
                        CoroutineScope(Dispatchers.Main).launch { app.blockedAppRepository.insert(BlockedApp(uid, appPackage, appName)) }
                        restartAndClose(afterMs = EXCLUDE_DELAY_MS)
                    },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        prefsVar.set(R.string.key_is_app_compat_activity_active, false)
    }

    override fun onDestroy() {
        prefsVar.set(R.string.key_is_app_compat_activity_active, false)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        prefsVar.set(R.string.key_is_app_compat_activity_active, true)
    }

    private companion object {
        // Exclude: give the blocklist insert a moment before the service restarts and reads it.
        const val EXCLUDE_DELAY_MS = 100L
        const val CLOSE_DELAY_MS = 300L
    }
}
