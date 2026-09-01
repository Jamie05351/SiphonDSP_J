package app.siphondsp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PrivatePeqBackup(
    val version: Int = CURRENT_VERSION,
    val createdAtEpochMs: Long,
    val state: BmwPeqPreset,
    val graphDisplay: GraphDisplay = GraphDisplay(),
    val savedPresets: List<BmwPeqPreset> = emptyList(),
    // Added in version 2: the full BMW DSP array (NativeBmwDspValues.SIZE floats) backing Gains
    // & Delay, Compressor, and Crossovers & Tilt (see NativeBmwDspValues) -- null on older
    // backups, which only ever captured PEQ bands via [state]. A backup written by an older
    // schema carries a shorter array; the restore path pads it forward. Kept separate from
    // [state]/[BmwPeqPreset] rather than
    // folding it in there, since BmwPeqPreset is also used standalone for the unrelated
    // PEQ-only preset import/export flow that shouldn't suddenly carry gain/crossover data too.
    val nativeDspValues: List<Float>? = null,
) {
    @Serializable
    data class GraphDisplay(
        val showIndividualFilters: Boolean = true,
        val channelDisplay: String = "BOTH",
    )

    fun validatedState(): BmwPeqState {
        require(version in 1..CURRENT_VERSION) {
            if (version > CURRENT_VERSION) {
                "Backup version $version is newer than supported version $CURRENT_VERSION"
            } else {
                "Unsupported backup version $version"
            }
        }
        require(savedPresets.size <= MAX_SAVED_PRESETS) {
            "Backup contains more than $MAX_SAVED_PRESETS saved presets"
        }
        listOf(state).plus(savedPresets).forEach { preset ->
            require((preset.name?.length ?: 0) <= MAX_NAME_LENGTH) {
                "Backup contains an oversized preset name"
            }
            require((preset.description?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
                "Backup contains an oversized preset description"
            }
            preset.toState()
        }
        require(graphDisplay.channelDisplay in setOf("BOTH", "LEFT", "RIGHT")) {
            "Backup contains an invalid graph channel display"
        }
        nativeDspValues?.let { values ->
            // Accept a shorter array from a backup written by an older schema (e.g. pre-MBC
            // 144-value builds) and let the restore path pad it forward from DEFAULTS -- see
            // NativeBmwDspValues.padToCurrentSize. Only a longer-than-current or absurdly short
            // array is a hard reject.
            require(values.size in NativeBmwDspValues.MIN_RESTORABLE_SIZE..NativeBmwDspValues.SIZE) {
                "Backup's BMW DSP array has ${values.size} values, expected " +
                    "${NativeBmwDspValues.MIN_RESTORABLE_SIZE}..${NativeBmwDspValues.SIZE}"
            }
        }
        return state.toState()
    }

    companion object {
        const val CURRENT_VERSION = 2
        const val MAX_SAVED_PRESETS = 50
        const val MAX_NAME_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 2_000
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun encode(backup: PrivatePeqBackup): String = json.encodeToString(backup)

        fun decode(text: String): PrivatePeqBackup =
            runCatching { json.decodeFromString<PrivatePeqBackup>(text) }
                .getOrElse { throw IllegalArgumentException("The private PEQ backup is malformed", it) }
    }
}
