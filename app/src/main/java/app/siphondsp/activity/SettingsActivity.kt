package app.siphondsp.activity

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.backup.BackupCreatorJob
import app.siphondsp.backup.BackupManager
import app.siphondsp.backup.BackupRestoreService
import app.siphondsp.compose.settings.SettingsActions
import app.siphondsp.compose.settings.SettingsScreen
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.service.NotificationListenerService
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.sdkAbove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

/** Settings, in Compose ([SettingsScreen]). This activity owns the pickers and intents. */
class SettingsActivity : BaseActivity(), SettingsActions {

    private val dumpManager: DumpManager by inject()

    private var pendingRestore by mutableStateOf<Uri?>(null)

    // A rapid double tap on "Create backup" would otherwise open two Create Document pickers:
    // WorkManager has nothing enqueued yet while the first tap's check is still in flight.
    private var checkingBackupJob = false

    private val backupLocationPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefsApp.set(R.string.key_backup_location, uri.toString())
        } else {
            resetFrequencyIfLocationUnset()
        }
    }

    private val backupSavePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/*")) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        BackupCreatorJob.startNow(this, uri)
    }

    private val backupLoadPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The saved location is useless once its permission is gone (app data cleared, or
        // the folder deleted): forget it, and stop automatic backups that would fail.
        if (!hasBackupLocationPermission()) {
            prefsApp.reset<String>(R.string.key_backup_location, false)
            resetFrequencyIfLocationUnset()
        }

        setContent {
            BmwDspTheme {
                SettingsScreen(
                    prefs = prefsApp,
                    actions = this,
                    pendingRestore = pendingRestore,
                    onRestoreHandled = { pendingRestore = null },
                    onExit = ::finish,
                )
            }
        }
    }

    override fun onBufferOrEncodingChanged() {
        AppEvents.send(AppEvent.RestartCapture)
    }

    override fun pickBackupLocation() {
        try {
            toast(R.string.backup_select_location)
            backupLocationPicker.launch(null)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_activity_found)
        }
    }

    override fun createBackup() {
        if (checkingBackupJob) return
        checkingBackupJob = true
        lifecycleScope.launch {
            try {
                val running = withContext(Dispatchers.IO) { BackupCreatorJob.isManualJobRunning(this@SettingsActivity) }
                if (running) {
                    toast(R.string.backup_in_progress)
                } else {
                    try {
                        backupSavePicker.launch(BackupManager.getBackupFilename())
                    } catch (_: ActivityNotFoundException) {
                        toast(R.string.no_activity_found)
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
            toast(R.string.no_activity_found)
        }
    }

    override fun restore(uri: Uri, dirty: Boolean) {
        BackupRestoreService.start(this, uri, dirty)
    }

    override fun onBackupFrequencyChanged(hours: Int) {
        BackupCreatorJob.setupTask(this, hours)
        if (hours > 0 && prefsApp.get<String>(R.string.key_backup_location).isBlank()) {
            pickBackupLocation()
        }
    }

    private fun resetFrequencyIfLocationUnset() {
        if (prefsApp.get<String>(R.string.key_backup_location).isBlank()) {
            prefsApp.reset<String>(R.string.key_backup_frequency)
            // The job may already be scheduled (the frequency was picked first): with nowhere to
            // write, it would only fail at every interval.
            BackupCreatorJob.setupTask(this, 0)
        }
    }

    private fun hasBackupLocationPermission(): Boolean {
        val location = prefsApp.get<String>(R.string.key_backup_location).toUri()
        return contentResolver.persistedUriPermissions.any { it.uri == location && it.isWritePermission }
    }

    override fun restartSetup() {
        startActivity(Intent(this, OnboardingActivity::class.java).apply {
            putExtra(OnboardingActivity.EXTRA_ROOTLESS_REDO_ADB_SETUP, true)
        })
    }

    override fun shareDump() {
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { writeDump() }
            val uri = FileProvider.getUriForFile(this@SettingsActivity, BuildConfig.APPLICATION_ID + ".dump_provider", path)
            val share = Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setType("text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, getString(R.string.troubleshooting_dump_share_title)))
        }
    }

    /** The session dump followed by the application log, in `files/dump.txt`. */
    private fun writeDump(): File {
        val path = File(filesDir, "dump.txt")
        val log = File(cacheDir, "application.log")
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

    override fun openNotificationAccess() {
        val component = ComponentName(packageName, NotificationListenerService::class.java.name)
        val intent = sdkAbove(Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        }.below {
            val value = "$packageName/${component.className}"
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", value) })
                .putExtra(":settings:fragment_args_key", value)
        }
        // TVs, watches and some head units have no such settings page.
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast(R.string.no_activity_found)
        }
    }

    override fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_activity_found)
        }
    }

    override fun toast(message: Int) {
        toast(message, false)
    }
}
