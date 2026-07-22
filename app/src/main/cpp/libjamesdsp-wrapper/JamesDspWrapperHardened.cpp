#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>

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

// Compile the legacy implementations under private names, then expose
// validated replacements below without changing the large upstream file.
#define Java_app_siphondsp_interop_JamesDspWrapper_alloc Java_app_siphondsp_interop_JamesDspWrapper_alloc_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt16 Java_app_siphondsp_interop_JamesDspWrapper_processInt16_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt32 Java_app_siphondsp_interop_JamesDspWrapper_processInt32_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt24Packed Java_app_siphondsp_interop_JamesDspWrapper_processInt24Packed_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processInt8U24 Java_app_siphondsp_interop_JamesDspWrapper_processInt8U24_legacy_unsafe
#define Java_app_siphondsp_interop_JamesDspWrapper_processFloat Java_app_siphondsp_interop_JamesDspWrapper_processFloat_legacy_unsafe

#define NSEEL_code_getcodeerror(vm) safeNseelCodeError(vm)
#define to_string stable_to_string
#define LiveProgEnable(dsp) ((ret > 0) ? (::LiveProgEnable)(dsp) : LiveProgDisable(dsp))

#include "JamesDspWrapper.cpp"

#undef Java_app_siphondsp_interop_JamesDspWrapper_alloc
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt16
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt32
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt24Packed
#undef Java_app_siphondsp_interop_JamesDspWrapper_processInt8U24
#undef Java_app_siphondsp_interop_JamesDspWrapper_processFloat
#undef NSEEL_code_getcodeerror
#undef to_string
#undef LiveProgEnable

// EEL exposes C-style min/max macros; remove them before using the C++
// standard-library functions in the hardened JNI replacements below.
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
    wrapper->env = env;
    wrapper->callbackInterface = env->NewGlobalRef(callback);
    if(wrapper->callbackInterface == nullptr)
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
    dsp->processInt16Multiplexd(dsp, input + safeOffset, output, samples / 2);
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
    dsp->processInt32Multiplexd(dsp, input + safeOffset, output, samples / 2);
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
    dsp->processFloatMultiplexd(dsp, input + safeOffset, output, samples / 2);
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

    // Packed 24-bit stereo uses six bytes per frame, not two samples per frame.
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

    dsp->processInt8_24Multiplexd(dsp, input, output, sampleCount / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}
