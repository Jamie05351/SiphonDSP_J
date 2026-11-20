package app.siphondsp.compose.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import app.siphondsp.R
import app.siphondsp.compose.controls.GlassButton
import app.siphondsp.compose.controls.GlassButtonKind
import app.siphondsp.compose.controls.GlassDialog
import app.siphondsp.compose.controls.GlassDialogText
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.fragment.PeqGraphPreferences
import app.siphondsp.model.BmwPeqPreset
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.PrivatePeqBackup
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.extensions.ContextExtensions.toast
import timber.log.Timber
import java.text.DateFormat
import java.util.Date

/*
 * The complete DSP backup: every PEQ bank plus Gains & Delay, Compressor and Crossovers & Tilt, in
 * one file. Offered from the PEQ toolbar's Backup menu and the home screen's More menu.
 */

/** What a Backup menu can do, and when this unit last saved a backup (null: never). */
internal class DspBackupActions(val backUp: () -> Unit, val restore: () -> Unit, val lastBackupMs: Long?)

/**
 * The backup and restore file pickers and the red "Restore…?" confirm dialog, for a Backup menu.
 * [holder] is asked for only when a backup is written or restored, so a screen without the PEQ
 * open (the home screen) doesn't load it up front. [onValuesRestored] gets the restored DSP values.
 */
@Composable
internal fun rememberDspBackup(holder: () -> PeqStateHolder, onValuesRestored: (FloatArray) -> Unit = {}): DspBackupActions {
    val context = LocalContext.current
    var lastBackupMs by remember { mutableStateOf(DspBackupLog.lastBackupMs(context)) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && exportPrivateBackup(context, holder().peqState, PeqGraphPreferences(context), uri)) {
            lastBackupMs = DspBackupLog.lastBackupMs(context)
        }
    }
    var pending by remember { mutableStateOf<PendingBackupRestore?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pending = readBackupForConfirm(context, it) }
    }
    pending?.let { prompt ->
        // Red: a restore replaces the whole current DSP setup.
        GlassDialog(
            title = "Restore all DSP settings?",
            icon = R.drawable.ic_twotone_settings_backup_restore_24dp,
            danger = true,
            onDismissRequest = { pending = null },
            buttons = listOf(
                GlassButton("Cancel", GlassButtonKind.Cancel) { pending = null },
                GlassButton("Restore", GlassButtonKind.Danger) {
                    pending = null
                    applyBackupRestore(context, holder(), PeqGraphPreferences(context), prompt, onValuesRestored)
                },
            ),
        ) { GlassDialogText(prompt.message) }
    }
    return DspBackupActions(
        backUp = { exportLauncher.launch("SiphonDSP-private-peq-backup.json") },
        restore = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
        lastBackupMs = lastBackupMs,
    )
}

/** "Last backup: 21 Nov, 18:02", or that there's none yet, for the Backup menu. */
internal fun lastBackupText(lastBackupMs: Long?): String =
    lastBackupMs?.let { "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        ?: "No backup saved from this unit yet"

/** When this unit last wrote a backup file, in the app's own preferences. */
internal object DspBackupLog {
    private const val PREFS = "dsp_backup"
    private const val KEY_LAST = "last_backup_ms"

    fun lastBackupMs(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L).takeIf { it > 0L }

    fun recordBackup(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST, System.currentTimeMillis()) }
    }
}

// --- private-backup export / restore ---------------------------------------------------------

internal class PendingBackupRestore(val candidate: BmwPeqState, val backup: PrivatePeqBackup, val message: String)

