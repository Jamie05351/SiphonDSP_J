package app.siphondsp.model.debug

import java.lang.Double.doubleToLongBits

/**
 * Kotlin mirror of the flat array [app.siphondsp.interop.JamesDspWrapper.getNativeTruthSnapshot]
 * returns. Every field here is copied verbatim (or trivially relabeled -- e.g. an ordinal to its
 * enum) from that array; nothing is reconstructed from SharedPreferences, cached Kotlin state, UI
 * state, or broadcasts. See [parseNativeTruthSnapshot] and
 * NativeBmwDspProcessor::captureTruthSnapshot()'s comment for the exact wire layout.
 */

/** Order matches NativeBmwRouting::OutputId and the fixed block order in the native array. */
enum class NativeDspOutput { LOW_LEFT, LOW_RIGHT, MID_LEFT, MID_RIGHT }

/**
 * One Biquad's actual installed coefficients (topology + every coefficient field), read straight
 * off the running filter object -- not re-derived from the requested frequency/type. [isIdentity]
 * is true exactly when this stage is the SVF pass-through NativeBmwDspProcessor::makeIdentity()
 * installs for a crossover slope's unused second stage (BW1, BW2): topology Svf2 with
 * a1=a2=a3=0, m0=1, m1=0, m2=0. That's the one shape a genuine filter design (LP/HP/shelf/etc.)
 * never produces, so it's a safe, exact discriminator -- no epsilon needed, the native builders
 * either assign these exact literals or a computed g/k-derived value that's never all-zero for a
 * finite corner frequency.
 */
data class NativeBiquadStage(
    val topology: Int,
    val a1: Double,
    val a2: Double,
    val a3: Double,
    val m0: Double,
    val m1: Double,
    val m2: Double,
    val opA: Double,
) {
    val isIdentity: Boolean
        get() = topology == TOPOLOGY_SVF2 && a1 == 0.0 && a2 == 0.0 && a3 == 0.0 &&
            m0 == 1.0 && m1 == 0.0 && m2 == 0.0
    val isActive: Boolean get() = !isIdentity

    /**
     * Deterministic fingerprint of this stage's actual installed topology/coefficients (not the
     * requested crossover type). FNV-1a over each field's raw bit pattern -- stable across JVM
     * runs and processes, unlike Kotlin data class hashCode() (unspecified across versions) or
     * System.identityHashCode(). Two stages fingerprint equal iff every field compares equal.
     */
    fun fingerprint(): Long {
        var hash = FNV_OFFSET_BASIS
        fun mix(bits: Long) {
            var h = hash
            for (shift in 0 until 64 step 8) {
                h = h xor ((bits ushr shift) and 0xffL)
                h *= FNV_PRIME
            }
            hash = h
        }
        mix(topology.toLong())
        mix(doubleToLongBits(a1))
        mix(doubleToLongBits(a2))
        mix(doubleToLongBits(a3))
        mix(doubleToLongBits(m0))
        mix(doubleToLongBits(m1))
        mix(doubleToLongBits(m2))
        mix(doubleToLongBits(opA))
        return hash
    }

    companion object {
        const val TOPOLOGY_SVF2 = 0
        const val TOPOLOGY_ONE_POLE_ALLPASS = 1
        const val TOPOLOGY_ONE_POLE_LOWPASS = 2
        const val TOPOLOGY_ONE_POLE_HIGHPASS = 3
        private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 14695981039346656037 as Long
        private const val FNV_PRIME = 0x100000001b3L
    }
}

/** [nativeValue] matches NativeBmwDspProcessor::OutputConfig::CrossoverType's stored ordinal. */
enum class NativeCrossoverType(val nativeValue: Int, val label: String, val dbPerOctave: Int) {
    BUTTERWORTH2(0, "BW2", 12),
    BUTTERWORTH3(1, "BW3", 18),
    LINKWITZ_RILEY4(2, "LR4", 24),
    BUTTERWORTH1(3, "BW1", 6),
    UNKNOWN(-1, "?", 0);

    companion object {
        fun fromOrdinal(value: Int): NativeCrossoverType = entries.firstOrNull { it.nativeValue == value } ?: UNKNOWN
    }
}

data class NativeCrossoverSnapshot(
    val output: NativeDspOutput,
    val crossoverFreqHz: Double,
    val crossoverType: NativeCrossoverType,
    val subsonicEnabled: Boolean,
    val subsonicFreqHz: Double,
    val muted: Boolean,
    val polarityInverted: Boolean,
    val gainDb: Double,
    val delayMs: Double,
    val stage1: NativeBiquadStage,
    val stage2: NativeBiquadStage,
) {
    val activeStageCount: Int get() = (if (stage1.isActive) 1 else 0) + (if (stage2.isActive) 1 else 0)

    /** Combined fingerprint of both stages -- changes iff the actual installed topology does. */
    fun topologyFingerprint(): String =
        java.lang.Long.toHexString(stage1.fingerprint()) + java.lang.Long.toHexString(stage2.fingerprint())
}

