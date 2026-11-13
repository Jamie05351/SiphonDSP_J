// Audio-thread placement: pins the calling thread to the device's fastest CPU cluster, so the
// real-time loop never lands on a slow core the governor keeps at a low clock. JNI only (not in
// the host tests): app.siphondsp.interop.NativeDsp.pinCurrentThreadToFastCores().
//
// The fast cluster is every CPU whose hardware maximum clock (cpuinfo_max_freq) is the highest on
// the device. The thread is pinned to those of them it is allowed on (its current affinity, which
// the platform's cpuset already limits); when there is no faster cluster, none of it is allowed,
// or the kernel refuses, the affinity is left exactly as it was (the automatic fallback) and the
// result says why.
#include <jni.h>

#include <sched.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>

namespace {
// Result codes, matching AudioThreadTuning.PIN_* (Kotlin).
constexpr jlong kPinned = 0;
constexpr jlong kNoFasterCluster = 1;
constexpr jlong kFastCoresNotAllowed = 2;
constexpr jlong kAffinityRefused = 3;
constexpr jlong kUnreadable = 4;

long maxFreqKhz(int cpu) {
    char path[96];
    std::snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* f = std::fopen(path, "r");
    if (f == nullptr) {
        return -1;
    }
    long khz = -1;
    if (std::fscanf(f, "%ld", &khz) != 1) {
        khz = -1;
    }
    std::fclose(f);
    return khz;
}

std::uint64_t maskOf(const cpu_set_t& set, int cpus) {
    std::uint64_t mask = 0;
    for (int c = 0; c < cpus && c < 64; ++c) {
        if (CPU_ISSET(c, &set)) {
            mask |= std::uint64_t{1} << c;
        }
    }
    return mask;
}
}  // namespace

// [result, allowed mask (before), pinned mask (after), fast-cluster mask, fast-cluster max kHz].
extern "C" JNIEXPORT jlongArray JNICALL
Java_app_siphondsp_interop_NativeDsp_pinCurrentThreadToFastCores(JNIEnv* env, jobject) {
    jlong out[5] = {kUnreadable, 0, 0, 0, 0};
    const int cpus = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (cpus > 0 && sched_getaffinity(0, sizeof allowed, &allowed) == 0) {
        out[1] = static_cast<jlong>(maskOf(allowed, cpus));
        out[2] = out[1];
        long best = -1, slowest = -1;
        for (int c = 0; c < cpus && c < 64; ++c) {
            const long khz = maxFreqKhz(c);
            if (khz <= 0) {
                continue;
            }
            if (khz > best) {
                best = khz;
            }
            if (slowest < 0 || khz < slowest) {
                slowest = khz;
            }
        }
        if (best > 0) {
            cpu_set_t fast, target;
            CPU_ZERO(&fast);
            CPU_ZERO(&target);
            for (int c = 0; c < cpus && c < 64; ++c) {
                if (maxFreqKhz(c) == best) {
                    CPU_SET(c, &fast);
                    if (CPU_ISSET(c, &allowed)) {
                        CPU_SET(c, &target);
                    }
                }
            }
            out[3] = static_cast<jlong>(maskOf(fast, cpus));
            out[4] = best;
            if (best == slowest) {
                out[0] = kNoFasterCluster;
            } else if (CPU_COUNT(&target) == 0) {
                out[0] = kFastCoresNotAllowed;
            } else if (sched_setaffinity(0, sizeof target, &target) != 0) {
                out[0] = kAffinityRefused;
            } else {
                out[0] = kPinned;
                out[2] = static_cast<jlong>(maskOf(target, cpus));
            }
        }
    }
    if (env == nullptr) {
        return nullptr;
    }
    jlongArray result = env->NewLongArray(5);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 5, out);
    }
    return result;
}
