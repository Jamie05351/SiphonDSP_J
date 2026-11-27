package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.widthIn
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.GlassButton
import app.siphondsp.compose.controls.GlassButtonKind
import app.siphondsp.compose.controls.GlassChip
import app.siphondsp.compose.controls.GlassDialog
import app.siphondsp.compose.controls.GlassDialogText
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxHeight
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtSlider
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.controls.bmwFocusRing
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.interop.ConvolverStatus
import app.siphondsp.model.BandIrManifest
import app.siphondsp.model.ConvolverIrStore
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Current stereo FIR or three correction-only band FIRs; the two sets stay stored independently. */
@Composable
fun ConvolverScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val context = LocalContext.current.applicationContext
    val ir = rememberIrState(context)
    val controls: @Composable (ConvolverRow) -> Unit = { row -> ConvolverGroup(dsp, ir, row) }
    val impulse: @Composable (ConvolverRow) -> Unit = { row -> IrGroup(ir, dsp, row) }
    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                ConvolverOnCarGraph(
                    dsp.get(NativeBmwDspValues.INDEX_CONVOLVER_GAIN), dsp.isOn(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED),
                    Modifier.artRect(artDp(GraphX, GroupTop, GraphWidth, GraphHeight)),
                )
                controls { y, h -> Modifier.artRect(artDp(LeftX, GroupTop + y, HeadGroupWidth, h)) }
                impulse { y, h -> Modifier.artRect(artDp(RightX, GroupTop + y, HeadGroupWidth, h)) }
            }
        } else {
            PhoneConvolverPage(controls, impulse, modifier) { m ->
                ConvolverOnCarGraph(dsp.get(NativeBmwDspValues.INDEX_CONVOLVER_GAIN), dsp.isOn(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED), m)
            }
        }
    }
}

/** Where one row of a group goes: [y] dp down the group on the head unit, [h] tall. */
private typealias ConvolverRow = (y: Int, h: Int) -> Modifier

/** The stored IR's name, the running engine's view of it, and the import/clear actions. */
private class ConvolverIrUiState(private val context: Context) {
    var slot by mutableStateOf(-1)
    var mode by mutableStateOf(ConvolverIrStore.mode(context))
    var runningMode by mutableStateOf<Int?>(null)
    var activeMode by mutableStateOf<Int?>(null)
    var loadError by mutableStateOf<String?>(null)
    var name by mutableStateOf<String?>(null)
    /** Every slot's stored file (Stereo, Low, Mid, High), for the slot tiles. */
    var slotNames by mutableStateOf<Map<Int, String?>>(emptyMap())
    var status by mutableStateOf<ConvolverStatus?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)
    var allBandsStored by mutableStateOf(false)
    var stereoStored by mutableStateOf(false)
    var pendingPackage by mutableStateOf<android.net.Uri?>(null)

    /** Blocking (file + native mutex): IO dispatcher only. */
    fun refreshBlocking() {
        val selectedSlot = slot
        name = ConvolverIrStore.displayName(context, selectedSlot)
        slotNames = Slots.associateWith { ConvolverIrStore.displayName(context, it) }
        status = RootlessAudioProcessorService.convolverStatus(selectedSlot)
        runningMode = RootlessAudioProcessorService.convolverMode()
        activeMode = RootlessAudioProcessorService.activeConvolverMode()
        loadError = RootlessAudioProcessorService.convolverLoadError()
        mode = ConvolverIrStore.mode(context)
        serviceRunning = runningMode != null
        allBandsStored = ConvolverIrStore.allBandsStored(context)
        // What the engine runs, not just a file on disk: a stored IR can have been rejected.
        // With no service, the stored file is all there is to go by.
        stereoStored = RootlessAudioProcessorService.convolverStatus(ConvolverIrStore.SLOT_STEREO)
            ?.let { it.loaded && it.streamFrames > 0 && it.error == null }
            ?: ConvolverIrStore.hasIr(context, ConvolverIrStore.SLOT_STEREO)
    }

    suspend fun selectSlot(value: Int) {
        if (busy) return
        slot = value
        name = null
        status = null
        withContext(Dispatchers.IO) { refreshBlocking() }
    }

    suspend fun importIr(uri: android.net.Uri, selected: Int) = runAction {
        ConvolverIrStore.importIr(context, uri, selected) ?:
            RootlessAudioProcessorService.reloadConvolverIr(selected)
    }

    suspend fun importPackage(uri: android.net.Uri, dsp: BmwDspState, allowMismatch: Boolean = false) = runAction {
        // The actual per-output corners, not the retired legacy Low/Mid indices.
        val corners = BandIrManifest.appCorners(dsp::get)
        val result = ConvolverIrStore.importBandPackage(
            context, uri, corners.first().first, corners.first().second, allowMismatch, corners,
        )
        if (result?.startsWith("Crossover mismatch:") == true) {
            pendingPackage = uri
            return@runAction result
        }
        pendingPackage = null
        result ?: RootlessAudioProcessorService.reloadBandConvolverSet()
    }

    suspend fun clear(dsp: BmwDspState) {
        if (slot >= 0 && mode == ConvolverIrStore.MODE_BANDS) {
            switchMode(ConvolverIrStore.MODE_STEREO, dsp)
            if (mode != ConvolverIrStore.MODE_STEREO || message != null) return
        }
        runAction {
            ConvolverIrStore.clear(context, slot)
            RootlessAudioProcessorService.reloadConvolverIr(slot)
        }
    }

    suspend fun switchMode(target: Int, dsp: BmwDspState) {
        if (busy || target == mode) return
        val currentGain = dsp.get(NativeBmwDspValues.INDEX_CONVOLVER_GAIN)
        var targetGain = currentGain
        runAction {
            if (target == ConvolverIrStore.MODE_BANDS && !ConvolverIrStore.allBandsStored(context)) {
                return@runAction "Load Low, Mid and High IRs before selecting per-band mode"
            }
            if (!ConvolverIrStore.saveGain(context, mode, currentGain)) {
                return@runAction "Could not save the current gain"
            }
            targetGain = ConvolverIrStore.savedGain(context, target, if (target == 1) 0f else currentGain)
                .coerceAtMost(if (target == 1) 0f else NativeBmwDspValues.CONVOLVER_MAX_GAIN_DB)
            RootlessAudioProcessorService.setConvolverMode(target)?.let { return@runAction it }
            if (!ConvolverIrStore.setMode(context, target)) return@runAction "Could not save the convolution mode"
            null
        }
        if (message == null) dsp.commit(NativeBmwDspValues.INDEX_CONVOLVER_GAIN, targetGain)
    }

    private suspend fun runAction(action: () -> String?) {
        if (busy) return
        busy = true
        message = null
        try {
            message = withContext(Dispatchers.IO) {
                val result = action()
                refreshBlocking()
                result
            }
        } finally {
            busy = false
        }
    }
}

