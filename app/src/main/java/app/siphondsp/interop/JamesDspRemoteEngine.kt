package app.siphondsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import app.siphondsp.MainApplication
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.AudioEffectExtensions.getParameterInt
import app.siphondsp.utils.extensions.AudioEffectExtensions.setParameter
import app.siphondsp.utils.extensions.AudioEffectExtensions.setParameterCharBuffer
import app.siphondsp.utils.extensions.AudioEffectExtensions.setParameterFloatArray
import app.siphondsp.utils.extensions.AudioEffectExtensions.setParameterImpulseResponseBuffer
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.showAlert
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.extensions.crc
import app.siphondsp.utils.extensions.toShort
import timber.log.Timber
import java.util.UUID

class JamesDspRemoteEngine(
    context: Context,
    val sessionId: Int,
    val priority: Int,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null,
) : JamesDspBaseEngine(context, callbacks) {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> rebootEngine()
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { clearCache(); syncWithPreferences() }
            }
        }
    }

    var effect: AudioEffectHidden? = createEffect()

    override var enabled: Boolean
        set(value) { effect?.enabled = value }
        get() = effect?.enabled ?: false

    override var sampleRate: Float
        get() {
            super.sampleRate = effect.getParameterInt(20001)?.toFloat() ?: -0f
            return super.sampleRate
        }
        set(_){}

    init {
        syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(Constants.ACTION_PREFERENCES_UPDATED)
        filter.addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
    }

    private fun createEffect(): AudioEffectHidden {
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId)
        } catch (e: Exception) {
            Timber.e("Failed to create JamesDSP effect")
            Timber.e(e)
            throw IllegalStateException(e)
        }
    }

    private fun checkEngine() {
        if (!isPidValid) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine probably crashed or detached.")
            context.toast("Engine crashed. Rebooting JamesDSP.", false)
            rebootEngine()
        }

        if (isSampleRateAbnormal) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine crashed.")
            context.toast("Abnormal sampling rate. Rebooting JamesDSP.", false)
            rebootEngine()
        }
    }

    private fun rebootEngine() {
        try {
            effect?.release()
            effect = createEffect()
        }
        catch (ex: IllegalStateException) {
            Timber.e("Failed to re-instantiate JamesDSP effect")
            Timber.e(ex.cause)
            effect = null
            return
        }
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        if(effect == null) {
            Timber.d("Rejecting update due to disposed engine")
            return
        }

        checkEngine()
        super.syncWithPreferences(forceUpdateNamespaces)
    }

    override fun close() {
        context.unregisterLocalReceiver(broadcastReceiver)
        effect?.release()
        effect = null
        super.close()
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return effect.setParameterFloatArray(
            1500,
            floatArrayOf(threshold, release, postGain)
        ) == AudioEffect.SUCCESS
    }










    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {

        val prevCrc = this.convolverHash

        Timber.i("Convolver hash before: $prevCrc, current: $irCrc")
        if (prevCrc != irCrc && enable) {
            effect.setParameterImpulseResponseBuffer(12000, 10004, impulseResponse, irChannels)
            effect.setParameter(25003, irCrc) // Commit hash
        }

        return effect.setParameter(1205, enable.toShort()) == AudioEffect.SUCCESS
    }





    // Status
    val pid: Int
        get() = effect.getParameterInt(20002) ?: -1
    val isPidValid: Boolean
        get() = pid > 0
    val isSampleRateAbnormal: Boolean
        get() = sampleRate <= 0
    val paramCommitCount: Int
        get() = effect.getParameterInt(19998) ?: -1
    val isPresetInitialized: Boolean
        get() = paramCommitCount > 0
    val bufferLength: Int
        get() = effect.getParameterInt(19999) ?: -1
    val allocatedBlockLength: Int
        get() = effect.getParameterInt(20000) ?: -1
    val convolverHash: Int
        get() = effect.getParameterInt(30003) ?: -1

    enum class PluginState {
        Unavailable,
        Available,
        Unsupported
    }

    companion object {
        private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
        private val EFFECT_JAMESDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

        fun isPluginInstalled(): PluginState {
            return try {
                AudioEffect
                    .queryEffects()
                    .orEmpty()
                    .firstOrNull { it.uuid == EFFECT_JAMESDSP }
                    ?.run {
                        if(name.contains("v3")) PluginState.Unsupported else PluginState.Available
                    } ?: PluginState.Unavailable
            } catch (e: Exception) {
                Timber.e("isPluginInstalled: exception raised")
                Timber.e(e)
                MainApplication.instance.showAlert(
                    "Error while checking audio effect status",
                    "Unexpected error while checking whether JamesDSP's audio effect library is installed. \n\n" +
                            "Error: $e",
                )
                PluginState.Unavailable
            }
        }
    }
}
