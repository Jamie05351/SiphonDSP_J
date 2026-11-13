package app.siphondsp.model.debug

/**
 * The engine's per-stage timing counters (NativeBmwProfiler.h,
 * [app.siphondsp.interop.NativeDsp.getStageProfile]): running totals since profiling was switched
 * on. [summarize] turns two readings into the averages over the time between them.
 */
data class StageProfile(
    val enabled: Boolean,
    val buffers: Long,
    val frames: Long,
    val wallNs: Long,
    val cpuNs: Long,
    val maxWallNs: Long,
    val maxCpuNs: Long,
    /** The core the last buffer ran on; -1 unknown. */
    val lastCpu: Int,
    val stageTotalNs: LongArray,
    val stageMaxNs: LongArray,
) {
    /** One stage over a window: average and worst time per buffer, and its share of the chain. */
    data class Stage(val name: String, val avgUs: Double, val maxUs: Double, val sharePercent: Double)

    /** The averages over a window of [buffers] buffers. */
    data class Summary(
        val buffers: Long,
        /** Real-time budget of one buffer (its own duration), µs; 0 if the rate is unknown. */
        val budgetUs: Double,
        val avgWallUs: Double,
        val avgCpuUs: Double,
        val maxWallUs: Double,
        val maxCpuUs: Double,
        val lastCpu: Int,
        /** Most expensive first. */
        val stages: List<Stage>,
    ) {
        /** Time inside process() spent off the CPU (preempted or waiting), µs per buffer. */
        val avgOffCpuUs: Double get() = (avgWallUs - avgCpuUs).coerceAtLeast(0.0)
        fun ofBudgetPercent(us: Double): Double = if (budgetUs > 0) us * 100.0 / budgetUs else 0.0
    }

    /**
     * The window from [previous] (an earlier reading of the same run) to this one; since
     * profiling started when there is no usable earlier reading. Maxima are since it started.
     */
    fun summarize(previous: StageProfile?, sampleRate: Double): Summary? {
        val base = previous?.takeIf { it.enabled && it.buffers in 1 until buffers } ?: EMPTY
        val n = buffers - base.buffers
        if (!enabled || n <= 0) return null
        val framesPerBuffer = (frames - base.frames).toDouble() / n
        val stages = STAGE_NAMES.indices.map { i ->
            (stageTotalNs[i] - base.stageTotalNs.getOrElse(i) { 0 }).toDouble() / n / 1000.0
        }
        val total = stages.sum().takeIf { it > 0 } ?: 1.0
        return Summary(
            buffers = n,
            budgetUs = if (sampleRate > 0) framesPerBuffer * 1e6 / sampleRate else 0.0,
            avgWallUs = (wallNs - base.wallNs).toDouble() / n / 1000.0,
            avgCpuUs = (cpuNs - base.cpuNs).toDouble() / n / 1000.0,
            maxWallUs = maxWallNs / 1000.0,
            maxCpuUs = maxCpuNs / 1000.0,
            lastCpu = lastCpu,
            stages = STAGE_NAMES.indices
                .map { Stage(STAGE_NAMES[it], stages[it], stageMaxNs[it] / 1000.0, stages[it] * 100.0 / total) }
                .sortedByDescending { it.avgUs },
        )
    }

    companion object {
        /** In NativeBmwDsp::StageProfiler::Stage order. */
        val STAGE_NAMES = listOf(
            "Input", "DC blocker", "Mono bass", "Virtual bass", "Pre EQ + headroom", "MBC", "Virtual centre",
            "Routing", "Crossovers", "Band EQ", "All-pass", "Band delay + gain", "Bus limiters", "Band sum",
            "Tilt", "Post gain / mutes", "Convolver", "Master limiter", "Volume + loudness", "Output guard",
        )
        private const val HEADER = 8
        val SIZE = HEADER + 2 * STAGE_NAMES.size

        private val EMPTY = StageProfile(true, 0, 0, 0, 0, 0, 0, -1, LongArray(STAGE_NAMES.size), LongArray(STAGE_NAMES.size))

        fun parse(raw: DoubleArray?): StageProfile? {
            if (raw == null || raw.size < SIZE) return null
            return StageProfile(
                enabled = raw[0] >= .5,
                buffers = raw[1].toLong(),
                frames = raw[2].toLong(),
                wallNs = raw[3].toLong(),
                cpuNs = raw[4].toLong(),
                maxWallNs = raw[5].toLong(),
                maxCpuNs = raw[6].toLong(),
                lastCpu = raw[7].toInt(),
                stageTotalNs = LongArray(STAGE_NAMES.size) { raw[HEADER + 2 * it].toLong() },
                stageMaxNs = LongArray(STAGE_NAMES.size) { raw[HEADER + 2 * it + 1].toLong() },
            )
        }
    }
}
