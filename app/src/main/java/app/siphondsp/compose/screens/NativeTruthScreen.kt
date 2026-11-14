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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.state.SelfTestRunner
import app.siphondsp.compose.state.SelfTestState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.state.rememberEngineStatus
import app.siphondsp.compose.state.rememberSelfTestState
import app.siphondsp.compose.state.setMeasurementNeutral
import app.siphondsp.compose.state.setNeutralStageAllowed
import app.siphondsp.model.DspStage
import app.siphondsp.model.MeasurementNeutral
import app.siphondsp.interop.NativeDsp
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
import app.siphondsp.model.debug.StageProfile
import app.siphondsp.service.AudioThreadTuning
import app.siphondsp.service.RootlessAudioProcessorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val REFRESH_INTERVAL_MS = 500L
private const val TIMING_WINDOW_MS = 3_000L

/**
 * Developer/debug screen proving what NativeBmwDspProcessor and the native PEQ engine are
 * ACTUALLY running right now -- not SharedPreferences, not cached Kotlin state, not the last
 * requested UI value, not a broadcast. Every value below is sourced from one of:
 *   - [RootlessAudioProcessorService.nativeConfigRevisionStatus] (requested/native-active
 *     revision counters + last apply result, read from DspEngine's own bookkeeping and
 *     the native revision-ack atomics via JNI getters).
 *   - [RootlessAudioProcessorService.nativeTruthSnapshot] (NativeBmwDspProcessor::
 *     captureTruthSnapshot(), one stateMutex_-guarded read of the actual installed crossover
 *     Biquad coefficients and PEQ band arrays -- see that method's doc comment for the wire
 *     layout).
 *   - [RootlessAudioProcessorService.pipelineRuntimeSnapshot] (the running service's own
 *     AudioRecord/AudioTrack/health-watchdog state, read directly, not mirrored).
 *   - [RootlessAudioProcessorService.engineStatus] (NativeBmwDspProcessor::readEngineStatus():
 *     the stages the engine reports running, from its installed state).
 * The one control here is MEASUREMENT: measurement neutral, the stages it lets back on, and the
 * self-test (an impulse through an offline copy of the running chain). Everything it shows is
 * the engine's report or the self-test's measurement, never the settings.
 */