data class NativePeqBand(
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
    val type: Int,
    val channel: Int,
    val active: Boolean,
)

data class NativePeqBank(
    val rawBandCount: Int,
    val leftActiveCount: Int,
    val rightActiveCount: Int,
    val bands: List<NativePeqBand>,
)

data class NativePeqSnapshot(
    val enabled: Boolean,
    val preampDb: Double,
    val full: NativePeqBank,
    val low: NativePeqBank,
    val mid: NativePeqBank,
)

data class NativeDspTruthSnapshot(
    val sampleRate: Double,
    val crossovers: Map<NativeDspOutput, NativeCrossoverSnapshot>,
    val peq: NativePeqSnapshot,
)

/**
 * Live rootless-pipeline runtime state for the native-truth debug screen's PIPELINE section --
 * read directly off the running RootlessAudioProcessorService/AudioRecord/AudioTrack objects
 * (see RootlessAudioProcessorService.Companion.pipelineRuntimeSnapshot()), never from a cached
 * flag mirrored for UI/broadcast purposes. Null when the service isn't running at all --
 * distinguished in the UI from every individual field being "inactive".
 */
data class RootlessPipelineRuntimeSnapshot(
    val recorderStateInitialized: Boolean,
    val recorderRecording: Boolean,
    val trackStateInitialized: Boolean,
    val trackPlaying: Boolean,
    val recreateRequested: Boolean,
    val recreationInProgress: Boolean,
    val measurementGeneratorActive: Boolean,
    val processorDisposing: Boolean,
    val serviceDisposing: Boolean,
    val pipelineHealthState: String?,
    val pipelineHealthReason: String?,
    /** AudioTrack underruns since the current track was created (0 when unavailable). */
    val underrunCount: Int = 0,
    /** Watchdog-triggered pipeline recreations since this service instance started. */
    val recoveriesThisSession: Int = 0,
    /** Milliseconds since audio was last written out; -1 if none yet. */
    val lastFlowAgeMs: Long = -1,
)

private const val HEADER_WIDTH = 3
private const val OUTPUT_BLOCK_WIDTH = 24
private val OUTPUT_ORDER = listOf(
    NativeDspOutput.LOW_LEFT, NativeDspOutput.LOW_RIGHT, NativeDspOutput.MID_LEFT, NativeDspOutput.MID_RIGHT,
)

/**
 * Parses the flat array [app.siphondsp.interop.JamesDspWrapper.getNativeTruthSnapshot] returns.
 * Returns null on a malformed/short array (should not happen against a matching native build --
 * treated as "unavailable" rather than crashing the debug screen on a schema mismatch).
 */
fun parseNativeTruthSnapshot(raw: DoubleArray?): NativeDspTruthSnapshot? {
    if (raw == null || raw.size < HEADER_WIDTH) return null
    var cursor = 0
    fun next(): Double = raw[cursor++]

    val sampleRate = next()
    val peqEnabled = next() != 0.0
    val peqPreampDb = next()

    if (raw.size < HEADER_WIDTH + OUTPUT_ORDER.size * OUTPUT_BLOCK_WIDTH) return null
    fun readStage(): NativeBiquadStage = NativeBiquadStage(
        topology = next().toInt(),
        a1 = next(),
        a2 = next(),
        a3 = next(),
        m0 = next(),
        m1 = next(),
        m2 = next(),
        opA = next(),
    )
    val crossovers = OUTPUT_ORDER.associateWith { output ->
        val crossoverFreqHz = next()
        val crossoverType = NativeCrossoverType.fromOrdinal(next().toInt())
        val subsonicEnabled = next() != 0.0
        val subsonicFreqHz = next()
        val muted = next() != 0.0
        val polarityInverted = next() != 0.0
        val gainDb = next()
        val delayMs = next()
        val stage1 = readStage()
        val stage2 = readStage()
        NativeCrossoverSnapshot(
            output, crossoverFreqHz, crossoverType, subsonicEnabled, subsonicFreqHz, muted,
            polarityInverted, gainDb, delayMs, stage1, stage2,
        )
    }

    fun readBank(): NativePeqBank? {
        if (cursor + 3 > raw.size) return null
        val rawBandCount = next().toInt()
        val leftActiveCount = next().toInt()
        val rightActiveCount = next().toInt()
        if (rawBandCount < 0 || cursor + rawBandCount * 6 > raw.size) return null
        val bands = (0 until rawBandCount).map {
            NativePeqBand(
                frequencyHz = next(),
                gainDb = next(),
                q = next(),
                type = next().toInt(),
                channel = next().toInt(),
                active = next() != 0.0,
            )
        }
        return NativePeqBank(rawBandCount, leftActiveCount, rightActiveCount, bands)
    }

    val full = readBank() ?: return null
    val low = readBank() ?: return null
    val mid = readBank() ?: return null

    return NativeDspTruthSnapshot(
        sampleRate = sampleRate,
        crossovers = crossovers,
        peq = NativePeqSnapshot(peqEnabled, peqPreampDb, full, low, mid),
    )
}
