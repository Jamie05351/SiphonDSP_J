package app.siphondsp.interop

/**
 * The native convolver's IR as the engine reports it ([NativeDsp.getConvolverInfo]): what was
 * loaded, at which rates, how long it runs, and its loudest frequency (to set the gain trim by).
 */
data class ConvolverStatus(
    val loaded: Boolean,
    val channels: Int,
    val sourceRate: Int,
    val sourceFrames: Int,
    val streamRate: Int,
    val streamFrames: Int,
    val partitions: Int,
    val peakGainDb: Double,
    /** The audio thread has a kernel installed (false until the next buffer after a load). */
    val kernelActive: Boolean,
    /** Why the last load or sample-rate rebuild failed, or null. */
    val error: String?,
) {
    val resampled: Boolean get() = loaded && sourceRate != streamRate
    val lengthMs: Double get() = if (streamRate > 0) streamFrames * 1000.0 / streamRate else 0.0

    companion object {
        fun fromNative(values: DoubleArray, error: String?): ConvolverStatus? {
            if (values.size < NativeDsp.CONVOLVER_COUNT) return null
            return ConvolverStatus(
                loaded = values[NativeDsp.CONVOLVER_LOADED] != 0.0,
                channels = values[NativeDsp.CONVOLVER_CHANNELS].toInt(),
                sourceRate = values[NativeDsp.CONVOLVER_SOURCE_RATE].toInt(),
                sourceFrames = values[NativeDsp.CONVOLVER_SOURCE_FRAMES].toInt(),
                streamRate = values[NativeDsp.CONVOLVER_STREAM_RATE].toInt(),
                streamFrames = values[NativeDsp.CONVOLVER_STREAM_FRAMES].toInt(),
                partitions = values[NativeDsp.CONVOLVER_PARTITIONS].toInt(),
                peakGainDb = values[NativeDsp.CONVOLVER_PEAK_GAIN_DB],
                kernelActive = values[NativeDsp.CONVOLVER_KERNEL_ACTIVE] != 0.0,
                error = error,
            )
        }
    }
}
