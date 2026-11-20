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
 * `NativeBmwDspUpdated` collector is roadmap 10g). Undo/Redo, Edit-as-string, JSON
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
    val showConvolver = rememberShowConvolverInGraphs()

    fun reloadEverything() {
        holder.refreshFromDisk()
        systemValues = NativeBmwDspValues.load(context)
        graphMode = graphPrefs.responseMode
        channelDisplay = graphPrefs.channelDisplay
        showOverlays = graphPrefs.showIndividualFilters
    }

    LaunchedEffect(holder) { holder.loadCarBaseline() }
    LaunchedEffect(holder) { holder.loadHouseCurve() }

    LifecycleResumeEffect(Unit) {
        reloadEverything()
        onPauseOrDispose { }
    }

    // 10g: keep the graph tracking edits made on the other DSP screens (crossover / tilt / gains
    // / compressor all send AppEvent.NativeBmwDspUpdated) and full-config swaps (preset
    // load, app-wide backup restore) without needing a screen re-entry.
    LaunchedEffect(holder) {
        AppEvents.flow.collect { event ->
            when (event) {
                is AppEvent.NativeBmwDspUpdated ->
                    event.values.takeIf { it.size == NativeBmwDspValues.SIZE }?.let { systemValues = it.copyOf() }
                AppEvent.ParametricEqChanged -> holder.refreshFromDisk()
                AppEvent.PresetLoaded, AppEvent.BackupRestored -> reloadEverything()
                else -> Unit
            }
        }
    }

    // --- layout: two-page horizontal swipe, Graph then List -----------------------------------
    // Replaces the old Graph/List BmwSegmentedControl (moved out entirely, along with the scope
    // switch and action-chip row -- see PeqToolbarActions). Page state isn't persisted: every
    // entry starts on the graph, matching how the toggle always defaulted before a
    // graphPrefs.listModeName restore; swiping during the session is enough on its own.
    val pagerState = rememberPagerState(pageCount = { 2 })
    val focusManager = LocalFocusManager.current
    val pagerScope = rememberCoroutineScope()
    val contentFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { contentFocusRequester.requestFocus() }

    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp)
            .focusRequester(contentFocusRequester)
            .focusGroup()
            // Graph/List paging for a hardware D-pad/rotary controller: swipe is the only way to
            // turn the page otherwise, and the graph (page 0) has no drag/adjust gesture even for
            // touch (see PeqGraph) so it holds no focusable content to land D-pad focus on at all.
            // Left/Right only converts to a page turn once normal focus movement inside this
            // Compose subtree has nowhere left to go -- e.g. immediately on page 0, or at the
            // List page's leftmost/rightmost cell -- so it never steals Left/Right away from
            // moving between a band row's own Hz/dB/Q cells.
            //
            // LocalFocusManager only searches this ComposeView -- it can never reach the sidebar's
            // focusable rows (DspCrossNavBar.populate, a sibling View, not part of this Compose
            // tree) -- so on page 0, Left has nothing to move to and nowhere to page (already the
            // leftmost page): both moveFocus and the page check are no-ops. Returning true there
            // anyway would consume the key and stop it from ever reaching Android's own
            // cross-View focus search, trapping a rotary user in the content area with no way
            // back to the sidebar. Only report the event as handled when it actually moved focus
            // or turned the page.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    else -> return@onKeyEvent false
                }
                if (focusManager.moveFocus(direction)) return@onKeyEvent true
                val targetPage = if (direction == FocusDirection.Right) 1 else 0
                if (pagerState.currentPage == targetPage) return@onKeyEvent false
                pagerScope.launch { pagerState.animateScrollToPage(targetPage) }
                true
            },
    ) {
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
                    showConvolver = showConvolver,
                    showTiltHandles = true,
                    showGainMeters = true,
                    sampleRate = (RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f).toDouble(),
                    onNodeTapped = { band -> holder.selectedUuid = band.uuid },
                    graphOptions = PeqGraphOptions(
                        onModeChange = { graphMode = it; graphPrefs.responseMode = it },
                        onChannelDisplayChange = { channelDisplay = it; graphPrefs.channelDisplay = it },
                        onShowIndividualFiltersChange = { showOverlays = it; graphPrefs.showIndividualFilters = it },
                        onShowConvolverChange = { graphPrefs.showConvolverInGraphs = it },
                    ),
                    carBaseline = holder.graphBaseline,
                    houseCurve = holder.houseCurve,
                    showHouseCurve = holder.showHouseCurve,
                    onShowHouseCurveChange = holder::setHouseCurveShown,
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

    // The hosting ComposeView spans the toolbar's full width (from x=0, under the native back
    // arrow) so its own visibility can be toggled as one unit -- this row has to clear that
    // space itself. dsp_status_strip_margin_start is the same "just past the indented back
    // arrow" offset dsp_status_strip used before it was hidden on this screen (see
    // ParametricEqualizerActivity), so the row starts exactly where that strip used to.
    //
    // top = 25.dp matches the native toolbar's own paddingTop (activity_parametric_eq.xml):
    // without it, this Row centers within the ComposeView's full (bezel-padded) height and lands
    // higher than the toolbar's own (padded-then-centered) back arrow -- close enough to read as
    // two misaligned rows fighting for the same line rather than one clean line.
    //
    // The leading dsp_toolbar_nav_inset gap is applied as a native layout_marginStart on the
    // ComposeView itself (activity_parametric_eq.xml), not as padding here: a horizontalScroll
    // Row hit-tests across its whole viewport, empty padding included, so padding alone still
    // covered the toolbar's back arrow underneath and swallowed its touches.
    val toolbarFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { toolbarFocusRequester.requestFocus() }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(top = 25.dp)
            .horizontalScroll(rememberScrollState())
            .padding(end = 25.dp)
            .focusRequester(toolbarFocusRequester)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Default height (24dp) restored -- 32dp made the segments cramped enough at a narrow
        // width to look degraded rather than just smaller. Needs an explicit bounded width here
        // (unlike its old Modifier.weight(1f), which only works in a plain, non-scrolling Row):
        // BmwSegmentedControl's own segments use weight(1f) internally, which requires a bounded
        // parent width to distribute -- inside this horizontalScroll Row, an unconstrained
        // modifier would give it an unbounded width and crash. 100dp per segment keeps each of the 4
        // labels ("PRE EQ"/"LOW"/"MID"/"HIGH") comfortably unscrunched.
        PeqScopeControl(
            selected = holder.selectedScope,
            onSelect = { holder.selectedScope = it },
            modifier = Modifier.width(400.dp),
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
