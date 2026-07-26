package app.siphondsp.model

import android.content.Context
import android.content.Intent
import app.siphondsp.fragment.NativeBmwDspBottomSheet
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast

data class NativeBmwCompressorState(
    val enabled: Boolean,
    val thresholdDb: Float,
    val ratio: Float,
    val kneeDb: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val makeupDb: Float,
) {
    fun persistAndApply(context: Context) {
        val values = loadNativeValues(context)
        values[INDEX_ENABLED] = if (enabled) 1f else 0f
        values[INDEX_THRESHOLD] = thresholdDb.coerceIn(-18f, 0f)
        values[INDEX_RATIO] = ratio.coerceIn(1f, 10f)
        values[INDEX_KNEE] = kneeDb.coerceIn(0f, 12f)
        values[INDEX_ATTACK] = attackMs.coerceIn(1f, 50f)
        values[INDEX_RELEASE] = releaseMs.coerceIn(20f, 400f)
        values[INDEX_MAKEUP] = makeupDb.coerceIn(0f, 6f)
        context.getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(NATIVE_VALUES_KEY, values.joinToString(",")).apply()
        context.getSharedPreferences(MENU_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("bmw_comp_enable", enabled)
            .putFloat("bmw_comp_threshold", values[INDEX_THRESHOLD])
            .putFloat("bmw_comp_ratio", values[INDEX_RATIO])
            .putFloat("bmw_comp_knee", values[INDEX_KNEE])
            .putFloat("bmw_comp_attack", values[INDEX_ATTACK])
            .putFloat("bmw_comp_release", values[INDEX_RELEASE])
            .putFloat("bmw_comp_makeup", values[INDEX_MAKEUP])
            .apply()
        context.sendLocalBroadcast(
            Intent(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
                .putExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES, values)
        )
    }

    companion object {
        private const val NATIVE_PREFS = "native_bmw_dsp"
        private const val NATIVE_VALUES_KEY = "values"
        private const val MENU_PREFS = "native_bmw_dsp_menu"
        private const val INDEX_ENABLED = 28
        private const val INDEX_THRESHOLD = 29
        private const val INDEX_RATIO = 30
        private const val INDEX_KNEE = 31
        private const val INDEX_ATTACK = 32
        private const val INDEX_RELEASE = 33
        private const val INDEX_MAKEUP = 34

        fun load(context: Context): NativeBmwCompressorState {
            val values = loadNativeValues(context)
            return NativeBmwCompressorState(
                enabled = values[INDEX_ENABLED] >= .5f,
                thresholdDb = values[INDEX_THRESHOLD],
                ratio = values[INDEX_RATIO],
                kneeDb = values[INDEX_KNEE],
                attackMs = values[INDEX_ATTACK],
                releaseMs = values[INDEX_RELEASE],
                makeupDb = values[INDEX_MAKEUP],
            )
        }

        private fun loadNativeValues(context: Context): FloatArray {
            val saved = context.getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
                .getString(NATIVE_VALUES_KEY, null)
            val parsed = saved?.split(',')?.mapNotNull(String::toFloatOrNull)?.toFloatArray()
            return if (parsed?.size == NativeBmwDspBottomSheet.DEFAULTS.size) parsed
            else NativeBmwDspBottomSheet.DEFAULTS.copyOf()
        }
    }
}