@Composable
private fun rememberIrState(context: Context): ConvolverIrUiState {
    val state = remember { ConvolverIrUiState(context) }
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    // The engine rebuilds the kernel on a stream-rate change and loads the IR at service start,
    // so keep polling while the page is up (off the main thread: the native read waits out a load).
    LaunchedEffect(resumed) {
        while (resumed) {
            withContext(Dispatchers.IO) { state.refreshBlocking() }
            delay(StatusPollMs)
        }
    }
    return state
}

@Composable
private fun ConvolverGroup(dsp: BmwDspState, ir: ConvolverIrUiState, row: ConvolverRow) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    ArtGroupHeader(
        title = "Convolver",
        accent = ConvolverAccent,
        modifier = row(0, HeaderHeight),
        checked = dsp.isOn(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED),
        onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED, if (on) 1f else 0f) },
    )
    // The mode as a switch, like the other pages' HIGH / MID / LOW.
    BmwSegmentedControl(
        options = listOf("STEREO", "PER BAND"),
        selectedIndex = ir.mode,
        onSelect = { if (!ir.busy) scope.launch { ir.switchMode(it, dsp) } },
        modifier = row(48, 44),
        segmentHeight = 36.dp,
    )
    ArtSlider(
        label = "Mode gain",
        value = dsp.get(NativeBmwDspValues.INDEX_CONVOLVER_GAIN),
        valueRange = NativeBmwDspValues.CONVOLVER_MIN_GAIN_DB..(if (ir.mode == 1) 0f else NativeBmwDspValues.CONVOLVER_MAX_GAIN_DB),
        step = .5f, unit = "dB", accentColor = ConvolverAccent,
        onPreview = { dsp.preview(NativeBmwDspValues.INDEX_CONVOLVER_GAIN, it) },
        onCommit = { gain ->
            if (dsp.commit(NativeBmwDspValues.INDEX_CONVOLVER_GAIN, gain)) {
                scope.launch(Dispatchers.IO) {
                    if (!ConvolverIrStore.saveGain(context, ir.mode, gain)) ir.message = "Could not save this mode's gain"
                }
            }
        },
        enabled = !ir.busy, modifier = row(104, 48), labelWidth = 96.dp,
    )
    InfoLine(if (ir.mode == 0) "Stereo IR after the band sum" else "Low / Mid / High IRs before the band sum", row(164, 28))
    val bypassed = dsp.isOn(NativeBmwDspValues.INDEX_CROSSOVER_BYPASS)
    val convolverOn = dsp.isOn(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED)
    InfoLine(when {
        bypassed && ir.mode == 1 && convolverOn && ir.stereoStored -> "Bypassed: stereo IR runs until crossovers return"
        bypassed && ir.mode == 1 && convolverOn -> "Bypassed: no convolution (no stereo IR loaded)"
        bypassed -> "Crossovers bypassed: band FIRs cannot run"
        else -> "Existing crossover settings are retained"
    }, row(196, 28))
    val error = ir.message ?: ir.loadError
    when {
        error != null -> InfoLine(error, row(236, 56), color = ErrorColor, maxLines = 2)
        ir.serviceRunning && ir.runningMode != ir.mode -> InfoLine("Requested mode is not active — check the loaded IRs", row(236, 56), ErrorColor, maxLines = 2)
        !ir.serviceRunning -> InfoLine("Saved; loads when the DSP engine starts", row(236, 28))
        ir.runningMode == 1 && ir.activeMode == 0 && bypassed ->
            InfoLine("Per-band paused while the crossovers are bypassed", row(236, 28), Color.White)
        // Bypass just switched on; per-band stops after the output's short dip.
        ir.runningMode == 1 && ir.activeMode == 1 && bypassed ->
            InfoLine("Pausing per-band: crossovers bypassed", row(236, 28), Color.White)
        // The audio side switches at its next buffer, after a short dip: with nothing playing
        // that's whenever audio starts.
        ir.activeMode != null && ir.activeMode != ir.runningMode ->
            InfoLine("${if (ir.runningMode == 1) "Per-band" else "Stereo"} selected; applies as audio plays", row(236, 28), Color.White)
        else -> InfoLine("Active: ${if (ir.activeMode == 1) "per-band" else "stereo"} convolution", row(236, 28), Color.White)
    }
}

