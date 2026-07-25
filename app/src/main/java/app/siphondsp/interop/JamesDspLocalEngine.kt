package app.siphondsp.interop

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import app.siphondsp.R
import app.siphondsp.interop.structure.EelVmVariable
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    private val nativeLock = Any()
    private val parametricEq = ParametricBiquadProcessor()
    private val bandPeqPrefs = context.getSharedPreferences(BAND_PEQ_PREFS, Context.MODE_PRIVATE)
    private val bandPeqPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_LOW_BANDS || key == KEY_MID_BANDS) {
            synchronized(nativeLock) {
                val current = handle
                if (current != 0L && !configureNativeBmwBandPeqLocked(current)) {
                    Timber.e("Rejected updated native BMW band PEQ configuration")
                }
            }
        }
    }

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
                } else {
                    parametricEq.configure(false, "", 0f, value)
                }
            }
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate

    override var enabled: Boolean = true

    init {
        bandPeqPrefs.registerOnSharedPreferenceChangeListener(bandPeqPreferenceListener)
        if (BenchmarkManager.hasBenchmarksCached()) BenchmarkManager.loadBenchmarksFromCache()
        val restored = loadNativeBmwDspValues()
        if (!configureNativeBmwDsp(restored)) Timber.e("Failed to restore saved native BMW DSP configuration")
        if (!configureNativeBmwBandPeq()) Timber.e("Failed to restore saved native BMW band PEQ configuration")
    }

    private inline fun <T> withHandle(default: T, block: (JamesDspHandle) -> T): T = synchronized(nativeLock) {
        val current = handle
        if (current == 0L) default else block(current)
    }

    private inline fun withHandle(block: (JamesDspHandle) -> Unit) {
        synchronized(nativeLock) {
            val current = handle
            if (current != 0L) block(current)
        }
    }

    override fun close() {
        bandPeqPrefs.unregisterOnSharedPreferenceChangeListener(bandPeqPreferenceListener)
        super.close()
        synchronized(nativeLock) {
            val oldHandle = handle
            handle = 0L
            parametricEq.configure(false, "", 0f, sampleRate)
            if (oldHandle != 0L) {
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

    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1) {
        synchronized(nativeLock) {
            val current = handle
            if (!enabled || current == 0L) copyBypass(input, output, offset, length)
            else {
                JamesDspWrapper.processInt16(current, input, output, offset, length)
                parametricEq.process(output, processedSampleCount(input.size, output.size, offset, length))
            }
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1) {
        synchronized(nativeLock) {
            val current = handle
            if (!enabled || current == 0L) copyBypass(input, output, offset, length)
            else {
                JamesDspWrapper.processInt32(current, input, output, offset, length)
                parametricEq.process(output, processedSampleCount(input.size, output.size, offset, length))
            }
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1) {
        synchronized(nativeLock) {
            val current = handle
            if (!enabled || current == 0L) copyBypass(input, output, offset, length)
            else {
                JamesDspWrapper.processFloat(current, input, output, offset, length)
                parametricEq.process(output, processedSampleCount(input.size, output.size, offset, length))
            }
        }
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean =
        withHandle(false) { JamesDspWrapper.setLimiter(it, threshold, release) and JamesDspWrapper.setPostGain(it, postGain) }

    override fun setReverb(enable: Boolean, preset: Int): Boolean = withHandle(false) { JamesDspWrapper.setReverb(it, enable, preset) }
    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean = withHandle(false) { JamesDspWrapper.setCrossfeed(it, enable, mode, 0, 0) }
    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean = withHandle(false) { JamesDspWrapper.setCrossfeed(it, enable, 99, fcut, feed) }
    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean = withHandle(false) { JamesDspWrapper.setBassBoost(it, enable, maxGain) }
    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean = withHandle(false) { JamesDspWrapper.setStereoEnhancement(it, enable, level) }
    override fun setVacuumTube(enable: Boolean, level: Float): Boolean = withHandle(false) { JamesDspWrapper.setVacuumTube(it, enable, level) }

    override fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean =
        withHandle(false) { JamesDspWrapper.setMultiEqualizer(it, enable, filterType, interpolationMode, bands) }

    override fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray): Boolean =
        withHandle(false) { JamesDspWrapper.setCompander(it, enable, timeConstant, granularity, tfTransforms, bands) }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean = withHandle(false) { JamesDspWrapper.setVdc(it, enable, vdc) }

    override fun setConvolverInternal(enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int, irCrc: Int): Boolean =
        withHandle(false) { JamesDspWrapper.setConvolver(it, enable, impulseResponse, irChannels, irFrames) }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean = synchronized(nativeLock) { refreshEqualizersLocked() }

    private fun refreshEqualizersLocked(): Boolean {
        val current = handle
        if (current == 0L) {
            parametricEq.configure(false, "", 0f, sampleRate)
            return false
        }
        val geqPrefs = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        val peqPrefs = context.getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
        val geqEnabled = geqPrefs.getBoolean(context.getString(R.string.key_geq_enable), false)
        val geqBands = geqPrefs.getString(context.getString(R.string.key_geq_nodes), Constants.DEFAULT_GEQ_INTERNAL) ?: Constants.DEFAULT_GEQ_INTERNAL
        val peqEnabled = peqPrefs.getBoolean(context.getString(R.string.key_peq_enable), false)
        val peqBands = peqPrefs.getString(context.getString(R.string.key_peq_bands), Constants.DEFAULT_PEQ) ?: Constants.DEFAULT_PEQ
        val peqPreamp = peqPrefs.getFloat(context.getString(R.string.key_peq_preamp), 0f)
        val geqOk = JamesDspWrapper.setGraphicEq(current, geqEnabled, geqBands)
        val peqOk = parametricEq.configure(peqEnabled, peqBands, peqPreamp, sampleRate)
        if (!peqOk && peqEnabled) Timber.e("Rejected invalid parametric EQ configuration; PEQ has been bypassed")
        val bandPeqOk = configureNativeBmwBandPeqLocked(current)
        if (!bandPeqOk) Timber.e("Rejected invalid native BMW band PEQ configuration")
        return geqOk && peqOk && bandPeqOk
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean = withHandle(false) { JamesDspWrapper.setLiveprog(it, enable, name, script) }
    override fun supportsEelVmAccess(): Boolean = true
    override fun supportsCustomCrossfeed(): Boolean = true
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> = withHandle(arrayListOf()) { JamesDspWrapper.enumerateEelVariables(it) }
    override fun manipulateEelVariable(name: String, value: Float): Boolean = withHandle(false) { JamesDspWrapper.manipulateEelVariable(it, name, value) }
    override fun freezeLiveprogExecution(freeze: Boolean) { withHandle { JamesDspWrapper.freezeLiveprogExecution(it, freeze) } }

    private fun loadNativeBmwDspValues(): FloatArray {
        val saved = context.getSharedPreferences(NATIVE_BMW_PREFS, Context.MODE_PRIVATE).getString(NATIVE_BMW_KEY, null)
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

    private fun flattenBands(serialized: String): DoubleArray {
        val bands = ParametricEqBandList().apply { deserialize(serialized) }
        return DoubleArray(bands.size * PEQ_VALUES_PER_BAND).also { values ->
            bands.forEachIndexed { index, band ->
                val offset = index * PEQ_VALUES_PER_BAND
                values[offset] = band.frequency
                values[offset + 1] = band.gain
                values[offset + 2] = band.q
                values[offset + 3] = band.filterType.code.toDouble()
                values[offset + 4] = band.channel.code.toDouble()
            }
        }
    }

    private fun configureNativeBmwBandPeqLocked(current: JamesDspHandle): Boolean {
        val low = flattenBands(bandPeqPrefs.getString(KEY_LOW_BANDS, EMPTY_PEQ) ?: EMPTY_PEQ)
        val mid = flattenBands(bandPeqPrefs.getString(KEY_MID_BANDS, EMPTY_PEQ) ?: EMPTY_PEQ)
        return JamesDspWrapper.configureNativeBmwBandPeq(current, low, mid)
    }

    fun configureNativeBmwBandPeq(): Boolean = withHandle(false) { configureNativeBmwBandPeqLocked(it) }

    companion object {
        private const val NATIVE_BMW_PREFS = "native_bmw_dsp"
        private const val NATIVE_BMW_KEY = "values"
        private const val BAND_PEQ_PREFS = "native_bmw_band_peq"
        private const val KEY_LOW_BANDS = "low_bands"
        private const val KEY_MID_BANDS = "mid_bands"
        private const val EMPTY_PEQ = "PEQ: "
        private const val PEQ_VALUES_PER_BAND = 5
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
