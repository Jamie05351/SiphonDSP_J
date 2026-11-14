package app.siphondsp.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import app.siphondsp.model.DspStage
import app.siphondsp.model.MeasurementNeutral
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.debug.EngineStatus
import app.siphondsp.model.debug.SelfTestReport
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The engine's own report of what it is running ([EngineStatus]), polled from the running service
 * every [ENGINE_STATUS_POLL_MS] while the caller is composed, and right after every DSP edit (a
 * few quick reads, so a switch shows the engine's answer within a frame or two of it landing).
 * Null while no engine is running: the UI shows that as unknown, never as the saved settings.
 */
@Composable
fun rememberEngineStatus(): State<EngineStatus?> = produceState(RootlessAudioProcessorService.engineStatus()) {
    launch {
        AppEvents.of<AppEvent.NativeBmwDspUpdated>().collect {
            repeat(FAST_READS) {
                delay(FAST_READ_MS)
                value = RootlessAudioProcessorService.engineStatus()
            }
        }
    }
    while (true) {
        value = RootlessAudioProcessorService.engineStatus()
        delay(ENGINE_STATUS_POLL_MS)
    }
}

/** The measurement self-test, as the UI sees it. */
sealed interface SelfTestState {
    data object Idle : SelfTestState
    data object Running : SelfTestState
    /** No engine running (or it hasn't accepted a config yet), so nothing to test. */
    data object Unavailable : SelfTestState
    data class Done(val report: SelfTestReport) : SelfTestState
}

/**
 * Runs the self-test ([RootlessAudioProcessorService.runSelfTest]) off the main thread, one at a
 * time, and keeps the last result for every screen that shows it. App-wide: a result outlives the
 * screen that asked for it.
 */
object SelfTestRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SelfTestState>(SelfTestState.Idle)
    val state: StateFlow<SelfTestState> = _state.asStateFlow()
    private var job: Job? = null
    // The config as last broadcast, and as it was when the shown result was measured.
    private var latestValues: FloatArray? = null
    private var measuredValues: FloatArray? = null

    init {
        // A shown result describes the config it measured: dropped as soon as that changes. The
        // master volume (the car's volume, turned while driving) is excluded from the test, so a
        // volume step keeps it -- unless Deep bass, which follows the volume, is running.
        scope.launch {
            AppEvents.of<AppEvent.NativeBmwDspUpdated>().collect { event ->
                synchronized(this@SelfTestRunner) {
                    latestValues = event.values.copyOf()
                    if (_state.value is SelfTestState.Done && !sameButVolume(measuredValues, event.values)) {
                        _state.value = SelfTestState.Idle
                    }
                }
            }
        }
    }

    private fun sameButVolume(a: FloatArray?, b: FloatArray): Boolean {
        if (a == null || a.size != b.size) return false
        val deepBass = b[NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED] >= .5f &&
            MeasurementNeutral.runs(b, DspStage.DEEP_BASS)
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            val volume = i == NativeBmwDspValues.INDEX_MASTER_VOLUME_DB || i == NativeBmwDspValues.INDEX_MASTER_MUTE
            if (!volume || deepBass) return false
        }
        return true
    }

    /** On demand. [awaitEngine]: first wait (up to [ENGINE_CONFIRM_TIMEOUT_MS]) for the engine to
     *  report the chain just switched to, so the test measures that and not the one before. */
    fun run(awaitEngine: ((EngineStatus) -> Boolean)? = null) {
        synchronized(this) {
            job?.cancel()
            _state.value = SelfTestState.Running
            job = scope.launch {
                // What a result will describe: the config as it stands once the engine has it.
                if (awaitEngine != null) {
                    withTimeoutOrNull(ENGINE_CONFIRM_TIMEOUT_MS) {
                        while (RootlessAudioProcessorService.engineStatus()?.let(awaitEngine) != true) {
                            delay(FAST_READ_MS)
                        }
                    }
                }
                // The engine copies its state inside runSelfTest(), after this snapshot: an edit
                // landing in between would be measured but not recorded. So a result counts only
                // if the config didn't change (master volume aside) while it ran; otherwise
                // measure again, a few times at most, before giving up on a moving config.
                repeat(MAX_ATTEMPTS) {
                    val measuring = synchronized(this@SelfTestRunner) { latestValues?.copyOf() }
                    val report = RootlessAudioProcessorService.runSelfTest()
                    synchronized(this@SelfTestRunner) {
                        // A newer run (or a clear) took over while this one measured: not its result.
                        if (!isActive) return@launch
                        val latest = latestValues
                        val unchanged = measuring == null && latest == null ||
                            latest != null && sameButVolume(measuring, latest)
                        if (report == null || unchanged) {
                            measuredValues = measuring
                            _state.value = report?.let { SelfTestState.Done(it) } ?: SelfTestState.Unavailable
                            return@launch
                        }
                    }
                }
                synchronized(this@SelfTestRunner) {
                    if (isActive) _state.value = SelfTestState.Idle
                }
            }
        }
    }

    /** A result for a chain that has since been switched no longer describes it. */
    fun clear() {
        synchronized(this) {
            job?.cancel()
            _state.value = SelfTestState.Idle
        }
    }
}

@Composable
fun rememberSelfTestState(): State<SelfTestState> = SelfTestRunner.state.collectAsState()

/**
 * The one way the UI switches measurement neutral: commits it (saved, so it survives a restart;
 * the stage settings themselves are never touched) and, switching on, runs the self-test as soon
 * as the engine confirms it.
 */
fun setMeasurementNeutral(dsp: BmwDspState, on: Boolean): Boolean {
    if (!dsp.commitAll(MeasurementNeutral.updates(on))) return false
    if (on) SelfTestRunner.run { it.measurementNeutral && it.neutralAllowed.isEmpty() } else SelfTestRunner.clear()
    return true
}

/** While neutral is on: [stage] back on (or bypassed again), then the self-test on the new chain. */
fun setNeutralStageAllowed(dsp: BmwDspState, stage: DspStage, allowed: Boolean): Boolean {
    if (!dsp.commitAll(MeasurementNeutral.allowUpdate(dsp.values, stage, allowed))) return false
    if (MeasurementNeutral.isOn(dsp.values)) {
        val expected = DspStage.fromMask(MeasurementNeutral.allowMask(dsp.values)).toSet()
        SelfTestRunner.run { it.measurementNeutral && it.neutralAllowed == expected }
    }
    return true
}

const val ENGINE_STATUS_POLL_MS = 500L
private const val FAST_READS = 4
private const val FAST_READ_MS = 60L
private const val ENGINE_CONFIRM_TIMEOUT_MS = 3_000L
// Self-test attempts while the config keeps changing under it.
private const val MAX_ATTEMPTS = 3
