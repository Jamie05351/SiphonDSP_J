package app.siphondsp.compose.screens

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
 * The retry / exclude-and-restart in flight. Kept in a ViewModel so a recreation during the wait
 * neither cancels it nor leaves it reporting to the old screen: the current screen sees [finished].
 */
class AppCompatibilityViewModel(application: Application) : AndroidViewModel(application) {
    var busy by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set

    /** [first] runs before the wait (the blocklist insert), so the restarted service reads it. */
    fun restart(projection: Intent?, afterMs: Long, first: suspend () -> Unit = {}) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            first()
            delay(afterMs)
            if (projection != null && SdkCheck.isQ) RootlessAudioProcessorService.start(getApplication<Application>(), projection)
            delay(CLOSE_DELAY_MS)
            finished = true
        }
    }
}

/**
 * Opened from the "incompatible app" notification: retry the capture, or exclude the app that
 * broke it and restart. [onDone] runs once the engine has been restarted: [AppCompatRequest.internalCall]
 * says whether the app was in use (go on to the front page) or not (close).
 */
@Composable
fun AppCompatibilityRoute(
    request: AppCompatRequest,
    onBack: () -> Unit,
    onDone: (internalCall: Boolean) -> Unit,
    vm: AppCompatibilityViewModel = viewModel(),
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { context.getSystemService<NotificationManager>()?.cancel(Notifications.ID_SERVICE_APPCOMPAT) }
    LaunchedEffect(vm.finished) { if (vm.finished) onDone(request.internalCall) }
    // Leaving mid-restart would cancel it with the ViewModel.
    BackHandler(enabled = vm.busy) {}

    val appPackage = remember(request) { context.getPackageNameFromUid(request.uid) ?: context.getString(R.string.app_compat_unknown_pkg_name) }
    val appName = remember(request) { context.getAppNameFromUidSafe(request.uid) }
    val icon = remember(appPackage) { context.getAppIcon(appPackage) ?: context.getAppIcon("android") }

    AppCompatibilityScreen(
        appName = appName,
        packageName = appPackage,
        icon = icon,
        busy = vm.busy,
        onOpenApp = { context.packageManager.getLaunchIntentForPackage(appPackage)?.let { context.startActivity(it) } },
        onRetry = {
            Timber.d("Requesting retry")
            vm.restart(request.projection, afterMs = 0L)
        },
        onExclude = {
            Timber.d("Requesting exclude of $appPackage")
            val app = context.applicationContext as MainApplication
            vm.restart(request.projection, afterMs = EXCLUDE_DELAY_MS) {
                app.blockedAppRepository.insert(BlockedApp(request.uid, appPackage, appName))
            }
        },
        onBack = { if (!vm.busy) onBack() },
    )
}

// Exclude: give the service a moment after the blocklist insert before it restarts and reads it.
private const val EXCLUDE_DELAY_MS = 100L
private const val CLOSE_DELAY_MS = 300L
