package app.siphondsp.compose.screens

import app.siphondsp.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.compose.controls.GlassButton
import app.siphondsp.compose.controls.GlassButtonKind
import app.siphondsp.compose.controls.GlassChip
import app.siphondsp.compose.controls.GlassDialog
import app.siphondsp.compose.controls.GlassDialogText
import app.siphondsp.compose.controls.GlassDivider
import app.siphondsp.compose.controls.GlassItem
import app.siphondsp.compose.controls.GlassMenu
import app.siphondsp.compose.controls.GlassNote
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.fragment.CarBaselineStore
import app.siphondsp.fragment.PeqApoImport
import app.siphondsp.fragment.PeqBandEditor
import app.siphondsp.fragment.PeqBandEditResult
import app.siphondsp.fragment.PeqGraphPreferences
import app.siphondsp.fragment.PeqScope
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.PeqDiagnosticReport
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.extensions.ContextExtensions.toast
import kotlinx.coroutines.launch
import timber.log.Timber

/** Graph and full-size filters pages share one holder and retain their swipe/rotary navigation. */
@Composable
fun ParametricEqScreen(holder: PeqStateHolder, modifier: Modifier = Modifier, showSpectrum: Boolean = true) {
    val context = LocalContext.current
    val graphPrefs = remember(context) { PeqGraphPreferences(context) }
    var systemValues by remember { mutableStateOf(NativeBmwDspValues.load(context)) }
    var graphMode by remember { mutableStateOf(graphPrefs.responseMode) }
    var channelDisplay by remember { mutableStateOf(graphPrefs.channelDisplay) }
    var showOverlays by remember { mutableStateOf(graphPrefs.showIndividualFilters) }
    val showConvolver = rememberShowConvolverInGraphs()
    // Capture the edited value, not only the holder identity, in the subcomposed graph lambda.
    val responsePeqState = holder.peqState
    val responseSelection = holder.selectedUuid
    // A saved filter selection is not an instruction to open the inspector on entry.
    var editorId by rememberSaveable(holder.selectedScope) { mutableStateOf<String?>(null) }
    val editorIndex = holder.visibleBands.indexOfFirst { it.uuid.toString() == editorId }

    fun reloadEverything() {
        holder.refreshFromDisk()
        systemValues = NativeBmwDspValues.load(context)
        graphMode = graphPrefs.responseMode
        channelDisplay = graphPrefs.channelDisplay
        showOverlays = graphPrefs.showIndividualFilters
    }
    LaunchedEffect(holder) { holder.loadCarBaseline(); holder.loadHouseCurve() }
    LifecycleResumeEffect(Unit) {
        reloadEverything()
        onPauseOrDispose { }
    }
    LaunchedEffect(holder) {
        AppEvents.flow.collect { event ->
            when (event) {
                is AppEvent.NativeBmwDspUpdated -> event.values.takeIf { it.size == NativeBmwDspValues.SIZE }
                    ?.let { systemValues = it.copyOf() }
                AppEvent.ParametricEqChanged -> holder.refreshFromDisk()
                AppEvent.PresetLoaded, AppEvent.BackupRestored -> { reloadEverything(); editorId = null }
                else -> Unit
            }
        }
    }
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun select(band: app.siphondsp.model.ParametricEqBand) {
        holder.selectedUuid = band.uuid
        editorId = band.uuid.toString()
    }
    Column(
        modifier.fillMaxSize().padding(bottom = PeqBottomClearance)
            .focusRequester(focus).focusGroup()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    else -> return@onKeyEvent false
                }
                if (focusManager.moveFocus(direction)) return@onKeyEvent true
                val target = if (direction == FocusDirection.Right) 1 else 0
                if (pager.currentPage == target) return@onKeyEvent false
                scope.launch { pager.animateScrollToPage(target) }
                true
            },
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 0) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val wide = maxWidth >= 720.dp || (maxHeight < 360.dp && maxWidth >= 520.dp)
                        val editorWidth = (maxWidth * 0.36f).coerceIn(220.dp, 288.dp)
                        val editorHeight = (maxHeight * 0.45f).coerceAtMost(260.dp)
                        val graph: @Composable (Modifier) -> Unit = { graphModifier ->
                            PeqGraph(
                                systemValues = systemValues,
                                peqState = responsePeqState,
                                activeBank = holder.selectedScope.bank,
                                selectedBandId = responseSelection,
                                modifier = graphModifier.testTag("peq-response-graph")
                                    .border(1.dp, Color(0xFF353535), RoundedCornerShape(10.dp)),
                                mode = graphMode,
                                channelDisplay = channelDisplay,
                                showIndividualFilters = showOverlays,
                                showConvolver = showConvolver,
                                showTiltHandles = true,
                                showGainMeters = false,
                                showSpectrum = showSpectrum,
                                showNodeCallout = false,
                                compactPlotPadding = true,
                                onSelectionCleared = { editorId = null },
                                sampleRate = (RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f).toDouble(),
                                onNodeTapped = ::select,
                                graphOptions = PeqGraphOptions(
                                    onModeChange = { graphMode = it; graphPrefs.responseMode = it; editorId = null },
                                    onChannelDisplayChange = {
                                        channelDisplay = it; graphPrefs.channelDisplay = it; editorId = null
                                    },
                                    onShowIndividualFiltersChange = { showOverlays = it; graphPrefs.showIndividualFilters = it },
                                    onShowConvolverChange = { graphPrefs.showConvolverInGraphs = it },
                                ),
                                carBaseline = holder.graphBaseline,
                                houseCurve = holder.houseCurve,
                                showHouseCurve = holder.showHouseCurve,
                                onShowHouseCurveChange = holder::setHouseCurveShown,
                            )
                        }
                        if (wide) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                graph(Modifier.weight(1f).fillMaxHeight())
                                if (editorIndex >= 0) PeqSelectedFilter(holder, editorIndex, { editorId = null },
                                    Modifier.width(editorWidth).fillMaxHeight())
                            }
                        } else {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                graph(Modifier.weight(1f).fillMaxWidth())
                                if (editorIndex >= 0) PeqSelectedFilter(holder, editorIndex, { editorId = null },
                                    Modifier.heightIn(max = editorHeight).fillMaxWidth())
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).testTag("peq-filter-bar"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("FILTERS", color = PeqMutedText, fontSize = 12.sp)
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            holder.visibleBands.forEachIndexed { index, band ->
                                PeqActionButton("%02d".format(java.util.Locale.ROOT, holder.filterNumberOffset + index + 1), { select(band) },
                                    Modifier.width(64.dp), selected = band.uuid.toString() == editorId)
                            }
                            if (holder.visibleBands.size < BmwPeqState.MAX_BANDS) {
                                PeqActionButton("+ Add", {
                                    val previousSelection = holder.selectedUuid
                                    holder.addBand()
                                    if (holder.selectedUuid != previousSelection) {
                                        editorId = holder.selectedUuid?.toString()
                                    }
                                })
                            }
                        }
                        PeqActionButton("Filters", { scope.launch { pager.animateScrollToPage(1) } }, Modifier.testTag("peq-open-filters"))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PeqActionButton("Graph", { scope.launch { pager.animateScrollToPage(0) } }, Modifier.testTag("peq-open-graph"))
                        PeqActionButton("Filters", {}, selected = true)
                    }
                    PeqBandList(holder, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** Compact bank selector and the existing backup/file/filter/overlay menus. */
@Composable
fun PeqToolbarActions(holder: PeqStateHolder, modifier: Modifier = Modifier, scopeWidth: androidx.compose.ui.unit.Dp = 340.dp) {
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

    // The complete DSP backup (the restore's confirm dialog comes with it).
    val backup = rememberDspBackup({ holder })

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

    // The compact PEQ header is already aligned to the sidebar's top bezel.
    Row(
        modifier = modifier
            .fillMaxHeight()
            .horizontalScroll(rememberScrollState())
            .padding(end = 4.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeqScopeControl(
            selected = holder.selectedScope,
            onSelect = { holder.selectedScope = it },
            modifier = Modifier.width(scopeWidth),
        )
        // Four menus, so everything fits on the line: the whole-DSP backup first (the most
        // important, and it covers every page, not only the PEQ), then this scope's files, the
        // filter tools and the graph's overlays.
        BackupChip(backup)
        FileChip(
            scope = holder.selectedScope.label,
            onImport = { importLauncher.launch(arrayOf("text/plain", "text/*")) },
            onExport = { exportLauncher.launch(holder.selectedScope.fileName) },
            onClear = { showResetConfirm = true },
        )
        FilterToolsChip(
            holder, { copyWholeScopeTarget = it }, { message -> context.toast(message) },
            onDiagnostics = {
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
            },
        )
        GraphChip(holder)
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
        // Replace throws the target's filters away, so it's the red one; Append is the go-ahead.
        GlassDialog(
            title = "Copy ${from.label} to ${target.label}",
            icon = R.drawable.ic_twotone_add_24dp,
            onDismissRequest = { copyWholeScopeTarget = null },
            buttons = listOf(
                GlassButton("Cancel", GlassButtonKind.Cancel) { copyWholeScopeTarget = null },
                GlassButton("Replace", GlassButtonKind.Danger) {
                    copyWholeScopeTarget = null
                    holder.apply(PeqBandEditor.copyWholeScope(holder.peqState, from, target, append = false))
                },
                GlassButton("Append", GlassButtonKind.Primary) {
                    copyWholeScopeTarget = null
                    holder.apply(PeqBandEditor.copyWholeScope(holder.peqState, from, target, append = true))
                },
            ),
        ) { GlassDialogText("Append keeps ${target.label}'s existing filters. Replace removes them first.") }
    }

    diagnosticReport?.let { report ->
        GlassDialog(
            title = "DSP diagnostic report",
            icon = R.drawable.ic_twotone_troubleshoot_24dp,
            onDismissRequest = { diagnosticReport = null },
            buttons = listOf(
                GlassButton("Close", GlassButtonKind.Cancel) { diagnosticReport = null },
                GlassButton("Export", GlassButtonKind.Primary, icon = R.drawable.ic_twotone_download_24) {
                    pendingDiagnostic = report
                    diagnosticReport = null
                    diagnosticExportLauncher.launch("siphondsp-peq-diagnostic.txt")
                },
            ),
        ) {
            // The report in a sunken well, monospace so its columns line up, scrolling inside it. Up
            // to 260 dp tall, less on a short screen: the dialog gives it only what its buttons leave.
            Text(
                report,
                color = Color(0xFFD1D5DB),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF111216))
                    .border(1.dp, Color(0xFF353840), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            )
        }
    }

}

// --- action chips + filter-tools menu ------------------------------------------------------

@Composable
private fun FilterToolsChip(
    holder: PeqStateHolder,
    onCopyWholeScope: (PeqScope) -> Unit,
    toast: (String) -> Unit,
    onDiagnostics: () -> Unit,
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
        GlassChip("Tools", onClick = { expanded = true })
        GlassMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GlassItem(
                text = "Duplicate selected filter",
                enabled = selectedIndex >= 0,
                onClick = { run(PeqBandEditor.duplicateFilter(holder.peqState, scope, selectedIndex)) },
            )
            GlassItem(
                text = "Move up",
                enabled = selectedIndex > 0,
                onClick = { run(PeqBandEditor.moveFilter(holder.peqState, scope, selectedIndex, selectedIndex - 1)) },
            )
            GlassItem(
                text = "Move down",
                enabled = selectedIndex in 0 until bands.lastIndex,
                onClick = { run(PeqBandEditor.moveFilter(holder.peqState, scope, selectedIndex, selectedIndex + 1)) },
            )
            GlassDivider()
            PeqScope.entries.filter { it != scope }.forEach { target ->
                GlassItem(
                    text = "Copy selected to ${target.label}",
                    enabled = selectedIndex >= 0,
                    onClick = { run(PeqBandEditor.copyFilter(holder.peqState, scope, selectedIndex, target)) },
                )
                GlassItem(
                    text = "Copy all to ${target.label}…",
                    onClick = { expanded = false; onCopyWholeScope(target) },
                )
            }
            GlassDivider()
            GlassItem(
                text = "Copy all Left filters to Right",
                onClick = {
                    run(PeqBandEditor.copyChannelFilters(holder.peqState, scope, ParametricEqChannel.LEFT, ParametricEqChannel.RIGHT, replaceBoth = false))
                },
            )
            GlassItem(
                text = "Copy all Right filters to Left",
                onClick = {
                    run(PeqBandEditor.copyChannelFilters(holder.peqState, scope, ParametricEqChannel.RIGHT, ParametricEqChannel.LEFT, replaceBoth = false))
                },
            )
            GlassItem(
                text = "Split Both filters into Left + Right",
                onClick = {
                    run(PeqBandEditor.copyChannelFilters(holder.peqState, scope, ParametricEqChannel.LEFT_RIGHT, ParametricEqChannel.LEFT, replaceBoth = true))
                },
            )
            GlassDivider()
            GlassItem("Diagnostics", icon = R.drawable.ic_twotone_troubleshoot_24dp, onClick = { expanded = false; onDiagnostics() })
        }
    }
}

