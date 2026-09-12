package app.siphondsp.compose.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.R
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.fragment.PeqApoImport
import app.siphondsp.fragment.PeqBandEditor
import app.siphondsp.fragment.PeqBandEditResult
import app.siphondsp.fragment.PeqGraphPreferences
import app.siphondsp.fragment.PeqScope
import app.siphondsp.model.BmwPeqPreset
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.PeqDiagnosticReport
import app.siphondsp.model.PrivatePeqBackup
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import timber.log.Timber

/**
 * The Parametric EQ graph/list half of the workspace (roadmap Phase 10e/10f, redesigned to share
 * the toolbar line with [PeqToolbarActions] -- see that composable for the scope switch and
 * action-chip row, both relocated off this screen). A two-page horizontal swipe replaces the old
 * Graph/List toggle: page 1 is [PeqGraph], page 2 is [PeqBandList] for whichever scope
 * ([holder]'s selectedScope, driven by [PeqToolbarActions]'s Pre EQ/Low/Mid buttons) is current.
 *
 * [holder] is created once by `ParametricEqualizerActivity` and shared with [PeqToolbarActions]
 * (a separate Compose tree hosted directly on the toolbar, not nested under this screen) so a
 * scope change there is reflected here -- `PeqStateHolder`'s properties are plain
 * `mutableStateOf`, so this works across composition roots as long as it's the same instance.
 * The 192-float native config is reloaded from disk on every resume (the live
 * `ACTION_NATIVE_BMW_DSP_UPDATED` receiver is roadmap 10g). Undo/Redo, Edit-as-string, JSON
 * presets and portrait are dropped (2026-09-09 direction).
 */
