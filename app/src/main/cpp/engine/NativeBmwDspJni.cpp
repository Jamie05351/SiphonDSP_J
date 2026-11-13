// The JNI layer: one NativeEngine handle per audio pipeline, wrapping NativeBmwDspProcessor. The
// Kotlin side is app.siphondsp.interop.NativeDsp (natives) and DspEngine (handle lifetime and
// locking). Not compiled into the host tests or the parity harness (both use the processor
// directly).
#include <jni.h>
#include <android/trace.h>

#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "NativeBmwDspProcessor.h"
#include "NativeBmwSelfTest.h"
#include "ScopedFlushToZero.h"

namespace {
constexpr jlong kRevisionRejected = -1;

// Sample formats processDirect() accepts; match NativeDsp.ENCODING_* (and AudioEncoding.value).
constexpr jint kEncodingPcm16 = 0;
constexpr jint kEncodingPcmFloat = 1;

struct NativeEngine {
    NativeBmwDspProcessor processor;
    // Native-side acknowledgement of the last completely accepted configuration. Advanced only
    // after the processor accepted the update, so a rejected request never advances them.
    std::atomic<std::int64_t> dspRevision{0};
    std::atomic<std::int64_t> peqRevision{0};
    // Audio-path counters for the diagnostics screen (NativeDsp.REALTIME_*), written by the
    // audio thread with relaxed stores, read lock-free.
    std::atomic<std::int64_t> lastBlockFrames{0};
    std::atomic<std::int64_t> blockSizeChanges{0};
};

NativeEngine* fromHandle(jlong handle) {
    return reinterpret_cast<NativeEngine*>(handle);
}

// A Perfetto/systrace slice for the duration of the scope, recorded only while tracing is on.
class TraceSection {
public:
    explicit TraceSection(const char* name) : enabled_(ATrace_isEnabled()) {
        if (enabled_) {
            ATrace_beginSection(name);
        }
    }
    ~TraceSection() {
        if (enabled_) {
            ATrace_endSection();
        }
    }
    TraceSection(const TraceSection&) = delete;
    TraceSection& operator=(const TraceSection&) = delete;

private:
    const bool enabled_;
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_alloc(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) NativeEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_free(JNIEnv*, jobject, jlong self) {
    delete fromHandle(self);
}

// Processes `sampleCount` interleaved stereo samples of a direct ByteBuffer in place: no copy and
// no array pinning, so a buffer costs one JNI transition and the DSP itself. False (buffer
// untouched) for a gone handle, a heap buffer, an unknown encoding or a count the buffer can't
// hold; the caller then plays the buffer unprocessed.
extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_NativeDsp_processDirect(JNIEnv* env, jobject, jlong self,
                                                    jobject buffer, jint sampleCount,
                                                    jint encoding) {
    if (env == nullptr || self == 0 || buffer == nullptr || sampleCount <= 0) {
        return JNI_FALSE;
    }
    const std::size_t bytesPerSample =
        encoding == kEncodingPcm16 ? sizeof(std::int16_t)
        : encoding == kEncodingPcmFloat ? sizeof(float) : 0;
    if (bytesPerSample == 0) {
        return JNI_FALSE;
    }
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    const auto samples = static_cast<std::size_t>(sampleCount) & ~std::size_t{1};
    if (address == nullptr || capacity < 0 ||
        static_cast<std::size_t>(capacity) < samples * bytesPerSample) {
        return JNI_FALSE;
    }
    auto* engine = fromHandle(self);
    const auto frames = static_cast<std::int64_t>(samples / 2);
    if (engine->lastBlockFrames.exchange(frames, std::memory_order_relaxed) != frames) {
        engine->blockSizeChanges.fetch_add(1, std::memory_order_relaxed);
    }
    const TraceSection trace("SiphonDSP process");
    // Denormals flushed for the processing only, not the JNI calls around it.
    const ScopedFlushToZero flushToZero;
    if (encoding == kEncodingPcm16) {
        engine->processor.process(static_cast<const std::int16_t*>(address), samples);
    } else {
        engine->processor.process(static_cast<const float*>(address), samples);
    }
    return JNI_TRUE;
}

// A stream is (re)starting: see NativeBmwDspProcessor::notifyStreamStart(). One atomic store.
extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_notifyStreamStart(JNIEnv*, jobject, jlong self) {
    if (self != 0) {
        fromHandle(self)->processor.notifyStreamStart();
    }
}

// Fills [out] with the audio-path counters, in NativeDsp.REALTIME_* order: lastBlockFrames,
// blockSizeChanges, then the output guard's (NativeBmwDspProcessor::GuardCounters) nonFinite
// faults, over-range faults, clipped samples, stream-start resets and fade-ins. Atomics only, so
// it takes no lock. False for a gone handle or a short array.
extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_NativeDsp_getRealtimeCounters(JNIEnv* env, jobject, jlong self,
                                                          jlongArray out) {
    if (env == nullptr || self == 0 || out == nullptr) {
        return JNI_FALSE;
    }
    const auto* engine = fromHandle(self);
    const auto guard = engine->processor.guardCounters();
    const jlong values[] = {
        engine->lastBlockFrames.load(std::memory_order_relaxed),
        engine->blockSizeChanges.load(std::memory_order_relaxed),
        guard.nonFiniteFaults,
        guard.overRangeFaults,
        guard.clippedSamples,
        guard.streamStarts,
        guard.fadeIns,
    };
    constexpr jsize count = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    if (env->GetArrayLength(out) < count) {
        return JNI_FALSE;
    }
    env->SetLongArrayRegion(out, 0, count, values);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_configureNativeBmwDsp(JNIEnv* env, jobject, jlong self,
                                                                 jfloatArray valuesObj,
                                                                 jlong revision) {
    if (env == nullptr || self == 0 || valuesObj == nullptr || revision <= 0) {
        return kRevisionRejected;
    }
    auto* engine = fromHandle(self);
    auto* processor = &engine->processor;
    const jsize count = env->GetArrayLength(valuesObj);
    if (count != static_cast<jsize>(NativeBmwDspProcessor::kConfigSize)) {
        return kRevisionRejected;
    }
    jfloat* values = env->GetFloatArrayElements(valuesObj, nullptr);
    if (values == nullptr) {
        return kRevisionRejected;
    }
    const bool result = processor->configure(values, static_cast<std::size_t>(count));
    env->ReleaseFloatArrayElements(valuesObj, values, JNI_ABORT);
    if (!result) {
        return kRevisionRejected;
    }
    // configure() returns true only once the whole update is validated and handed to the audio
    // thread, which installs it at the start of its next buffer (and anything that reads the
    // running state installs it first). Publish the acknowledgement only after that commit
    // point; a rejected request can therefore never advance native-active.
    engine->dspRevision.store(static_cast<std::int64_t>(revision),
                                        std::memory_order_release);
    return revision;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_getNativeBmwDspActiveRevision(JNIEnv*, jobject,
                                                                         jlong self) {
    if (self == 0) {
        return 0;
    }
    return static_cast<jlong>(fromHandle(self)->dspRevision.load(std::memory_order_acquire));
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_configureNativeBmwPeq(JNIEnv* env, jobject, jlong self,
                                                                 jboolean enabled, jfloat preampDb,
                                                                 jdoubleArray fullObj,
                                                                 jdoubleArray lowObj,
                                                                 jdoubleArray midObj,
                                                                 jdoubleArray highObj,
                                                                 jlong revision) {
    if (env == nullptr || self == 0 || fullObj == nullptr || lowObj == nullptr ||
        midObj == nullptr || highObj == nullptr || revision <= 0) {
        return kRevisionRejected;
    }
    auto* engine = fromHandle(self);
    auto* processor = &engine->processor;
    const jsize fullCount = env->GetArrayLength(fullObj), lowCount = env->GetArrayLength(lowObj),
                midCount = env->GetArrayLength(midObj), highCount = env->GetArrayLength(highObj);
    jdouble* full = env->GetDoubleArrayElements(fullObj, nullptr);
    jdouble* low = env->GetDoubleArrayElements(lowObj, nullptr);
    jdouble* mid = env->GetDoubleArrayElements(midObj, nullptr);
    jdouble* high = env->GetDoubleArrayElements(highObj, nullptr);
    if (full == nullptr || low == nullptr || mid == nullptr || high == nullptr) {
        if (full) {
            env->ReleaseDoubleArrayElements(fullObj, full, JNI_ABORT);
        }
        if (low) {
            env->ReleaseDoubleArrayElements(lowObj, low, JNI_ABORT);
        }
        if (mid) {
            env->ReleaseDoubleArrayElements(midObj, mid, JNI_ABORT);
        }
        if (high) {
            env->ReleaseDoubleArrayElements(highObj, high, JNI_ABORT);
        }
        return kRevisionRejected;
    }
    const bool result = processor->configurePeq(enabled == JNI_TRUE, preampDb, full, fullCount, low,
                                                lowCount, mid, midCount, high, highCount);
    env->ReleaseDoubleArrayElements(fullObj, full, JNI_ABORT);
    env->ReleaseDoubleArrayElements(lowObj, low, JNI_ABORT);
    env->ReleaseDoubleArrayElements(midObj, mid, JNI_ABORT);
    env->ReleaseDoubleArrayElements(highObj, high, JNI_ABORT);
    if (!result) {
        return kRevisionRejected;
    }
    engine->peqRevision.store(static_cast<std::int64_t>(revision),
                                        std::memory_order_release);
    return revision;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_getNativeBmwPeqActiveRevision(JNIEnv*, jobject,
                                                                         jlong self) {
    if (self == 0) {
        return 0;
    }
    return static_cast<jlong>(fromHandle(self)->peqRevision.load(std::memory_order_acquire));
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_setSampleRate(JNIEnv* env, jobject,
                                                                     jlong self,
                                                                     jfloat sampleRate) {
    if (env == nullptr || self == 0) {
        return;
    }
    fromHandle(self)->processor.setSampleRate(sampleRate);
}

// Fills [out] with one meter's current values instead of returning a new array, so a UI poll
// running ~30 times a second allocates nothing. kind (NativeDsp.METER_*): 1 = MBC
// (12 floats: 4 bands x [inputDb, outputDb, gainReductionDb]), 2 = bus limiters (3 floats:
// [lowGrDb, midGrDb, highGrDb]). False, with [out] untouched, for an unknown kind, a short
// array or a gone handle. The readers only load atomics, so no lock is taken.
extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_NativeDsp_readNativeBmwMeter(JNIEnv* env, jobject, jlong self,
                                                             jint kind, jfloatArray out) {
    if (env == nullptr || self == 0 || out == nullptr) {
        return JNI_FALSE;
    }
    auto* processor = &fromHandle(self)->processor;
    float values[12];
    jsize count = 0;
    switch (kind) {
        case 1:
            count = 12;
            processor->readMbcMeter(values, 12);
            break;
        case 2:
            count = 3;
            processor->readBusLimiterMeter(values, 3);
            break;
        default:
            return JNI_FALSE;
    }
    if (env->GetArrayLength(out) < count) {
        return JNI_FALSE;
    }
    env->SetFloatArrayRegion(out, 0, count, values);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_siphondsp_interop_NativeDsp_getNativeBmwMasterLimiterMeter(JNIEnv* env, jobject,
                                                                          jlong self) {
    if (env == nullptr || self == 0) {
        return nullptr;
    }
    auto* processor = &fromHandle(self)->processor;
    float values[1];
    processor->readMasterLimiterMeter(values, 1);
    jfloatArray result = env->NewFloatArray(1);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, 1, values);
    }
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_siphondsp_interop_NativeDsp_getNativeBmwVirtualMeter(JNIEnv* env, jobject,
                                                                    jlong self) {
    if (env == nullptr || self == 0) {
        return nullptr;
    }
    auto* processor = &fromHandle(self)->processor;
    float values[2];
    processor->readVirtualMeter(values, 2);
    jfloatArray result = env->NewFloatArray(2);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, 2, values);
    }
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_app_siphondsp_interop_NativeDsp_getNativeTruthSnapshot(JNIEnv* env, jobject,
                                                                  jlong self) {
    if (env == nullptr || self == 0) {
        return nullptr;
    }
    auto* processor = &fromHandle(self)->processor;
    // See NativeBmwDspProcessor::captureTruthSnapshot()'s comment for the exact array layout.
    const std::vector<double> snapshot = processor->captureTruthSnapshot();
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(snapshot.size()));
    if (result != nullptr && !snapshot.empty()) {
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(snapshot.size()), snapshot.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_startNativeBmwCapture(JNIEnv* env, jobject, jlong self) {
    if (env == nullptr || self == 0) {
        return;
    }
    fromHandle(self)->processor.startCapture();
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_stopNativeBmwCapture(JNIEnv* env, jobject, jlong self) {
    if (env == nullptr || self == 0) {
        return;
    }
    fromHandle(self)->processor.stopCapture();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_getNativeBmwCaptureFrameCount(JNIEnv* env, jobject,
                                                                         jlong self) {
    if (env == nullptr || self == 0) {
        return 0;
    }
    auto* processor = &fromHandle(self)->processor;
    return static_cast<jlong>(processor->captureFrameCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_takeNativeBmwCaptureSnapshot(JNIEnv* env, jobject,
                                                                       jlong self) {
    if (env == nullptr || self == 0) return 0;
    auto* processor = &fromHandle(self)->processor;
    try {
        return reinterpret_cast<jlong>(processor->takeCaptureSnapshot().release());
    } catch (const std::bad_alloc&) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_freeNativeBmwCaptureSnapshot(JNIEnv*, jobject,
                                                                       jlong snapshot) {
    delete reinterpret_cast<NativeBmwDspProcessor::CaptureSnapshot*>(snapshot);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_siphondsp_interop_NativeDsp_exportNativeBmwCaptureWav(JNIEnv* env, jobject,
                                                                     jlong snapshotHandle,
                                                                     jstring rawInPathObj,
                                                                     jstring outPathObj) {
    if (env == nullptr || snapshotHandle == 0 || rawInPathObj == nullptr || outPathObj == nullptr) {
        return nullptr;
    }
    auto* snapshot = reinterpret_cast<NativeBmwDspProcessor::CaptureSnapshot*>(snapshotHandle);
    const char* rawInPath = env->GetStringUTFChars(rawInPathObj, nullptr);
    const char* outPath = env->GetStringUTFChars(outPathObj, nullptr);
    if (rawInPath == nullptr || outPath == nullptr) {
        if (rawInPath) {
            env->ReleaseStringUTFChars(rawInPathObj, rawInPath);
        }
        if (outPath) {
            env->ReleaseStringUTFChars(outPathObj, outPath);
        }
        return nullptr;
    }
    NativeBmwDspProcessor::CaptureExportResult result;
    const bool ok = snapshot->exportWav(rawInPath, outPath, result);
    env->ReleaseStringUTFChars(rawInPathObj, rawInPath);
    env->ReleaseStringUTFChars(outPathObj, outPath);
    if (!ok) {
        return nullptr;
    }
    const float values[3] = {result.peakInDb, result.peakOutDb, result.nullTestRmsDb};
    jfloatArray resultArray = env->NewFloatArray(3);
    if (resultArray != nullptr) {
        env->SetFloatArrayRegion(resultArray, 0, 3, values);
    }
    return resultArray;
}

// ---- Convolver IR ---------------------------------------------------------------------------
// Control calls (never the audio thread): see NativeBmwDspProcessor::loadConvolverIr().

namespace {
// A Java string from UTF-8 that may be odd: messages are built from ASCII here, so this is a
// plain NewStringUTF.
jstring toJava(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }
}  // namespace

// Null on success, else why the IR was rejected (the previous IR keeps running).
extern "C" JNIEXPORT jstring JNICALL
Java_app_siphondsp_interop_NativeDsp_loadConvolverIr(JNIEnv* env, jobject, jlong self, jstring pathObj) {
    if (env == nullptr || self == 0 || pathObj == nullptr) {
        return env != nullptr ? toJava(env, "Engine unavailable") : nullptr;
    }
    const char* path = env->GetStringUTFChars(pathObj, nullptr);
    if (path == nullptr) {
        return toJava(env, "Bad path");
    }
    std::string error;
    bool ok = false;
    try {
        ok = fromHandle(self)->processor.loadConvolverIr(path, error);
    } catch (const std::bad_alloc&) {
        error = "Out of memory loading the IR";
    }
    env->ReleaseStringUTFChars(pathObj, path);
    return ok ? nullptr : toJava(env, error);
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_clearConvolverIr(JNIEnv*, jobject, jlong self) {
    if (self != 0) {
        fromHandle(self)->processor.clearConvolverIr();
    }
}

// NativeDsp.CONVOLVER_* order: loaded (0/1), channels, sourceRate, sourceFrames, streamRate,
// streamFrames, partitions, peakGainDb, kernelActive (0/1), latencyFrames, latencyMs. Null for a
// gone handle.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_app_siphondsp_interop_NativeDsp_getConvolverInfo(JNIEnv* env, jobject, jlong self) {
    if (env == nullptr || self == 0) {
        return nullptr;
    }
    auto& processor = fromHandle(self)->processor;
    const auto info = processor.convolverInfo();
    const jdouble values[] = {
        info.loaded ? 1.0 : 0.0,
        static_cast<double>(info.channels),
        info.sourceRate,
        static_cast<double>(info.sourceFrames),
        info.streamRate,
        static_cast<double>(info.streamFrames),
        static_cast<double>(info.partitions),
        info.peakGainDb,
        processor.convolverKernelActive() ? 1.0 : 0.0,
        static_cast<double>(info.latencyFrames),
        info.latencyMs,
    };
    constexpr jsize count = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jdoubleArray result = env->NewDoubleArray(count);
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, count, values);
    }
    return result;
}

// The convolver's generation: bumped by every IR load, sample-rate rebuild and clear, so the app
// refetches getConvolverSnapshotResponse() only when it moves. -1 for a gone handle.
extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_getConvolverGeneration(JNIEnv*, jobject, jlong self) {
    if (self == 0) {
        return -1;
    }
    return static_cast<jlong>(fromHandle(self)->processor.convolverGeneration());
}

// A snapshot of the running IR for freeConvolverSnapshot() / getConvolverSnapshotResponse():
// O(1) (the taps are shared, not copied), so the app can take it under its engine-handle lock and
// run the DTFT after releasing it. 0 with no IR running or a gone handle. Free it with
// freeConvolverSnapshot().
extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_takeConvolverSnapshot(JNIEnv*, jobject, jlong self) {
    if (self == 0) {
        return 0;
    }
    try {
        auto snapshot = std::make_unique<NativeBmwDspProcessor::ConvolverSnapshot>(
            fromHandle(self)->processor.convolverSnapshot());
        if (!snapshot->ir) {
            return 0;
        }
        return reinterpret_cast<jlong>(snapshot.release());
    } catch (const std::bad_alloc&) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_NativeDsp_freeConvolverSnapshot(JNIEnv*, jobject, jlong snapshot) {
    delete reinterpret_cast<NativeBmwDspProcessor::ConvolverSnapshot*>(snapshot);
}

// The snapshot IR's complex response at freqs (Hz), bulk latency taken out, for the graphs:
// NativeDsp.CONVOLVER_RESPONSE_* header (generation, sampleRate, latencyFrames, peakGainDb), then
// left re/im per frequency, then right re/im. Null for a null snapshot. Needs no engine handle. A
// DTFT of the whole IR per frequency: call it off the main thread, and only when the generation
// moved.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_app_siphondsp_interop_NativeDsp_getConvolverSnapshotResponse(JNIEnv* env, jobject,
                                                                  jlong snapshotHandle,
                                                                  jdoubleArray freqsObj) {
    if (env == nullptr || snapshotHandle == 0 || freqsObj == nullptr) {
        return nullptr;
    }
    const auto& snapshot =
        *reinterpret_cast<const NativeBmwDspProcessor::ConvolverSnapshot*>(snapshotHandle);
    const jsize count = env->GetArrayLength(freqsObj);
    constexpr jsize kHeader = 4;
    if (count <= 0 || count > (std::numeric_limits<jsize>::max() - kHeader) / 4) {
        return nullptr;
    }
    NativeBmwDspProcessor::ConvolverResponse response;
    try {
        std::vector<double> freqs(static_cast<std::size_t>(count));
        env->GetDoubleArrayRegion(freqsObj, 0, count, freqs.data());
        if (!NativeBmwDspProcessor::convolverResponse(snapshot, freqs.data(), freqs.size(), response)) {
            return nullptr;
        }
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
    const jsize total = kHeader + 4 * count;
    jdoubleArray result = env->NewDoubleArray(total);
    if (result == nullptr) {
        return nullptr;
    }
    const jdouble header[kHeader] = {
        static_cast<double>(response.generation),
        response.sampleRate,
        static_cast<double>(response.latencyFrames),
        response.peakGainDb,
    };
    env->SetDoubleArrayRegion(result, 0, kHeader, header);
    env->SetDoubleArrayRegion(result, kHeader, 2 * count, response.left.data());
    env->SetDoubleArrayRegion(result, kHeader + 2 * count, 2 * count, response.right.data());
    return result;
}

// The last load / rebuild failure, or null.
extern "C" JNIEXPORT jstring JNICALL
Java_app_siphondsp_interop_NativeDsp_getConvolverError(JNIEnv* env, jobject, jlong self) {
    if (env == nullptr || self == 0) {
        return nullptr;
    }
    const auto info = fromHandle(self)->processor.convolverInfo();
    return info.error.empty() ? nullptr : toJava(env, info.error);
}

// Validates a WAV as an IR without an engine (the import, while the service is stopped): null
// when it would load at sampleRate, else why not.
extern "C" JNIEXPORT jstring JNICALL
Java_app_siphondsp_interop_NativeDsp_checkConvolverIr(JNIEnv* env, jobject, jstring pathObj,
                                                      jfloat sampleRate) {
    if (env == nullptr || pathObj == nullptr) {
        return nullptr;
    }
    const char* path = env->GetStringUTFChars(pathObj, nullptr);
    if (path == nullptr) {
        return toJava(env, "Bad path");
    }
    std::string error;
    bool ok = false;
    try {
        NativeBmwDsp::ConvolverIr ir, atRate;
        ok = NativeBmwDsp::loadConvolverIrWav(path, ir, error) &&
             NativeBmwDsp::resampleConvolverIr(ir, sampleRate, atRate, error);
    } catch (const std::bad_alloc&) {
        error = "Out of memory reading the IR";
    }
    env->ReleaseStringUTFChars(pathObj, path);
    return ok ? nullptr : toJava(env, error);
}

// The engine status (NativeBmwDspProcessor::readEngineStatus(), NativeDsp.ENGINE_STATUS_*): what
// the processor is actually running. Lock-free. Null for a gone handle.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_siphondsp_interop_NativeDsp_getEngineStatus(JNIEnv* env, jobject, jlong self) {
    if (env == nullptr || self == 0) {
        return nullptr;
    }
    constexpr auto kSize = static_cast<jsize>(NativeBmwDspProcessor::kEngineStatusSize);
    float values[NativeBmwDspProcessor::kEngineStatusSize];
    fromHandle(self)->processor.readEngineStatus(values, NativeBmwDspProcessor::kEngineStatusSize);
    jfloatArray result = env->NewFloatArray(kSize);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, kSize, values);
    }
    return result;
}

// What the self-test needs from the running engine (NativeBmwDspProcessor::selfTestSource()),
// copied out so the app can take it under its engine-handle lock and run the test after letting
// go. 0 before the first accepted config or for a gone handle. runSelfTest() frees it.
extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_NativeDsp_takeSelfTestSource(JNIEnv*, jobject, jlong self) {
    if (self == 0) {
        return 0;
    }
    try {
        auto source = std::make_unique<NativeBmwDspProcessor::SelfTestSource>();
        if (!fromHandle(self)->processor.selfTestSource(*source)) {
            return 0;
        }
        return reinterpret_cast<jlong>(source.release());
    } catch (const std::bad_alloc&) {
        return 0;
    }
}

// Runs the self-test (NativeBmwSelfTest.h) on the calling thread -- an offline processor, a
// second or so of work: never the main or audio thread -- and frees the source. Needs no engine
// handle. NativeDsp.SELF_TEST_* layout; null for a null source.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_app_siphondsp_interop_NativeDsp_runSelfTest(JNIEnv* env, jobject, jlong sourceHandle) {
    if (sourceHandle == 0) {
        return nullptr;
    }
    std::unique_ptr<NativeBmwDspProcessor::SelfTestSource> source(
        reinterpret_cast<NativeBmwDspProcessor::SelfTestSource*>(sourceHandle));
    if (env == nullptr) {
        return nullptr;
    }
    NativeBmwDsp::SelfTestResult r;
    try {
        const ScopedFlushToZero ftz;
        r = NativeBmwDsp::runSelfTest(*source);
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
    const jdouble values[] = {
        r.ran ? 1.0 : 0.0,
        r.verified ? 1.0 : 0.0,
        r.sampleRate,
        static_cast<double>(r.latencyFrames),
        r.magnitudeDevDb,
        r.phaseDevDeg,
        r.crosstalkDb,
        r.lowHz,
        r.highHz,
        static_cast<double>(r.activeStages),
        static_cast<double>(r.alteringStages),
        static_cast<double>(r.levelDependentStages),
        r.unexplained ? 1.0 : 0.0,
        r.measurementNeutral ? 1.0 : 0.0,
        r.masterVolumeDb,
        r.durationMs,
    };
    constexpr auto kCount = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jdoubleArray result = env->NewDoubleArray(kCount);
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, kCount, values);
    }
    return result;
}