/**
 * Load / show / clear the car baseline (see [app.siphondsp.dsp.CarBaseline]): a REW text export of
 * the car measured with this DSP flat or off, per channel or one file for both, which the graph
 * then adds to every curve so it shows the response you'll actually hear.
 */
@Composable
private fun CarBaselineMenu(holder: PeqStateHolder, expanded: Boolean, onDismiss: () -> Unit) {
    // Saveable: the activity can be recreated (memory, day/night) while the picker is open, and
    // a lost side would turn a Left or Right import into one over both channels.
    var pendingSide by rememberSaveable { mutableStateOf(CarBaselineStore.Side.BOTH) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { holder.importCarBaseline(it, pendingSide) }
    }
    fun load(side: CarBaselineStore.Side) {
        onDismiss()
        pendingSide = side
        launcher.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
    }

    GlassMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val leftName = holder.carBaselineSource(CarBaselineStore.Side.LEFT)
        val rightName = holder.carBaselineSource(CarBaselineStore.Side.RIGHT)
        GlassNote("Left: ${leftName ?: "none"}")
        GlassNote("Right: ${rightName ?: "none"}")
        GlassDivider()
        GlassItem("Load for Left + Right…", onClick = { load(CarBaselineStore.Side.BOTH) })
        GlassItem("Load for Left…", onClick = { load(CarBaselineStore.Side.LEFT) })
        GlassItem("Load for Right…", onClick = { load(CarBaselineStore.Side.RIGHT) })
        GlassDivider()
        GlassItem(
            text = "Show on graph",
            checked = holder.showCarBaseline,
            enabled = holder.carBaseline != null,
            onClick = { onDismiss(); holder.showCarBaselineOnGraph(!holder.showCarBaseline) },
        )
        GlassItem(
            text = "Clear baseline…",
            enabled = holder.carBaseline != null,
            onClick = { onDismiss(); showClearConfirm = true },
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "Clear car baseline?",
            message = "Remove the stored Left and Right measurements? The graph goes back to assuming a flat car.",
            confirmLabel = "Clear",
            onConfirm = { showClearConfirm = false; holder.clearCarBaseline() },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/**
 * Load / clear the house curve: a REW-style target (frequency, dB per line) drawn over the graph
 * as a dashed line, lined up with the full curve over 200 Hz - 5 kHz. Display only. Whether it
 * shows is the graph legend's toggle, mirrored here as "Show on graph".
 */
@Composable
private fun HouseCurveMenu(holder: PeqStateHolder, expanded: Boolean, onDismiss: () -> Unit) {
    var showClearConfirm by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { holder.importHouseCurve(it) }
    }

    GlassMenu(expanded = expanded, onDismissRequest = onDismiss) {
        GlassNote("Current: ${holder.houseCurveSource ?: "none"}")
        GlassDivider()
        GlassItem(
            text = "Load house curve…",
            onClick = {
                onDismiss()
                launcher.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
            },
        )
        // The same toggle as the graph legend's item, here too so rotary / D-pad navigation
        // (which can't reach a tap on the graph) can hide or show it without clearing it.
        GlassItem(
            text = "Show on graph",
            checked = holder.showHouseCurve,
            enabled = holder.houseCurve != null,
            onClick = { onDismiss(); holder.setHouseCurveShown(!holder.showHouseCurve) },
        )
        GlassItem(
            text = "Clear",
            enabled = holder.houseCurve != null,
            onClick = { onDismiss(); showClearConfirm = true },
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "Clear house curve?",
            message = "Remove the stored house curve from the graph?",
            confirmLabel = "Clear",
            onConfirm = { showClearConfirm = false; holder.clearHouseCurve() },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** The whole-DSP backup, lit green as the line's most important menu: back up, restore, and when it was last saved. */
@Composable
private fun BackupChip(backup: DspBackupActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlassChip("Backup", onClick = { expanded = true }, accent = BackupAccent)
        GlassMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GlassItem("Back up all DSP settings…", icon = R.drawable.ic_twotone_save_24dp, colour = BackupAccent) { expanded = false; backup.backUp() }
            GlassItem("Restore all DSP settings…", icon = R.drawable.ic_twotone_settings_backup_restore_24dp) { expanded = false; backup.restore() }
            GlassDivider()
            GlassNote(lastBackupText(backup.lastBackupMs))
        }
    }
}

/** This scope's filters as a file: import, export, and Clear apart in red. */
@Composable
private fun FileChip(scope: String, onImport: () -> Unit, onExport: () -> Unit, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlassChip("File", onClick = { expanded = true })
        GlassMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GlassItem("Import $scope filters…", icon = R.drawable.ic_twotone_download_24) { expanded = false; onImport() }
            GlassItem("Export $scope filters…", icon = R.drawable.ic_twotone_save_24dp) { expanded = false; onExport() }
            GlassDivider()
            GlassItem("Clear $scope…", icon = R.drawable.ic_twotone_delete_24dp, danger = true) { expanded = false; onClear() }
        }
    }
}

