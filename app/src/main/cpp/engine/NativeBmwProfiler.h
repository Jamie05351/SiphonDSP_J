#ifndef SIPHONDSP_NATIVE_BMW_PROFILER_H
#define SIPHONDSP_NATIVE_BMW_PROFILER_H

// Per-stage timing of the processing chain, for Native DSP Truth.
//
// The chain runs each stage over a whole chunk (kChunkFrames) before the next (see
// NativeBmwDspProcessor::processChunk()), so a stage costs one clock read per chunk to time,
// not one per frame: lap(stage) charges the time since the previous lap to that stage. Off (the
// default) every call is one predictable branch. It is switched on only while the screen that
// shows it is open: requested from any thread, taken up by the audio thread at the start of a
// buffer, which then starts the counts from zero.
//
// Published per buffer as relaxed atomics, read lock-free; a reader may see one buffer half
// applied, which for running totals is harmless.

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <ctime>
#if defined(__linux__)
#include <sched.h>
#endif

namespace NativeBmwDsp {

class StageProfiler {
public:
    // In chain order. Keep in step with NativeDspProfile.STAGE_NAMES (Kotlin).
    enum Stage : std::uint8_t {
        Input,           // the measurement generator and the capture's raw-input copy
        DcBlocker,
        MonoBass,
        VirtualBass,
        PreEq,           // the full-range PEQ bank, its preamp and the headroom gain
        Mbc,
        VirtualCentre,
        Routing,
        Crossovers,      // every band's crossover filters (and subsonic)
        BandEq,
        AllPass,
        DelayGain,       // band alignment delays and driver gains
        BusLimiters,
        BandSum,         // per-output polarity/mute, the sum, the crossover-bypass crossfade
        Tilt,
        OutputGains,     // post gain, channel mute, measurement-mute bus
        Convolver,
        MasterLimiter,
        VolumeDeepBass,  // master volume, Deep bass, the hardware swap
        OutputGuard,     // fault check, output fade, guard clamp, capture tap
        kStageCount
    };
    // Layout of read(): kHeader values, then [total ns, max ns per buffer] per stage.
    enum : std::size_t {
        kEnabled = 0,
        kBuffers,
        kFrames,
        kWallNs,      // total wall time inside process(), all buffers
        kCpuNs,       // total thread CPU time inside process()
        kMaxWallNs,   // the slowest buffer
        kMaxCpuNs,
        kLastCpu,     // the core the last buffer ran on (-1 unknown)
        kHeader,
        kSize = kHeader + 2 * kStageCount,
    };

    // Any thread.
    void request(bool on) { requested_.store(on, std::memory_order_relaxed); }

    // Audio thread, start of a buffer: takes up a request, then starts its clocks.
    void beginBuffer() {
        const bool want = requested_.load(std::memory_order_relaxed);
        if (want != on_) {
            on_ = want;
            if (on_) {
                reset();
            }
            published_[kEnabled].store(on_ ? 1 : 0, std::memory_order_relaxed);
        }
        if (!on_) {
            return;
        }
        bufferNs_.fill(0);
        bufferStartNs_ = nowNs();
        bufferStartCpuNs_ = cpuNs();
        lastNs_ = bufferStartNs_;
    }
    // Audio thread: a chunk starts (time since the last lap is not charged to any stage).
    void startChunk() {
        if (on_) {
            lastNs_ = nowNs();
        }
    }
    void lap(Stage stage) {
        if (!on_) {
            return;
        }
        const std::int64_t now = nowNs();
        bufferNs_[stage] += now - lastNs_;
        lastNs_ = now;
    }
    // Audio thread, end of a buffer of `frames` frames.
    void endBuffer(std::size_t frames) {
        if (!on_) {
            return;
        }
        const std::int64_t wall = nowNs() - bufferStartNs_;
        const std::int64_t cpu = cpuNs() - bufferStartCpuNs_;
        add(kBuffers, 1);
        add(kFrames, static_cast<std::int64_t>(frames));
        add(kWallNs, wall);
        add(kCpuNs, cpu);
        raise(kMaxWallNs, wall);
        raise(kMaxCpuNs, cpu);
#if defined(__linux__)
        published_[kLastCpu].store(sched_getcpu(), std::memory_order_relaxed);
#endif
        for (std::size_t s = 0; s < kStageCount; ++s) {
            add(kHeader + 2 * s, bufferNs_[s]);
            raise(kHeader + 2 * s + 1, bufferNs_[s]);
        }
    }

    // Any thread, lock-free: kSize values (fewer if count is smaller).
    void read(double* out, std::size_t count) const {
        for (std::size_t i = 0; i < count && i < kSize; ++i) {
            out[i] = static_cast<double>(published_[i].load(std::memory_order_relaxed));
        }
    }

private:
    static std::int64_t nowNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
    }
    static std::int64_t cpuNs() {
        timespec ts{};
        if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) {
            return 0;
        }
        return static_cast<std::int64_t>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
    }
    void reset() {
        for (std::size_t i = 0; i < kSize; ++i) {
            published_[i].store(0, std::memory_order_relaxed);
        }
        published_[kLastCpu].store(-1, std::memory_order_relaxed);
    }
    void add(std::size_t i, std::int64_t v) {
        published_[i].store(published_[i].load(std::memory_order_relaxed) + v, std::memory_order_relaxed);
    }
    void raise(std::size_t i, std::int64_t v) {
        if (v > published_[i].load(std::memory_order_relaxed)) {
            published_[i].store(v, std::memory_order_relaxed);
        }
    }

    std::atomic<bool> requested_{false};
    bool on_ = false;
    std::int64_t lastNs_ = 0, bufferStartNs_ = 0, bufferStartCpuNs_ = 0;
    std::array<std::int64_t, kStageCount> bufferNs_{};
    std::array<std::atomic<std::int64_t>, kSize> published_{};
};

}  // namespace NativeBmwDsp

#endif
