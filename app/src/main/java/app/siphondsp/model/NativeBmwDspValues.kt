package app.siphondsp.model

import android.content.Context
import android.content.Intent
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast

/**
 * Canonical load/save/broadcast for the 35-float native BMW DSP config array (headroom,
 * crossover, tilt, compressor, gains -- everything except the PEQ banks, which live in
 * [BmwPeqState]/`BmwPeqStore`). Single source of truth for the
 * `SharedPreferences("native_bmw_dsp")` "values" contract and the
 * `ACTION_NATIVE_BMW_DSP_UPDATED` broadcast, replacing what were independent copies of the
 * same load/save/broadcast trio in `NativeBmwDspBottomSheet` and `NativeBmwCompressorState`
 * (both now delegate here) -- a third copy would otherwise have been needed for the new
 * interactive tilt-handle commit path.
 */
object NativeBmwDspValues {
    const val PREFS = "native_bmw_dsp"
    const val KEY = "values"
    const val SIZE = 35

    const val INDEX_ENABLED = 0
    const val INDEX_LPF_PASS = 1
    const val INDEX_HPF_PASS = 2
    const val INDEX_CHANNEL_MUTE = 3
    const val INDEX_MEASUREMENT_MUTE = 4
    const val INDEX_HEADROOM = 5
    const val INDEX_LOW_GAIN_L = 6
    const val INDEX_LOW_GAIN_R = 7
    const val INDEX_MID_GAIN_L = 8
    const val INDEX_MID_GAIN_R = 9
    const val INDEX_POST_GAIN_L = 10
    const val INDEX_POST_GAIN_R = 11
    const val INDEX_SUBSONIC_ENABLED = 12
    const val INDEX_SUBSONIC_FREQ = 13
    const val INDEX_LOW_MUTE = 14
    const val INDEX_LOW_CROSSOVER_FREQ = 15
    const val INDEX_LOW_LR4 = 16
    const val INDEX_MID_MUTE = 17
    const val INDEX_MID_CROSSOVER_FREQ = 18
    const val INDEX_LOW_INVERT = 19
    const val INDEX_MID_INVERT = 20
    const val INDEX_MID_DELAY_L = 21
    const val INDEX_MID_DELAY_R = 22
    const val INDEX_LOW_DELAY_L = 23
    const val INDEX_LOW_DELAY_R = 24
    const val INDEX_TILT_ENABLED = 25
    const val INDEX_TILT_AMOUNT = 26
    const val INDEX_TILT_FREQ = 27
    const val INDEX_COMPRESSOR_ENABLED = 28
    const val INDEX_COMPRESSOR_THRESHOLD = 29
    const val INDEX_COMPRESSOR_RATIO = 30
    const val INDEX_COMPRESSOR_KNEE = 31
    const val INDEX_COMPRESSOR_ATTACK = 32
    const val INDEX_COMPRESSOR_RELEASE = 33
    const val INDEX_COMPRESSOR_MAKEUP = 34

    val DEFAULTS = floatArrayOf(
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

    fun load(context: Context): FloatArray {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val parsed = saved?.split(',')?.mapNotNull(String::toFloatOrNull)?.toFloatArray()
        return if (parsed?.size == SIZE) parsed else DEFAULTS.copyOf()
    }

    fun save(context: Context, values: FloatArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, values.joinToString(","))
            .apply()
    }

    fun broadcast(context: Context, values: FloatArray) {
        context.sendLocalBroadcast(
            Intent(Constants.ACTION_NATIVE_BMW_DSP_UPDATED).putExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES, values)
        )
    }

    /** load -> mutate a private copy -> save -> broadcast. Returns the applied snapshot. */
    fun update(context: Context, mutate: (FloatArray) -> Unit): FloatArray {
        val values = load(context)
        mutate(values)
        save(context, values)
        broadcast(context, values)
        return values
    }
}
