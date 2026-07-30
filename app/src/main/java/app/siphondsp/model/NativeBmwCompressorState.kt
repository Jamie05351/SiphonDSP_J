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
    val midEnabled: Boolean,
    val midThresholdDb: Float,
    val midRatio: Float,
    val midKneeDb: Float,
    val midAttackMs: Float,
    val midReleaseMs: Float,
    val midMakeupDb: Float,
) {
    fun persistAndApply(context: Context) {
        NativeBmwDspValues.update(context) { v ->
            v[28] = if (enabled) 1f else 0f
            v[29] = thresholdDb.coerceIn(-24f, 0f)
            v[30] = ratio.coerceIn(1f, 10f)
            v[31] = kneeDb.coerceIn(0f, 12f)
            v[32] = attackMs.coerceIn(1f, 100f)
            v[33] = releaseMs.coerceIn(20f, 800f)
            v[34] = makeupDb.coerceIn(0f, 6f)
            v[35] = if (midEnabled) 1f else 0f
            v[36] = midThresholdDb.coerceIn(-24f, 0f)
            v[37] = midRatio.coerceIn(1f, 10f)
            v[38] = midKneeDb.coerceIn(0f, 12f)
            v[39] = midAttackMs.coerceIn(1f, 100f)
            v[40] = midReleaseMs.coerceIn(20f, 800f)
            v[41] = midMakeupDb.coerceIn(0f, 6f)
        }
    }

    companion object {
        fun load(context: Context): NativeBmwCompressorState {
            val v = NativeBmwDspValues.load(context)
            return NativeBmwCompressorState(
                v[28] >= .5f, v[29], v[30], v[31], v[32], v[33], v[34],
                v[35] >= .5f, v[36], v[37], v[38], v[39], v[40], v[41],
            )
        }
    }
}
