package app.siphondsp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class BmwPeqPreset(
    val version: Int = CURRENT_VERSION,
    val name: String? = null,
    val description: String? = null,
    val createdAtEpochMs: Long? = null,
    val enabled: Boolean,
    val preampDb: Float,
    val fullRange: List<PresetBand>,
    val lowBand: List<PresetBand>,
    val midBand: List<PresetBand>,
) {
    @Serializable
    data class PresetBand(
        val id: String,
        val frequency: Double,
        val gain: Double,
        val q: Double,
        val type: Int,
        val channel: Int,
    )

    fun toState(): BmwPeqState {
        require(version <= CURRENT_VERSION) { "Preset version $version is newer than supported version $CURRENT_VERSION" }
        require(version >= 1) { "Unsupported preset version $version" }
        fun decode(source: List<PresetBand>) = ParametricEqBandList().also { destination ->
            val ids = mutableSetOf<UUID>()
            source.forEach { saved ->
                val id = runCatching { UUID.fromString(saved.id) }
                    .getOrElse { throw IllegalArgumentException("Preset contains an invalid filter UUID") }
                require(ids.add(id)) { "Preset contains duplicate filter UUID $id" }
                require(ParametricEqFilterType.entries.any { it.code == saved.type }) {
                    "Preset contains unsupported filter type ${saved.type}"
                }
                require(ParametricEqChannel.entries.any { it.code == saved.channel }) {
                    "Preset contains unsupported channel ${saved.channel}"
                }
                destination.add(
                    ParametricEqBand(
                        saved.frequency,
                        saved.gain,
                        saved.q,
                        ParametricEqFilterType.fromCode(saved.type),
                        ParametricEqChannel.fromCode(saved.channel),
                        id,
                    )
                )
            }
        }
        return BmwPeqState(enabled, preampDb, decode(fullRange), decode(lowBand), decode(midBand))
    }

    companion object {
        const val CURRENT_VERSION = 1
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun fromState(
            state: BmwPeqState,
            name: String? = null,
            description: String? = null,
            createdAtEpochMs: Long? = null,
        ): BmwPeqPreset {
            fun encode(bands: List<ParametricEqBand>) = bands.map {
                PresetBand(
                    it.uuid.toString(), it.frequency, it.gain, it.q,
                    it.filterType.code, it.channel.code,
                )
            }
            return BmwPeqPreset(
                name = name,
                description = description,
                createdAtEpochMs = createdAtEpochMs,
                enabled = state.enabled,
                preampDb = state.preampDb,
                fullRange = encode(state.fullRangeBands),
                lowBand = encode(state.lowBandBands),
                midBand = encode(state.midBandBands),
            )
        }

        fun encode(preset: BmwPeqPreset): String = json.encodeToString(preset)

        fun decode(text: String): BmwPeqPreset =
            runCatching { json.decodeFromString<BmwPeqPreset>(text) }
                .getOrElse { throw IllegalArgumentException("The PEQ preset is malformed", it) }
    }
}
