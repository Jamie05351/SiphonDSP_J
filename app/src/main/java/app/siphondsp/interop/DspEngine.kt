package app.siphondsp.interop

import android.content.Context
import android.os.SystemClock
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.debug.NativeDspTruthSnapshot
import app.siphondsp.model.debug.parseNativeTruthSnapshot
import app.siphondsp.model.preference.AudioEncoding
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class NativeConfigRevisionStatus(
    val requestedDspRevision: Long,
    val nativeActiveDspRevision: Long,
    val requestedPeqRevision: Long,
    val nativeActivePeqRevision: Long,
    val lastDspApplySuccess: Boolean?,
    val lastPeqApplySuccess: Boolean?,
    val lastDspFailure: String?,
    val lastPeqFailure: String?,
    // elapsedRealtime() when requestedDspRevision/requestedPeqRevision last advanced (0 if never).
    // Not "when the mismatch began" in the strictest sense -- if the requested revision keeps
    // advancing while still unmatched, this tracks the latest request, not the first divergence --
    // but it's the only "since" timestamp obtainable without polling for the transition, and it's
    // exactly right in the common case (one request, then a mismatch until it's acknowledged).
    val dspRevisionRequestedAtMs: Long,
    val peqRevisionRequestedAtMs: Long,
) {
    enum class SyncState { MATCH, STALE, ERROR, UNINITIALIZED }

    private fun syncState(requested: Long, active: Long, lastApplySuccess: Boolean?): SyncState = when {
        requested == 0L && active == 0L -> SyncState.UNINITIALIZED
        lastApplySuccess == false -> SyncState.ERROR
        requested == active -> SyncState.MATCH
        else -> SyncState.STALE
    }

    val dspSyncState: SyncState get() = syncState(requestedDspRevision, nativeActiveDspRevision, lastDspApplySuccess)
    val peqSyncState: SyncState get() = syncState(requestedPeqRevision, nativeActivePeqRevision, lastPeqApplySuccess)

    // Gated on requested != active directly, not on dspSyncState -- ERROR is also entered by a
    // rejection that never reached next*RevisionLocked() (a size-mismatch/handle-unavailable/
    // validation failure), which leaves requestedDspRevision == nativeActiveDspRevision (no
    // mismatch at all) but *RevisionRequestedAtMs still pointing at an earlier, already-resolved
    // request. Reporting a duration off that stale timestamp would show an ever-growing
    // "mismatch" for a failure that isn't a revision mismatch and predates the failure itself.
    fun dspMismatchDurationMs(nowMs: Long = SystemClock.elapsedRealtime()): Long? =
        if (requestedDspRevision != nativeActiveDspRevision) {
            (nowMs - dspRevisionRequestedAtMs).coerceAtLeast(0)
        } else null

    fun peqMismatchDurationMs(nowMs: Long = SystemClock.elapsedRealtime()): Long? =
        if (requestedPeqRevision != nativeActivePeqRevision) {
            (nowMs - peqRevisionRequestedAtMs).coerceAtLeast(0)
        } else null
}

/**
 * Owns one native engine handle (see [NativeDsp]): the audio path, the BMW config and PEQ pushes
 * with their revision bookkeeping, the meters, the truth snapshot and the measurement capture.
 */
class DspEngine(val context: Context) : AutoCloseable {
    // Guards the native handle's lifetime. processDirect() holds it shared for a whole audio
    // buffer, and so do the config/PEQ pushes, the sample-rate change, the truth snapshot and the
    // capture calls: NativeBmwDspProcessor synchronises those against the audio thread itself, so
    // all they need here is a handle close() can't free mid-call. close() is the only exclusive
    // holder, so the URGENT_AUDIO loop never waits behind a default-priority thread.
    private val handleLock = ReentrantReadWriteLock()
    // Serialises the BMW config/PEQ pushes among themselves (revision counters, last-applied
    // state, PEQ persistence rollback) now that they no longer exclude each other through the
    // handle lock. Always taken before handleLock, never inside it.
    private val configLock = Any()
    // Guards only the handle's lifetime for the UI-polled meter getters. The native meter readers
    // are lock-free atomics, so all they need is a handle close() can't free mid-call. close()
    // takes meterLock then handleLock (exclusive); meter getters take meterLock only, process*()
    // handleLock only -- one fixed order, so no deadlock.
    private val meterLock = Any()
    @Volatile private var bmwPeqState: BmwPeqState = BmwPeqState.loadPersisted(context)
    /** The PEQ the native engine last accepted (what the automatic headroom is computed from). */
    val appliedPeqState: BmwPeqState get() = bmwPeqState
    /**
     * Called after every PEQ the native engine accepts, on the applying thread and under the
     * config lock -- so it must only hand work off (the service queues an automatic-headroom
     * recompute on its config thread).
     */
    @Volatile var onPeqApplied: (() -> Unit)? = null
    @Volatile private var peqRestorePending = true