@Composable
fun ParametricEqScreen(holder: PeqStateHolder, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graphPrefs = remember(context) { PeqGraphPreferences(context) }

    var systemValues by remember { mutableStateOf(NativeBmwDspValues.load(context)) }
    var graphMode by remember { mutableStateOf(graphPrefs.responseMode) }
    var channelDisplay by remember { mutableStateOf(graphPrefs.channelDisplay) }
    var showOverlays by remember { mutableStateOf(graphPrefs.showIndividualFilters) }

    fun reloadEverything() {
        holder.refreshFromDisk()
        systemValues = NativeBmwDspValues.load(context)
        graphMode = graphPrefs.responseMode
        channelDisplay = graphPrefs.channelDisplay
        showOverlays = graphPrefs.showIndividualFilters
    }

    LifecycleResumeEffect(Unit) {
        reloadEverything()
        onPauseOrDispose { }
    }

    // 10g: keep the graph tracking edits made on the other DSP screens (crossover / tilt / gains
    // / compressor all broadcast ACTION_NATIVE_BMW_DSP_UPDATED) and full-config swaps (preset
    // load, app-wide backup restore) without needing a screen re-entry.
    DisposableEffect(holder) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Constants.ACTION_NATIVE_BMW_DSP_UPDATED ->
                        intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)
                            ?.takeIf { it.size == NativeBmwDspValues.SIZE }
                            ?.let { systemValues = it.copyOf() }
                    Constants.ACTION_PRESET_LOADED, Constants.ACTION_BACKUP_RESTORED -> reloadEverything()
                }
            }
        }
        context.registerLocalReceiver(
            receiver,
            IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED).apply {
                addAction(Constants.ACTION_PRESET_LOADED)
                addAction(Constants.ACTION_BACKUP_RESTORED)
            },
        )
        onDispose { context.unregisterLocalReceiver(receiver) }
    }

    // --- layout: two-page horizontal swipe, Graph then List -----------------------------------
    // Replaces the old Graph/List BmwSegmentedControl (moved out entirely, along with the scope
    // switch and action-chip row -- see PeqToolbarActions). Page state isn't persisted: every
    // entry starts on the graph, matching how the toggle always defaulted before a
    // graphPrefs.listModeName restore; swiping during the session is enough on its own.
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column(modifier.fillMaxSize().padding(12.dp)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> PeqGraph(
                    systemValues = systemValues,
                    peqState = holder.peqState,
                    activeBank = holder.selectedScope.bank,
                    selectedBandId = holder.selectedUuid,
                    modifier = Modifier.fillMaxSize(),
                    mode = graphMode,
                    channelDisplay = channelDisplay,
                    showIndividualFilters = showOverlays,
                    showTiltHandles = true,
                    showGainMeters = true,
                    sampleRate = (RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f).toDouble(),
                    onNodeTapped = { band -> holder.selectedUuid = band.uuid },
                    graphOptions = PeqGraphOptions(
                        onModeChange = { graphMode = it; graphPrefs.responseMode = it },
                        onChannelDisplayChange = { channelDisplay = it; graphPrefs.channelDisplay = it },
                        onShowIndividualFiltersChange = { showOverlays = it; graphPrefs.showIndividualFilters = it },
                    ),
                )
                else -> PeqBandList(holder, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The Pre EQ/Low/Mid scope switch + action-chip row (Reset, Import, Export, Filter tools,
 * Diagnostics, Backup export, Restore backup), relocated onto the toolbar's own line -- see
 * activity_parametric_eq.xml's `peq_toolbar_actions` ComposeView and
 * `ParametricEqualizerActivity`, the only caller. One continuous horizontally-scrolling row: the
 * scope switch is its first (leftmost) item, everything else follows. `end = 25.dp` insets the
 * scrollable viewport from the true screen edge, so content reveals/hides there rather than
 * flush against the bezel.
 *
 * Shares [holder] with [ParametricEqScreen] (same instance, created once by the activity) so a
 * scope change here moves the graph/list there. Diagnostics reads [PeqGraphPreferences] and
 * `NativeBmwDspValues` fresh at click time instead of needing those kept in sync live from the
 * graph's own composition -- both are already persisted, so a fresh read is exactly as current.
 */
@Composable
fun PeqToolbarActions(holder: PeqStateHolder, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val apoImport = remember(context, holder) {
        val hostContext = context
        PeqApoImport(object : PeqApoImport.Host {
            override val context = hostContext
            override val activeScope get() = holder.selectedScope
            override val state get() = holder.peqState
            override fun applyCandidate(candidate: BmwPeqState, source: String) =
                holder.applyCandidate(candidate, source)
        })
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) apoImport.handleApoImport(uris) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let(apoImport::exportTo) }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { exportPrivateBackup(context, holder.peqState, PeqGraphPreferences(context), it) }
    }

    var pendingBackup by remember { mutableStateOf<PendingBackupRestore?>(null) }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingBackup = readBackupForConfirm(context, it) } }

    var pendingDiagnostic by remember { mutableStateOf<String?>(null) }
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val report = pendingDiagnostic
        pendingDiagnostic = null
        if (uri != null && report != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(report) }
            }.onSuccess { context.toast("Sanitised PEQ diagnostic report exported") }
                .onFailure {
                    Timber.e(it, "Failed to export PEQ diagnostic report")
                    context.toast("Diagnostic report export failed")
                }
        }
    }

    var showResetConfirm by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf<String?>(null) }
    var copyWholeScopeTarget by remember { mutableStateOf<PeqScope?>(null) }

    // The hosting ComposeView spans the toolbar's full width (from x=0, under the native back
    // arrow) so its own visibility can be toggled as one unit -- this row has to clear that
    // space itself. dsp_status_strip_margin_start is the same "just past the indented back
    // arrow" offset dsp_status_strip used before it was hidden on this screen (see
    // ParametricEqualizerActivity), so the row starts exactly where that strip used to.
    val startInset = dimensionResource(R.dimen.dsp_status_strip_margin_start)
    Row(
        modifier = modifier
            .fillMaxHeight()
            .horizontalScroll(rememberScrollState())
            .padding(start = startInset, end = 25.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Height matched to the AssistChips beside it (32dp, Material3's default chip height);
        // width fixed rather than the old weight(1f) row-filling stretch -- roughly half of what
        // that used to render as on a full-width toolbar line, now that it's a compact leading
        // item in a scrolling row rather than sharing a row with just the Graph/List toggle.
        PeqScopeControl(
            selected = holder.selectedScope,
            onSelect = { holder.selectedScope = it },
            segmentHeight = 32.dp,
            modifier = Modifier.width(160.dp),
        )
        Chip("Reset") { showResetConfirm = true }
        Chip("Import") { importLauncher.launch(arrayOf("text/plain", "text/*")) }
        Chip("Export") { exportLauncher.launch(holder.selectedScope.fileName) }
        FilterToolsChip(holder, { copyWholeScopeTarget = it }) { message -> context.toast(message) }
        Chip("Diagnostics") {
            val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate()
            val graphPrefs = PeqGraphPreferences(context)
            diagnosticReport = PeqDiagnosticReport.create(
                context = context,
                state = holder.peqState,
                systemValues = NativeBmwDspValues.load(context),
                sampleRate = sampleRate,
                serviceActive = sampleRate != null,
                nativeHandleReady = RootlessAudioProcessorService.nativeBmwPeqHandleReady(),
                channelDisplay = graphPrefs.channelDisplay.name,
                showIndividualFilters = graphPrefs.showIndividualFilters,
            )
        }
        Chip("Backup") { backupExportLauncher.launch("SiphonDSP-private-peq-backup.json") }
        Chip("Restore backup") { backupImportLauncher.launch(arrayOf("application/json", "text/plain")) }
    }

    // --- dialogs -----------------------------------------------------------------------------

    if (showResetConfirm) {
        val full = holder.selectedScope == PeqScope.FULL
        ConfirmDialog(
            title = "Clear ${holder.selectedScope.label}?",
            message = if (full) {
                "Clear every filter from Full Range and reset its preamp to 0 dB?"
            } else {
                "Clear every filter from ${holder.selectedScope.label}?"
            },
            confirmLabel = "Clear",
            onConfirm = { showResetConfirm = false; holder.resetScope() },
            onDismiss = { showResetConfirm = false },
        )
    }

    copyWholeScopeTarget?.let { target ->
        val from = holder.selectedScope
        AlertDialog(
            onDismissRequest = { copyWholeScopeTarget = null },
            title = { Text("Copy ${from.label} to ${target.label}") },
            text = { Text("Append keeps ${target.label}'s existing filters. Replace removes them first.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        copyWholeScopeTarget = null
                        holder.apply(PeqBandEditor.copyWholeScope(holder.peqState, from, target, append = false))
                    }) { Text("Replace") }
                    TextButton(onClick = {
                        copyWholeScopeTarget = null
                        holder.apply(PeqBandEditor.copyWholeScope(holder.peqState, from, target, append = true))
                    }) { Text("Append") }
                }
            },
            dismissButton = { TextButton(onClick = { copyWholeScopeTarget = null }) { Text("Cancel") } },
        )
    }

    diagnosticReport?.let { report ->
        AlertDialog(
            onDismissRequest = { diagnosticReport = null },
            title = { Text("DSP diagnostic report") },
            text = {
                Text(
                    report,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDiagnostic = report
                    diagnosticReport = null
                    diagnosticExportLauncher.launch("siphondsp-peq-diagnostic.txt")
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { diagnosticReport = null }) { Text("Close") } },
        )
    }

    pendingBackup?.let { prompt ->
        AlertDialog(
            onDismissRequest = { pendingBackup = null },
            title = { Text("Restore complete private BMW DSP backup?") },
            text = { Text(prompt.message) },
            confirmButton = {
                TextButton(onClick = {
                    val toRestore = prompt
                    pendingBackup = null
                    // Just applies + broadcasts; no local systemValues to update here (this
                    // composable doesn't render the graph) -- ParametricEqScreen's own
                    // ACTION_NATIVE_BMW_DSP_UPDATED receiver picks up the broadcast this sends.
                    applyBackupRestore(context, holder, PeqGraphPreferences(context), toRestore) {}
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingBackup = null }) { Text("Cancel") } },
        )
    }
}

