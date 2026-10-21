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
import app.siphondsp.interop.JamesDspWrapper
import app.siphondsp.interop.NativeConfigRevisionStatus
import app.siphondsp.model.debug.CpuFrequencies
import app.siphondsp.model.debug.NativeBiquadStage
import app.siphondsp.model.debug.NativeCrossoverSnapshot
import app.siphondsp.model.debug.NativeDspOutput
import app.siphondsp.model.debug.NativeDspTruthSnapshot
import app.siphondsp.model.debug.NativePeqBand
import app.siphondsp.model.debug.NativePeqBank
import app.siphondsp.model.debug.RealtimeDiagnostics
import app.siphondsp.model.debug.RootlessPipelineRuntimeSnapshot
import app.siphondsp.service.RootlessAudioProcessorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    var cpu by remember { mutableStateOf<List<CpuFrequencies.Policy>>(emptyList()) }
    var lastRefreshedAtMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            revision = RootlessAudioProcessorService.nativeConfigRevisionStatus()
            truth = RootlessAudioProcessorService.nativeTruthSnapshot()
            pipeline = RootlessAudioProcessorService.pipelineRuntimeSnapshot()
            handleReady = RootlessAudioProcessorService.nativeBmwPeqHandleReady()
            engineSampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate()
            cpu = withContext(Dispatchers.IO) { CpuFrequencies.read() }
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
                fontSize = 14.sp,
            )
        }
        item { EngineSection(handleReady, engineSampleRate, truth) }
        item { PipelineSection(pipeline) }
        item { RealtimeSection(pipeline?.realtime, cpu) }
        item { RevisionSection("DSP REVISION", revision?.let { RevisionRow.dsp(it) }) }
        item { RevisionSection("PEQ REVISION", revision?.let { RevisionRow.peq(it) }) }
        truth?.crossovers?.let { crossovers ->
            item { SectionHeader("CROSSOVERS") }
            items(
                listOf(
                    NativeDspOutput.LOW_LEFT, NativeDspOutput.LOW_RIGHT,
                    NativeDspOutput.MID_LEFT, NativeDspOutput.MID_RIGHT,
                    NativeDspOutput.HIGH_LEFT, NativeDspOutput.HIGH_RIGHT,
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
            item { PeqBankCard("High band", peq.high) }
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
        fontSize = 16.sp,
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
        Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        Text(value, color = valueColor, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
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

/** "12 s ago" / "4 min ago" / "2 h ago" for an age in milliseconds. */
private fun formatAgo(ageMs: Long): String {
    val s = ageMs.coerceAtLeast(0) / 1000
    return when {
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
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
                "Pipeline recreations",
                pipeline.pipelineRecreations.toString(),
                if (pipeline.pipelineRecreations > 0) BmwYellow else Color.White,
            )
            pipeline.lastRecreationReason?.let { reason ->
                val packages = if (pipeline.lastRecreationPackages.isEmpty()) "" else
                    " · ${pipeline.lastRecreationPackages.joinToString()}"
                KeyValueRow("Last recreation", "$reason$packages · ${formatAgo(pipeline.lastRecreationAgeMs)}")
            }
            KeyValueRow(
                "Exclusion checks kept pipeline",
                pipeline.exclusionRechecksSkipped.toString(),
                if (pipeline.exclusionRechecksSkipped > 0) BmwGreen else Color.White,
            )
            KeyValueRow(
                "Measurement generator",
                if (pipeline.measurementGeneratorActive) "active" else "inactive",
            )
            KeyValueRow(
                "Playback sessions processed",
                pipeline.playbackSessions.toString(),
                if (pipeline.playbackSessions > 0) BmwGreen else BmwYellow,
            )
            KeyValueRow("Excluded apps", pipeline.excludedApps.ifEmpty { listOf("none") }.joinToString())
            KeyValueRow(
                "Auto-excluded (block capture)",
                pipeline.autoExcludedApps.ifEmpty { listOf("none") }.joinToString(),
            )
            if (pipeline.excludedPlaying.isNotEmpty()) {
                // Playing but never processed: the case that otherwise reads as "nothing playing".
                KeyValueRow("Excluded and playing now", pipeline.excludedPlaying.joinToString(), BmwRed)
            }
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

@Composable
private fun RealtimeSection(realtime: RealtimeDiagnostics?, cpu: List<CpuFrequencies.Policy>) {
    Column {
        SectionHeader("REAL-TIME")
        DebugCard {
            if (realtime == null) {
                KeyValueRow("Audio thread", "no buffers yet", BmwYellow)
            } else {
                val budget = realtime.budgetMicros
                fun shareColor(micros: Double) = when {
                    budget <= 0.0 -> Color.White
                    micros >= budget -> BmwRed
                    micros >= budget * 0.75 -> BmwYellow
                    else -> Color.White
                }
                KeyValueRow("Buffers processed", realtime.processCalls.toString())
                KeyValueRow("Budget per buffer", "${fmt(budget / 1000.0)} ms")
                KeyValueRow("Process time, mean", "${fmt(realtime.processMeanMicros / 1000.0)} ms")
                KeyValueRow(
                    "Process time, max (last 10-20 s)",
                    "${fmt(realtime.processRecentMaxMicros / 1000.0)} ms",
                    shareColor(realtime.processRecentMaxMicros),
                )
                KeyValueRow(
                    "Process time, max (session)",
                    "${fmt(realtime.processMaxMicros / 1000.0)} ms",
                    shareColor(realtime.processMaxMicros),
                )
                val b = realtime.budgetBuckets
                if (b.size == 5) {
                    KeyValueRow("Budget used <25% / <50% / <75%", "${b[0]} / ${b[1]} / ${b[2]}")
                    KeyValueRow("Budget used 75-100%", b[3].toString(), if (b[3] > 0) BmwYellow else Color.White)
                }
                KeyValueRow("Over budget", realtime.overruns.toString(), if (realtime.overruns > 0) BmwRed else BmwGreen)
                KeyValueRow(
                    "Underruns, total / last minute",
                    "${realtime.underrunsTotal} / ${realtime.underrunsLastMinute}",
                    if (realtime.underrunsLastMinute > 0) BmwRed else Color.White,
                )
                KeyValueRow("Reads", realtime.readCalls.toString())
                KeyValueRow(
                    "Read size, mean ± sd",
                    "${fmt(realtime.readMean, 1)} ± ${fmt(realtime.readStdDev, 1)} of ${realtime.readRequested}",
                    if (realtime.readStdDev > 0.0) BmwYellow else Color.White,
                )
                KeyValueRow("Read size, min - max", "${realtime.readMin} - ${realtime.readMax}")
                KeyValueRow("Short reads", realtime.shortReads.toString(), if (realtime.shortReads > 0) BmwYellow else Color.White)
                val n = realtime.nativeCounters
                if (n == null || n.size < JamesDspWrapper.REALTIME_COUNT) {
                    KeyValueRow("Native counters", "unavailable", Color.Gray)
                } else {
                    KeyValueRow("Convolver IR loads", n[JamesDspWrapper.REALTIME_IR_LOADS].toString())
                    val changes = n[JamesDspWrapper.REALTIME_BLOCK_SIZE_CHANGES]
                    KeyValueRow("Blocks off the prepared size", changes.toString(), if (changes > 0) BmwYellow else Color.White)
                    val rebuilds = n[JamesDspWrapper.REALTIME_CONVOLVER_REBUILDS]
                    KeyValueRow("Convolver rebuilds (audio thread)", rebuilds.toString(), if (rebuilds > 0) BmwRed else BmwGreen)
                    val reallocs = n[JamesDspWrapper.REALTIME_WORK_BUFFER_REALLOCS]
                    KeyValueRow("Work-buffer reallocs (audio thread)", reallocs.toString(), if (reallocs > 0) BmwRed else BmwGreen)
                    KeyValueRow("Last block", "${n[JamesDspWrapper.REALTIME_LAST_BLOCK_FRAMES]} frames")
                    val prepared = n[JamesDspWrapper.REALTIME_PREPARED_FRAMES]
                    KeyValueRow(
                        "Prepared block",
                        if (prepared > 0) "$prepared frames" else "not prepared",
                        if (prepared > 0) BmwGreen else BmwRed,
                    )
                }
            }
            if (cpu.isEmpty()) {
                KeyValueRow("CPU clocks", "unreadable", Color.Gray)
            }
            cpu.forEach { policy ->
                fun mhz(khz: Long?) = khz?.let { "${it / 1000}" } ?: "?"
                val capped = policy.capKhz != null && policy.hardwareMaxKhz != null && policy.capKhz < policy.hardwareMaxKhz
                KeyValueRow(
                    "CPU ${policy.cpus}",
                    "${mhz(policy.currentKhz)} MHz (cap ${mhz(policy.capKhz)} / ${mhz(policy.hardwareMaxKhz)})",
                    if (capped) BmwYellow else Color.White,
                )
            }
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
            fontSize = 16.sp,
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
        KeyValueRow("Stage 3", snapshot.stage3.describe())
        KeyValueRow("Stage 4", snapshot.stage4.describe())
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
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