    @Volatile private var requestedDspRevision = 0L
    @Volatile private var requestedPeqRevision = 0L
    @Volatile private var lastDspApplySuccess: Boolean? = null
    @Volatile private var lastPeqApplySuccess: Boolean? = null
    @Volatile private var lastDspFailure: String? = null
    @Volatile private var lastPeqFailure: String? = null
    @Volatile private var dspRevisionRequestedAtMs = 0L
    @Volatile private var peqRevisionRequestedAtMs = 0L

    @Volatile
    private var handle: NativeDspHandle = try {
        NativeDsp.alloc()
    } catch (e: Throwable) {
        Timber.e(e, "NativeDsp.alloc() failed; degrading to a null native handle")
        0L
    }

    @Volatile
    var sampleRate: Float = 0.0f
        set(value) {
            field = value
            handleLock.read {
                val current = handle
                if (current != 0L) {
                    NativeDsp.setSampleRate(current, value)
                }
            }
            val attemptingColdStartRestore = value >= MIN_VALID_SAMPLE_RATE && peqRestorePending
            if (attemptingColdStartRestore) {
                restoreNativeBmwPeq()
            }
            if (!attemptingColdStartRestore) {
                synchronized(configLock) {
                    handleLock.read {
                        if (handle != 0L && !peqRestorePending) refreshEqualizersLocked()
                    }
                }
            }
        }

    /** False: the audio path passes buffers through unprocessed. */
    @Volatile var enabled: Boolean = true

    init {
        val restored = loadNativeBmwDspValues()
        if (!configureNativeBmwDsp(restored)) {
            Timber.e("Failed to restore saved native BMW DSP configuration")
        }
        restoreNativeBmwPeq()
    }

    private fun restoreNativeBmwPeq() {
        if (sampleRate < MIN_VALID_SAMPLE_RATE) {
            Timber.i(
                "BMW PEQ cold-start restore deferred sampleRate=$sampleRate " +
                    "enabled=${bmwPeqState.enabled} full=${bmwPeqState.fullRangeBands.size} " +
                    "low=${bmwPeqState.lowBandBands.size} mid=${bmwPeqState.midBandBands.size}"
            )
            return
        }
        val persisted = BmwPeqState.loadPersisted(context)
        if (configureNativeBmwPeq(persisted, persistOnSuccess = false, source = "cold-start")) {
            peqRestorePending = false
            BmwPeqState.recordRestoreResult(context, "persisted-state")
            return
        }
        val persistedValidation = persisted.validate(sampleRate)
        val persistedError = persistedValidation ?: "native configuration rejected"
        val structurallyInvalid = persistedValidation != null

        val lastKnownGood = BmwPeqState.loadLastKnownGood(context)
        if (lastKnownGood != null &&
            configureNativeBmwPeq(lastKnownGood, persistOnSuccess = structurallyInvalid, source = "cold-start-lkg")
        ) {
            peqRestorePending = false
            Timber.w("Native BMW PEQ recovered from last-known-good state (persisted=$structurallyInvalid)")
            BmwPeqState.recordRestoreResult(
                context, "last-known-good", persistedError, fallbackUsed = true
            )
            return
        }
        val safe = BmwPeqState.empty()
        if (structurallyInvalid && !BmwPeqState.backupRejectedPersistedState(context)) {
            Timber.e("Failed to preserve rejected BMW PEQ state before safe fallback")
        }
        val safeOk = configureNativeBmwPeq(safe, persistOnSuccess = structurallyInvalid, source = "cold-start-safe")
        if (safeOk) peqRestorePending = false
        Timber.e("Native BMW PEQ used safe fallback result=$safeOk reason=$persistedError persisted=$structurallyInvalid")
        BmwPeqState.recordRestoreResult(
            context,
            if (safeOk) "safe-empty" else "recovery-failed",
            persistedError,
            fallbackUsed = true,
        )
    }

    // Shared: for NativeBmwDspProcessor calls, which synchronise with the audio thread natively.
    private inline fun <T> withSharedHandle(default: T, block: (NativeDspHandle) -> T): T = handleLock.read {
        val current = handle
        if(current == 0L) default else block(current)
    }

    private inline fun withSharedHandle(block: (NativeDspHandle) -> Unit) {
        handleLock.read {
            val current = handle
            if(current != 0L)
                block(current)
        }
    }

