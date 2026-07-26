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
        return state.toState()
    }

    companion object {
        const val CURRENT_VERSION = 1
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
