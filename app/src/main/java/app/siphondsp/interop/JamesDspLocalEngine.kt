package app.siphondsp.interop

import android.content.Context
import android.content.Intent
import app.siphondsp.R
import app.siphondsp.interop.structure.EelVmVariable
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    private val nativeLock = Any()
    private val parametricEq = ParametricBiquadProcessor()
    private var shortPeqScratch = ShortArray(0)
    private var intPeqScratch = IntArray(0)
    private var floatPeqScratch = FloatArray(0)

    @Volatile
    private var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    override var sampleRate: Float
        set(value) {
            synchronized(nativeLock) {
                super.sampleRate = value
                val current = handle
                if (current != 0L) {
                    JamesDspWrapper.setSamplingRate(current, value, false)
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
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    private inline fun <T> withHandle(default: T, block: (JamesDspHandle) -> T): T = synchronized(nativeLock) {
        val current = handle
        if(current == 0L) default else block(current)
    }

    private inline fun withHandle(block: (JamesDspHandle) -> Unit) {
        synchronized(nativeLock) {
            val current = handle
            if(current != 0L) block(current)
        }
    }

    override fun close() {
        super.close()
        synchronized(nativeLock) {
            val oldHandle = handle
            handle = 0L
            parametricEq.configure(false, "", 0f, sampleRate)
            shortPeqScratch = ShortArray(0)
            intPeqScratch = IntArray(0)
            floatPeqScratch = FloatArray(0)
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

    private fun preparePeqInput(input: ShortArray, outputSize: Int, offset: Int, length: Int): Int {
        val count = processedSampleCount(input.size, outputSize, offset, length)
        if (count <= 0) return 0
        if (shortPeqScratch.size < count) shortPeqScratch = ShortArray(count)
        val safeOffset = max(offset, 0)
        input.copyInto(shortPeqScratch, 0, safeOffset, safeOffset + count)
        parametricEq.process(shortPeqScratch, count)
        return count
    }

    private fun preparePeqInput(input: IntArray, outputSize: Int, offset: Int, length: Int): Int {
        val count = processedSampleCount(input.size, outputSize, offset, length)
        if (count <= 0) return 0
        if (intPeqScratch.size < count) intPeqScratch = IntArray(count)
        val safeOffset = max(offset, 0)
        input.copyInto(intPeqScratch, 0, safeOffset, safeOffset + count)
        parametricEq.process(intPeqScratch, count)
        return count
    }

    private fun preparePeqInput(input: FloatArray, outputSize: Int, offset: Int, length: Int): Int {
        val count = processedSampleCount(input.size, outputSize, offset, length)
        if (count <= 0) return 0
        if (floatPeqScratch.size < count) floatPeqScratch = FloatArray(count)
        val safeOffset = max(offset, 0)
        input.copyInto(floatPeqScratch, 0, safeOffset, safeOffset + count)
        parametricEq.process(floatPeqScratch, count)
        return count
    }

    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1) {
        synchronized(nativeLock) {
            val current = handle
            if(!enabled || current == 0L) copyBypass(input, output, offset, length)
            else if (!parametricEq.isActive) JamesDspWrapper.processInt16(current, input, output, offset, length)
            else {
                val count = preparePeqInput(input, output.size, offset, length)
                if (count > 0) JamesDspWrapper.processInt16(current, shortPeqScratch, output, 0, count)
            }
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1) {
        synchronized(nativeLock) {
            val current = handle
            if(!enabled || current == 0L) copyBypass(input, output, offset, length)
            else if (!parametricEq.isActive) JamesDspWrapper.processInt32(current, input, output, offset, length)
            else {
                val count = preparePeqInput(input, output.size, offset, length)
                if (count > 0) JamesDspWrapper.processInt32(current, intPeqScratch, output, 0, count)
            }
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1) {
        synchronized(nativeLock) {
            val current = handle
            if(!enabled || current == 0L) copyBypass(input, output, offset, length)
            else if (!parametricEq.isActive) JamesDspWrapper.processFloat(current, input, output, offset, length)
            else {
                val count = preparePeqInput(input, output.size, offset, length)
                if (count > 0) JamesDspWrapper.processFloat(current, floatPeqScratch, output, 0, count)
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
        return geqOk && peqOk
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean =
        withHandle(false) { JamesDspWrapper.setLiveprog(it, enable, name, script) }

    override fun supportsEelVmAccess(): Boolean = true
    override fun supportsCustomCrossfeed(): Boolean = true
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> =
        withHandle(arrayListOf<EelVmVariable>()) { JamesDspWrapper.enumerateEelVariables(it) }
    override fun manipulateEelVariable(name: String, value: Float): Boolean =
        withHandle(false) { JamesDspWrapper.manipulateEelVariable(it, name, value) }
    override fun freezeLiveprogExecution(freeze: Boolean) {
        withHandle { JamesDspWrapper.freezeLiveprogExecution(it, freeze) }
    }
}
