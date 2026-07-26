package app.siphondsp.model

import android.content.Context
import timber.log.Timber

data class BmwPeqState(
    val enabled: Boolean,
    val preampDb: Float,
    val fullRangeBands: ParametricEqBandList,
    val lowBandBands: ParametricEqBandList,
    val midBandBands: ParametricEqBandList,
) {
    fun deepCopy() = BmwPeqState(
        enabled,
        preampDb,
        fullRangeBands.deepCopy(),
        lowBandBands.deepCopy(),
        midBandBands.deepCopy(),
    )

    fun validate(sampleRate: Float): String? {
        if (!preampDb.isFinite() || preampDb !in -30f..12f) return "preamp is outside -30..12 dB"
        if (!sampleRate.isFinite() || sampleRate < 8000f) return "invalid sample rate"
        return listOf(
            "Full Range" to fullRangeBands,
            "Low Band" to lowBandBands,
            "Mid Band" to midBandBands,
        ).firstNotNullOfOrNull { (name, bands) ->
            when {
                bands.size > MAX_BANDS -> "$name has more than $MAX_BANDS bands"
                else -> bands.firstNotNullOfOrNull { band ->
                    when {
                        !band.frequency.isFinite() || !band.gain.isFinite() || !band.q.isFinite() ->
                            "$name contains a non-finite value"
                        band.frequency < 20.0 || band.frequency >= sampleRate / 2.0 ->
                            "$name frequency is outside 20 Hz..<Nyquist"
                        band.gain !in -30.0..30.0 -> "$name gain is outside -30..30 dB"
                        band.q !in MIN_Q..MAX_Q -> "$name Q is outside $MIN_Q..$MAX_Q"
                        else -> null
                    }
                }
            }
        }
    }

    fun nativeValues(bands: ParametricEqBandList): DoubleArray =
        bands.flatMap { band ->
            listOf(
                band.frequency,
                band.gain,
                band.q,
                band.filterType.code.toDouble(),
                band.channel.code.toDouble(),
            )
        }.toDoubleArray()

    fun persist(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERSION, VERSION)
            .putBoolean(KEY_ENABLED, enabled)
            .putFloat(KEY_PREAMP, preampDb)
            .putString(KEY_FULL, fullRangeBands.serialize())
            .putString(KEY_LOW, lowBandBands.serialize())
            .putString(KEY_MID, midBandBands.serialize())
            .commit()

    companion object {
        const val VERSION = 1
        const val MAX_BANDS = 16
        const val MIN_Q = 0.1
        const val MAX_Q = 30.0
        private const val PREFS = "native_bmw_peq"
        private const val KEY_VERSION = "version"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PREAMP = "preamp"
        private const val KEY_FULL = "full_range"
        private const val KEY_LOW = "low_band"
        private const val KEY_MID = "mid_band"

        fun empty() = BmwPeqState(false, 0f, ParametricEqBandList(), ParametricEqBandList(), ParametricEqBandList())

        fun load(context: Context): BmwPeqState {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_VERSION, 0) != VERSION) return empty()
            fun bands(key: String) = ParametricEqBandList().apply { deserialize(prefs.getString(key, "PEQ: ") ?: "PEQ: ") }
            return BmwPeqState(
                prefs.getBoolean(KEY_ENABLED, false),
                prefs.getFloat(KEY_PREAMP, 0f),
                bands(KEY_FULL),
                bands(KEY_LOW),
                bands(KEY_MID),
            )
        }

        fun log(prefix: String, state: BmwPeqState, result: Boolean? = null) {
            Timber.d(
                "$prefix PEQ state v$VERSION enabled=${state.enabled} preamp=${state.preampDb} " +
                    "full=${state.fullRangeBands.size} low=${state.lowBandBands.size} mid=${state.midBandBands.size}" +
                    (result?.let { " nativeApply=$it" } ?: "")
            )
        }
    }
}

fun ParametricEqBandList.deepCopy() = ParametricEqBandList().also { copy ->
    copy.addAll(this)
}
