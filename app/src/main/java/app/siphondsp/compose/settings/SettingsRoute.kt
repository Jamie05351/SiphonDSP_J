package app.siphondsp.compose.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.backup.BackupCreatorJob
import app.siphondsp.backup.BackupManager
import app.siphondsp.backup.BackupRestoreService
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.nav.Routes
import app.siphondsp.service.NotificationListenerService
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.preferences.Preferences
import app.siphondsp.utils.sdkAbove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File

/** Settings as a route of the app's graph: the pickers and intents behind [SettingsScreen]. */
@Composable
fun SettingsRoute() {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val prefs = remember { GlobalContext.get().get<Preferences.App>() }
    val dumpManager = remember { GlobalContext.get().get<DumpManager>() }
    val scope = rememberCoroutineScope()

    var pendingRestore by rememberSaveable { mutableStateOf<Uri?>(null) }

    fun resetFrequencyIfLocationUnset() {
        if (prefs.get<String>(R.string.key_backup_location).isBlank()) {
            prefs.reset<String>(R.string.key_backup_frequency)
            // The job may already be scheduled (the frequency was picked first): with nowhere to
            // write, it would only fail at every interval.
            BackupCreatorJob.setupTask(context, 0)
        }
    }

    val backupLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.set(R.string.key_backup_location, uri.toString())
        } else {
            resetFrequencyIfLocationUnset()
        }
    }
    val backupSavePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/*")) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            BackupCreatorJob.startNow(context, uri)
        }
    }
    val backupLoadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }

    // The saved location is useless once its permission is gone (app data cleared, or the folder
    // deleted): forget it, and stop automatic backups that would fail.
    LaunchedEffect(Unit) {
        val location = prefs.get<String>(R.string.key_backup_location).toUri()
        val permitted = context.contentResolver.persistedUriPermissions.any { it.uri == location && it.isWritePermission }
        if (!permitted) {
            prefs.reset<String>(R.string.key_backup_location, false)
            resetFrequencyIfLocationUnset()
        }
    }

    val actions = remember(context) {
        object : SettingsActions {
            // A rapid double tap on "Create backup" would otherwise open two Create Document
            // pickers: WorkManager has nothing enqueued yet while the first tap's check runs.
            private var checkingBackupJob = false

            override fun onBufferOrEncodingChanged() = AppEvents.send(AppEvent.RestartCapture)

            override fun pickBackupLocation() {
                try {
                    context.toast(R.string.backup_select_location, false)
                    backupLocationPicker.launch(null)
                } catch (_: ActivityNotFoundException) {
                    context.toast(R.string.no_activity_found, false)
                }
            }

            override fun createBackup() {
                if (checkingBackupJob) return
                checkingBackupJob = true
                scope.launch {
                    try {
                        val running = withContext(Dispatchers.IO) { BackupCreatorJob.isManualJobRunning(context) }
                        if (running) {
                            context.toast(R.string.backup_in_progress, false)
                        } else {
                            try {
                                backupSavePicker.launch(BackupManager.getBackupFilename())
                            } catch (_: ActivityNotFoundException) {
                                context.toast(R.string.no_activity_found, false)
                            }
                        }
                    } finally {
                        checkingBackupJob = false
                    }
                }
            }

            override fun pickRestoreFile() {
                try {
                    backupLoadPicker.launch(arrayOf("application/tar+gzip", "application/gzip"))
                } catch (_: ActivityNotFoundException) {
                    context.toast(R.string.no_activity_found, false)
                }
            }

            override fun restore(uri: Uri, dirty: Boolean) = BackupRestoreService.start(context, uri, dirty)

            override fun onBackupFrequencyChanged(hours: Int) {
                BackupCreatorJob.setupTask(context, hours)
                if (hours > 0 && prefs.get<String>(R.string.key_backup_location).isBlank()) pickBackupLocation()
            }

            override fun restartSetup() = navigator.open(Routes.onboarding(redoAdbSetup = true))

            override fun shareDump() {
                scope.launch {
                    val path = withContext(Dispatchers.IO) { writeDump(context, dumpManager) }
                    val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".dump_provider", path)
                    val share = Intent(Intent.ACTION_SEND)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .setType("text/plain")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(share, context.getString(R.string.troubleshooting_dump_share_title)))
                }
            }

            override fun openNotificationAccess() {
                val component = ComponentName(context.packageName, NotificationListenerService::class.java.name)
                val intent = sdkAbove(Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                }.below {
                    val value = "${context.packageName}/${component.className}"
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", value) })
                        .putExtra(":settings:fragment_args_key", value)
                }
                // TVs, watches and some head units have no such settings page.
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.toast(R.string.no_activity_found, false)
                }
            }

            override fun openUrl(url: String) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: ActivityNotFoundException) {
                    context.toast(R.string.no_activity_found, false)
                }
            }

            override fun toast(message: Int) = context.toast(message, false)
        }
    }

    SettingsScreen(
        prefs = prefs,
        actions = actions,
        pendingRestore = pendingRestore,
        onRestoreHandled = { pendingRestore = null },
        onExit = navigator::back,
    )
}

/** The session dump followed by the application log, in `files/dump.txt`. */
private fun writeDump(context: Context, dumpManager: DumpManager): File {
    val path = File(context.filesDir, "dump.txt")
    val log = File(context.cacheDir, "application.log")
    path.outputStream().use { output ->
        val writer = output.writer()
        writer.write(dumpManager.collectDebugDumps())
        writer.write("==================> Application log\n")
        writer.flush()
        if (log.exists()) {
            try {
                log.inputStream().use { it.copyTo(output) }
            } catch (ex: Exception) {
                writer.write("NOTE: Failed to append log file.\n$ex\n")
            }
        } else {
            writer.write("NOTE: Log file does not exist\n")
        }
        writer.flush()
    }
    return path
}