internal fun exportPrivateBackup(
    context: android.content.Context,
    peqState: BmwPeqState,
    graphPrefs: PeqGraphPreferences,
    uri: android.net.Uri,
): Boolean {
    return try {
        val backup = PrivatePeqBackup(
            createdAtEpochMs = System.currentTimeMillis(),
            state = BmwPeqPreset.fromState(peqState, name = "Jamie private PEQ backup"),
            graphDisplay = PrivatePeqBackup.GraphDisplay(
                graphPrefs.showIndividualFilters,
                graphPrefs.channelDisplayName,
            ),
            // Full BMW DSP state (Gains & Delay, Compressor, Crossovers & Tilt), not just PEQ.
            nativeDspValues = NativeBmwDspValues.load(context).toList(),
        )
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
            it.write(PrivatePeqBackup.encode(backup))
        }
        Timber.i("Private BMW DSP backup exported format=${PrivatePeqBackup.CURRENT_VERSION}")
        DspBackupLog.recordBackup(context)
        context.toast("Complete private BMW DSP backup exported")
        true
    } catch (error: Exception) {
        Timber.e(error, "Private PEQ backup export failed")
        context.toast("Backup export failed")
        false
    }
}

internal fun readBackupForConfirm(context: android.content.Context, uri: android.net.Uri): PendingBackupRestore? {
    return try {
        val text = context.contentResolver.openInputStream(uri)
            ?.use(app.siphondsp.fragment.ApoImportRouter::readImportText)
            ?: throw IllegalArgumentException("The backup file is empty")
        val backup = PrivatePeqBackup.decode(text)
        val candidate = backup.validatedState()
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f
        candidate.validate(sampleRate)?.let { throw IllegalArgumentException(it) }
        val restoresFullState = backup.nativeDspValues != null
        val message = buildString {
            append("Full Range ${candidate.fullRangeBands.size}, Low ${candidate.lowBandBands.size}, ")
            append("Mid ${candidate.midBandBands.size}, High ${candidate.highBandBands.size}, preamp ${candidate.preampDb} dB")
            if (restoresFullState) append(", plus Gains & Delay, Compressor, and Crossovers & Tilt")
            append(". This replaces all PEQ banks")
            if (restoresFullState) append(" and the rest of the BMW DSP setup")
            append(". A failed save is reported; PEQ and DSP settings are saved separately.")
            if (!restoresFullState) append(" (This is an older backup file that only contains PEQ bands.)")
        }
        PendingBackupRestore(candidate, backup, message)
    } catch (error: Exception) {
        Timber.e(error, "Private PEQ backup import rejected")
        BmwPeqState.recordBackupRestoreResult(context, "rejected: ${error.message ?: "invalid backup"}")
        context.toast(error.message ?: "Invalid backup; previous PEQ was kept")
        null
    }
}

internal fun applyBackupRestore(
    context: android.content.Context,
    holder: PeqStateHolder,
    graphPrefs: PeqGraphPreferences,
    prompt: PendingBackupRestore,
    onValuesRestored: (FloatArray) -> Unit,
) {
    if (holder.applyCandidate(prompt.candidate, "private-backup-restore")) {
        prompt.backup.nativeDspValues?.let { values ->
            // An older backup carries a shorter, position-encoded array and/or leftover bytes in
            // slots this schema has since reclaimed for something else (stage delay, crossover
            // type, MBC, ...). migrateRestoredValues() pads it AND runs every load()-time
            // migration those reclaims depend on -- padToCurrentSize() alone would leave a
            // leftover value from an older field silently reinterpreted as whatever the slot
            // means today, applied straight to the running DSP below.
            val restored = NativeBmwDspValues.migrateRestoredValues(context, values.toFloatArray())
            if (!NativeBmwDspValues.save(context, restored)) {
                BmwPeqState.recordBackupRestoreResult(context, "partial: PEQ restored; DSP save failed")
                Timber.e("Private backup restore incomplete: BMW DSP persistence failed")
                context.toast("Backup only partly restored: PEQ restored, other DSP settings could not be saved")
                return
            }
            NativeBmwDspValues.broadcast(restored)
            onValuesRestored(restored)
        }
        graphPrefs.writeBackupGraphDisplay(
            prompt.backup.graphDisplay.showIndividualFilters,
            prompt.backup.graphDisplay.channelDisplay,
        )
        BmwPeqState.recordBackupRestoreResult(context, "success-v${prompt.backup.version}")
        Timber.i("Private PEQ backup restore succeeded version=${prompt.backup.version}")
    } else {
        BmwPeqState.recordBackupRestoreResult(context, "native-apply-rejected")
    }
}
