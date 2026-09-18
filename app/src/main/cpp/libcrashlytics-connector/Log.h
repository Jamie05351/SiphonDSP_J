//
// Created by tim on 08.07.22.
// Do NOT include this file in a header
//
#ifndef ROOTLESSJAMESDSP_LOG_H
#define ROOTLESSJAMESDSP_LOG_H

#ifndef TAG
#define TAG "Global_JNI"
#endif

#ifndef NO_CRASHLYTICS
#include "crashlytics.h"
#endif

#include <android/log.h>
#include <memory>
#include <string>
#include <stdexcept>

namespace log {
    void toCrashlytics(const char* level, const char* tag, const char* fmt, ...);
}

// The do/while(0) wrapper makes each macro a single statement, so an unbraced
// `if (cond) LOGX(...);` call site gates both the log call and the Crashlytics
// upload -- without it, only the first statement was conditional and the
// Crashlytics call fired unconditionally on every invocation.
#define LOGE(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); \
    log::toCrashlytics("E", TAG, __VA_ARGS__); \
} while (0)
#define LOGD(...) do { \
    __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__); \
    log::toCrashlytics("D", TAG, __VA_ARGS__); \
} while (0)
#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); \
    log::toCrashlytics("I", TAG, __VA_ARGS__); \
} while (0)
#define LOGW(...) do { \
    __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__); \
    log::toCrashlytics("W", TAG, __VA_ARGS__); \
} while (0)
#define LOGF(...) do { \
    __android_log_print(ANDROID_LOG_FATAL, TAG, __VA_ARGS__); \
    log::toCrashlytics("F", TAG, __VA_ARGS__); \
} while (0)
#define LOGV(...) do { \
    __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__); \
    log::toCrashlytics("V", TAG, __VA_ARGS__); \
} while (0)

#endif //ROOTLESSJAMESDSP_LOG_H