@Composable
private fun IrGroup(ir: ConvolverIrUiState, dsp: BmwDspState, row: ConvolverRow) {
    val scope = rememberCoroutineScope()
    // Capture the destination at picker launch, not when an asynchronous picker returns.
    var pickerSlot by remember { mutableStateOf(-1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { ir.importIr(it, pickerSlot) } }
    }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { ir.importPackage(it, dsp) } }
    }
    ir.pendingPackage?.let { uri ->
        // The prompt text lives in message; clear it with the dialog, or it stays as an error.
        val dismiss = { ir.pendingPackage = null; ir.message = null }
        GlassDialog(
            title = "Different crossover settings",
            icon = R.drawable.ic_twotone_warning_24dp,
            onDismissRequest = dismiss,
            buttons = listOf(
                GlassButton("Cancel", GlassButtonKind.Cancel, onClick = dismiss),
                GlassButton("Import anyway", GlassButtonKind.Primary) { scope.launch { ir.importPackage(uri, dsp, allowMismatch = true) } },
            ),
        ) { GlassDialogText(ir.message ?: "The package was designed for different crossovers. Import without changing your app settings?") }
    }
    ArtGroupHeader("Impulse responses", Color.White, row(0, HeaderHeight))
    // A tile per slot with its stored file, so all four read at a glance; tap one to work on it.
    Column(row(48, SlotGridHeight), verticalArrangement = Arrangement.spacedBy(SlotGap)) {
        for (pair in Slots.chunked(2)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(SlotGap)) {
                for (slot in pair) {
                    SlotTile(
                        label = SlotLabels.getValue(slot), file = ir.slotNames[slot], selected = ir.slot == slot,
                        enabled = !ir.busy, modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { scope.launch { ir.selectSlot(slot) } }
                }
            }
        }
    }
    val s = ir.status
    val bufferMs = if (s != null && s.streamRate > 0) 1024_000.0 / s.streamRate else 0.0
    val lines = when {
        ir.name == null -> listOf("Load a mono or stereo correction WAV")
        s == null || !ir.serviceRunning -> listOf("Stored; loads when the DSP engine starts")
        !s.loaded -> listOf("Not loaded by the engine")
        else -> listOf(
            "${if (s.channels == 2) "Stereo" else "Mono"}, ${s.sourceRate} Hz, ${s.sourceFrames} samples",
            "${"%.0f".format(s.lengthMs)} ms at ${s.streamRate} Hz; peak ${formatDb(s.peakGainDb.toFloat())}",
            "IR bulk ${"%.1f".format(s.latencyMs)} + buffer ${"%.1f".format(bufferMs)} = ${"%.1f".format(s.latencyMs + bufferMs)} ms",
        )
    }
    lines.forEachIndexed { i, text -> InfoLine(text, row(164 + 26 * i, 26)) }
    Row(row(248, 48), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // "Load…" fills the selected slot with a WAV: short enough that all three fit a 296dp column.
        GlassChip(if (ir.busy) "Loading…" else "Load…", arrow = false, enabled = !ir.busy, onClick = {
            pickerSlot = ir.slot
            picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/*", "application/octet-stream"))
        })
        GlassChip("Load set…", arrow = false, enabled = !ir.busy, onClick = {
            packagePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        })
        // Red, apart at the end: it throws the slot's file away.
        GlassChip("Clear", arrow = false, enabled = !ir.busy && ir.name != null, accent = ErrorColor, onClick = { scope.launch { ir.clear(dsp) } })
    }
    InfoLine(if (ir.allBandsStored) "Bands run in parallel: buffer delay not tripled" else "Load all 3 slots to enable per-band mode", row(302, 28))
    s?.error?.let { InfoLine(it, row(330, 28), ErrorColor) }
}

@Composable
private fun InfoLine(text: String, modifier: Modifier, color: Color = InfoColor, maxLines: Int = 1) {
    // One line by default: the head unit's rows are absolutely placed ~30dp apart, so a second
    // line would overlap the next row. Rows given 56dp pass maxLines = 2.
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Text(text, color = color, fontSize = 13.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** An IR slot: its name and the file in it, edged in the convolver's blue while it's the one being worked on. */
@Composable
private fun SlotTile(label: String, file: String?, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .clip(shape)
            .background(Color(0xFF111216))
            .border(if (selected) 2.dp else 1.dp, if (selected) ConvolverAccent else Color(0xFF353840), shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .bmwFocusRing(source, cornerRadius = 12.dp)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = if (selected) Color.White else Color(0xFFB4B8C2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(file ?: "No IR", color = if (file != null) InfoColor else Color(0xFF5A5F6A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Phone: the two groups side by side when they fit, else stacked, then the graph; scrolls. */
@Composable
private fun PhoneConvolverPage(
    left: @Composable (ConvolverRow) -> Unit,
    right: @Composable (ConvolverRow) -> Unit,
    modifier: Modifier,
    graph: @Composable (Modifier) -> Unit,
) {
    val stack: ConvolverRow = { _, h -> Modifier.fillMaxWidth().height(h.dp) }
    val group: @Composable (@Composable (ConvolverRow) -> Unit) -> Unit = { content ->
        Column(Modifier.width(GroupWidth.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content(stack) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= GroupWidth.dp * 2 + PhoneGroupGap + 24.dp
        Box(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PhoneGroupGap)) { group(left); group(right) }
                } else {
                    group(left)
                    group(right)
                }
                graph(Modifier.widthIn(max = GroupWidth.dp * 2 + PhoneGroupGap).fillMaxWidth().height(300.dp))
            }
        }
    }
}

private fun formatDb(db: Float): String = "%+.1f dB".format(db)

private val ConvolverAccent = Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
private val InfoColor = Color(0xFF969EA8)
private val ErrorColor = Color(0xFFFF6B6B)
private val CapColor = Color(BmwDashboardSkin.SLIDER_LIMITER_COLOR)
private const val StatusPollMs = 1000L
private const val MaxIrSeconds = "1.36"

// Head-unit layout, in the 1280x480 editor's dp: the IR-on-your-car graph on the left (x 152..626,
// like the Output page's gain budget), then the two 296dp columns (x 646..1250), all starting
// under the toolbar line. The phone keeps 370dp columns.
private const val GroupWidth = 370
private const val HeadGroupWidth = 296
private const val GraphX = 152
private const val GraphWidth = 474
private const val GraphHeight = 384
private const val LeftX = 646
private const val RightX = 954
private const val GroupTop = 76
private const val HeaderHeight = 40
// The four slot tiles, two by two.
private const val SlotGridHeight = 108
private val SlotGap = 8.dp
private val Slots = listOf(ConvolverIrStore.SLOT_STEREO, 0, 1, 2)
private val SlotLabels = mapOf(ConvolverIrStore.SLOT_STEREO to "Stereo", 0 to "Low", 1 to "Mid", 2 to "High")
private val PhoneGroupGap = 60.dp
