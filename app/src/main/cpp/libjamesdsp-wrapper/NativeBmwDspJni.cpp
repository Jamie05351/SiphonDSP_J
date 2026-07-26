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
Java_app_siphondsp_interop_JamesDspWrapper_configureNativeBmwPeq(
    JNIEnv* env, jobject, jlong self, jboolean enabled, jfloat preampDb,
    jdoubleArray fullObj, jdoubleArray lowObj, jdoubleArray midObj)
{
    if(env == nullptr || self == 0 || fullObj == nullptr || lowObj == nullptr || midObj == nullptr)
        return false;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr)
        return false;
    const jsize fullCount=env->GetArrayLength(fullObj),lowCount=env->GetArrayLength(lowObj),midCount=env->GetArrayLength(midObj);
    jdouble* full=env->GetDoubleArrayElements(fullObj,nullptr);
    jdouble* low=env->GetDoubleArrayElements(lowObj,nullptr);
    jdouble* mid=env->GetDoubleArrayElements(midObj,nullptr);
    if(full==nullptr||low==nullptr||mid==nullptr){
        if(full!=nullptr)env->ReleaseDoubleArrayElements(fullObj,full,JNI_ABORT);
        if(low!=nullptr)env->ReleaseDoubleArrayElements(lowObj,low,JNI_ABORT);
        if(mid!=nullptr)env->ReleaseDoubleArrayElements(midObj,mid,JNI_ABORT);
        return false;
    }
    const bool result=processor->configurePeq(enabled==JNI_TRUE,preampDb,full,static_cast<std::size_t>(fullCount),low,static_cast<std::size_t>(lowCount),mid,static_cast<std::size_t>(midCount));
    env->ReleaseDoubleArrayElements(fullObj,full,JNI_ABORT);
    env->ReleaseDoubleArrayElements(lowObj,low,JNI_ABORT);
    env->ReleaseDoubleArrayElements(midObj,mid,JNI_ABORT);
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