@Composable
fun NativeTruthScreen(modifier: Modifier = Modifier) {
    var revision by remember { mutableStateOf<NativeConfigRevisionStatus?>(null) }
    var truth by remember { mutableStateOf<NativeDspTruthSnapshot?>(null) }
    var pipeline by remember { mutableStateOf<RootlessPipelineRuntimeSnapshot?>(null) }
    var handleReady by remember { mutableStateOf<Boolean?>(null) }
    var engineSampleRate by remember { mutableStateOf<Float?>(null) }
    var cpu by remember { mutableStateOf<List<CpuFrequencies.Policy>>(emptyList()) }
    var timing by remember { mutableStateOf<StageProfile.Summary?>(null) }
    var timingCoreKhz by remember { mutableStateOf<Long?>(null) }
    var tuning by remember { mutableStateOf(AudioThreadTuning.status) }
    // Per-stage timing runs only while this screen is open.
    DisposableEffect(Unit) {
        RootlessAudioProcessorService.setStageProfiling(true)
        onDispose { RootlessAudioProcessorService.setStageProfiling(false) }
    }
    var lastRefreshedAtMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        // Readings of the last few seconds: the averages are over the oldest one to now.
        val readings = ArrayDeque<Pair<Long, StageProfile>>()
        while (true) {
            // Again every poll: an engine started (or rebuilt) since the screen opened gets it too.
            RootlessAudioProcessorService.setStageProfiling(true)
            val profile = StageProfile.parse(RootlessAudioProcessorService.stageProfile())
            val now = System.currentTimeMillis()
            if (profile == null || !profile.enabled || (readings.lastOrNull()?.second?.buffers ?: 0) > profile.buffers) {
                readings.clear()
            }
            if (profile != null) readings.addLast(now to profile)
            while (readings.size > 2 && now - readings[1].first >= TIMING_WINDOW_MS) readings.removeFirst()
            tuning = AudioThreadTuning.status
            val rate = RootlessAudioProcessorService.engineStatus()?.sampleRate?.toDouble() ?: 0.0
            timing = profile?.summarize(readings.firstOrNull()?.second?.takeIf { readings.size > 1 }, rate)
            timingCoreKhz = timing?.lastCpu?.takeIf { it >= 0 }?.let { core -> withContext(Dispatchers.IO) { CpuFrequencies.currentKhz(core) } }
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
        item { ProcessingTimeSection(timing, timingCoreKhz, cpu, tuning) }
        item { MeasurementSection() }
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


/**
 * Where the audio thread's time goes: per buffer, the wall time inside the engine against the
 * thread's own CPU time (the difference is time it was off the CPU), the core it ran on, and each
 * stage of the chain, most expensive first. Averages over the last few seconds; maxima since the
 * screen opened.
 */
@Composable
private fun ProcessingTimeSection(
    timing: StageProfile.Summary?,
    coreKhz: Long?,
    cpu: List<CpuFrequencies.Policy>,
    tuning: AudioThreadTuning.Status,
) {
    Column {
        SectionHeader("PROCESSING TIME")
        DebugCard {
            KeyValueRow(
                "Audio thread",
                if (tuning.tid == 0) "not started" else "tid ${tuning.tid}: ${tuning.pinSummary}",
                if (tuning.pinResult == AudioThreadTuning.PIN_PINNED) BmwGreen else BmwYellow,
            )
            KeyValueRow(
                "Performance hint",
                if (tuning.hint == "active") "active, target ${fmt(tuning.hintTargetMs)} ms" else tuning.hint,
                if (tuning.hint == "active") BmwGreen else BmwYellow,
            )
            if (timing == null) {
                KeyValueRow("Stage timing", "waiting for audio", BmwYellow)
                return@DebugCard
            }
            fun ms(us: Double) = "${fmt(us / 1000.0)} ms"
            fun budgetColor(us: Double) = when {
                timing.budgetUs <= 0 -> Color.White
                us >= timing.budgetUs -> BmwRed
                us >= timing.budgetUs * 0.75 -> BmwYellow
                else -> Color.White
            }
            KeyValueRow("Budget per buffer", ms(timing.budgetUs))
            KeyValueRow(
                "Engine time, mean",
                "${ms(timing.avgWallUs)} (${fmt(timing.ofBudgetPercent(timing.avgWallUs), 0)}%)",
                budgetColor(timing.avgWallUs),
            )
            KeyValueRow("Engine time, max", ms(timing.maxWallUs), budgetColor(timing.maxWallUs))
            KeyValueRow("of which on the CPU, mean", ms(timing.avgCpuUs))
            KeyValueRow(
                "of which off the CPU (preempted), mean",
                ms(timing.avgOffCpuUs),
                if (timing.avgOffCpuUs > timing.avgWallUs * 0.2) BmwYellow else Color.White,
            )
            val core = timing.lastCpu
            val policy = cpu.firstOrNull { core >= 0 && CpuFrequencies.covers(it.cpus, core) }
            KeyValueRow(
                "Core (last buffer)",
                if (core < 0) "unknown" else buildString {
                    append("cpu$core")
                    coreKhz?.let { append(" @ ${it / 1000} MHz") }
                    policy?.hardwareMaxKhz?.let { append(" (max ${it / 1000})") }
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Stage", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("mean   share   max", color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            for (stage in timing.stages) {
                if (stage.avgUs < 0.05 && stage.maxUs < 0.05) continue
                Row(Modifier.fillMaxWidth()) {
                    Text(stage.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        "%6.2f %5.1f%% %6.2f".format(stage.avgUs / 1000.0, stage.sharePercent, stage.maxUs / 1000.0),
                        color = if (stage.sharePercent >= 20) BmwYellow else Color.White,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text("ms per buffer; share of the engine's time", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

/**
 * Measurement neutral, the engine's own list of the stages it is running, and the self-test.
 * While neutral is on, each stage in the neutral set has its own switch to run it again (the
 * rest stay bypassed); the self-test reruns after each change.
 */
@Composable
private fun MeasurementSection() {
    val dsp = rememberBmwDspState()
    val status by rememberEngineStatus()
    val selfTest by rememberSelfTestState()
    val neutralOn = MeasurementNeutral.isOn(dsp.values)
    Column {
        SectionHeader("MEASUREMENT")
        DebugCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Measurement neutral", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Bypasses every stage below except the master volume; the settings are kept, " +
                            "so switching it off restores them exactly.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                    )
                }
                BmwSwitch(
                    checked = neutralOn,
                    onCheckedChange = { setMeasurementNeutral(dsp, it) },
                    contentDescription = "Measurement neutral",
                )
            }
            Spacer(Modifier.height(8.dp))
            val engine = status
            KeyValueRow(
                "Engine reports",
                when {
                    engine == null -> "no engine running"
                    engine.measurementNeutral -> "neutral on"
                    else -> "neutral off"
                },
                if (engine == null) BmwYellow else Color.White,
            )
            KeyValueRow(
                "Crossover",
                when (val ways = engine?.crossoverWays) {
                    null -> "—"
                    0 -> "off (one full-range path)"
                    else -> "$ways-way"
                },
            )
            KeyValueRow("Master volume (not bypassed)", engine?.let { "${fmt(it.masterVolumeDb.toDouble(), 1)} dB" } ?: "—")
            Spacer(Modifier.height(8.dp))
            Text("ACTIVE STAGES (engine-confirmed)", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (engine == null) {
                Text("unknown: no engine running", color = BmwYellow, fontSize = 14.sp)
            } else if (engine.activeStages.isEmpty()) {
                Text("none: the output is the input", color = BmwGreen, fontSize = 14.sp)
            }
            for (stage in DspStage.entries) {
                val active = engine?.isActive(stage) == true
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (active) "● " else "○ ") + stage.label,
                        color = if (active) BmwGreen else Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    if (neutralOn && stage.neutralised) {
                        Text("run in neutral", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                        BmwSwitch(
                            checked = MeasurementNeutral.isAllowed(dsp.values, stage),
                            onCheckedChange = { setNeutralStageAllowed(dsp, stage, it) },
                            contentDescription = "Run ${stage.label} in measurement neutral",
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("SELF-TEST", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { SelfTestRunner.run() }, enabled = selfTest != SelfTestState.Running) {
                    Text(if (selfTest == SelfTestState.Running) "Testing…" else "Run self-test")
                }
            }
            when (val t = selfTest) {
                SelfTestState.Idle -> Text(
                    "An impulse through an offline copy of the running chain, compared with a wire.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                )
                SelfTestState.Running -> Text("Measuring…", color = Color.Gray, fontSize = 14.sp)
                SelfTestState.Unavailable -> Text("No engine running to test.", color = BmwYellow, fontSize = 14.sp)
                is SelfTestState.Done -> {
                    val r = t.report
                    Text(r.headline, color = if (r.verified) BmwGreen else BmwYellow, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(r.detail, color = Color.White, fontSize = 13.sp)
                    if (!r.verified && r.unexplained) {
                        Text(
                            "No single stage alters it on its own: stages altering it only together, or the " +
                                "settings changed during the test. Run it again.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                        )
                    }
                    Text("Took ${fmt(r.durationMs, 0)} ms", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
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
                if (n == null || n.size < NativeDsp.REALTIME_COUNT) {
                    KeyValueRow("Native counters", "unavailable", Color.Gray)
                } else {
                    KeyValueRow("Last block", "${n[NativeDsp.REALTIME_LAST_BLOCK_FRAMES]} frames")
                    val changes = n[NativeDsp.REALTIME_BLOCK_SIZE_CHANGES]
                    KeyValueRow("Block size changes", changes.toString(), if (changes > 0) BmwYellow else Color.White)
                    val nonFinite = n[NativeDsp.REALTIME_GUARD_NON_FINITE]
                    val overRange = n[NativeDsp.REALTIME_GUARD_OVER_RANGE]
                    KeyValueRow(
                        "Guard resets, NaN/Inf / over-range",
                        "$nonFinite / $overRange",
                        if (nonFinite + overRange > 0) BmwRed else BmwGreen,
                    )
                    val clipped = n[NativeDsp.REALTIME_GUARD_CLIPPED]
                    KeyValueRow("Clipped samples (±1.0)", clipped.toString(), if (clipped > 0) BmwYellow else Color.White)
                    KeyValueRow(
                        "Stream starts / fade-ins",
                        "${n[NativeDsp.REALTIME_STREAM_STARTS]} / ${n[NativeDsp.REALTIME_FADE_INS]}",
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