    // Meter getters only -- see meterLock. Never call a native function that touches DSP state
    // (configure, process, truth snapshot, capture) through this.
    private inline fun <T> withMeterHandle(default: T, block: (NativeDspHandle) -> T): T = synchronized(meterLock) {
        val current = handle
        if(current == 0L) default else block(current)
    }

    fun isNativeHandleReady(): Boolean = handleLock.read { handle != 0L }

    fun nativeConfigRevisionStatus(): NativeConfigRevisionStatus = synchronized(configLock) {
        handleLock.read { revisionStatusLocked() }
    }

    private fun revisionStatusLocked(): NativeConfigRevisionStatus {
        val current = handle
        return NativeConfigRevisionStatus(
            requestedDspRevision = requestedDspRevision,
            nativeActiveDspRevision = if (current == 0L) 0L
                else NativeDsp.getNativeBmwDspActiveRevision(current),
            requestedPeqRevision = requestedPeqRevision,
            nativeActivePeqRevision = if (current == 0L) 0L
                else NativeDsp.getNativeBmwPeqActiveRevision(current),
            lastDspApplySuccess = lastDspApplySuccess,
            lastPeqApplySuccess = lastPeqApplySuccess,
            lastDspFailure = lastDspFailure,
            lastPeqFailure = lastPeqFailure,
            dspRevisionRequestedAtMs = dspRevisionRequestedAtMs,
            peqRevisionRequestedAtMs = peqRevisionRequestedAtMs,
        )
    }

    /**
     * Whole-state read-only snapshot of what NativeBmwDspProcessor and the native PEQ engine are
     * actually running -- for the native-truth debug screen. Null when the native handle doesn't
     * exist (distinguished from a live-but-never-configured engine, which nativeConfigRevisionStatus()
     * reports as UNINITIALIZED). Logged at a level well below per-buffer audio-thread logging: this
     * is only ever called from a debug-screen read, never the audio loop.
     */
    fun nativeTruthSnapshot(): NativeDspTruthSnapshot? {
        val raw = withSharedHandle(null) { NativeDsp.getNativeTruthSnapshot(it) }
        val snapshot = parseNativeTruthSnapshot(raw)
        if (raw != null && snapshot == null) {
            Timber.e(
                "nativeTruthSnapshot: native returned %d values that failed to parse -- Kotlin/native array schema likely drifted",
                raw.size,
            )
        } else if (snapshot != null) {
            Timber.d(
                "nativeTruthSnapshot: sampleRate=%s peqEnabled=%s peqBands(full/low/mid)=%d/%d/%d",
                snapshot.sampleRate, snapshot.peq.enabled, snapshot.peq.full.bands.size,
                snapshot.peq.low.bands.size, snapshot.peq.mid.bands.size,
            )
        }
        return snapshot
    }

    fun requestedNativeBmwDspRevision(): Long = requestedDspRevision
    fun requestedNativeBmwPeqRevision(): Long = requestedPeqRevision
    fun nativeActiveBmwDspRevision(): Long =
        withSharedHandle(0L) { NativeDsp.getNativeBmwDspActiveRevision(it) }
    fun nativeActiveBmwPeqRevision(): Long =
        withSharedHandle(0L) { NativeDsp.getNativeBmwPeqActiveRevision(it) }

    private fun nextDspRevisionLocked(): Long? {
        if (requestedDspRevision == Long.MAX_VALUE) {
            lastDspApplySuccess = false
            lastDspFailure = "DSP revision counter exhausted"
            Timber.e(lastDspFailure)
            return null
        }
        requestedDspRevision += 1L
        dspRevisionRequestedAtMs = SystemClock.elapsedRealtime()
        return requestedDspRevision
    }

    private fun nextPeqRevisionLocked(): Long? {
        if (requestedPeqRevision == Long.MAX_VALUE) {
            lastPeqApplySuccess = false
            lastPeqFailure = "PEQ revision counter exhausted"
            Timber.e(lastPeqFailure)
            return null
        }
        requestedPeqRevision += 1L
        peqRevisionRequestedAtMs = SystemClock.elapsedRealtime()
        return requestedPeqRevision
    }

    override fun close() {
        Timber.d("Closing engine")
        // meterLock first (see its declaration): a meter read already in flight finishes before
        // the handle is freed, and any later one sees handle == 0.
        synchronized(meterLock) {
            handleLock.write {
                val oldHandle = handle
                handle = 0L
                if(oldHandle != 0L) {
                    NativeDsp.free(oldHandle)
                    Timber.d("Handle $oldHandle has been freed")
                }
                BmwPeqState.clearActiveSession(context, this)
            }
        }
        AppEvents.send(AppEvent.ParametricEqChanged)
    }

