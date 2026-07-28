package app.siphondsp.model

import android.content.Context

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
        val values = NativeBmwDspValues.update(context) { values ->
            values[INDEX_ENABLED] = if (enabled) 1f else 0f
            values[INDEX_THRESHOLD] = thresholdDb.coerceIn(-18f, 0f)
            values[INDEX_RATIO] = ratio.coerceIn(1f, 10f)
            values[INDEX_KNEE] = kneeDb.coerceIn(0f, 12f)
            values[INDEX_ATTACK] = attackMs.coerceIn(1f, 50f)
            values[INDEX_RELEASE] = releaseMs.coerceIn(20f, 400f)
            values[INDEX_MAKEUP] = makeupDb.coerceIn(0f, 6f)
        }
        // Separate mirror kept for whatever else reads the "menu" prefs directly -- unrelated
        // to the shared load/save/broadcast trio, so it stays local rather than moving into
        // NativeBmwDspValues.
        context.getSharedPreferences(MENU_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("bmw_comp_enable", enabled)
            .putFloat("bmw_comp_threshold", values[INDEX_THRESHOLD])
            .putFloat("bmw_comp_ratio", values[INDEX_RATIO])
            .putFloat("bmw_comp_knee", values[INDEX_KNEE])
            .putFloat("bmw_comp_attack", values[INDEX_ATTACK])
            .putFloat("bmw_comp_release", values[INDEX_RELEASE])
            .putFloat("bmw_comp_makeup", values[INDEX_MAKEUP])
            .apply()
    }

    companion object {
        private const val MENU_PREFS = "native_bmw_dsp_menu"
        private const val INDEX_ENABLED = NativeBmwDspValues.INDEX_COMPRESSOR_ENABLED
        private const val INDEX_THRESHOLD = NativeBmwDspValues.INDEX_COMPRESSOR_THRESHOLD
        private const val INDEX_RATIO = NativeBmwDspValues.INDEX_COMPRESSOR_RATIO
        private const val INDEX_KNEE = NativeBmwDspValues.INDEX_COMPRESSOR_KNEE
        private const val INDEX_ATTACK = NativeBmwDspValues.INDEX_COMPRESSOR_ATTACK
        private const val INDEX_RELEASE = NativeBmwDspValues.INDEX_COMPRESSOR_RELEASE
        private const val INDEX_MAKEUP = NativeBmwDspValues.INDEX_COMPRESSOR_MAKEUP

        fun load(context: Context): NativeBmwCompressorState {
            val values = NativeBmwDspValues.load(context)
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
    }
}
