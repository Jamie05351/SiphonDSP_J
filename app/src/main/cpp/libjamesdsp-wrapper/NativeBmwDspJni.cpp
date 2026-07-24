#include <jni.h>
#include "NativeBmwDspProcessor.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_app_siphondsp_interop_JamesDspWrapper_configureNativeBmwDsp(
    JNIEnv* env,
    jobject,
    jfloatArray valuesObj)
{
    if(env == nullptr || valuesObj == nullptr)
        return false;
    auto* processor = NativeBmwDspProcessor::latest();
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
