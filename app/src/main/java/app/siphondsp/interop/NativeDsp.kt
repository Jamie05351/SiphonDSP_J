package app.siphondsp.interop

import java.nio.ByteBuffer

typealias NativeDspHandle = Long

/**
 * JNI entry points of libsiphondsp-engine.so (app/src/main/cpp/engine/NativeBmwDspJni.cpp). A
 * handle wraps one NativeBmwDspProcessor; [DspEngine] owns it and guards its lifetime.
 */
object NativeDsp {
    external fun alloc(): NativeDspHandle
    external fun free(self: NativeDspHandle)

    /**
     * Processes [sampleCount] interleaved stereo samples of the direct [buffer] in place.
     * [encoding] is [ENCODING_PCM_16] or [ENCODING_PCM_FLOAT]. False, with the buffer untouched,
     * for a gone handle, a heap buffer, an unknown encoding or a count the buffer can't hold.
     */
    external fun processDirect(self: NativeDspHandle, buffer: ByteBuffer, sampleCount: Int, encoding: Int): Boolean
    const val ENCODING_PCM_16 = 0
    const val ENCODING_PCM_FLOAT = 1

    external fun setSampleRate(self: NativeDspHandle, sampleRate: Float)

    // Returns the exact revision native acknowledged, or -1 when the configuration was rejected.
    external fun configureNativeBmwDsp(self: NativeDspHandle, values: FloatArray, revision: Long): Long
    // The last revision native accepted. It is installed at the start of the next audio buffer
    // (the moment it becomes audible), and the truth snapshot installs it before reading.
    external fun getNativeBmwDspActiveRevision(self: NativeDspHandle): Long
    // Returns the exact revision native acknowledged, or -1 when the configuration was rejected.
    external fun configureNativeBmwPeq(
        self: NativeDspHandle,
        enabled: Boolean,
        preampDb: Float,
        fullRangeBands: DoubleArray,
        lowBandBands: DoubleArray,
        midBandBands: DoubleArray,
        highBandBands: DoubleArray,
        revision: Long,
    ): Long
    // Same contract as getNativeBmwDspActiveRevision.
    external fun getNativeBmwPeqActiveRevision(self: NativeDspHandle): Long

    // Fills [out] with one meter's values (no allocation, for ~30 Hz UI polls); false and [out]
    // untouched when the handle is gone or [out] is too short. [kind]:
    //   METER_MBC          12 floats: 4 MBC bands x [inputDb, outputDb, gainReductionDb];
    //                      all-idle (-60/-60/0) while the multiband compressor is disabled.
    //   METER_BUS_LIMITER  3 floats: [lowGrDb, midGrDb, highGrDb]; 0 for a disabled bus.
    external fun readNativeBmwMeter(self: NativeDspHandle, kind: Int, out: FloatArray): Boolean
    const val METER_MBC = 1
    const val METER_BUS_LIMITER = 2

    // Fills [out] (at least REALTIME_COUNT longs) with the audio-path counters, indexed by
    // REALTIME_*. Lock-free atomics; false for a gone handle or a short array.
    external fun getRealtimeCounters(self: NativeDspHandle, out: LongArray): Boolean
    // Frames in the last processed buffer.
    const val REALTIME_LAST_BLOCK_FRAMES = 0
    // Buffers whose length differed from the one before.
    const val REALTIME_BLOCK_SIZE_CHANGES = 1
    const val REALTIME_COUNT = 2

    // 1 float: [masterLimiterGrDb] -- master brick-wall limiter gain reduction; 0 while bypassed.
    external fun getNativeBmwMasterLimiterMeter(self: NativeDspHandle): FloatArray?
    // 2 floats: [centreWeight 0..1, centreRmsDb] of the virtual centre; [0, -60] while the
    // virtual stage is disabled.
    external fun getNativeBmwVirtualMeter(self: NativeDspHandle): FloatArray?
    // Whole-state read-only snapshot for the native-truth debug screen. Null when the native
    // handle is gone. See NativeBmwDspProcessor::captureTruthSnapshot()'s comment for the exact
    // array layout (sample rate + PEQ enable/preamp, the per-output crossover blocks, then the
    // variable-length PEQ bank blocks).
    external fun getNativeTruthSnapshot(self: NativeDspHandle): DoubleArray?
    external fun startNativeBmwCapture(self: NativeDspHandle)
    external fun stopNativeBmwCapture(self: NativeDspHandle)
    external fun getNativeBmwCaptureFrameCount(self: NativeDspHandle): Long
    external fun takeNativeBmwCaptureSnapshot(self: NativeDspHandle): Long
    external fun freeNativeBmwCaptureSnapshot(snapshot: Long)
    external fun exportNativeBmwCaptureWav(snapshot: Long, rawInPath: String, outPath: String): FloatArray?

    // ---- Convolver IR (NativeBmwConvolver.h) -- control calls, never from the audio thread ----
    // Reads, resamples (if the rates differ) and installs the WAV IR at [path]; the stage
    // crossfades to it. Null on success, else why it was rejected (the previous IR keeps running).
    external fun loadConvolverIr(self: NativeDspHandle, path: String): String?
    external fun clearConvolverIr(self: NativeDspHandle)
    // CONVOLVER_COUNT doubles indexed by CONVOLVER_*; null for a gone handle.
    external fun getConvolverInfo(self: NativeDspHandle): DoubleArray?
    // The last load / sample-rate rebuild failure, or null.
    external fun getConvolverError(self: NativeDspHandle): String?
    // Validates a WAV as an IR at [sampleRate] without an engine. Null when it would load.
    external fun checkConvolverIr(path: String, sampleRate: Float): String?
    const val CONVOLVER_LOADED = 0
    const val CONVOLVER_CHANNELS = 1
    const val CONVOLVER_SOURCE_RATE = 2
    const val CONVOLVER_SOURCE_FRAMES = 3
    const val CONVOLVER_STREAM_RATE = 4
    const val CONVOLVER_STREAM_FRAMES = 5
    const val CONVOLVER_PARTITIONS = 6
    const val CONVOLVER_PEAK_GAIN_DB = 7
    const val CONVOLVER_KERNEL_ACTIVE = 8
    const val CONVOLVER_LATENCY_FRAMES = 9
    const val CONVOLVER_LATENCY_MS = 10
    const val CONVOLVER_COUNT = 11

    init {
        System.loadLibrary("siphondsp-engine")
    }
}
