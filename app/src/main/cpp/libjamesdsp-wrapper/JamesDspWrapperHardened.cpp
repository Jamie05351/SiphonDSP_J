#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>

#include "NativeBmwDspProcessor.h"
#include "ScopedFlushToZero.h"

extern "C" {
#include <jdsp_header.h>
}

namespace std {
inline const string& stable_to_string(double value)
{
    thread_local string storage;
    storage = to_string(value);
    return storage;
}
}

static const char* safeNseelCodeError(void* vm)
{
    const char* error = NSEEL_code_getcodeerror(vm);
    return error != nullptr ? error : "";
}

#define Java_app_siphondsp_interop_JamesDspWrapper_alloc Java_app_siphondsp_interop_JamesDspWrapper_alloc_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_free Java_app_siphondsp_interop_JamesDspWrapper_free_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt16 Java_app_siphondsp_interop_JamesDspWrapper_processInt16_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt32 Java_app_siphondsp_interop_JamesDspWrapper_processInt32_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt24Packed Java_app_siphondsp_interop_JamesDspWrapper_processInt24Packed_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt8U24 Java_app_siphondsp_interop_JamesDspWrapper_processInt8U24_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processFloat Java_app_siphondsp_interop_JamesDspWrapper_processFloat_legacy_unsafe

#define NSEEL_code_getcodeerror(vm) safeNseelCodeError(vm)
#define to_string stable_to_string
#define LiveProgEnable(dsp) ((ret > 0) ? (::LiveProgEnable)(dsp) : LiveProgDisable(dsp))

#include "JamesDspWrapper.cpp"

extern "C" {
// Defined in JamesDSP's jdspController.c but not declared in its header.
void JamesDSPRefreshConvolutions(JamesDSPLib* jdsp, char refreshAll);
void JamesDSPProcess(JamesDSPLib* jdsp, size_t n);
// Set once cached benchmarks are loaded; JamesDSPRefreshConvolutions() is a no-op without it.
extern char benchmarkEnable;
// Set when benchmarks load; JamesDSP's first-stage process function then refreshes every
// convolver on the audio thread and swaps itself for JamesDSPProcess.
extern char benchmarkCompletionFlag;
}