    /**
     * Processes [sampleCount] interleaved stereo samples of the direct [buffer] in place. False
     * when bypassed (disabled or no native handle) or native refused the buffer; the buffer then
     * holds its input unchanged, so it can be played as it is.
     */
    fun processDirect(buffer: ByteBuffer, sampleCount: Int, encoding: AudioEncoding): Boolean =
        handleLock.read {
            val current = handle
            enabled && current != 0L && NativeDsp.processDirect(
                current,
                buffer,
                sampleCount,
                if (encoding == AudioEncoding.PcmFloat) NativeDsp.ENCODING_PCM_FLOAT else NativeDsp.ENCODING_PCM_16,
            )
        }

    private fun refreshEqualizersLocked(): Boolean {
        if (handle == 0L) return false
        return if (peqRestorePending) true
        else configureNativeBmwPeqLocked(BmwPeqState.load(context), "preference-sync")
    }

    private fun configureNativeBmwPeqLocked(state: BmwPeqState, source: String): Boolean {
        val validation = state.validate(sampleRate)
        if (validation != null) {
            lastPeqApplySuccess = false
            lastPeqFailure = "$source validation failed: $validation"
            Timber.e("$source native BMW PEQ validation failed: $validation")
            return false
        }
        val current = handle
        if (current == 0L) {
            lastPeqApplySuccess = false
            lastPeqFailure = "$source native handle unavailable"
            return false
        }
        val revision = nextPeqRevisionLocked() ?: return false
        val acknowledged = NativeDsp.configureNativeBmwPeq(
            current,
            state.enabled,
            state.preampDb,
            state.nativeValues(state.fullRangeBands),
            state.nativeValues(state.lowBandBands),
            state.nativeValues(state.midBandBands),
            state.nativeValues(state.highBandBands),
            revision,
        )
        val active = NativeDsp.getNativeBmwPeqActiveRevision(current)
        val result = acknowledged == revision && active == revision
        BmwPeqState.log(source, state, result)
        if (result) {
            bmwPeqState = state.deepCopy()
            BmwPeqState.publishActiveSession(context, this, state)
            lastPeqApplySuccess = true
            lastPeqFailure = null
            onPeqApplied?.invoke()
        } else {
            lastPeqApplySuccess = false
            lastPeqFailure =
                "$source native PEQ rejected/stale requested=$revision acknowledged=$acknowledged active=$active"
            Timber.e(lastPeqFailure)
        }
        return result
    }

    fun configureNativeBmwPeq(
        state: BmwPeqState,
        persistOnSuccess: Boolean = true,
        source: String = "editor",
    ): Boolean {
        val previous: BmwPeqState
        val applied: Boolean
        synchronized(configLock) {
            handleLock.read {
                previous = bmwPeqState
                applied = configureNativeBmwPeqLocked(state, source)
            }
        }
        if (!applied) return false
        var result = true
        if (persistOnSuccess && !state.persist(context)) {
            Timber.e("$source native BMW PEQ applied but persistence commit failed")
            synchronized(configLock) { handleLock.read {
                // Only this call's own status is stale here: if a newer configureNativeBmwPeq
                // call has already applied a different state (and possibly already reported its
                // own success) while this write was unlocked on disk I/O, that newer call's
                // status must survive -- overwriting it here would make
                // nativeConfigRevisionStatus() combine the newer requested/active revision with
                // this older call's failure.
                if (bmwPeqState == state) {
                    val rollbackOk = configureNativeBmwPeqLocked(previous, "$source-persistence-rollback")
                    val rollbackRevision = requestedPeqRevision
                    if (!rollbackOk) {
                        Timber.e("$source native BMW PEQ persistence rollback failed")
                    }
                    lastPeqApplySuccess = false
                    lastPeqFailure =
                        "$source persistence commit failed; previous state requested as rollback revision=$rollbackRevision"
                } else {
                    Timber.w(
                        "$source native BMW PEQ persistence-rollback skipped: a newer state " +
                            "was applied while this write was in flight"
                    )
                }
            } }
            result = false
        }
        AppEvents.send(AppEvent.ParametricEqChanged)
        return result
    }

    private fun loadNativeBmwDspValues(): FloatArray = NativeBmwDspValues.load(context)

    fun configureNativeBmwDsp(values: FloatArray): Boolean {
        if (values.size != NativeBmwDspValues.SIZE) {
            lastDspApplySuccess = false
            lastDspFailure = "Rejected native BMW DSP configuration with ${values.size} values"
            Timber.e(lastDspFailure)
            return false
        }
        return synchronized(configLock) { handleLock.read { configureNativeBmwDspLocked(values) } }
    }

