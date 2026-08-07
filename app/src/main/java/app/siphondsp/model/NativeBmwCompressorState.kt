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
            val lowEnabled = if (enabled) 1f else 0f
            val lowThreshold = thresholdDb.coerceIn(-24f, 0f)
            val lowRatio = ratio.coerceIn(1f, 10f)
            val lowKnee = kneeDb.coerceIn(0f, 12f)
            val lowAttack = attackMs.coerceIn(1f, 100f)
            val lowRelease = releaseMs.coerceIn(20f, 800f)
            val lowMakeup = makeupDb.coerceIn(0f, 6f)
            val midEnabledValue = if (midEnabled) 1f else 0f
            val midThreshold = midThresholdDb.coerceIn(-24f, 0f)
            val midRatioValue = midRatio.coerceIn(1f, 10f)
            val midKnee = midKneeDb.coerceIn(0f, 12f)
            val midAttack = midAttackMs.coerceIn(1f, 100f)
            val midRelease = midReleaseMs.coerceIn(20f, 800f)
            val midMakeup = midMakeupDb.coerceIn(0f, 6f)

            // Keep the historical band indices current for the existing UI/response model.
            v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED] = lowEnabled
            v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_THRESHOLD] = lowThreshold
            v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_RATIO] = lowRatio
            v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_KNEE] = lowKnee
            v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ATTACK] = lowAttack
            v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_RELEASE] = lowRelease
            v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_MAKEUP] = lowMakeup
            v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_ENABLED] = midEnabledValue
            v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_THRESHOLD] = midThreshold
            v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_RATIO] = midRatioValue
            v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_KNEE] = midKnee
            v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_ATTACK] = midAttack
            v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_RELEASE] = midRelease
            v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_MAKEUP] = midMakeup

            fun writeCompressor(output: Int, values: FloatArray) {
                v[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED)] = values[0]
                v[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_THRESHOLD)] = values[1]
                v[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_RATIO)] = values[2]
                v[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_KNEE)] = values[3]
                v[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_ATTACK)] = values[4]
                v[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_RELEASE)] = values[5]
                v[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_MAKEUP)] = values[6]
            }

            val low = floatArrayOf(lowEnabled, lowThreshold, lowRatio, lowKnee, lowAttack, lowRelease, lowMakeup)
            val mid = floatArrayOf(midEnabledValue, midThreshold, midRatioValue, midKnee, midAttack, midRelease, midMakeup)
            writeCompressor(NativeBmwDspValues.OUTPUT_LOW_LEFT, low)
            writeCompressor(NativeBmwDspValues.OUTPUT_LOW_RIGHT, low)
            writeCompressor(NativeBmwDspValues.OUTPUT_MID_LEFT, mid)
            writeCompressor(NativeBmwDspValues.OUTPUT_MID_RIGHT, mid)
        }
    }

    companion object {
        fun load(context: Context): NativeBmwCompressorState {
            val v = NativeBmwDspValues.load(context)
            return NativeBmwCompressorState(
                v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED] >= .5f,
                v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_THRESHOLD],
                v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_RATIO],
                v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_KNEE],
                v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ATTACK],
                v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_RELEASE],
                v[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_MAKEUP],
                v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_ENABLED] >= .5f,
                v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_THRESHOLD],
                v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_RATIO],
                v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_KNEE],
                v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_ATTACK],
                v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_RELEASE],
                v[NativeBmwDspValues.INDEX_MID_COMPRESSOR_MAKEUP],
            )
        }
    }
}
