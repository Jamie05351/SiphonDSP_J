package app.siphondsp.compose.home

import androidx.compose.runtime.Immutable
import app.siphondsp.model.DspStage
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.debug.EngineStatus
import app.siphondsp.view.HomeStageStatus

/** The five signal-chain menu tiles, in chain order. */
enum class HomeStage { PEQ, ALIGNMENT, XOVERS, DYNAMICS, OUTPUT }

/** The global stages shown as live-data cells on the top screen. */
enum class GlobalStage { TILT, MBC, LIMITER, ALLPASS, XOVER, NEUTRAL }

/**
 * What the front page shows (not the live levels or the power state, which change on their own
 * clocks and are passed to [HomeScreen] separately).
 *
 * The stage cells are what the native engine reports it is running ([EngineStatus]), never the
 * saved settings: [known] is false while no engine reports (the cells then read "—").
 * [tiltDb] and [limiterDb] are the running values while the stage is on, else null; [mbcBands] is
 * how many bands are compressing (0 while the MBC is off); [allPassOutputs] is how many outputs
 * have at least one all-pass section running; [crossoverWays] is 0 for the crossover bypass (one
 * full-range path), else 2 or 3; [neutral] is measurement neutral. [masterVolumeDb] and
 * [masterMuted] are the DSP master volume control's saved setting (see
 * [app.siphondsp.model.MasterVolume]).
 */
@Immutable
data class HomeEngineState(
    val allPassOutputs: Int,
    val tiltDb: Float?,
    val mbcBands: Int,
    val limiterDb: Float?,
    val masterVolumeDb: Float = NativeBmwDspValues.MASTER_VOLUME_MIN_DB,
    val masterMuted: Boolean = false,
    val known: Boolean = true,
    val crossoverWays: Int = 2,
    val neutral: Boolean = false,
) {
    val allPassOn: Boolean get() = allPassOutputs > 0

    /** The meters' ceiling: the limiter threshold while the limiter is on, else full scale. */
    val ceilingDb: Float get() = limiterDb ?: 0f

    companion object {
        val OFF = HomeEngineState(
            allPassOutputs = 0,
            tiltDb = null,
            mbcBands = 0,
            limiterDb = null,
        )

        /** The cells from [status] (null: no engine, so unknown), the volume from the saved [values]. */
        fun from(status: EngineStatus?, values: FloatArray): HomeEngineState {
            val volumeDb = values.getOrElse(NativeBmwDspValues.INDEX_MASTER_VOLUME_DB) { NativeBmwDspValues.MASTER_VOLUME_MIN_DB }
            val muted = values.getOrElse(NativeBmwDspValues.INDEX_MASTER_MUTE) { 0f } >= .5f
            if (status == null) return OFF.copy(masterVolumeDb = volumeDb, masterMuted = muted, known = false)
            return HomeEngineState(
                allPassOutputs = status.allPassOutputs,
                tiltDb = (status.tiltDb * NativeBmwDspValues.TILT_END_GAIN_PER_AMOUNT).takeIf { status.isActive(DspStage.TILT) },
                mbcBands = status.mbcBands,
                limiterDb = status.limiterThresholdDb.takeIf { status.isActive(DspStage.MASTER_LIMITER) },
                masterVolumeDb = volumeDb,
                masterMuted = muted,
                known = true,
                crossoverWays = status.crossoverWays,
                neutral = status.measurementNeutral,
            )
        }

        fun from(values: FloatArray): HomeEngineState {
            if (values.size < NativeBmwDspValues.SIZE) return OFF
            fun on(index: Int) = values[index] >= 0.5f
            val stages = HomeStageStatus.from(values)
            return HomeEngineState(
                allPassOutputs = stages.allPass.count { it.on },
                tiltDb = (values[NativeBmwDspValues.INDEX_TILT_AMOUNT] * NativeBmwDspValues.TILT_END_GAIN_PER_AMOUNT)
                    .takeIf { on(NativeBmwDspValues.INDEX_TILT_ENABLED) },
                mbcBands = stages.mbcBands.count { it },
                limiterDb = values[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD]
                    .takeIf { on(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED) },
                masterVolumeDb = values[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB],
                masterMuted = on(NativeBmwDspValues.INDEX_MASTER_MUTE),
            )
        }
    }
}
