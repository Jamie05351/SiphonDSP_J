package app.siphondsp.model

import android.content.Context
import app.siphondsp.R
import app.siphondsp.utils.Constants
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
        val allIds = (fullRangeBands.asSequence() + lowBandBands.asSequence() + midBandBands.asSequence())
            .map { it.uuid }
            .toList()
        if (allIds.size != allIds.toSet().size) return "PEQ contains duplicate filter identities"
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

    fun persist(context: Context): Boolean {
        val success = store(context).save(this)
        if (success) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LKG_TIMESTAMP, System.currentTimeMillis())
                .apply()
        }
        return success
    }

    companion object {
        const val VERSION = BmwPeqStore.VERSION
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
        private const val KEY_LKG_VERSION = "lkg_version"
        private const val KEY_LKG_ENABLED = "lkg_enabled"
        private const val KEY_LKG_PREAMP = "lkg_preamp"
        private const val KEY_LKG_FULL = "lkg_full_range"
        private const val KEY_LKG_LOW = "lkg_low_band"
        private const val KEY_LKG_MID = "lkg_mid_band"
        private const val KEY_LKG_TIMESTAMP = "lkg_timestamp"
        private const val KEY_LAST_RESTORE = "last_restore_result"
        private const val KEY_LAST_ERROR = "last_restore_error"
        private const val KEY_FALLBACK_USED = "fallback_used"
        private const val KEY_LEGACY_BACKUP = "legacy_backup_bands"
        private const val KEY_LEGACY_PREAMP = "legacy_backup_preamp"
        private const val KEY_LEGACY_ENABLED = "legacy_backup_enabled"
        private const val KEY_MIGRATION_VERSION = "migration_version"
        private const val KEY_MIGRATION_TIMESTAMP = "migration_timestamp"
        private const val KEY_REJECTED_VERSION = "rejected_version"
        private const val KEY_REJECTED_ENABLED = "rejected_enabled"
        private const val KEY_REJECTED_PREAMP = "rejected_preamp"
        private const val KEY_REJECTED_FULL = "rejected_full_range"
        private const val KEY_REJECTED_LOW = "rejected_low_band"
        private const val KEY_REJECTED_MID = "rejected_mid_band"
        private const val KEY_REJECTED_TIMESTAMP = "rejected_timestamp"
        private const val KEY_LAST_BACKUP_RESTORE = "last_backup_restore"

        fun empty() = BmwPeqState(false, 0f, ParametricEqBandList(), ParametricEqBandList(), ParametricEqBandList())

        // PEQ has no enable/disable control anymore -- it's always live -- so every load() result
        // is coerced on regardless of what's actually persisted (legacy data, migrations, etc.).
        fun load(context: Context): BmwPeqState = loadStored(context).copy(enabled = true)

        private fun loadStored(context: Context): BmwPeqState {
            val result = store(context).load()
            result.state?.let { restored ->
                if (result.source == BmwPeqStore.Source.LEGACY_V1_PRIMARY ||
                    result.source == BmwPeqStore.Source.LEGACY_V1_RECOVERY
                ) {
                    val upgraded = restored.persist(context)
                    Timber.i("BMW PEQ legacy file migration version=$VERSION success=$upgraded")
                }
                return restored
            }

            loadPreferenceFallback(context)?.let { fallback ->
                val migrated = fallback.persist(context)
                Timber.i(
                    "BMW PEQ restore source=preference-migration version=$VERSION " +
                        "enabled=${fallback.enabled} full=${fallback.fullRangeBands.size} " +
                        "low=${fallback.lowBandBands.size} mid=${fallback.midBandBands.size} success=$migrated"
                )
                if (migrated) clearObsoleteStatePreferences(context)
                return fallback
            }

            loadLegacyPeqFallback(context)?.let { fallback ->
                val migrated = fallback.persist(context)
                Timber.i(
                    "BMW PEQ restore source=legacy-pref-migration version=$VERSION " +
                        "enabled=${fallback.enabled} full=${fallback.fullRangeBands.size} " +
                        "low=0 mid=0 success=$migrated"
                )
                return fallback
            }

            Timber.w("BMW PEQ restore selected empty default because no persistent state exists")
            return empty()
        }

        fun loadLastKnownGood(context: Context): BmwPeqState? {
            store(context).loadRecovery()?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_LKG_VERSION, 0) != 1) return null
            fun bands(key: String) = ParametricEqBandList().apply {
                deserialize(prefs.getString(key, "PEQ: ") ?: "PEQ: ")
            }
            return BmwPeqState(
                prefs.getBoolean(KEY_LKG_ENABLED, false),
                prefs.getFloat(KEY_LKG_PREAMP, 0f),
                bands(KEY_LKG_FULL),
                bands(KEY_LKG_LOW),
                bands(KEY_LKG_MID),
            )
        }

        private fun store(context: Context) = BmwPeqStore(context.noBackupFilesDir)

        private fun loadPreferenceFallback(context: Context): BmwPeqState? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_VERSION, 0) != 1) return null
            fun bands(key: String) = ParametricEqBandList().apply {
                deserialize(prefs.getString(key, "PEQ: ") ?: "PEQ: ")
            }
            return BmwPeqState(
                prefs.getBoolean(KEY_ENABLED, false),
                prefs.getFloat(KEY_PREAMP, 0f),
                bands(KEY_FULL),
                bands(KEY_LOW),
                bands(KEY_MID),
            )
        }

        private fun loadLegacyPeqFallback(context: Context): BmwPeqState? {
            val prefs = context.getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
            val bandsKey = context.getString(R.string.key_peq_bands)
            if (!prefs.contains(bandsKey)) return null
            val bands = ParametricEqBandList().apply {
                deserialize(prefs.getString(bandsKey, "PEQ: ") ?: "PEQ: ")
            }
            return BmwPeqState(
                prefs.getBoolean(context.getString(R.string.key_peq_enable), false),
                prefs.getFloat(context.getString(R.string.key_peq_preamp), 0f),
                bands,
                ParametricEqBandList(),
                ParametricEqBandList(),
            )
        }

        private fun clearObsoleteStatePreferences(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_VERSION)
                .remove(KEY_ENABLED)
                .remove(KEY_PREAMP)
                .remove(KEY_FULL)
                .remove(KEY_LOW)
                .remove(KEY_MID)
                .remove(KEY_LKG_VERSION)
                .remove(KEY_LKG_ENABLED)
                .remove(KEY_LKG_PREAMP)
                .remove(KEY_LKG_FULL)
                .remove(KEY_LKG_LOW)
                .remove(KEY_LKG_MID)
                .apply()
        }

        fun recordRestoreResult(
            context: Context,
            result: String,
            error: String? = null,
            fallbackUsed: Boolean = false,
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_RESTORE, result)
                .putString(KEY_LAST_ERROR, error)
                .putBoolean(KEY_FALLBACK_USED, fallbackUsed)
                .apply()
        }

        fun diagnosticMetadata(context: Context): RestoreMetadata {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return RestoreMetadata(
                prefs.getString(KEY_LAST_RESTORE, "not-recorded") ?: "not-recorded",
                prefs.getString(KEY_LAST_ERROR, null),
                prefs.getBoolean(KEY_FALLBACK_USED, false),
                prefs.getLong(KEY_LKG_TIMESTAMP, 0L),
                prefs.getString(KEY_LAST_BACKUP_RESTORE, "not-recorded") ?: "not-recorded",
            )
        }

        fun recordBackupRestoreResult(context: Context, result: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_BACKUP_RESTORE, result)
                .apply()
        }

        fun backupLegacyOnce(
            context: Context,
            enabled: Boolean,
            preampDb: Float,
            serializedBands: String,
        ): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_MIGRATION_VERSION, 0) >= VERSION) return true
            return prefs.edit()
                .putString(KEY_LEGACY_BACKUP, serializedBands)
                .putFloat(KEY_LEGACY_PREAMP, preampDb)
                .putBoolean(KEY_LEGACY_ENABLED, enabled)
                .putInt(KEY_MIGRATION_VERSION, VERSION)
                .putLong(KEY_MIGRATION_TIMESTAMP, System.currentTimeMillis())
                .commit()
        }

        fun backupRejectedPersistedState(context: Context): Boolean {
            val rejected = load(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.edit()
                .putInt(KEY_REJECTED_VERSION, VERSION)
                .putBoolean(KEY_REJECTED_ENABLED, rejected.enabled)
                .putFloat(KEY_REJECTED_PREAMP, rejected.preampDb)
                .putString(KEY_REJECTED_FULL, rejected.fullRangeBands.serialize())
                .putString(KEY_REJECTED_LOW, rejected.lowBandBands.serialize())
                .putString(KEY_REJECTED_MID, rejected.midBandBands.serialize())
                .putLong(KEY_REJECTED_TIMESTAMP, System.currentTimeMillis())
                .commit()
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

data class RestoreMetadata(
    val result: String,
    val error: String?,
    val fallbackUsed: Boolean,
    val lastKnownGoodTimestamp: Long,
    val backupRestoreResult: String,
)

fun ParametricEqBandList.deepCopy() = ParametricEqBandList().also { copy ->
    copy.addAll(this)
}
