package app.siphondsp.model

import android.content.Context

data class NativeBmwCompressorBand(
    val enabled: Boolean,
    val thresholdDb: Float,
    val ratio: Float,
    val kneeDb: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val makeupDb: Float,
)

data class NativeBmwCompressorState(
    val low: NativeBmwCompressorBand,
    val mid: NativeBmwCompressorBand,
) {
    fun persistAndApply(context: Context) {
        NativeBmwDspValues.update(context) { values ->
            writeBand(values, low, LOW_INDICES)
            writeBand(values, mid, MID_INDICES)
        }
    }

    companion object {
        private data class Indices(
            val enabled: Int,
            val threshold: Int,
            val ratio: Int,
            val knee: Int,
            val attack: Int,
            val release: Int,
            val makeup: Int,
        )

        private val LOW_INDICES = Indices(
            NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED,
            NativeBmwDspValues.INDEX_LOW_COMPRESSOR_THRESHOLD,
            NativeBmwDspValues.INDEX_LOW_COMPRESSOR_RATIO,
            NativeBmwDspValues.INDEX_LOW_COMPRESSOR_KNEE,
            NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ATTACK,
            NativeBmwDspValues.INDEX_LOW_COMPRESSOR_RELEASE,
            NativeBmwDspValues.INDEX_LOW_COMPRESSOR_MAKEUP,
        )
        private val MID_INDICES = Indices(
            NativeBmwDspValues.INDEX_MID_COMPRESSOR_ENABLED,
            NativeBmwDspValues.INDEX_MID_COMPRESSOR_THRESHOLD,
            NativeBmwDspValues.INDEX_MID_COMPRESSOR_RATIO,
            NativeBmwDspValues.INDEX_MID_COMPRESSOR_KNEE,
            NativeBmwDspValues.INDEX_MID_COMPRESSOR_ATTACK,
            NativeBmwDspValues.INDEX_MID_COMPRESSOR_RELEASE,
            NativeBmwDspValues.INDEX_MID_COMPRESSOR_MAKEUP,
        )

        private fun readBand(values: FloatArray, i: Indices) = NativeBmwCompressorBand(
            enabled = values[i.enabled] >= .5f,
            thresholdDb = values[i.threshold],
            ratio = values[i.ratio],
            kneeDb = values[i.knee],
            attackMs = values[i.attack],
            releaseMs = values[i.release],
            makeupDb = values[i.makeup],
        )

        private fun writeBand(values: FloatArray, band: NativeBmwCompressorBand, i: Indices) {
            values[i.enabled] = if (band.enabled) 1f else 0f
            values[i.threshold] = band.thresholdDb.coerceIn(-24f, 0f)
            values[i.ratio] = band.ratio.coerceIn(1f, 10f)
            values[i.knee] = band.kneeDb.coerceIn(0f, 12f)
            values[i.attack] = band.attackMs.coerceIn(1f, 100f)
            values[i.release] = band.releaseMs.coerceIn(20f, 800f)
            values[i.makeup] = band.makeupDb.coerceIn(0f, 6f)
        }

        fun load(context: Context): NativeBmwCompressorState {
            val values = NativeBmwDspValues.load(context)
            return NativeBmwCompressorState(
                low = readBand(values, LOW_INDICES),
                mid = readBand(values, MID_INDICES),
            )
        }
    }
}
