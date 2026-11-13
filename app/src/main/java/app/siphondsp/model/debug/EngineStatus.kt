package app.siphondsp.model.debug

import app.siphondsp.model.DspStage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the native engine reports it is actually running (NativeBmwDspProcessor::readEngineStatus(),
 * [app.siphondsp.interop.NativeDsp.getEngineStatus]): computed from its installed state after
 * every config it accepts, never from the settings the UI last asked for. The status chips and the
 * Native DSP Truth screen show this; null (no engine, or nothing accepted yet) shows as unknown.
 */
data class EngineStatus(
    val activeStages: Set<DspStage>,
    val measurementNeutral: Boolean,
    val neutralAllowed: Set<DspStage>,
    /** 0 = bypassed (one full-range path), 2 = 2-way, 3 = 3-way. */
    val crossoverWays: Int,
    val tiltDb: Float,
    val limiterThresholdDb: Float,
    val mbcBands: Int,
    val allPassOutputs: Int,
    val masterVolumeDb: Float,
    val masterMute: Boolean,
    val headroomDb: Float,
    val sampleRate: Float,
    /** Bumped by every install: a reader can tell it caught up with an edit. */
    val revision: Long,
) {
    fun isActive(stage: DspStage): Boolean = stage in activeStages

    companion object {
        const val SIZE = 14

        fun parse(raw: FloatArray?): EngineStatus? {
            if (raw == null || raw.size < SIZE || raw[0] < .5f) return null
            val active = raw[1].toInt()
            val allow = raw[3].toInt()
            return EngineStatus(
                activeStages = DspStage.fromMask(active).toSet(),
                measurementNeutral = raw[2] >= .5f,
                neutralAllowed = DspStage.fromMask(allow).toSet(),
                crossoverWays = raw[4].roundToInt(),
                tiltDb = raw[5],
                limiterThresholdDb = raw[6],
                mbcBands = raw[7].roundToInt(),
                allPassOutputs = raw[8].roundToInt(),
                masterVolumeDb = raw[9],
                masterMute = raw[10] >= .5f,
                headroomDb = raw[11],
                sampleRate = raw[12],
                revision = raw[13].toLong(),
            )
        }
    }
}

/**
 * The measurement self-test's result (NativeBmwSelfTest.h, [app.siphondsp.interop.NativeDsp.runSelfTest]):
 * an impulse through an offline copy of the running chain, compared with a wire.
 */
data class SelfTestReport(
    val verified: Boolean,
    val sampleRate: Double,
    val lowHz: Double,
    val highHz: Double,
    val latencyFrames: Long,
    val magnitudeDevDb: Double,
    val phaseDevDeg: Double,
    val crosstalkDb: Double,
    val activeStages: List<DspStage>,
    val alteringStages: List<DspStage>,
    val levelDependentStages: List<DspStage>,
    val unexplained: Boolean,
    val measurementNeutral: Boolean,
    val masterVolumeDb: Double,
    val durationMs: Double,
    /** When it ran (System.currentTimeMillis()). */
    val atMs: Long,
) {
    /** One line: "Neutral verified: flat ±0.01 dB, 0°" or what is still altering the signal. */
    val headline: String
        get() {
            val subject = if (measurementNeutral) "Neutral" else "Chain"
            return if (verified) {
                "$subject verified: flat ±${formatDb(MAGNITUDE_TOL_DB)} dB, 0°, 0 samples"
            } else {
                val what = when {
                    alteringStages.isNotEmpty() -> alteringStages.joinToString { it.label } + " still altering the signal"
                    else -> "not neutral (no single stage explains it)"
                }
                "$subject: $what"
            }
        }

    /** The measured numbers behind [headline]. */
    val detail: String
        get() = buildString {
            append("±${formatDb(magnitudeDevDb)} dB, ${formatDeg(phaseDevDeg)}°")
            append(", latency $latencyFrames samples")
            if (sampleRate > 0) append(" (${"%.2f".format(latencyFrames * 1000.0 / sampleRate)} ms)")
            append(", crosstalk ${if (crosstalkDb <= -300) "none" else "${"%.1f".format(crosstalkDb)} dB"}")
            append(" over ${lowHz.roundToInt()} Hz – ${(highHz / 1000).let { "%.1f".format(it) }} kHz")
            append(". Master volume ${"%.1f".format(masterVolumeDb)} dB excluded")
            if (levelDependentStages.isNotEmpty()) {
                append(". Level-dependent, not proven by an impulse: ")
                append(levelDependentStages.joinToString { it.label })
            }
            append('.')
        }

    companion object {
        const val SIZE = 16
        const val MAGNITUDE_TOL_DB = 0.01

        fun parse(raw: DoubleArray?, atMs: Long = System.currentTimeMillis()): SelfTestReport? {
            if (raw == null || raw.size < SIZE || raw[0] < .5) return null
            return SelfTestReport(
                verified = raw[1] >= .5,
                sampleRate = raw[2],
                latencyFrames = raw[3].toLong(),
                magnitudeDevDb = raw[4],
                phaseDevDeg = raw[5],
                crosstalkDb = raw[6],
                lowHz = raw[7],
                highHz = raw[8],
                activeStages = DspStage.fromMask(raw[9].toInt()),
                alteringStages = DspStage.fromMask(raw[10].toInt()),
                levelDependentStages = DspStage.fromMask(raw[11].toInt()),
                unexplained = raw[12] >= .5,
                measurementNeutral = raw[13] >= .5,
                masterVolumeDb = raw[14],
                durationMs = raw[15],
                atMs = atMs,
            )
        }

        private fun formatDb(db: Double): String = if (abs(db) < 0.1) "%.2f".format(abs(db)) else "%.1f".format(abs(db))
        private fun formatDeg(deg: Double): String = if (deg < 1) "%.2f".format(deg) else "%.1f".format(deg)
    }
}
