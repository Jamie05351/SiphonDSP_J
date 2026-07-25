#include <jni.h>
#include "NativeBmwDspProcessor.h"
#include "JamesDspWrapper.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_configureNativeBmwDsp(
    JNIEnv* env,
    jobject,
    jlong self,
    jfloatArray valuesObj)
{
    if(env == nullptr || self == 0 || valuesObj == nullptr)
        return false;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr)
        return false;
    const jsize count = env->GetArrayLength(valuesObj);
    if(count != static_cast<jsize>(NativeBmwDspProcessor::kConfigSize))
        return false;
    jfloat* values = env->GetFloatArrayElements(valuesObj, nullptr);
    if(values == nullptr)
        return false;
    const bool result = processor->configure(values, static_cast<std::size_t>(count));
    env->ReleaseFloatArrayElements(valuesObj, values, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_configureNativeBmwBandPeq(
    JNIEnv* env,
    jobject,
    jlong self,
    jdoubleArray lowValuesObj,
    jdoubleArray midValuesObj)
{
    if(env == nullptr || self == 0 || lowValuesObj == nullptr || midValuesObj == nullptr)
        return false;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr)
        return false;

    const jsize lowCount = env->GetArrayLength(lowValuesObj);
    const jsize midCount = env->GetArrayLength(midValuesObj);
    jdouble* lowValues = lowCount > 0 ? env->GetDoubleArrayElements(lowValuesObj, nullptr) : nullptr;
    if(lowCount > 0 && lowValues == nullptr)
        return false;
    jdouble* midValues = midCount > 0 ? env->GetDoubleArrayElements(midValuesObj, nullptr) : nullptr;
    if(midCount > 0 && midValues == nullptr) {
        if(lowValues != nullptr) env->ReleaseDoubleArrayElements(lowValuesObj, lowValues, JNI_ABORT);
        return false;
    }

    const bool result = processor->configureBandPeq(
        lowValues,
        static_cast<std::size_t>(lowCount),
        midValues,
        static_cast<std::size_t>(midCount));
    if(lowValues != nullptr) env->ReleaseDoubleArrayElements(lowValuesObj, lowValues, JNI_ABORT);
    if(midValues != nullptr) env->ReleaseDoubleArrayElements(midValuesObj, midValues, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_setNativeBmwDspSampleRate(
    JNIEnv* env,
    jobject,
    jlong self,
    jfloat sampleRate)
{
    if(env == nullptr || self == 0)
        return;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor != nullptr)
        processor->setSampleRate(sampleRate);
}
