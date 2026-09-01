#include <jni.h>
#include "NativeBmwDspProcessor.h"
#include "JamesDspWrapper.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_configureNativeBmwDsp(
    JNIEnv* env, jobject, jlong self, jfloatArray valuesObj)
{
    if(env == nullptr || self == 0 || valuesObj == nullptr) return false;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr) return false;
    const jsize count = env->GetArrayLength(valuesObj);
    if(count != static_cast<jsize>(NativeBmwDspProcessor::kConfigSize)) return false;
    jfloat* values = env->GetFloatArrayElements(valuesObj, nullptr);
    if(values == nullptr) return false;
    const bool result = processor->configure(values, static_cast<std::size_t>(count));
    env->ReleaseFloatArrayElements(valuesObj, values, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_configureNativeBmwPeq(
    JNIEnv* env, jobject, jlong self, jboolean enabled, jfloat preampDb,
    jdoubleArray fullObj, jdoubleArray lowObj, jdoubleArray midObj)
{
    if(env == nullptr || self == 0 || fullObj == nullptr || lowObj == nullptr || midObj == nullptr) return false;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr) return false;
    const jsize fullCount=env->GetArrayLength(fullObj),lowCount=env->GetArrayLength(lowObj),midCount=env->GetArrayLength(midObj);
    jdouble* full=env->GetDoubleArrayElements(fullObj,nullptr);jdouble* low=env->GetDoubleArrayElements(lowObj,nullptr);jdouble* mid=env->GetDoubleArrayElements(midObj,nullptr);
    if(full==nullptr||low==nullptr||mid==nullptr){if(full)env->ReleaseDoubleArrayElements(fullObj,full,JNI_ABORT);if(low)env->ReleaseDoubleArrayElements(lowObj,low,JNI_ABORT);if(mid)env->ReleaseDoubleArrayElements(midObj,mid,JNI_ABORT);return false;}
    const bool result=processor->configurePeq(enabled==JNI_TRUE,preampDb,full,fullCount,low,lowCount,mid,midCount);
    env->ReleaseDoubleArrayElements(fullObj,full,JNI_ABORT);env->ReleaseDoubleArrayElements(lowObj,low,JNI_ABORT);env->ReleaseDoubleArrayElements(midObj,mid,JNI_ABORT);return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_setNativeBmwDspSampleRate(JNIEnv* env,jobject,jlong self,jfloat sampleRate)
{
    if(env == nullptr || self == 0) return;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor != nullptr) processor->setSampleRate(sampleRate);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_getNativeBmwCompressorMeter(JNIEnv* env,jobject,jlong self)
{
    if(env == nullptr || self == 0) return nullptr;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr) return nullptr;
    float values[6];processor->readCompressorMeter(values,6);
    jfloatArray result=env->NewFloatArray(6);
    if(result != nullptr) env->SetFloatArrayRegion(result,0,6,values);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_getNativeBmwMbcMeter(JNIEnv* env,jobject,jlong self)
{
    if(env == nullptr || self == 0) return nullptr;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr) return nullptr;
    float values[12];processor->readMbcMeter(values,12);
    jfloatArray result=env->NewFloatArray(12);
    if(result != nullptr) env->SetFloatArrayRegion(result,0,12,values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_startNativeBmwCapture(JNIEnv* env,jobject,jlong self)
{
    if(env == nullptr || self == 0) return;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor != nullptr) processor->startCapture();
}

extern "C" JNIEXPORT void JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_stopNativeBmwCapture(JNIEnv* env,jobject,jlong self)
{
    if(env == nullptr || self == 0) return;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor != nullptr) processor->stopCapture();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_getNativeBmwCaptureFrameCount(JNIEnv* env,jobject,jlong self)
{
    if(env == nullptr || self == 0) return 0;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr) return 0;
    return static_cast<jlong>(processor->captureFrameCount());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_exportNativeBmwCaptureWav(
    JNIEnv* env, jobject, jlong self, jstring rawInPathObj, jstring outPathObj)
{
    if(env == nullptr || self == 0 || rawInPathObj == nullptr || outPathObj == nullptr) return nullptr;
    auto* wrapper = reinterpret_cast<JamesDspWrapper*>(self);
    auto* processor = static_cast<NativeBmwDspProcessor*>(wrapper->nativeBmwDsp);
    if(processor == nullptr) return nullptr;
    const char* rawInPath = env->GetStringUTFChars(rawInPathObj, nullptr);
    const char* outPath = env->GetStringUTFChars(outPathObj, nullptr);
    if(rawInPath == nullptr || outPath == nullptr) {
        if(rawInPath) env->ReleaseStringUTFChars(rawInPathObj, rawInPath);
        if(outPath) env->ReleaseStringUTFChars(outPathObj, outPath);
        return nullptr;
    }
    NativeBmwDspProcessor::CaptureExportResult result;
    const bool ok = processor->exportCaptureWav(rawInPath, outPath, result);
    env->ReleaseStringUTFChars(rawInPathObj, rawInPath);
    env->ReleaseStringUTFChars(outPathObj, outPath);
    if(!ok) return nullptr;
    const float values[3] = {result.peakInDb, result.peakOutDb, result.nullTestRmsDb};
    jfloatArray resultArray = env->NewFloatArray(3);
    if(resultArray != nullptr) env->SetFloatArrayRegion(resultArray, 0, 3, values);
    return resultArray;
}
