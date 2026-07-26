package app.siphondsp.interop

import android.content.Context
import android.content.Intent
import app.siphondsp.R
import app.siphondsp.interop.structure.EelVmVariable
import app.siphondsp.model.BmwPeqState
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    private val nativeLock = Any()
    @Volatile private var bmwPeqState: BmwPeqState = BmwPeqState.empty()

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
                    refreshEqualizersLocked()
                    configureNativeBmwPeqLocked(bmwPeqState, "sample-rate")
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
        val persisted = BmwPeqState.load(context)
        if (configureNativeBmwPeq(persisted, persistOnSuccess = false, source = "cold-start")) {
            BmwPeqState.recordRestoreResult(context, "persisted-state")
            return
        }
        val persistedError = persisted.validate(sampleRate) ?: "native configuration rejected"
        val lastKnownGood = BmwPeqState.loadLastKnownGood(context)
        if (lastKnownGood != null &&
            configureNativeBmwPeq(lastKnownGood, persistOnSuccess = true, source = "cold-start-lkg")
        ) {
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

    // Processing
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

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean =
        withHandle(false) {
            JamesDspWrapper.setLimiter(it, threshold, release) and
                JamesDspWrapper.setPostGain(it, postGain)
        }

    override fun setReverb(enable: Boolean, preset: Int): Boolean =
        withHandle(false) { JamesDspWrapper.setReverb(it, enable, preset) }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean =
        withHandle(false) { JamesDspWrapper.setCrossfeed(it, enable, mode, 0, 0) }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean =
        withHandle(false) { JamesDspWrapper.setCrossfeed(it, enable, 99, fcut, feed) }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean =
        withHandle(false) { JamesDspWrapper.setBassBoost(it, enable, maxGain) }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean =
        withHandle(false) { JamesDspWrapper.setStereoEnhancement(it, enable, level) }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean =
        withHandle(false) { JamesDspWrapper.setVacuumTube(it, enable, level) }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean = withHandle(false) {
        JamesDspWrapper.setMultiEqualizer(it, enable, filterType, interpolationMode, bands)
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean = withHandle(false) {
        JamesDspWrapper.setCompander(it, enable, timeConstant, granularity, tfTransforms, bands)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean =
        withHandle(false) { JamesDspWrapper.setVdc(it, enable, vdc) }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean = withHandle(false) {
        JamesDspWrapper.setConvolver(it, enable, impulseResponse, irChannels, irFrames)
    }

    /**
     * The base class still builds a merged GraphicEQ string for compatibility
     * with the rooted AudioEffect engine. The rootless engine deliberately
     * ignores that approximation: GEQ remains in libjamesdsp and PEQ is applied
     * afterwards by the real stateful biquad cascade above.
     */
    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean =
        synchronized(nativeLock) { refreshEqualizersLocked() }

    private fun refreshEqualizersLocked(): Boolean {
        val current = handle
        if (current == 0L) return false

        val geqPrefs = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)

        val geqEnabled = geqPrefs.getBoolean(context.getString(R.string.key_geq_enable), false)
        val geqBands = geqPrefs.getString(
            context.getString(R.string.key_geq_nodes),
            Constants.DEFAULT_GEQ_INTERNAL,
        ) ?: Constants.DEFAULT_GEQ_INTERNAL

        val geqOk = JamesDspWrapper.setGraphicEq(current, geqEnabled, geqBands)
        val peqPrefs = context.getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
        val requestedEnabled = peqPrefs.getBoolean(context.getString(R.string.key_peq_enable), bmwPeqState.enabled)
        val peqOk = if (requestedEnabled != bmwPeqState.enabled) {
            val snapshot = bmwPeqState.copy(enabled = requestedEnabled)
            configureNativeBmwPeqLocked(snapshot, "preference-toggle") && snapshot.persist(context)
        } else true
        return geqOk && peqOk
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
    ): Boolean = synchronized(nativeLock) {
        val previous = bmwPeqState
        val result = configureNativeBmwPeqLocked(state, source)
        if (result && persistOnSuccess && !state.persist(context)) {
            Timber.e("$source native BMW PEQ applied but persistence commit failed")
            configureNativeBmwPeqLocked(previous, "$source-persistence-rollback")
            return@synchronized false
        }
        result
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean =
        withHandle(false) { JamesDspWrapper.setLiveprog(it, enable, name, script) }

    // Feature support
    override fun supportsEelVmAccess(): Boolean = true
    override fun supportsCustomCrossfeed(): Boolean = true

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> =
        withHandle(arrayListOf<EelVmVariable>()) { JamesDspWrapper.enumerateEelVariables(it) }

    override fun manipulateEelVariable(name: String, value: Float): Boolean =
        withHandle(false) { JamesDspWrapper.manipulateEelVariable(it, name, value) }

    override fun freezeLiveprogExecution(freeze: Boolean) {
        withHandle { JamesDspWrapper.freezeLiveprogExecution(it, freeze) }
    }

    private fun loadNativeBmwDspValues(): FloatArray {
        val saved = context.getSharedPreferences(NATIVE_BMW_PREFS, Context.MODE_PRIVATE)
            .getString(NATIVE_BMW_KEY, null)
        val parsed = saved?.split(',')?.mapNotNull(String::toFloatOrNull)?.toFloatArray()
        return if (parsed?.size == NATIVE_BMW_DEFAULTS.size) parsed else NATIVE_BMW_DEFAULTS.copyOf()
    }

    fun configureNativeBmwDsp(values: FloatArray): Boolean {
        if (values.size != NATIVE_BMW_DEFAULTS.size) {
            Timber.e("Rejected native BMW DSP configuration with ${values.size} values")
            return false
        }
        return withHandle(false) { JamesDspWrapper.configureNativeBmwDsp(it, values) }
    }

    companion object {
        private const val NATIVE_BMW_PREFS = "native_bmw_dsp"
        private const val NATIVE_BMW_KEY = "values"
        private val NATIVE_BMW_DEFAULTS = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            -6f, 0f, 0f, -1f, -1f, 0f, 0f,
            1f, 32f,
            0f, 150f, 0f,
            0f, 125f,
            0f, 0f,
            0f, 0f, 0f, 0f,
            1f, 3f, 550f,
            1f, -12f, 2f, 8f, 40f, 250f, 1.5f,
        )
    }
}
