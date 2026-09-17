package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import app.siphondsp.interop.NativeConfigRevisionStatus
import app.siphondsp.model.debug.NativeBiquadStage
import app.siphondsp.model.debug.NativeCrossoverSnapshot
import app.siphondsp.model.debug.NativeDspOutput
import app.siphondsp.model.debug.NativeDspTruthSnapshot
import app.siphondsp.model.debug.NativePeqBand
import app.siphondsp.model.debug.NativePeqBank
import app.siphondsp.model.debug.RootlessPipelineRuntimeSnapshot
import app.siphondsp.service.RootlessAudioProcessorService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val REFRESH_INTERVAL_MS = 500L

/**
 * Developer/debug screen proving what NativeBmwDspProcessor and the native PEQ engine are
 * ACTUALLY running right now -- not SharedPreferences, not cached Kotlin state, not the last
 * requested UI value, not a broadcast. Every value below is sourced from one of:
 *   - [RootlessAudioProcessorService.nativeConfigRevisionStatus] (requested/native-active
 *     revision counters + last apply result, read from JamesDspLocalEngine's own bookkeeping and
 *     the native revision-ack atomics via JNI getters).
 *   - [RootlessAudioProcessorService.nativeTruthSnapshot] (NativeBmwDspProcessor::
 *     captureTruthSnapshot(), one stateMutex_-guarded read of the actual installed crossover
 *     Biquad coefficients and PEQ band arrays -- see that method's doc comment for the wire
 *     layout).
 *   - [RootlessAudioProcessorService.pipelineRuntimeSnapshot] (the running service's own
 *     AudioRecord/AudioTrack/health-watchdog state, read directly, not mirrored).
 * Read-only: this screen has no controls that change DSP configuration.
 */
@Composable
fun NativeTruthScreen(modifier: Modifier = Modifier) {
    var revision by remember { mutableStateOf<NativeConfigRevisionStatus?>(null) }
    var truth by remember { mutableStateOf<NativeDspTruthSnapshot?>(null) }
    var pipeline by remember { mutableStateOf<RootlessPipelineRuntimeSnapshot?>(null) }
    var handleReady by remember { mutableStateOf<Boolean?>(null) }
    var engineSampleRate by remember { mutableStateOf<Float?>(null) }
    var lastRefreshedAtMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            revision = RootlessAudioProcessorService.nativeConfigRevisionStatus()
            truth = RootlessAudioProcessorService.nativeTruthSnapshot()
            pipeline = RootlessAudioProcessorService.pipelineRuntimeSnapshot()
            handleReady = RootlessAudioProcessorService.nativeBmwPeqHandleReady()
            engineSampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate()
            lastRefreshedAtMs = System.currentTimeMillis()
            nowMs = lastRefreshedAtMs
            delay(REFRESH_INTERVAL_MS)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Last refreshed ${if (lastRefreshedAtMs == 0L) "never" else "${(nowMs - lastRefreshedAtMs).coerceAtLeast(0)} ms ago"}",
                color = Color.Gray,
                fontSize = 11.sp,
            )
        }
        item { EngineSection(handleReady, engineSampleRate, truth) }
        item { PipelineSection(pipeline) }
        item { RevisionSection("DSP REVISION", revision?.let { RevisionRow.dsp(it) }) }
        item { RevisionSection("PEQ REVISION", revision?.let { RevisionRow.peq(it) }) }
        truth?.crossovers?.let { crossovers ->
            item { SectionHeader("CROSSOVERS") }
            items(
                listOf(
                    NativeDspOutput.LOW_LEFT, NativeDspOutput.LOW_RIGHT,
                    NativeDspOutput.MID_LEFT, NativeDspOutput.MID_RIGHT,
                ),
            ) { output ->
                crossovers[output]?.let { CrossoverCard(output, it) }
            }
        }
        truth?.peq?.let { peq ->
            item { SectionHeader("PEQ") }
            item { PeqBankCard("Full-range", peq.full) }
            item { PeqBankCard("Low band", peq.low) }
            item { PeqBankCard("Mid band", peq.mid) }
        }
        item { SyncErrorsSection(revision) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun DebugCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(10.dp), content = content)
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun fmt(value: Double, decimals: Int = 2): String {
    val scale = Math.pow(10.0, decimals.toDouble())
    return ((value * scale).roundToInt() / scale).toString()
}

