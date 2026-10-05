package app.siphondsp.compose.home

import androidx.compose.runtime.Immutable
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.HomeStageStatus

/** The five signal-chain menu tiles, in chain order. */
enum class HomeStage { PEQ, GAINS, XOVERS, COMPRESSOR, ALLPASS }

/** The global stages shown as chips in the status panel. */
enum class GlobalStage { TILT, MBC, LIMITER }

/**
 * What the front page shows from the saved DSP values (not the live levels or the power state,
 * which change on their own clocks and are passed to [HomeScreen] separately).
 *
 * Only stages the engine can actually switch off have an on/off: the compressor (the MBC, a bus
 * limiter, or a legacy per-output compressor) and the all-pass (any section on any output). PEQ, Gains / Delay and
 * Xovers have no bypass in the engine, so [stageOn] is null for them and their tiles carry no pill.
 *
 * [tiltDb] and [limiterDb] are the settings while the stage is on, else null; [mbcBands] is how
 * many bands are compressing (0 while the MBC is off).
 */
@Immutable
data class HomeEngineState(
    val compressorOn: Boolean,
    val allPassOn: Boolean,
    val tiltDb: Float?,
    val mbcBands: Int,
    val limiterDb: Float?,
) {
    fun stageOn(stage: HomeStage): Boolean? = when (stage) {
        HomeStage.COMPRESSOR -> compressorOn
        HomeStage.ALLPASS -> allPassOn
        else -> null
    }

    /** The meters' ceiling: the limiter threshold while the limiter is on, else full scale. */
    val ceilingDb: Float get() = limiterDb ?: 0f

    companion object {
        val OFF = HomeEngineState(
            compressorOn = false,
            allPassOn = false,
            tiltDb = null,
            mbcBands = 0,
            limiterDb = null,
        )

        fun from(values: FloatArray): HomeEngineState {
            if (values.size < NativeBmwDspValues.SIZE) return OFF
            fun on(index: Int) = values[index] >= 0.5f
            val stages = HomeStageStatus.from(values)
            // The Compressor screen owns the MBC and the per-bus limiters. The legacy per-output
            // compressors have no UI any more, but native still runs them if a config has one on.
            val busLimiter = listOf(
                NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED,
                NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED,
                NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED,
            ).any(::on)
            val legacyCompressor = (0 until NativeBmwDspValues.OUTPUT_COUNT).any {
                on(NativeBmwDspValues.outputIndex(it, NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED))
            }
            return HomeEngineState(
                compressorOn = on(NativeBmwDspValues.INDEX_MBC_ENABLED) || busLimiter || legacyCompressor,
                allPassOn = stages.allPass.any { it.on },
                tiltDb = values[NativeBmwDspValues.INDEX_TILT_AMOUNT]
                    .takeIf { on(NativeBmwDspValues.INDEX_TILT_ENABLED) },
                mbcBands = stages.mbcBands.count { it },
                limiterDb = values[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD]
                    .takeIf { on(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED) },
            )
        }
    }
}
