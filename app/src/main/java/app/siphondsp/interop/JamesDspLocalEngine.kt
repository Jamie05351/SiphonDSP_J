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
    @Volatile private var bmwPeqState: BmwPeqState = BmwPeqState.load(context)
    @Volatile private var peqRestorePending = true

    @Volatile
    private var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

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
            if (value >= MIN_VALID_SAMPLE_RATE && peqRestorePending) {
                restoreNativeBmwPeq()
            }
            synchronized(nativeLock) {
                if (handle != 0L && !peqRestorePending) refreshEqualizersLocked()
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
        val persisted = BmwPeqState.load(context)
        if (configureNativeBmwPeq(persisted, persistOnSuccess = false, source = "cold-start")) {
            peqRestorePending = false
            BmwPeqState.recordRestoreResult(context, "persisted-state")
            return
        }
        val persistedError = persisted.validate(sampleRate) ?: "native configuration rejected"
        val lastKnownGood = BmwPeqState.loadLastKnownGood(context)
        if (lastKnownGood != null &&
            configureNativeBmwPeq(lastKnownGood, persistOnSuccess = true, source = "cold-start-lkg")
        ) {
            peqRestorePending = false
            Timber.w("Native BMW PEQ recovered from last-known-good state")
            BmwPeqState.recordRestoreResult(
                context, "last-known-good", persistedError, fallbackUsed = true
            )
            return
        }
        val safe = BmwPeqState.empty()
        if (!BmwPeqState.backupRejectedPersistedState(context)) {
            Timber.e("Failed to preserve rejected BMW PEQ state before safe fallback")
        }
        val safeOk = configureNativeBmwPeq(safe, persistOnSuccess = true, source = "cold-start-safe")
        if (safeOk) peqRestorePending = false
        Timber.e("Native BMW PEQ used safe fallback result=$safeOk reason=$persistedError")
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
        }
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

    // Re-push the BMW three-bank PEQ from disk to the running engine. Called from the
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
        if (result) bmwPeqState = state
        return result
    }

    fun configureNativeBmwPeq(
        state: BmwPeqState,
        persistOnSuccess: Boolean = true,
        source: String = "editor",
    ): Boolean {
        val previous: BmwPeqState
        val result: Boolean
        synchronized(nativeLock) {
            previous = bmwPeqState
            result = configureNativeBmwPeqLocked(state, source)
        }
        if (result && persistOnSuccess && !state.persist(context)) {
            Timber.e("$source native BMW PEQ applied but persistence commit failed")
            synchronized(nativeLock) {
                configureNativeBmwPeqLocked(previous, "$source-persistence-rollback")
            }
            return false
        }
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

    fun startNativeBmwCapture() = withHandle { JamesDspWrapper.startNativeBmwCapture(it) }

    fun stopNativeBmwCapture() = withHandle { JamesDspWrapper.stopNativeBmwCapture(it) }

    fun nativeBmwCaptureFrameCount(): Long =
        withHandle<Long>(0L) { JamesDspWrapper.getNativeBmwCaptureFrameCount(it) }

    fun exportNativeBmwCaptureWav(rawInPath: String, outPath: String): FloatArray? =
        withHandle<FloatArray?>(null) { JamesDspWrapper.exportNativeBmwCaptureWav(it, rawInPath, outPath) }

    companion object {
        private const val MIN_VALID_SAMPLE_RATE = 8000f
    }
}
