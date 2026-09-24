#ifndef SIPHONDSP_NATIVE_BMW_MEASUREMENT_H
#define SIPHONDSP_NATIVE_BMW_MEASUREMENT_H

// Measurement tooling: the measurement-mute bus brick-wall, the signal generator hookup
// (the generator itself is NativeBmwMeasurementGenerator.h), and the raw-input/output capture
// recorder. The capture take's WAV export lives in NativeBmwMeasurement.cpp as well.

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <vector>
#include "NativeBmwCrossovers.h"
#include "NativeBmwDspParams.h"
#include "NativeBmwFilters.h"
#include "NativeBmwMeasurementGenerator.h"
#include "NativeBmwRouting.h"

namespace NativeBmwDsp {

// Measurement mute values: 0 off, 1 isolate Mid, 2 isolate Low, 3 isolate High.
constexpr bool measurementMuteIsolates(int mode, NativeBmwRouting::Band band) {
    return (mode == 1 && band == NativeBmwRouting::Band::Mid) ||
           (mode == 2 && band == NativeBmwRouting::Band::Low) ||
           (mode == 3 && band == NativeBmwRouting::Band::High);
}

// Option A measurement-mute bus brick-wall. Inert unless measurement mute is on: the sections
// only run while active(), and are only (re)built by rebuild() on a DirtyMeasBus transition --
// never touched for the normal tuned output, so it adds no latency, no phase shift and no
// per-sample/coefficient cost when measurement mute is off. LR8 = 4 cascaded Butterworth
// Q=1/sqrt(2) sections (48 dB/oct), steeper than the LR4 crossovers; excluded-band phase is
// irrelevant (discarded).
class MeasurementBus {
public:
    static constexpr std::size_t kSections = 4;
    void rebuild(int mode, float stopbandOctaves,
                 const std::array<OutputConfig, NativeBmwRouting::kOutputCount>& configs,
                 float sampleRate);
    bool active() const {
        return active_;
    }
    // Only call while active().
    void process(float& left, float& right);

private:
    std::array<Biquad, kSections> busL_{}, busR_{};
    // Isolate-Mid's second (LPF) brick-wall above Mid's upper corner, per side: Mid Left and
    // Mid Right enable their upper corner independently, so each side follows its own.
    std::array<Biquad, kSections> upperL_{}, upperR_{};
    bool active_ = false;
    bool upperActiveL_ = false, upperActiveR_ = false;
};

// (Re)configures the generator for params.measGenType -- restarts the run.
void configureMeasurementGenerator(NativeBmwMeasurementGenerator& generator, const Params& params,
                                   float sampleRate);
// Substitutes the generator's stimulus for l/r when params.measGenType != 0.
void applyMeasurementGenerator(NativeBmwMeasurementGenerator& generator, const Params& params,
                               float& l, float& r);

// Raw-input/final-output capture buffers for the in-app measurement tool. Not thread-safe on its
// own: the owner guards every call except frameCount() with its audio lock. writeIndex_ is the
// one exception -- an atomic, so frameCount() can be polled from the UI thread for progress
// without taking the audio thread's lock at all.
class CaptureRecorder {
public:
    bool sizedFor(std::size_t capacity) const {
        return rawInL_.size() == capacity;
    }
    // Arms a new take. With swapIn, the four (freshly allocated, same-size) buffers are swapped
    // in first -- O(1), no memory touched; otherwise the current buffers are reused.
    void start(bool swapIn, std::vector<float>& rawInL, std::vector<float>& rawInR,
               std::vector<float>& outL, std::vector<float>& outR);
    void stop() {
        enabled_ = false;
    }
    // Acquire-paired with tapOut()'s release store: a caller that reads this (UI-thread progress
    // polling) is guaranteed to see every capture-buffer write up to the returned count, not just
    // an up-to-date index with possibly-stale/torn sample data behind it on a weakly-ordered CPU
    // (this app's target, ARM64).
    std::size_t frameCount() const {
        return writeIndex_.load(std::memory_order_acquire);
    }
    // Stops capture and moves the buffers out in O(1); returns the recorded frame count. The
    // take is consumed; start() needs fresh buffers for the next one.
    std::size_t take(std::vector<float>& rawInL, std::vector<float>& rawInR,
                     std::vector<float>& outL, std::vector<float>& outR);

    // Called once per frame before processing (rawIn) and once after (out). No-ops (single
    // branch) when capture is off or the buffer's already full, so this is cheap on every frame
    // regardless.
    void tapIn(float l, float r) {
        if (!enabled_) {
            return;
        }
        const std::size_t i = writeIndex_.load(std::memory_order_relaxed);
        if (i < capacity_) {
            rawInL_[i] = l;
            rawInR_[i] = r;
        }
    }
    void tapOut(float l, float r) {
        if (!enabled_) {
            return;
        }
        const std::size_t i = writeIndex_.load(std::memory_order_relaxed);
        if (i >= capacity_) {
            return;
        }
        outL_[i] = l;
        outR_[i] = r;
        const std::size_t next = i + 1;
        // Release: publishes the plain-float writes above (and tapIn()'s, earlier this same
        // process() call) so a thread that reads frameCount() with a matching acquire load is
        // guaranteed to see them -- see frameCount()'s comment. The two loads inside this class
        // (here and in tapIn()) stay relaxed; they're same-thread bookkeeping reads with no
        // cross-thread consumer of their own.
        writeIndex_.store(next, std::memory_order_release);
        if (next >= capacity_) {
            enabled_ = false;
        }
    }

private:
    std::vector<float> rawInL_, rawInR_, outL_, outR_;
    bool enabled_ = false;
    std::atomic<std::size_t> writeIndex_{0};
    std::size_t capacity_ = 0;
};

}  // namespace NativeBmwDsp

#endif
