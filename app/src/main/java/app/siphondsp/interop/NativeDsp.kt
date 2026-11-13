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
    // Output guard (after the master limiter): faults from a NaN/Inf sample, and from a finite
    // sample far beyond full scale (a runaway stage) -- each clears the whole chain and fades it
    // back in.
    const val REALTIME_GUARD_NON_FINITE = 2
    const val REALTIME_GUARD_OVER_RANGE = 3
    // Finite samples the final clamp to +-1.0 cut.
    const val REALTIME_GUARD_CLIPPED = 4
    // Stream starts served (notifyStreamStart): chain cleared.
    const val REALTIME_STREAM_STARTS = 5
    // 50 ms output fade-ins begun (guard fault, IR load while the convolver plays).
    const val REALTIME_FADE_INS = 6
    const val REALTIME_COUNT = 7

    // A stream is (re)starting: the next buffer starts the chain from silence (every filter,
    // delay, limiter and convolver history cleared; settings and the IR kept). No fade: the
    // output is then exactly what a freshly started engine plays, so a REW sweep played into
    // the stream is untouched. One atomic store; safe from any thread.
    external fun notifyStreamStart(self: NativeDspHandle)

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
    // What the engine is actually running, from its installed state: EngineStatus.SIZE floats
    // (see NativeBmwDspProcessor::readEngineStatus() and EngineStatus.parse()). Lock-free; null for
    // a gone handle.
    external fun getEngineStatus(self: NativeDspHandle): FloatArray?
    // The measurement self-test (NativeBmwSelfTest.h): take the source under the handle lock (0
    // before the first accepted config), then run it after releasing the lock -- an offline copy
    // of the processor, about a second of work: never on the main or audio thread. runSelfTest()
    // frees the source and returns SelfTestReport.SIZE doubles (SelfTestReport.parse()).
    external fun takeSelfTestSource(self: NativeDspHandle): Long
    // Per-stage timing (NativeBmwProfiler.h): on only while Native DSP Truth shows it (switching
    // on starts the counts from zero). getStageProfile: StageProfile.SIZE doubles, lock-free.
    external fun setStageProfiling(self: NativeDspHandle, on: Boolean)
    // Pins the calling thread to the fastest CPU cluster it is allowed on (NativeAudioThread.cpp):
    // [result (AudioThreadTuning.PIN_*), allowed mask, pinned mask, fast-cluster mask, fast max kHz].
    // On any failure the affinity is left as it was. Needs no engine handle.
    external fun pinCurrentThreadToFastCores(): LongArray?
    external fun getStageProfile(self: NativeDspHandle): DoubleArray?
    external fun runSelfTest(source: Long): DoubleArray?
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
    // Bumped by every IR load, sample-rate rebuild and clear; -1 for a gone handle.
    external fun getConvolverGeneration(self: NativeDspHandle): Long
    // An O(1) snapshot of the running IR (0 with none): take it under the handle lock, evaluate it
    // with getConvolverSnapshotResponse() after releasing the lock, then freeConvolverSnapshot().
    external fun takeConvolverSnapshot(self: NativeDspHandle): Long
    external fun freeConvolverSnapshot(snapshot: Long)
    // The snapshot IR's complex response at [frequencies] (Hz), bulk latency taken out:
    // CONVOLVER_RESPONSE_HEADER doubles, then left re/im per frequency, then right re/im. Needs no
    // engine handle. A DTFT of the whole IR per frequency: off the main thread, and only when the
    // generation moved.
    external fun getConvolverSnapshotResponse(snapshot: Long, frequencies: DoubleArray): DoubleArray?
    const val CONVOLVER_RESPONSE_GENERATION = 0
    const val CONVOLVER_RESPONSE_SAMPLE_RATE = 1
    const val CONVOLVER_RESPONSE_LATENCY_FRAMES = 2
    const val CONVOLVER_RESPONSE_PEAK_GAIN_DB = 3
    const val CONVOLVER_RESPONSE_HEADER = 4
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
