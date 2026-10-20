#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>
#include <atomic>
#include <cstdint>

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

// Audio-path counters for the diagnostics screen (JamesDspWrapper.getRealtimeCounters, which
// lists the layout). Bumped with relaxed increments by whichever thread does the work and read
// lock-free, so counting costs the audio thread one atomic add per event.
struct RealtimeCounters
{
    // setConvolver() impulse-response loads (control thread).
    std::atomic<std::int64_t> irLoads{0};
    // Buffers whose block size differed from the one JamesDSP last ran at (audio thread).
    std::atomic<std::int64_t> blockSizeChanges{0};
    // Of those, the ones that rebuilt JamesDSP's FFT convolvers on the audio thread: the
    // impulse response's, and the crossfeed / EQ ones a refresh rebuilds with it.
    std::atomic<std::int64_t> convolverRebuilds{0};
    // Buffers larger than JamesDSP's work buffers, which reallocated them (audio thread).
    std::atomic<std::int64_t> workBufferReallocs{0};
    // Frames in the last processed buffer.
    std::atomic<std::int64_t> lastBlockFrames{0};
    // Block size JamesDSP's work buffers and FFT convolvers were prepared for (prepareBlockSize,
    // under the engine's exclusive lock); 0 until then. Once set, the audio thread never
    // reallocates or rebuilds them.
    std::atomic<std::int64_t> preparedFrames{0};
};

typedef struct
{
    void* dsp;
    void* nativeBmwDsp;
    // Native-side acknowledgement of the last completely accepted logical configuration.
    // These live with the native handle, not Kotlin/UI state, and are advanced only after the
    // synchronous NativeBmwDspProcessor configure call has returned success.
    std::atomic<std::int64_t> nativeBmwDspRevision{0};
    std::atomic<std::int64_t> nativeBmwPeqRevision{0};
    // Whether the user has the convolver on with an impulse response loaded (setConvolver). Where
    // it runs is decided per buffer by the process paths: inside the BMW chain before its master
    // limiter, or JamesDSP's own pass, whose switch only they set.
    std::atomic<bool> convolverWanted{false};
    RealtimeCounters realtime;
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