/**
 * The graph's overlays: Car baseline and House curve, each opening its own menu in the same place
 * (one long menu would scroll on the 480 dp screen). A tick shows which are on the graph.
 */
@Composable
private fun GraphChip(holder: PeqStateHolder) {
    var expanded by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf(false) }
    var house by remember { mutableStateOf(false) }
    Box {
        GlassChip("Graph", onClick = { expanded = true })
        GlassMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GlassItem("Car baseline ›", checked = holder.graphBaseline != null) { expanded = false; baseline = true }
            GlassItem("House curve ›", checked = holder.houseCurve != null && holder.showHouseCurve) { expanded = false; house = true }
        }
        CarBaselineMenu(holder, baseline) { baseline = false }
        HouseCurveMenu(holder, house) { house = false }
    }
}

private val BackupAccent = Color(0xFF5DCAA5)

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Red: each one clears something stored (a baseline, a house curve).
    GlassDialog(
        title = title,
        icon = R.drawable.ic_twotone_delete_24dp,
        danger = true,
        onDismissRequest = onDismiss,
        buttons = listOf(
            GlassButton("Cancel", GlassButtonKind.Cancel, onClick = onDismiss),
            GlassButton(confirmLabel, GlassButtonKind.Danger, onClick = onConfirm),
        ),
    ) { GlassDialogText(message) }
}