// --- action chips + filter-tools menu ------------------------------------------------------

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

@Composable
private fun FilterToolsChip(
    holder: PeqStateHolder,
    onCopyWholeScope: (PeqScope) -> Unit,
    toast: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = holder.selectedScope
    val bands = holder.visibleBands
    val selectedIndex = bands.indexOfFirst { it.uuid == holder.selectedUuid }

    fun run(result: PeqBandEditResult) {
        expanded = false
        when (holder.apply(result)) {
            PeqBandEditResult.NoMatchingChannel -> toast("No filters on that channel to copy")
            else -> Unit
        }
    }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("Filter tools") },
            trailingIcon = { Text("▾") },
            colors = AssistChipDefaults.assistChipColors(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Duplicate selected filter") },
                enabled = selectedIndex >= 0,
                onClick = { run(PeqBandEditor.duplicateFilter(holder.peqState, scope, selectedIndex)) },
            )
            DropdownMenuItem(
                text = { Text("Move up") },
                enabled = selectedIndex > 0,
                onClick = { run(PeqBandEditor.moveFilter(holder.peqState, scope, selectedIndex, selectedIndex - 1)) },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                enabled = selectedIndex in 0 until bands.lastIndex,
                onClick = { run(PeqBandEditor.moveFilter(holder.peqState, scope, selectedIndex, selectedIndex + 1)) },
            )
            HorizontalDivider()
            PeqScope.entries.filter { it != scope }.forEach { target ->
                DropdownMenuItem(
                    text = { Text("Copy selected to ${target.label}") },
                    enabled = selectedIndex >= 0,
                    onClick = { run(PeqBandEditor.copyFilter(holder.peqState, scope, selectedIndex, target)) },
                )
                DropdownMenuItem(
                    text = { Text("Copy all to ${target.label}…") },
                    onClick = { expanded = false; onCopyWholeScope(target) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Copy all Left filters to Right") },
                onClick = {
                    run(PeqBandEditor.copyChannelFilters(holder.peqState, scope, ParametricEqChannel.LEFT, ParametricEqChannel.RIGHT, replaceBoth = false))
                },
            )
            DropdownMenuItem(
                text = { Text("Copy all Right filters to Left") },
                onClick = {
                    run(PeqBandEditor.copyChannelFilters(holder.peqState, scope, ParametricEqChannel.RIGHT, ParametricEqChannel.LEFT, replaceBoth = false))
                },
            )
            DropdownMenuItem(
                text = { Text("Split Both filters into Left + Right") },
                onClick = {
                    run(PeqBandEditor.copyChannelFilters(holder.peqState, scope, ParametricEqChannel.LEFT_RIGHT, ParametricEqChannel.LEFT, replaceBoth = true))
                },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- private-backup export / restore ---------------------------------------------------------

private class PendingBackupRestore(val candidate: BmwPeqState, val backup: PrivatePeqBackup, val message: String)

private fun exportPrivateBackup(
    context: android.content.Context,
    peqState: BmwPeqState,
    graphPrefs: PeqGraphPreferences,
    uri: android.net.Uri,
) {
    try {
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
        context.toast("Complete private BMW DSP backup exported")
    } catch (error: Exception) {
        Timber.e(error, "Private PEQ backup export failed")
        context.toast("Backup export failed")
    }
}

private fun readBackupForConfirm(context: android.content.Context, uri: android.net.Uri): PendingBackupRestore? {
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
            append("Mid ${candidate.midBandBands.size}, preamp ${candidate.preampDb} dB")
            if (restoresFullState) append(", plus Gains & Delay, Compressor, and Crossovers & Tilt")
            append(". This replaces all PEQ banks")
            if (restoresFullState) append(" and the rest of the BMW DSP setup")
            append(", only after the complete state validates and applies successfully.")
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

private fun applyBackupRestore(
    context: android.content.Context,
    holder: PeqStateHolder,
    graphPrefs: PeqGraphPreferences,
    prompt: PendingBackupRestore,
    onValuesRestored: (FloatArray) -> Unit,
) {
    if (holder.applyCandidate(prompt.candidate, "private-backup-restore")) {
        prompt.backup.nativeDspValues?.let { values ->
            // An older backup carries a shorter, position-encoded array; pad it forward so the
            // newer MBC/limiter indices land on their shipped defaults instead of tripping
            // save()'s size check.
            val restored = NativeBmwDspValues.padToCurrentSize(values.toFloatArray())
            NativeBmwDspValues.save(context, restored)
            NativeBmwDspValues.broadcast(context, restored)
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
