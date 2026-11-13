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
import app.siphondsp.model.ConvolverIrStore
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Output workspace's CONVOLVER page: the stereo FIR convolver, after the band sum and ahead
 * of the master limiter.
 *
 * - **Convolver**: the header switch turns it on, or off as a bypass (the engine crossfades over
 *   20 ms either way); Gain trims the IR's level. The engine caps the gain at the IR's peak
 *   response: an IR never lifts any frequency above unity (a boosting IR is brought down to 0 dB
 *   at its loudest frequency). The page shows the gain actually applied.
 * - **Impulse response**: load a mono or stereo WAV (copied into app storage; a mono IR runs on
 *   both channels, a stereo one L on L and R on R), what the engine made of it -- rates, length,
 *   whether it was resampled to the stream, its bulk latency (where its largest sample sits) --
 *   and clear.
 */
@Composable
fun ConvolverScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val context = LocalContext.current.applicationContext
    val ir = rememberIrState(context)
    val controls: @Composable (ConvolverRow) -> Unit = { row -> ConvolverGroup(dsp, ir, row) }
    val impulse: @Composable (ConvolverRow) -> Unit = { row -> IrGroup(ir, row) }
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
    var name by mutableStateOf<String?>(null)
    var status by mutableStateOf<ConvolverStatus?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)

    /** Blocking (file + native mutex): IO dispatcher only. */
    fun refreshBlocking() {
        val n = ConvolverIrStore.displayName(context)
        val s = RootlessAudioProcessorService.convolverStatus()
        name = n
        status = s
        serviceRunning = s != null
    }

    suspend fun importIr(uri: android.net.Uri) = runAction {
        ConvolverIrStore.importIr(context, uri) ?: RootlessAudioProcessorService.reloadConvolverIr()
    }

    suspend fun clear() = runAction {
        ConvolverIrStore.clear(context)
        RootlessAudioProcessorService.reloadConvolverIr()
    }

    private suspend fun runAction(action: () -> String?) {
        busy = true
        message = null
        val error = withContext(Dispatchers.IO) {
            val result = action()
            refreshBlocking()
            result
        }
        message = error
        busy = false
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
    ArtGroupHeader(
        title = "Convolver",
        accent = ConvolverAccent,
        modifier = row(0, HeaderHeight),
        checked = dsp.isOn(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED),
        onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED, if (on) 1f else 0f) },
    )
    DspArtSlider(
        dsp, "Gain", NativeBmwDspValues.INDEX_CONVOLVER_GAIN,
        NativeBmwDspValues.CONVOLVER_MIN_GAIN_DB..NativeBmwDspValues.CONVOLVER_MAX_GAIN_DB, 0.5f, "dB",
        ConvolverAccent, row(52, 48),
    )
    val peak = ir.status?.takeIf { it.loaded && it.streamFrames > 0 }?.peakGainDb
    // What the engine applies: the trim, capped so the IR's loudest frequency stays at or below
    // unity (NativeBmwDsp::ConvolverStage::process()).
    val trim = dsp.get(NativeBmwDspValues.INDEX_CONVOLVER_GAIN)
    InfoLine(
        when {
            peak == null -> "Gain is capped so the IR never lifts above unity"
            trim > -peak -> "Applied: ${formatDb((-peak).toFloat())} (capped at the IR's peak, ${formatDb(peak.toFloat())})"
            else -> "Applied: ${formatDb(trim)}, the IR's loudest frequency at ${formatDb((peak + trim).toFloat())}"
        },
        row(112, 28),
        color = if (peak != null && trim > -peak) CapColor else InfoColor,
    )
    InfoLine(
        when {
            !dsp.isOn(NativeBmwDspValues.INDEX_CONVOLVER_ENABLED) -> "Off: bypassed, the chain's output untouched"
            peak == null -> "On, but no IR loaded: passing through"
            else -> "On: after the band sum, ahead of the limiter"
        },
        row(172, 28),
    )
    InfoLine("21 ms latency while on (1024-sample partitions); none when off", row(204, 28))
}

@Composable
private fun IrGroup(ir: ConvolverIrUiState, row: ConvolverRow) {
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { ir.importIr(it) } }
    }
    ArtGroupHeader("Impulse response", Color.White, row(0, HeaderHeight))
    InfoLine(ir.name ?: "No IR loaded", row(52, 28), color = Color.White)
    val s = ir.status
    val lines = when {
        ir.name == null -> listOf("Mono or stereo WAV, up to ${MaxIrSeconds}s at 48 kHz")
        s == null || !ir.serviceRunning -> listOf("Loads when the DSP engine starts")
        !s.loaded -> listOf("Not loaded by the engine")
        else -> buildList {
            add("${if (s.channels == 2) "Stereo" else "Mono"}, ${s.sourceRate} Hz, ${s.sourceFrames} samples")
            if (s.streamFrames > 0) {
                add(
                    if (s.resampled) "Resampled to ${s.streamRate} Hz: ${s.streamFrames} samples"
                    else "At the stream rate: ${s.streamRate} Hz",
                )
                add("${"%.0f".format(s.lengthMs)} ms, ${s.partitions} partitions, peak ${formatDb(s.peakGainDb.toFloat())}")
                add("Latency ${"%.1f".format(s.latencyMs)} ms (${s.latencyFrames} samples)")
            }
        }
    }
    lines.forEachIndexed { i, text -> InfoLine(text, row(84 + 30 * i, 28)) }
    val error = ir.message ?: ir.status?.error
    if (error != null) InfoLine(error, row(206, 28), color = ErrorColor)
    Row(row(242, 48), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionChip(if (ir.busy) "Loading…" else "Load IR…", enabled = !ir.busy) {
            picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/*", "application/octet-stream"))
        }
        ActionChip("Clear", enabled = !ir.busy && ir.name != null) { scope.launch { ir.clear() } }
    }
}

@Composable
private fun InfoLine(text: String, modifier: Modifier, color: Color = InfoColor) {
    Box(modifier, contentAlignment = Alignment.CenterStart) { ArtLabel(text, color = color) }
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
private const val GroupWidth = 333
private const val LeftX = 317
private const val RightX = 770
private const val GroupTop = 76
private const val HeaderHeight = 40
private val PhoneGroupGap = 60.dp
