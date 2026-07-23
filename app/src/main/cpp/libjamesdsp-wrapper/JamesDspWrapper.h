#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>

/**
 * JNIEnv is thread-local and must never be cached and reused by another thread.
 * Keep the process-wide JavaVM instead and resolve/attach the current callback
 * thread whenever JNI is needed.
 */
struct JniEnvironmentHandle
{
    JavaVM* vm = nullptr;

    JniEnvironmentHandle& operator=(JNIEnv* env)
    {
        vm = nullptr;
        if(env != nullptr)
            env->GetJavaVM(&vm);
        return *this;
    }

    JNIEnv* operator->() const
    {
        if(vm == nullptr)
            return nullptr;

        JNIEnv* env = nullptr;
        const jint status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if(status == JNI_EDETACHED)
        {
            if(vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
                return nullptr;
        }
        else if(status != JNI_OK)
        {
            return nullptr;
        }
        return env;
    }
};

typedef struct
{
    void* dsp;
    void* nativePeq;
    JniEnvironmentHandle env;
    jobject callbackInterface;
    jmethodID callbackOnLiveprogOutput;
    jmethodID callbackOnLiveprogExec;
    jmethodID callbackOnLiveprogResult;
    jmethodID callbackOnVdcParseError;
} JamesDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
