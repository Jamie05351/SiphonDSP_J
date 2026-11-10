package app.siphondsp.compose.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import app.siphondsp.MainApplication
import app.siphondsp.R
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

/** The "incompatible app" request MainActivity was opened with. */
data class AppCompatRequest(val uid: Int, val internalCall: Boolean, val projection: Intent?) {
    companion object {
        /** The request [intent] carries, if it is one (see ServiceNotificationHelper.createAppTroubleshootIntent). */
        fun from(intent: Intent?): AppCompatRequest? {
            val uid = intent?.getIntExtra(RootlessAudioProcessorService.EXTRA_APP_UID, -1) ?: -1
            if (uid < 0) return null
            return AppCompatRequest(
                uid = uid,
                internalCall = intent!!.getBooleanExtra(RootlessAudioProcessorService.EXTRA_APP_COMPAT_INTERNAL_CALL, false),
                projection = intent.extras?.getParcelableAs<Intent>(RootlessAudioProcessorService.EXTRA_MEDIA_PROJECTION_DATA),
            )
        }
    }
}

/**
 * Opened from the "incompatible app" notification: retry the capture, or exclude the app that
 * broke it and restart. [onDone] runs once the engine has been restarted: [AppCompatRequest.internalCall]
 * says whether the app was in use (go on to the front page) or not (close).
 */
@Composable
fun AppCompatibilityRoute(request: AppCompatRequest, onBack: () -> Unit, onDone: (internalCall: Boolean) -> Unit) {
    val context = LocalContext.current
    var busy by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { context.getSystemService<NotificationManager>()?.cancel(Notifications.ID_SERVICE_APPCOMPAT) }

    val appPackage = remember(request) { context.getPackageNameFromUid(request.uid) ?: context.getString(R.string.app_compat_unknown_pkg_name) }
    val appName = remember(request) { context.getAppNameFromUidSafe(request.uid) }
    val icon = remember(appPackage) { context.getAppIcon(appPackage) ?: context.getAppIcon("android") }

    fun restartThenDone(appContext: Context, afterMs: Long) {
        busy = true
        // Not a composition scope: leaving or recreating the screen during the wait must not
        // cancel the engine restart.
        CoroutineScope(Dispatchers.Main).launch {
            delay(afterMs)
            if (request.projection != null && SdkCheck.isQ) RootlessAudioProcessorService.start(appContext, request.projection)
            delay(CLOSE_DELAY_MS)
            onDone(request.internalCall)
        }
    }

    AppCompatibilityScreen(
        appName = appName,
        packageName = appPackage,
        icon = icon,
        busy = busy,
        onOpenApp = { context.packageManager.getLaunchIntentForPackage(appPackage)?.let { context.startActivity(it) } },
        onRetry = {
            Timber.d("Requesting retry")
            restartThenDone(context.applicationContext, afterMs = 0L)
        },
        onExclude = {
            Timber.d("Requesting exclude of $appPackage")
            val app = context.applicationContext as MainApplication
            CoroutineScope(Dispatchers.Main).launch {
                app.blockedAppRepository.insert(BlockedApp(request.uid, appPackage, appName))
            }
            restartThenDone(app, afterMs = EXCLUDE_DELAY_MS)
        },
        onBack = onBack,
    )
}

// Exclude: give the blocklist insert a moment before the service restarts and reads it.
private const val EXCLUDE_DELAY_MS = 100L
private const val CLOSE_DELAY_MS = 300L