    private fun configureNativeBmwDspLocked(values: FloatArray): Boolean {
        val current = handle
        if (current == 0L) {
            lastDspApplySuccess = false
            lastDspFailure = "Native BMW DSP handle unavailable"
            return false
        }
        val revision = nextDspRevisionLocked() ?: return false
        val acknowledged = NativeDsp.configureNativeBmwDsp(current, values, revision)
        val active = NativeDsp.getNativeBmwDspActiveRevision(current)
        val result = acknowledged == revision && active == revision
        if (result) {
            lastDspApplySuccess = true
            lastDspFailure = null
        } else {
            lastDspApplySuccess = false
            lastDspFailure =
                "Native BMW DSP rejected/stale requested=$revision acknowledged=$acknowledged active=$active"
            Timber.e(lastDspFailure)
        }
        return result
    }

    /** Loads the WAV IR at [path] into the convolver. Null on success, else why not. */
    fun loadConvolverIr(path: String): String? =
        withSharedHandle<String?>("DSP engine unavailable") { NativeDsp.loadConvolverIr(it, path) }

    fun clearConvolverIr() = withSharedHandle { NativeDsp.clearConvolverIr(it) }

    /** What the convolver is running; null with no native handle. */
    fun convolverStatus(): ConvolverStatus? = withSharedHandle<ConvolverStatus?>(null) { handle ->
        NativeDsp.getConvolverInfo(handle)?.let { ConvolverStatus.fromNative(it, NativeDsp.getConvolverError(handle)) }
    }

    /** See [NativeDsp.readNativeBmwMeter]. */
    fun readNativeBmwMeter(kind: Int, out: FloatArray): Boolean =
        withMeterHandle(false) { NativeDsp.readNativeBmwMeter(it, kind, out) }

    /** See [NativeDsp.getRealtimeCounters]. Atomics only, so the meter lock suffices. */
    fun readRealtimeCounters(out: LongArray): Boolean =
        withMeterHandle(false) { NativeDsp.getRealtimeCounters(it, out) }

    fun nativeBmwMasterLimiterMeter(): FloatArray? =
        withMeterHandle<FloatArray?>(null) { NativeDsp.getNativeBmwMasterLimiterMeter(it) }

    fun nativeBmwVirtualMeter(): FloatArray? =
        withMeterHandle<FloatArray?>(null) { NativeDsp.getNativeBmwVirtualMeter(it) }

    fun startNativeBmwCapture() {
        freePendingCaptureSnapshot()
        withSharedHandle { NativeDsp.startNativeBmwCapture(it) }
    }

    fun stopNativeBmwCapture() = withSharedHandle { NativeDsp.stopNativeBmwCapture(it) }

    fun nativeBmwCaptureFrameCount(): Long =
        withSharedHandle<Long>(0L) { NativeDsp.getNativeBmwCaptureFrameCount(it) }

    private val captureSnapshotLock = Any()

    @Volatile
    private var pendingCaptureSnapshot: Long = 0L

    // Atomically reads and clears the pending snapshot so a concurrent freePendingCaptureSnapshot()
    // and exportNativeBmwCaptureWav() can never both observe the same non-zero handle -- one frees
    // it natively while the other passes the same (now-freed) handle to a native call.
    private fun takePendingCaptureSnapshot(): Long = synchronized(captureSnapshotLock) {
        val current = pendingCaptureSnapshot
        pendingCaptureSnapshot = 0L
        current
    }

    private fun freePendingCaptureSnapshot() {
        val stale = takePendingCaptureSnapshot()
        if (stale != 0L) {
            NativeDsp.freeNativeBmwCaptureSnapshot(stale)
        }
    }

    fun exportNativeBmwCaptureWav(rawInPath: String, outPath: String): FloatArray? {
        val snapshot = takePendingCaptureSnapshot().takeIf { it != 0L }
            ?: withSharedHandle(0L) { NativeDsp.takeNativeBmwCaptureSnapshot(it) }
        if (snapshot == 0L) return null
        val result = NativeDsp.exportNativeBmwCaptureWav(snapshot, rawInPath, outPath)
        if (result != null) {
            NativeDsp.freeNativeBmwCaptureSnapshot(snapshot)
        } else {
            synchronized(captureSnapshotLock) { pendingCaptureSnapshot = snapshot }
        }
        return result
    }

    companion object {
        private const val MIN_VALID_SAMPLE_RATE = 8000f
    }
}