@Composable
private fun EngineSection(
    handleReady: Boolean?,
    engineSampleRate: Float?,
    truth: NativeDspTruthSnapshot?,
) {
    Column {
        SectionHeader("ENGINE")
        DebugCard {
            KeyValueRow(
                "Native handle available",
                handleReady?.toString() ?: "unavailable",
                if (handleReady == true) BmwGreen else BmwRed,
            )
            KeyValueRow(
                "Native DSP initialized",
                (truth != null).toString(),
                if (truth != null) BmwGreen else BmwRed,
            )
            KeyValueRow("Engine sample rate (Kotlin-set)", engineSampleRate?.let { "$it Hz" } ?: "unavailable")
            KeyValueRow(
                "Native processor sample rate",
                truth?.sampleRate?.let { "${fmt(it, 0)} Hz" } ?: "unavailable",
            )
        }
    }
}

@Composable
private fun PipelineSection(pipeline: RootlessPipelineRuntimeSnapshot?) {
    Column {
        SectionHeader("PIPELINE")
        DebugCard {
            if (pipeline == null) {
                KeyValueRow("Rootless service", "not running", BmwYellow)
                return@DebugCard
            }
            KeyValueRow(
                "Recorder",
                if (pipeline.recorderRecording) "recording" else if (pipeline.recorderStateInitialized) "initialized, idle" else "inactive",
                if (pipeline.recorderRecording) BmwGreen else BmwYellow,
            )
            KeyValueRow(
                "AudioTrack",
                if (pipeline.trackPlaying) "playing" else if (pipeline.trackStateInitialized) "initialized, idle" else "inactive",
                if (pipeline.trackPlaying) BmwGreen else BmwYellow,
            )
            KeyValueRow(
                "Recorder recreation",
                when {
                    pipeline.recreationInProgress -> "in progress"
                    pipeline.recreateRequested -> "requested"
                    else -> "none"
                },
                if (pipeline.recreateRequested || pipeline.recreationInProgress) BmwYellow else Color.White,
            )
            KeyValueRow(
                "Measurement generator",
                if (pipeline.measurementGeneratorActive) "active" else "inactive",
            )
            KeyValueRow(
                "Processor disposing",
                pipeline.processorDisposing.toString(),
                if (pipeline.processorDisposing) BmwYellow else Color.White,
            )
            KeyValueRow(
                "Service disposing",
                pipeline.serviceDisposing.toString(),
                if (pipeline.serviceDisposing) BmwYellow else Color.White,
            )
            KeyValueRow(
                "Pipeline health",
                pipeline.pipelineHealthState ?: "unavailable",
                when (pipeline.pipelineHealthState) {
                    "HEALTHY" -> BmwGreen
                    "FAILED" -> BmwRed
                    null -> Color.Gray
                    else -> BmwYellow
                },
            )
            pipeline.pipelineHealthReason?.let { KeyValueRow("Health reason", it) }
        }
    }
}

private data class RevisionRow(
    val requested: Long,
    val active: Long,
    val syncState: NativeConfigRevisionStatus.SyncState,
    val lastApplySuccess: Boolean?,
    val lastFailure: String?,
    val mismatchDurationMs: Long?,
) {
    companion object {
        fun dsp(status: NativeConfigRevisionStatus) = RevisionRow(
            status.requestedDspRevision, status.nativeActiveDspRevision, status.dspSyncState,
            status.lastDspApplySuccess, status.lastDspFailure, status.dspMismatchDurationMs(),
        )
        fun peq(status: NativeConfigRevisionStatus) = RevisionRow(
            status.requestedPeqRevision, status.nativeActivePeqRevision, status.peqSyncState,
            status.lastPeqApplySuccess, status.lastPeqFailure, status.peqMismatchDurationMs(),
        )
    }
}

@Composable
private fun StatusColorFor(state: NativeConfigRevisionStatus.SyncState): Color = when (state) {
    NativeConfigRevisionStatus.SyncState.MATCH -> BmwGreen
    NativeConfigRevisionStatus.SyncState.STALE -> BmwYellow
    NativeConfigRevisionStatus.SyncState.ERROR -> BmwRed
    NativeConfigRevisionStatus.SyncState.UNINITIALIZED -> Color.Gray
}

