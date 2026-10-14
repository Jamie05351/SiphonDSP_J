#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>

#include "NativeBmwDspProcessor.h"

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
}

namespace {
// One buffer's convolver pass: whether it is wanted, and whether the BMW chain ran it.
struct ConvolverPass
{
    JamesDSPLib* dsp;
    bool wanted;
    bool ran;
};

// The convolver's place in the BMW chain: run by NativeBmwDspProcessor::process() just before its
// master limiter (see BlockHook), so the limiter catches whatever the impulse response adds and
// its channels are the logical L / R. The same block-size bookkeeping as JamesDSP's own entry
// points, then its convolver on JamesDSP's work buffers, under its lock as in its own pass.
void convolveBeforeMasterLimiter(void* context, float* x, std::size_t frames)
{
    auto& pass = *static_cast<ConvolverPass*>(context);
    pass.ran = true;
    if(!pass.wanted)
        return;
    JamesDSPLib* jdsp = pass.dsp;
    if(jdsp->blockSizeMax < frames)
        JamesDSPReallocateBlock(jdsp, frames);
    if(jdsp->blockSize != frames)
    {
        jdsp->blockSize = frames;
        JamesDSPRefreshConvolutions(jdsp, 1);
    }
    jdsp_lock(jdsp);
    if(jdsp->conv.process)
    {
        for(std::size_t i = 0; i < frames; ++i)
        {
            jdsp->tmpBuffer[0][i] = x[i << 1];
            jdsp->tmpBuffer[1][i] = x[(i << 1) + 1];
        }
        jdsp->conv.process(jdsp, frames);
        for(std::size_t i = 0; i < frames; ++i)
        {
            x[i << 1] = jdsp->tmpBuffer[0][i];
            x[(i << 1) + 1] = jdsp->tmpBuffer[1][i];
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
    auto* bmw = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    ConvolverPass pass{dsp, wrapper->convolverWanted.load(), false};
    const int16_t* processed = bmw != nullptr
        ? bmw->process(reinterpret_cast<int16_t*>(input + safeOffset), static_cast<std::size_t>(samples),
                       convolverHook(dsp), &pass)
        : reinterpret_cast<int16_t*>(input + safeOffset);
    syncCoreConvolver(wrapper, dsp, pass.ran);
    dsp->processInt16Multiplexd(dsp, const_cast<int16_t*>(processed), output, samples / 2);
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
    auto* bmw = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    ConvolverPass pass{dsp, wrapper->convolverWanted.load(), false};
    const int32_t* processed = bmw != nullptr
        ? bmw->process(reinterpret_cast<int32_t*>(input + safeOffset), static_cast<std::size_t>(samples),
                       convolverHook(dsp), &pass)
        : reinterpret_cast<int32_t*>(input + safeOffset);
    syncCoreConvolver(wrapper, dsp, pass.ran);
    dsp->processInt32Multiplexd(dsp, const_cast<int32_t*>(processed), output, samples / 2);
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
    auto* bmw = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    ConvolverPass pass{dsp, wrapper->convolverWanted.load(), false};
    const float* processed = bmw != nullptr
        ? bmw->process(input + safeOffset, static_cast<std::size_t>(samples), convolverHook(dsp), &pass)
        : input + safeOffset;
    syncCoreConvolver(wrapper, dsp, pass.ran);
    dsp->processFloatMultiplexd(dsp, const_cast<float*>(processed), output, samples / 2);
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
    syncCoreConvolver(wrapper, dsp, false);
    dsp->processInt24PackedMultiplexd(dsp, input, output, byteCount / 6);
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
    syncCoreConvolver(wrapper, dsp, false);
    dsp->processInt8_24Multiplexd(dsp, input, output, sampleCount / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}