namespace {
// One buffer's convolver pass: whether it is wanted, and whether the BMW chain ran it.
struct ConvolverPass
{
    JamesDSPLib* dsp;
    bool wanted;
    bool ran;
    // JamesDSP's prepared block size (prepareBlockSize), or 0 if it was never prepared.
    std::size_t preparedFrames;
};

// The convolver's place in the BMW chain: run by NativeBmwDspProcessor::process() just before its
// master limiter (see BlockHook), so the limiter catches whatever the impulse response adds and
// its channels are the logical L / R. Runs JamesDSP's convolver on its work buffers, under its
// lock as in its own pass. Once prepared, it never reallocates or rebuilds anything here: the
// convolver takes any block length, so a block larger than the work buffers goes through in
// pieces. Unprepared, it falls back to JamesDSP's own grow-and-refresh bookkeeping.
void convolveBeforeMasterLimiter(void* context, float* x, std::size_t frames)
{
    auto& pass = *static_cast<ConvolverPass*>(context);
    pass.ran = true;
    if(!pass.wanted)
        return;
    JamesDSPLib* jdsp = pass.dsp;
    if(pass.preparedFrames == 0)
    {
        if(jdsp->blockSizeMax < frames)
            JamesDSPReallocateBlock(jdsp, frames);
        if(jdsp->blockSize != frames)
        {
            jdsp->blockSize = frames;
            JamesDSPRefreshConvolutions(jdsp, 1);
        }
    }
    const std::size_t capacity = std::max<std::size_t>(jdsp->blockSizeMax, 1);
    jdsp_lock(jdsp);
    if(jdsp->conv.process)
    {
        for(std::size_t done = 0; done < frames;)
        {
            const std::size_t n = (std::min)(frames - done, capacity);
            float* chunk = x + (done << 1);
            for(std::size_t i = 0; i < n; ++i)
            {
                jdsp->tmpBuffer[0][i] = chunk[i << 1];
                jdsp->tmpBuffer[1][i] = chunk[(i << 1) + 1];
            }
            jdsp->conv.process(jdsp, n);
            for(std::size_t i = 0; i < n; ++i)
            {
                chunk[i << 1] = jdsp->tmpBuffer[0][i];
                chunk[(i << 1) + 1] = jdsp->tmpBuffer[1][i];
            }
            done += n;
        }
    }
    jdsp_unlock(jdsp);
}

// The hook to give the BMW chain: none while JamesDSP resamples internally (ASRC), when the
// convolver has to run at the internal rate inside JamesDSP's own pass.
NativeBmwDspProcessor::BlockHook convolverHook(const JamesDSPLib* dsp)
{
    return dsp->enableASRC ? nullptr : &convolveBeforeMasterLimiter;
}

// Counts the buffer about to run. Prepared, a block size other than the prepared one only means
// the passes go in pieces (nothing is rebuilt). Unprepared, it counts what JamesDSP is about to
// do on the audio thread: grow the work buffers for a larger block, and refresh every FFT
// convolver for a block size different from the last one. Called before either pass runs.
void noteBlockSize(JamesDspWrapper* wrapper, const JamesDSPLib* dsp, std::size_t frames)
{
    auto& counters = wrapper->realtime;
    counters.lastBlockFrames.store(static_cast<std::int64_t>(frames), std::memory_order_relaxed);
    const auto prepared = static_cast<std::size_t>(counters.preparedFrames.load(std::memory_order_relaxed));
    if(prepared != 0)
    {
        if(frames != prepared)
            counters.blockSizeChanges.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if(dsp->blockSizeMax < frames)
        counters.workBufferReallocs.fetch_add(1, std::memory_order_relaxed);
    if(dsp->blockSize != frames)
    {
        counters.blockSizeChanges.fetch_add(1, std::memory_order_relaxed);
        if(benchmarkEnable)
            counters.convolverRebuilds.fetch_add(1, std::memory_order_relaxed);
    }
}

std::size_t preparedFrames(const JamesDspWrapper* wrapper)
{
    return static_cast<std::size_t>(wrapper->realtime.preparedFrames.load(std::memory_order_relaxed));
}

// Runs one of JamesDSP's interleaved entry points over `frames` frames (`stride` elements each).
// Each entry point grows its work buffers for a larger block and rebuilds every FFT convolver
// when the block size differs from the last call -- on the audio thread. Once prepared, neither
// may happen: the block goes in pieces no larger than the work buffers, with blockSize set to
// each piece's length for the call (so the size check passes) and put back to the prepared size
// after, so impulse-response loads keep building for it. Only the audio thread changes it, and
// loads run under the engine's exclusive lock, never during a pass.
template <class In, class Out>
void runCorePass(const JamesDspWrapper* wrapper, JamesDSPLib* dsp,
                 void (*pass)(JamesDSPLib*, In*, Out*, size_t), In* x, Out* y,
                 std::size_t frames, std::size_t stride)
{
    const std::size_t prepared = preparedFrames(wrapper);
    if(prepared == 0)
    {
        pass(dsp, x, y, frames);
        return;
    }
    const std::size_t capacity = std::max<std::size_t>(dsp->blockSizeMax, 1);
    for(std::size_t done = 0; done < frames;)
    {
        const std::size_t n = (std::min)(frames - done, capacity);
        dsp->blockSize = n;
        pass(dsp, x + done * stride, y + done * stride, n);
        done += n;
    }
    dsp->blockSize = prepared;
}

// Sets JamesDSP's own convolver switch for the pass about to run: on only when the convolver is
// wanted and the BMW chain didn't already run it on this buffer.
void syncCoreConvolver(const JamesDspWrapper* wrapper, JamesDSPLib* dsp, bool ranInChain)
{
    const int on = wrapper->convolverWanted.load() && !ranInChain && dsp->conv.process ? 1 : 0;
    if(dsp->convolverEnabled != on)
    {
        jdsp_lock(dsp);
        dsp->convolverEnabled = on;
        jdsp_unlock(dsp);
    }
}
} // namespace

#undef Java_app_siphondsp_interop_JamesDspWrapper_alloc
#undef Java_app_siphondsp_interop_JamesDspWrapper_free
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt16
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt32
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt24Packed
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt8U24
#undef Java_app_siphondsp_interop_JamesDspWrapper_processFloat
#undef NSEEL_code_getcodeerror
#undef to_string
#undef LiveProgEnable

#ifdef min
#undef min
#endif
#ifdef max
#undef max
#endif

static void destroyPartialWrapper(JNIEnv* env, JamesDspWrapper* wrapper)
{
    if(wrapper == nullptr)
        return;
    delete static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    wrapper->nativeBmwDsp = nullptr;
    if(env != nullptr && wrapper->callbackInterface != nullptr)
        env->DeleteGlobalRef(wrapper->callbackInterface);
    delete wrapper;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_alloc(JNIEnv* env, jobject, jobject callback)
{
    if(env == nullptr || callback == nullptr)
        return 0;

    auto* wrapper = new(std::nothrow) JamesDspWrapper();
    if(wrapper == nullptr)
        return 0;

    wrapper->dsp = nullptr;
    wrapper->nativeBmwDsp = new(std::nothrow) NativeBmwDspProcessor();
    wrapper->env = env;
    wrapper->callbackInterface = env->NewGlobalRef(callback);
    if(wrapper->nativeBmwDsp == nullptr || wrapper->callbackInterface == nullptr)
    {
        destroyPartialWrapper(env, wrapper);
        return 0;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    if(callbackClass == nullptr)
    {
        destroyPartialWrapper(env, wrapper);
        return 0;
    }

    wrapper->callbackOnLiveprogOutput = env->GetMethodID(callbackClass, "onLiveprogOutput", "(Ljava/lang/String;)V");
    wrapper->callbackOnLiveprogExec = env->GetMethodID(callbackClass, "onLiveprogExec", "(Ljava/lang/String;)V");
    wrapper->callbackOnLiveprogResult = env->GetMethodID(callbackClass, "onLiveprogResult", "(ILjava/lang/String;Ljava/lang/String;)V");
    wrapper->callbackOnVdcParseError = env->GetMethodID(callbackClass, "onVdcParseError", "()V");
    env->DeleteLocalRef(callbackClass);

    if(wrapper->callbackOnLiveprogOutput == nullptr ||
       wrapper->callbackOnLiveprogExec == nullptr ||
       wrapper->callbackOnLiveprogResult == nullptr ||
       wrapper->callbackOnVdcParseError == nullptr)
    {
        destroyPartialWrapper(env, wrapper);
        return 0;
    }

    auto* dsp = static_cast<JamesDSPLib*>(std::malloc(sizeof(JamesDSPLib)));
    if(dsp == nullptr)
    {
        destroyPartialWrapper(env, wrapper);
        return 0;
    }
    std::memset(dsp, 0, sizeof(JamesDSPLib));

    JamesDSPGlobalMemoryAllocation();
    JamesDSPInit(dsp, 128, 48000);
    if(!JamesDSPGetMutexStatus(dsp))
    {
        JamesDSPFree(dsp);
        std::free(dsp);
        JamesDSPGlobalMemoryDeallocation();
        destroyPartialWrapper(env, wrapper);
        return 0;
    }

    wrapper->dsp = dsp;
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_free(JNIEnv* env, jobject, jlong self)
{
    DECLARE_DSP_V
    setStdOutHandler(nullptr, nullptr);
    JamesDSPFree(dsp);
    std::free(dsp);
    wrapper->dsp = nullptr;
    delete static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    wrapper->nativeBmwDsp = nullptr;
    JamesDSPGlobalMemoryDeallocation();
    if(env != nullptr && wrapper->callbackInterface != nullptr)
        env->DeleteGlobalRef(wrapper->callbackInterface);
    delete wrapper;
}

static jsize validatedStereoSamples(jsize inputLength, jsize outputLength, jint offset, jint size)
{
    if(offset < 0)
        offset = 0;
    if(offset >= inputLength || outputLength <= 0)
        return 0;

    const jsize availableInput = inputLength - offset;
    const jsize requested = size < 0 ? availableInput : size;
    if(requested <= 0)
        return 0;

    jsize samples = std::min(availableInput, std::min(outputLength, requested));
    return samples & ~static_cast<jsize>(1);
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_processInt16(JNIEnv* env, jobject, jlong self,
                                                         jshortArray inputObj, jshortArray outputObj,
                                                         jint offset, jint size)
{
    DECLARE_DSP_V
    if(env == nullptr || inputObj == nullptr || outputObj == nullptr)
        return;

    const jsize samples = validatedStereoSamples(env->GetArrayLength(inputObj),
                                                  env->GetArrayLength(outputObj), offset, size);
    if(samples <= 0)
        return;

    jshort* input = env->GetShortArrayElements(inputObj, nullptr);
    jshort* output = env->GetShortArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseShortArrayElements(outputObj, output, 0);
        return;
    }

    const jint safeOffset = std::max(offset, 0);
    {
        // Denormals flushed for the processing only, not the JNI calls around it.
        const ScopedFlushToZero flushToZero;
        noteBlockSize(wrapper, dsp, static_cast<std::size_t>(samples / 2));
        auto* bmw = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
        ConvolverPass pass{dsp, wrapper->convolverWanted.load(), false, preparedFrames(wrapper)};
        const int16_t* processed = bmw != nullptr
            ? bmw->process(reinterpret_cast<int16_t*>(input + safeOffset), static_cast<std::size_t>(samples),
                           convolverHook(dsp), &pass)
            : reinterpret_cast<int16_t*>(input + safeOffset);
        syncCoreConvolver(wrapper, dsp, pass.ran);
        runCorePass(wrapper, dsp, dsp->processInt16Multiplexd, const_cast<int16_t*>(processed),
                    reinterpret_cast<int16_t*>(output), static_cast<std::size_t>(samples / 2), 2);
    }
    env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseShortArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_processInt32(JNIEnv* env, jobject, jlong self,
                                                         jintArray inputObj, jintArray outputObj,
                                                         jint offset, jint size)
{
    DECLARE_DSP_V
    if(env == nullptr || inputObj == nullptr || outputObj == nullptr)
        return;

    const jsize samples = validatedStereoSamples(env->GetArrayLength(inputObj),
                                                  env->GetArrayLength(outputObj), offset, size);
    if(samples <= 0)
        return;

    jint* input = env->GetIntArrayElements(inputObj, nullptr);
    jint* output = env->GetIntArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseIntArrayElements(outputObj, output, 0);
        return;
    }

    const jint safeOffset = std::max(offset, 0);
    {
        // Denormals flushed for the processing only, not the JNI calls around it.
        const ScopedFlushToZero flushToZero;
        noteBlockSize(wrapper, dsp, static_cast<std::size_t>(samples / 2));
        auto* bmw = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
        ConvolverPass pass{dsp, wrapper->convolverWanted.load(), false, preparedFrames(wrapper)};
        const int32_t* processed = bmw != nullptr
            ? bmw->process(reinterpret_cast<int32_t*>(input + safeOffset), static_cast<std::size_t>(samples),
                           convolverHook(dsp), &pass)
            : reinterpret_cast<int32_t*>(input + safeOffset);
        syncCoreConvolver(wrapper, dsp, pass.ran);
        runCorePass(wrapper, dsp, dsp->processInt32Multiplexd, const_cast<int32_t*>(processed),
                    reinterpret_cast<int32_t*>(output), static_cast<std::size_t>(samples / 2), 2);
    }
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_processFloat(JNIEnv* env, jobject, jlong self,
                                                         jfloatArray inputObj, jfloatArray outputObj,
                                                         jint offset, jint size)
{
    DECLARE_DSP_V
    if(env == nullptr || inputObj == nullptr || outputObj == nullptr)
        return;

    const jsize samples = validatedStereoSamples(env->GetArrayLength(inputObj),
                                                  env->GetArrayLength(outputObj), offset, size);
    if(samples <= 0)
        return;

    jfloat* input = env->GetFloatArrayElements(inputObj, nullptr);
    jfloat* output = env->GetFloatArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseFloatArrayElements(outputObj, output, 0);
        return;
    }

    const jint safeOffset = std::max(offset, 0);
    {
        // Denormals flushed for the processing only, not the JNI calls around it.
        const ScopedFlushToZero flushToZero;
        noteBlockSize(wrapper, dsp, static_cast<std::size_t>(samples / 2));
        auto* bmw = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
        ConvolverPass pass{dsp, wrapper->convolverWanted.load(), false, preparedFrames(wrapper)};
        const float* processed = bmw != nullptr
            ? bmw->process(input + safeOffset, static_cast<std::size_t>(samples), convolverHook(dsp), &pass)
            : input + safeOffset;
        syncCoreConvolver(wrapper, dsp, pass.ran);
        runCorePass(wrapper, dsp, dsp->processFloatMultiplexd, const_cast<float*>(processed),
                    static_cast<float*>(output), static_cast<std::size_t>(samples / 2), 2);
    }
    env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_processInt24Packed(JNIEnv* env, jobject, jlong self,
                                                               jbooleanArray inputObj)
{
    DECLARE_DSP(inputObj)
    if(env == nullptr || inputObj == nullptr)
        return inputObj;

    const jsize byteCount = env->GetArrayLength(inputObj);
    if(byteCount <= 0 || byteCount % 6 != 0)
        return inputObj;

    jbooleanArray outputObj = env->NewBooleanArray(byteCount);
    if(outputObj == nullptr)
        return inputObj;

    jboolean* input = env->GetBooleanArrayElements(inputObj, nullptr);
    jboolean* output = env->GetBooleanArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseBooleanArrayElements(outputObj, output, 0);
        env->DeleteLocalRef(outputObj);
        return inputObj;
    }

    // Not through the BMW chain, so JamesDSP's own pass runs the convolver.
    {
        // Denormals flushed for the processing only, not the JNI calls around it.
        const ScopedFlushToZero flushToZero;
        noteBlockSize(wrapper, dsp, static_cast<std::size_t>(byteCount / 6));
        syncCoreConvolver(wrapper, dsp, false);
        runCorePass(wrapper, dsp, dsp->processInt24PackedMultiplexd, reinterpret_cast<uint8_t*>(input),
                    reinterpret_cast<uint8_t*>(output), static_cast<std::size_t>(byteCount / 6), 6);
    }
    env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseBooleanArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_processInt8U24(JNIEnv* env, jobject, jlong self,
                                                           jintArray inputObj)
{
    DECLARE_DSP(inputObj)
    if(env == nullptr || inputObj == nullptr)
        return inputObj;

    const jsize sampleCount = env->GetArrayLength(inputObj);
    if(sampleCount <= 0 || (sampleCount & 1) != 0)
        return inputObj;

    jintArray outputObj = env->NewIntArray(sampleCount);
    if(outputObj == nullptr)
        return inputObj;

    jint* input = env->GetIntArrayElements(inputObj, nullptr);
    jint* output = env->GetIntArrayElements(outputObj, nullptr);
    if(input == nullptr || output == nullptr)
    {
        if(input != nullptr) env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
        if(output != nullptr) env->ReleaseIntArrayElements(outputObj, output, 0);
        env->DeleteLocalRef(outputObj);
        return inputObj;
    }

    // Not through the BMW chain, so JamesDSP's own pass runs the convolver.
    {
        // Denormals flushed for the processing only, not the JNI calls around it.
        const ScopedFlushToZero flushToZero;
        noteBlockSize(wrapper, dsp, static_cast<std::size_t>(sampleCount / 2));
        syncCoreConvolver(wrapper, dsp, false);
        runCorePass(wrapper, dsp, dsp->processInt8_24Multiplexd, reinterpret_cast<int32_t*>(input),
                    reinterpret_cast<int32_t*>(output), static_cast<std::size_t>(sampleCount / 2), 2);
    }
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}

// Fills [out] with the audio-path counters (see RealtimeCounters), in this order -- matching
// JamesDspWrapper.REALTIME_*: irLoads, blockSizeChanges, convolverRebuilds, workBufferReallocs,
// lastBlockFrames, preparedFrames. Atomics only, so it takes no lock. False for a gone handle or
// a short array.
extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_getRealtimeCounters(JNIEnv* env, jobject, jlong self,
                                                                jlongArray out)
{
    DECLARE_WRAPPER_B
    if(env == nullptr || out == nullptr)
        return JNI_FALSE;
    const auto& counters = wrapper->realtime;
    const jlong values[] = {
        counters.irLoads.load(std::memory_order_relaxed),
        counters.blockSizeChanges.load(std::memory_order_relaxed),
        counters.convolverRebuilds.load(std::memory_order_relaxed),
        counters.workBufferReallocs.load(std::memory_order_relaxed),
        counters.lastBlockFrames.load(std::memory_order_relaxed),
        counters.preparedFrames.load(std::memory_order_relaxed),
    };
    constexpr jsize count = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    if(env->GetArrayLength(out) < count)
        return JNI_FALSE;
    env->SetLongArrayRegion(out, 0, count, values);
    return JNI_TRUE;
}

// Sizes everything the audio path works in for blocks of up to `frames` frames, so process*()
// never reallocates or rebuilds on the audio thread: JamesDSP's work buffers, every FFT convolver
// (partitioned for this block size), and the BMW chain's own workspaces. Also retires JamesDSP's
// benchmark-ready first-stage process function, which would otherwise refresh every convolver
// on the first buffer after benchmarks load. Rebuilding allocates -- the engine calls this under
// its exclusive lock, when the pipeline starts, never mid-stream.
extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_prepareBlockSize(JNIEnv*, jobject, jlong self, jint frames)
{
    DECLARE_DSP_B
    if(frames <= 0)
        return JNI_FALSE;
    const auto n = static_cast<std::size_t>(frames);
    if(dsp->blockSizeMax < n)
        JamesDSPReallocateBlock(dsp, n);
    const bool sizeChanged = dsp->blockSize != n;
    const bool benchmarkRefreshPending = benchmarkCompletionFlag == 1 && dsp->processInternal != JamesDSPProcess;
    dsp->blockSize = n;
    if(sizeChanged || benchmarkRefreshPending)
    {
        if(benchmarkEnable)
            JamesDSPRefreshConvolutions(dsp, 1);
        else if(dsp->impulseResponseStorage.impulseResponse)
            // Without benchmarks JamesDSP's refresh does nothing, which would leave an impulse
            // response loaded before this partitioned for the old block size (128 frames at
            // start-up: many times the work per sample). Rebuild that one convolver here.
            Convolver1DLoadImpulseResponse(dsp, dsp->impulseResponseStorage.impulseResponse,
                                           dsp->impulseResponseStorage.impChannels,
                                           dsp->impulseResponseStorage.impulseLengthActual, 0);
    }
    // The first-stage function only adds that refresh check; benchmarks loaded later take effect
    // at the next prepare or impulse-response load instead of on the audio thread.
    dsp->processInternal = JamesDSPProcess;
    if(auto* bmw = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp))
        bmw->reserveBlock(n * 2);
    wrapper->realtime.preparedFrames.store(static_cast<std::int64_t>(n), std::memory_order_relaxed);
    return JNI_TRUE;
}