@Composable
private fun RevisionSection(title: String, row: RevisionRow?) {
    Column {
        SectionHeader(title)
        DebugCard {
            if (row == null) {
                KeyValueRow("Status", "unavailable")
                return@DebugCard
            }
            KeyValueRow("Requested revision", row.requested.toString())
            KeyValueRow("Native active revision", row.active.toString())
            KeyValueRow("Sync state", row.syncState.name, StatusColorFor(row.syncState))
            row.mismatchDurationMs?.let { KeyValueRow("Mismatch duration", "${it} ms") }
            KeyValueRow("Last apply result", row.lastApplySuccess?.toString() ?: "never attempted")
            row.lastFailure?.let { KeyValueRow("Last failure reason", it, BmwRed) }
        }
    }
}

private fun NativeBiquadStage.describe(): String {
    val topologyName = when (topology) {
        NativeBiquadStage.TOPOLOGY_SVF2 -> if (isIdentity) "SVF2 (identity/bypassed)" else "SVF2"
        NativeBiquadStage.TOPOLOGY_ONE_POLE_ALLPASS -> "1-pole allpass"
        NativeBiquadStage.TOPOLOGY_ONE_POLE_LOWPASS -> "1-pole lowpass"
        NativeBiquadStage.TOPOLOGY_ONE_POLE_HIGHPASS -> "1-pole highpass"
        else -> "unknown ($topology)"
    }
    return topologyName
}

@Composable
private fun CrossoverCard(output: NativeDspOutput, snapshot: NativeCrossoverSnapshot) {
    DebugCard {
        Text(
            output.name.replace('_', ' '),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        KeyValueRow(
            "Type",
            "${snapshot.crossoverType.label} (${snapshot.crossoverType.dbPerOctave} dB/oct)",
        )
        KeyValueRow("Crossover freq", "${fmt(snapshot.crossoverFreqHz, 0)} Hz")
        KeyValueRow(
            "Subsonic HPF",
            if (snapshot.subsonicEnabled) "${fmt(snapshot.subsonicFreqHz, 0)} Hz" else "off",
        )
        KeyValueRow("Mute", snapshot.muted.toString(), if (snapshot.muted) BmwYellow else Color.White)
        KeyValueRow("Polarity inverted", snapshot.polarityInverted.toString())
        KeyValueRow("Output gain", "${fmt(snapshot.gainDb)} dB")
        KeyValueRow("Delay", "${fmt(snapshot.delayMs)} ms")
        KeyValueRow("Active stage count", snapshot.activeStageCount.toString())
        KeyValueRow("Stage 1", snapshot.stage1.describe())
        KeyValueRow("Stage 2", snapshot.stage2.describe())
        KeyValueRow(
            "Topology fingerprint",
            snapshot.topologyFingerprint(),
            MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun PeqBankCard(label: String, bank: NativePeqBank) {
    DebugCard {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        KeyValueRow("Raw band count", bank.rawBandCount.toString())
        KeyValueRow("Active left / right", "${bank.leftActiveCount} / ${bank.rightActiveCount}")
        if (bank.bands.isEmpty()) {
            KeyValueRow("Bands", "none")
        }
        bank.bands.forEachIndexed { index, band -> PeqBandRow(index, band) }
    }
}

@Composable
private fun PeqBandRow(index: Int, band: NativePeqBand) {
    val channelLabel = when (band.channel) {
        1 -> "L"
        2 -> "R"
        else -> "L+R"
    }
    KeyValueRow(
        "#$index",
        "type=${band.type} f=${fmt(band.frequencyHz, 0)}Hz gain=${fmt(band.gainDb)}dB Q=${fmt(band.q)} ch=$channelLabel" +
            if (band.active) "" else " (inactive)",
        if (band.active) Color.White else Color.Gray,
    )
}

@Composable
private fun SyncErrorsSection(revision: NativeConfigRevisionStatus?) {
    Column {
        SectionHeader("SYNC / ERRORS")
        DebugCard {
            if (revision == null) {
                KeyValueRow("Status", "engine unavailable", BmwRed)
                return@DebugCard
            }
            KeyValueRow("DSP", revision.dspSyncState.name, StatusColorFor(revision.dspSyncState))
            KeyValueRow("PEQ", revision.peqSyncState.name, StatusColorFor(revision.peqSyncState))
        }
    }
}

private val BmwGreen = Color(0xFF3DDC84)
private val BmwYellow = Color(0xFFF2C94C)
private val BmwRed = Color(0xFFE74C3C)
