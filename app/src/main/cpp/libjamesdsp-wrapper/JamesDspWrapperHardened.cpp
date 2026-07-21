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

// The existing implementation is included once through this hardened wrapper.
// These narrow substitutions preserve its JNI ABI while fixing the unsafe
// error paths without duplicating the 700+ line wrapper source.
#define NSEEL_code_getcodeerror(vm) safeNseelCodeError(vm)
#define to_string stable_to_string
#define LiveProgEnable(dsp) ((ret > 0) ? (::LiveProgEnable)(dsp) : LiveProgDisable(dsp))

#include "JamesDspWrapper.cpp"
