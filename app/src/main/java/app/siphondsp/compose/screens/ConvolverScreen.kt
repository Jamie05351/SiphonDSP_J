package app.siphondsp.compose.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
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
                controls { y, h -> Modifier.artRect(artDp(LeftX, GroupTop + y, GroupWidth, h)) }
                impulse { y, h -> Modifier.artRect(artDp(RightX, GroupTop + y, GroupWidth, h)) }
            }
        } else {
            PhoneConvolverPage(controls, impulse, modifier)
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
    var status by mutableStateOf<ConvolverStatus?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)
    var allBandsStored by mutableStateOf(false)
    var pendingPackage by mutableStateOf<android.net.Uri?>(null)

    /** Blocking (file + native mutex): IO dispatcher only. */
    fun refreshBlocking() {
        val selectedSlot = slot
        name = ConvolverIrStore.displayName(context, selectedSlot)
        status = RootlessAudioProcessorService.convolverStatus(selectedSlot)
        runningMode = RootlessAudioProcessorService.convolverMode()
        activeMode = RootlessAudioProcessorService.activeConvolverMode()
        loadError = RootlessAudioProcessorService.convolverLoadError()
        mode = ConvolverIrStore.mode(context)
        serviceRunning = runningMode != null
        allBandsStored = ConvolverIrStore.allBandsStored(context)
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
    Row(row(46, 48), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionChip(if (ir.mode == 0) "✓ Stereo" else "Stereo", !ir.busy) {
            scope.launch { ir.switchMode(0, dsp) }
        }
        ActionChip(if (ir.mode == 1) "✓ Per band" else "Per band", !ir.busy) {
            scope.launch { ir.switchMode(1, dsp) }
        }
    }
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
        enabled = !ir.busy, modifier = row(104, 48), labelWidth = 110.dp,
    )
    InfoLine(if (ir.mode == 0) "Stereo IR after the band sum" else "Low / Mid / High IRs before the band sum", row(164, 28))
    val bypassed = dsp.isOn(NativeBmwDspValues.INDEX_CROSSOVER_BYPASS)
    InfoLine(when {
        bypassed && ir.mode == 1 -> "Crossovers bypassed: the stereo IR runs until they're back"
        bypassed -> "Crossovers bypassed: band FIRs cannot run"
        else -> "Existing crossover settings are retained"
    }, row(196, 28))
    InfoLine(if (ir.mode == 0) "Stereo and band files are stored separately" else "All three bands switch together; no extra crossovers", row(228, 28))
    val error = ir.message ?: ir.loadError
    when {
        error != null -> InfoLine(error, row(268, 56), color = ErrorColor)
        ir.serviceRunning && ir.runningMode != ir.mode -> InfoLine("Requested mode is not active — check the loaded IRs", row(268, 56), ErrorColor)
        !ir.serviceRunning -> InfoLine("Saved; loads when the DSP engine starts", row(268, 28))
        ir.runningMode == 1 && ir.activeMode == 0 -> InfoLine("Per-band paused while the crossovers are bypassed", row(268, 28), Color.White)
        else -> InfoLine("Active: ${if (ir.activeMode == 1) "per-band" else "stereo"} convolution", row(268, 28), Color.White)
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
        AlertDialog(
            onDismissRequest = { ir.pendingPackage = null },
            title = { Text("Different crossover settings") },
            text = { Text(ir.message ?: "The package was designed for different crossovers. Import without changing your app settings?") },
            confirmButton = { TextButton(onClick = { scope.launch { ir.importPackage(uri, dsp, allowMismatch = true) } }) { Text("Import anyway") } },
            dismissButton = { TextButton(onClick = { ir.pendingPackage = null }) { Text("Cancel") } },
        )
    }
    ArtGroupHeader("Impulse responses", Color.White, row(0, HeaderHeight))
    Row(row(44, 48), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Stereo", "Low", "Mid", "High").forEachIndexed { index, label ->
            val slot = index - 1
            ActionChip(if (ir.slot == slot) "• $label" else label, !ir.busy) {
                scope.launch { ir.selectSlot(slot) }
            }
        }
    }
    InfoLine(ir.name ?: "No IR in this slot", row(100, 28), color = Color.White)
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
    lines.forEachIndexed { i, text -> InfoLine(text, row(132 + 30 * i, 28)) }
    Row(row(230, 48), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionChip(if (ir.busy) "Loading…" else "Load WAV…", enabled = !ir.busy) {
            pickerSlot = ir.slot
            picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/*", "application/octet-stream"))
        }
        ActionChip("Clear", enabled = !ir.busy && ir.name != null) { scope.launch { ir.clear(dsp) } }
        ActionChip("Load set…", enabled = !ir.busy) {
            packagePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
    }
    InfoLine(if (ir.allBandsStored) "3 bands run in parallel: buffer delay is not tripled" else "Load all 3 slots to enable per-band mode", row(282, 28))
    s?.error?.let { InfoLine(it, row(314, 28), ErrorColor) }
}

@Composable
private fun InfoLine(text: String, modifier: Modifier, color: Color = InfoColor) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Text(text, color = color, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        interactionSource = interactionSource,
        modifier = Modifier.bmwFocusRing(interactionSource),
    )
}

/** Phone: the two groups side by side when they fit, else stacked; scrolls if too tall. */
@Composable
private fun PhoneConvolverPage(
    left: @Composable (ConvolverRow) -> Unit,
    right: @Composable (ConvolverRow) -> Unit,
    modifier: Modifier,
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
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(PhoneGroupGap)) { group(left); group(right) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { group(left); group(right) }
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

// Head-unit layout, in the 1280x480 editor's dp: the Output page's two 333dp columns, starting
// under the 69dp toolbar line (status strip + OUTPUT/CONVOLVER finder).
private const val GroupWidth = 370
private const val LeftX = 317
private const val RightX = 760
private const val GroupTop = 76
private const val HeaderHeight = 40
private val PhoneGroupGap = 60.dp
