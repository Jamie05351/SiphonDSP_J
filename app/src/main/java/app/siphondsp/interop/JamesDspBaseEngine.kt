package app.siphondsp.interop

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.siphondsp.R
import app.siphondsp.model.ProcessorMessage
import app.siphondsp.preference.FileLibraryPreference
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File

abstract class JamesDspBaseEngine(val context: Context, val callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : AutoCloseable {
    abstract var enabled: Boolean
    open var sampleRate: Float = 0.0f
        set(value) {
            field = value
            reportSampleRate(value)
        }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()
    protected val cache = PreferenceCache(context)

    override fun close() {
        Timber.d("Closing engine")
        reportSampleRate(0f)
        syncScope.cancel()
    }

    open fun syncWithPreferences(forceUpdateNamespaces: Array<String>? = null) {
        syncScope.launch {
            syncWithPreferencesAsync(forceUpdateNamespaces)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun reportSampleRate(value: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, value)
        })
    }

    private suspend fun syncWithPreferencesAsync(forceUpdateNamespaces: Array<String>? = null) {
        Timber.d("Synchronizing with preferences... (forced: %s)", forceUpdateNamespaces?.joinToString(";") { it })

        syncMutex.withLock {
            cache.select(Constants.PREF_OUTPUT)
            val outputPostGain = cache.get(R.string.key_output_postgain, 0f)
            val limiterThreshold = cache.get(R.string.key_limiter_threshold, -0.1f)
            val limiterRelease = cache.get(R.string.key_limiter_release, 60f)

            cache.select(Constants.PREF_CONVOLVER)
            val convolverEnabled = cache.get(R.string.key_convolver_enable, false)
            val convolverFile = cache.get(R.string.key_convolver_file, "")
            val convolverAdvImp = cache.get(R.string.key_convolver_adv_imp, Constants.DEFAULT_CONVOLVER_ADVIMP)
            val convolverMode = cache.get(R.string.key_convolver_mode, "0").toInt()

            val targets = cache.changedNamespaces.toTypedArray() + (forceUpdateNamespaces ?: arrayOf())
            targets.forEach {
                Timber.i("Committing new changes in namespace '$it'")

                val result = when (it) {
                    Constants.PREF_OUTPUT -> setOutputControl(limiterThreshold, limiterRelease, outputPostGain)
                    Constants.PREF_CONVOLVER -> setConvolver(convolverEnabled, convolverFile, convolverMode, convolverAdvImp)
                    else -> true
                }

                if(!result) {
                    Timber.e("Failed to apply $it")
                }
            }

            cache.markChangesAsCommitted()
            Timber.i("Preferences synchronized")
        }
    }

    fun setConvolver(enable: Boolean, impulseResponsePath: String, optimizationMode: Int, waveEditStr: String): Boolean
    {
        val path = FileLibraryPreference.createFullPathCompat(context, impulseResponsePath)

        // Handle disabled state before everything else
        if(!enable || !File(path).exists() || File(path).isDirectory) {
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            return true
        }

        val advConv = waveEditStr.split(";")
        val advSetting = IntArray(6)
        advSetting.fill(0)
        advSetting[0] = -80
        advSetting[1] = -100
        try
        {
            if (advConv.size == 6)
            {
                for (i in advConv.indices) advSetting[i] = Integer.valueOf(advConv[i])
            }
            else {
                Timber.w("setConvolver: AdvImp setting has the wrong size (${advConv.size})")
                callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
            }
        }
        catch(ex: NumberFormatException) {
            Timber.e("setConvolver: NumberFormatException while parsing AdvImp setting. Using defaults.")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        val info = IntArray(4)
        val imp = JdspImpResToolbox.ReadImpulseResponseToFloat(
            path,
            sampleRate.toInt(),
            info,
            optimizationMode,
            advSetting
        )

        if(imp == null) {
            Timber.e("setConvolver: Failed to read IR")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.Corrupted)
            return false
        }

        // check frame count
        if(info[1] == 0) {
            Timber.e("setConvolver: IR has no frames")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.NoFrames)
            return false
        }

        // check if advSetting was invalid
        if(info[3] == 0) {
            Timber.w("setConvolver: advSetting was invalid")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        return setConvolverInternal(true, imp, info[0], info[1], info[2])
    }

    // Effect config -- only Output Control and Convolver remain; the generic JamesDSP effects
    // (EQ / graphic EQ / compander / reverb / crossfeed / bass boost / stereo widen / tube /
    // DDC / liveprog) were removed with their UI. The native Java_..._set* JNI impls are gone
    // too; the underlying libjamesdsp DSP is still compiled in but unreachable.
    abstract fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean
    protected abstract fun setConvolverInternal(enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int, irCrc: Int): Boolean

    protected inner class DummyCallbacks : JamesDspWrapper.JamesDspCallbacks
    {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }
}
