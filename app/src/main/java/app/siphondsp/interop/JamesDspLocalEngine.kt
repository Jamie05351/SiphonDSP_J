package app.siphondsp.interop

import android.content.Context
import android.content.Intent
import app.siphondsp.R
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

data class NativeConfigRevisionStatus(
    val requestedDspRevision: Long,
    val nativeActiveDspRevision: Long,
    val requestedPeqRevision: Long,
    val nativeActivePeqRevision: Long,
    val lastDspApplySuccess: Boolean?,
    val lastPeqApplySuccess: Boolean?,
    val lastDspFailure: String?,
    val lastPeqFailure: String?,
)

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    private val nativeLock = Any()
    @Volatile private var bmwPeqState: BmwPeqState = BmwPeqState.loadPersisted(context)
    @Volatile private var peqRestorePending = true

    @Volatile private var requestedDspRevision = 0L
    @Volatile private var requestedPeqRevision = 0L
    @Volatile private var lastDspApplySuccess: Boolean? = null
    @Volatile private var lastPeqApplySuccess: Boolean? = null
    @Volatile private var lastDspFailure: String? = null
    @Volatile private var lastPeqFailure: String? = null

    @Volatile
    private var handle: JamesDspHandle = try {
        JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())
    } catch (e: Throwable) {
        Timber.e(e, "JamesDspWrapper.alloc() failed; degrading to a null native handle")
        0L
    }

    override var sampleRate: Float
        set(value) {
            synchronized(nativeLock) {
                super.sampleRate = value
                val current = handle
                if (current != 0L) {
                    JamesDspWrapper.setSamplingRate(current, value, false)
                    JamesDspWrapper.setNativeBmwDspSampleRate(current, value)
                }
            }
            val attemptingColdStartRestore = value >= MIN_VALID_SAMPLE_RATE && peqRestorePending
            if (attemptingColdStartRestore) {
                restoreNativeBmwPeq()
            }
            if (!attemptingColdStartRestore) {
                synchronized(nativeLock) {
                    if (handle != 0L && !peqRestorePending) refreshEqualizersLocked()
                }
            }
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate

    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()

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

    private inline fun <T> withHandle(default: T, block: (JamesDspHandle) -> T): T = synchronized(nativeLock) {
        val current = handle
        if(current == 0L) default else block(current)
    }

    private inline fun withHandle(block: (JamesDspHandle) -> Unit) {
        synchronized(nativeLock) {
            val current = handle
            if(current != 0L)
                block(current)
        }
    }

    fun isNativeHandleReady(): Boolean = synchronized(nativeLock) { handle != 0L }

    fun nativeConfigRevisionStatus(): NativeConfigRevisionStatus = synchronized(nativeLock) {
        val current = handle
        NativeConfigRevisionStatus(
            requestedDspRevision = requestedDspRevision,
            nativeActiveDspRevision = if (current == 0L) 0L
                else JamesDspWrapper.getNativeBmwDspActiveRevision(current),
            requestedPeqRevision = requestedPeqRevision,
            nativeActivePeqRevision = if (current == 0L) 0L
                else JamesDspWrapper.getNativeBmwPeqActiveRevision(current),
            lastDspApplySuccess = lastDspApplySuccess,
            lastPeqApplySuccess = lastPeqApplySuccess,
            lastDspFailure = lastDspFailure,
            lastPeqFailure = lastPeqFailure,
        )
    }

    fun requestedNativeBmwDspRevision(): Long = requestedDspRevision
    fun requestedNativeBmwPeqRevision(): Long = requestedPeqRevision
    fun nativeActiveBmwDspRevision(): Long =
        withHandle(0L) { JamesDspWrapper.getNativeBmwDspActiveRevision(it) }
    fun nativeActiveBmwPeqRevision(): Long =
        withHandle(0L) { JamesDspWrapper.getNativeBmwPeqActiveRevision(it) }

    private fun nextDspRevisionLocked(): Long? {
        if (requestedDspRevision == Long.MAX_VALUE) {
            lastDspApplySuccess = false
            lastDspFailure = "DSP revision counter exhausted"
            Timber.e(lastDspFailure)
            return null
        }
        requestedDspRevision += 1L
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
        return requestedPeqRevision
    }

    override fun close() {
        super.close()

        synchronized(nativeLock) {
            val oldHandle = handle
            handle = 0L
            if(oldHandle != 0L) {
                JamesDspWrapper.free(oldHandle)
                Timber.d("Handle $oldHandle has been freed")
            }
            BmwPeqState.clearActiveSession(context, this)
        }
        context.sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
    }

    private fun processedSampleCount(inputSize: Int, outputSize: Int, offset: Int, length: Int): Int {
        val safeOffset = max(offset, 0)
        if (safeOffset >= inputSize) return 0
        val available = inputSize - safeOffset
        val requested = if (length < 0) available else length
        return min(outputSize, min(available, requested)).coerceAtLeast(0) and -2
    }

    private fun copyBypass(input: ShortArray, output: ShortArray, offset: Int, length: Int) {
        val safeOffset = max(offset, 0)
        val count = processedSampleCount(input.size, output.size, offset, length)
        if (count > 0) input.copyInto(output, 0, safeOffset, safeOffset + count)
    }

    private fun copyBypass(input: IntArray, output: IntArray, offset: Int, length: Int) {
        val safeOffset = max(offset, 0)
        val count = processedSampleCount(input.size, output.size, offset, length)
        if (count > 0) input.copyInto(output, 0, safeOffset, safeOffset + count)
    }

    private fun copyBypass(input: FloatArray, output: FloatArray, offset: Int, length: Int) {
        val safeOffset = max(offset, 0)
        val count = processedSampleCount(input.size, output.size, offset, length)
        if (count > 0) input.copyInto(output, 0, safeOffset, safeOffset + count)
    }

    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        synchronized(nativeLock) {
            val current = handle
            if(!enabled || current == 0L)
            {
                copyBypass(input, output, offset, length)
            }
            else {
                JamesDspWrapper.processInt16(current, input, output, offset, length)
            }
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        synchronized(nativeLock) {
            val current = handle
            if(!enabled || current == 0L)
            {
                copyBypass(input, output, offset, length)
            }
            else {
                JamesDspWrapper.processInt32(current, input, output, offset, length)
            }
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        synchronized(nativeLock) {
            val current = handle
            if(!enabled || current == 0L)
            {
                copyBypass(input, output, offset, length)
            }
            else {
                JamesDspWrapper.processFloat(current, input, output, offset, length)
            }
        }
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean =
        withHandle(false) {
            JamesDspWrapper.setLimiter(it, threshold, release) and
                JamesDspWrapper.setPostGain(it, postGain)
        }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int,
    ): Boolean = withHandle(false) {
        // The local JNI convolver does not currently use the IR CRC; it is part of the shared
        // engine contract for parity with the remote AudioEffect implementation.
        JamesDspWrapper.setConvolver(it, enable, impulseResponse, irChannels, irFrames)
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
        val acknowledged = JamesDspWrapper.configureNativeBmwPeq(
            current,
            state.enabled,
            state.preampDb,
            state.nativeValues(state.fullRangeBands),
            state.nativeValues(state.lowBandBands),
            state.nativeValues(state.midBandBands),
            revision,
        )
        val active = JamesDspWrapper.getNativeBmwPeqActiveRevision(current)
        val result = acknowledged == revision && active == revision
        BmwPeqState.log(source, state, result)
        if (result) {
            bmwPeqState = state.deepCopy()
            BmwPeqState.publishActiveSession(context, this, state)
            lastPeqApplySuccess = true
            lastPeqFailure = null
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
        synchronized(nativeLock) {
            previous = bmwPeqState
            applied = configureNativeBmwPeqLocked(state, source)
        }
        if (!applied) return false
        var result = true
        if (persistOnSuccess && !state.persist(context)) {
            Timber.e("$source native BMW PEQ applied but persistence commit failed")
            var rollbackRevision: Long? = null
            synchronized(nativeLock) {
                if (bmwPeqState == state) {
                    val rollbackOk = configureNativeBmwPeqLocked(previous, "$source-persistence-rollback")
                    rollbackRevision = requestedPeqRevision
                    if (!rollbackOk) {
                        Timber.e("$source native BMW PEQ persistence rollback failed")
                    }
                } else {
                    Timber.w(
                        "$source native BMW PEQ persistence-rollback skipped: a newer state " +
                            "was applied while this write was in flight"
                    )
                }
                lastPeqApplySuccess = false
                lastPeqFailure = if (rollbackRevision != null)
                    "$source persistence commit failed; previous state requested as rollback revision=$rollbackRevision"
                else
                    "$source persistence commit failed; rollback skipped because a newer state is active"
            }
            result = false
        }
        context.sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
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
        return synchronized(nativeLock) {
            val current = handle
            if (current == 0L) {
                lastDspApplySuccess = false
                lastDspFailure = "Native BMW DSP handle unavailable"
                return@synchronized false
            }
            val revision = nextDspRevisionLocked() ?: return@synchronized false
            val acknowledged = JamesDspWrapper.configureNativeBmwDsp(current, values, revision)
            val active = JamesDspWrapper.getNativeBmwDspActiveRevision(current)
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
            result
        }
    }

    fun nativeBmwCompressorMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwCompressorMeter(it) }

    fun nativeBmwMbcMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwMbcMeter(it) }

    fun nativeBmwBusLimiterMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwBusLimiterMeter(it) }

    fun nativeBmwMasterLimiterMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwMasterLimiterMeter(it) }

    fun startNativeBmwCapture() {
        freePendingCaptureSnapshot()
        withHandle { JamesDspWrapper.startNativeBmwCapture(it) }
    }

    fun stopNativeBmwCapture() = withHandle { JamesDspWrapper.stopNativeBmwCapture(it) }

    fun nativeBmwCaptureFrameCount(): Long =
        withHandle<Long>(0L) { JamesDspWrapper.getNativeBmwCaptureFrameCount(it) }

    @Volatile
    private var pendingCaptureSnapshot: Long = 0L

    private fun freePendingCaptureSnapshot() {
        val stale = pendingCaptureSnapshot
        if (stale != 0L) {
            pendingCaptureSnapshot = 0L
            JamesDspWrapper.freeNativeBmwCaptureSnapshot(stale)
        }
    }

    fun exportNativeBmwCaptureWav(rawInPath: String, outPath: String): FloatArray? {
        val snapshot = pendingCaptureSnapshot.takeIf { it != 0L }
            ?: withHandle(0L) { JamesDspWrapper.takeNativeBmwCaptureSnapshot(it) }
        if (snapshot == 0L) return null
        val result = JamesDspWrapper.exportNativeBmwCaptureWav(snapshot, rawInPath, outPath)
        if (result != null) {
            pendingCaptureSnapshot = 0L
            JamesDspWrapper.freeNativeBmwCaptureSnapshot(snapshot)
        } else {
            pendingCaptureSnapshot = snapshot
        }
        return result
    }

    companion object {
        private const val MIN_VALID_SAMPLE_RATE = 8000f
    }
}
