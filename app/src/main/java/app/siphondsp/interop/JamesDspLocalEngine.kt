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

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    private val nativeLock = Any()
    @Volatile private var bmwPeqState: BmwPeqState = BmwPeqState.loadPersisted(context)
    @Volatile private var peqRestorePending = true

    @Volatile
    private var handle: JamesDspHandle = try {
        JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())
    } catch (e: Throwable) {
        // Caught broadly (not just Exception) since the failure this guards against -- native
        // allocation failing under low memory -- can surface as an OutOfMemoryError, an Error
        // subtype Exception doesn't catch. Every other method in this class already treats
        // handle==0L as "native not ready" and degrades gracefully (copyBypass, withHandle's
        // default-value paths); an uncaught exception here instead crashes the constructor,
        // which callers (RootlessAudioProcessorService.onCreate()) run before startForeground().
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
            // restoreNativeBmwPeq() above already applies whatever PEQ state is needed at the
            // now-current sample rate (set via super.sampleRate = value earlier in this same
            // call) on success, so re-pushing it again immediately below would redundantly
            // reload from disk and reconfigure natively a second time under this same lock, for
            // no reason. Only do the normal "already running, rate changed" re-sync when this
            // call didn't just attempt a cold-start restore.
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
        // A structural validation failure (e.g. a band now above the current sample rate's
        // Nyquist) means this exact persisted config can never succeed on its own, so it's
        // correct -- necessary, even -- to permanently replace it below. A bare native-side
        // rejection with the config structurally valid by Kotlin's own check is different: that
        // can be a transient native/JNI failure (this app already tracks OOM-class native
        // failures elsewhere), not a real problem with the saved config. Persisting a fallback
        // over the user's real config for a one-off glitch would destroy it permanently for no
        // reason, so only the structural case persists; the transient case falls back for this
        // session only and leaves the real persisted state on disk for the next cold start to
        // try again fresh.
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
        irCrc: Int
    ): Boolean = withHandle(false) {
        JamesDspWrapper.setConvolver(it, enable, impulseResponse, irChannels, irFrames)
    }

    // Re-push the active BMW three-bank PEQ (including a session fallback). Called from the
    // sampleRate setter (a rate change invalidates the biquad coefficients).
    private fun refreshEqualizersLocked(): Boolean {
        if (handle == 0L) return false
        return if (peqRestorePending) true
        else configureNativeBmwPeqLocked(BmwPeqState.load(context), "preference-sync")
    }

    private fun configureNativeBmwPeqLocked(state: BmwPeqState, source: String): Boolean {
        val validation = state.validate(sampleRate)
        if (validation != null) {
            Timber.e("$source native BMW PEQ validation failed: $validation")
            return false
        }
        val current = handle
        if (current == 0L) return false
        val result = JamesDspWrapper.configureNativeBmwPeq(
            current,
            state.enabled,
            state.preampDb,
            state.nativeValues(state.fullRangeBands),
            state.nativeValues(state.lowBandBands),
            state.nativeValues(state.midBandBands),
        )
        BmwPeqState.log(source, state, result)
        if (result) {
            bmwPeqState = state.deepCopy()
            BmwPeqState.publishActiveSession(context, this, state)
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
            synchronized(nativeLock) {
                // nativeLock is deliberately released during state.persist()'s disk I/O above
                // (holding it would block the audio-processing hot path, which also takes
                // nativeLock, for the duration of two fsync'd file writes) -- so another call
                // could have applied and persisted a different state while this write was in
                // flight. Only roll back if bmwPeqState still equals what this call itself
                // applied; otherwise the stale `previous` snapshot from before this call even
                // started would clobber that newer, already-successful state. All real call
                // sites serialize on the main thread today so this window is currently latent,
                // but nothing structurally prevented it before this check.
                if (bmwPeqState == state) {
                    configureNativeBmwPeqLocked(previous, "$source-persistence-rollback")
                } else {
                    Timber.w(
                        "$source native BMW PEQ persistence-rollback skipped: a newer state " +
                            "was applied while this write was in flight"
                    )
                }
            }
            result = false
        }
        context.sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        return result
    }

    private fun loadNativeBmwDspValues(): FloatArray = NativeBmwDspValues.load(context)

    fun configureNativeBmwDsp(values: FloatArray): Boolean {
        if (values.size != NativeBmwDspValues.SIZE) {
            Timber.e("Rejected native BMW DSP configuration with ${values.size} values")
            return false
        }
        return withHandle(false) { JamesDspWrapper.configureNativeBmwDsp(it, values) }
    }

    fun nativeBmwCompressorMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwCompressorMeter(it) }

    /** 12 floats: 4 MBC bands x [inputDb, outputDb, gainReductionDb]. See getNativeBmwMbcMeter. */
    fun nativeBmwMbcMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwMbcMeter(it) }

    /** 2 floats: [lowBusGrDb, midBusGrDb] -- per-bus limiter gain reduction. */
    fun nativeBmwBusLimiterMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwBusLimiterMeter(it) }

    /** 1 float: [masterLimiterGrDb] -- master limiter gain reduction; 0 while bypassed. */
    fun nativeBmwMasterLimiterMeter(): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.getNativeBmwMasterLimiterMeter(it) }

    // Starting a fresh capture makes any snapshot retained from a previous failed export
    // (see exportNativeBmwCaptureWav below) irrelevant -- free it now rather than leaking it
    // until the next successful export, which may never come if the user just re-records.
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

    // Detach ownership while the engine is protected, then release nativeLock
    // before file I/O. Closing the engine cannot invalidate this snapshot.
    //
    // A snapshot is only freed once WAV export from it actually succeeds. On failure (a full
    // cache partition, a transient write error) it's kept in pendingCaptureSnapshot instead: the
    // completed native capture is still intact and calling this again retries the export from
    // the same data, rather than permanently discarding a finished take and forcing another
    // capture. startNativeBmwCapture() frees a stale pending snapshot once it's no longer the
    // most recent capture.
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
