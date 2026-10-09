#ifndef SIPHONDSP_NATIVE_BMW_DELAY_GAIN_H
#define SIPHONDSP_NATIVE_BMW_DELAY_GAIN_H

// Delay lines, the DC-blocking input stage, and the ms -> samples conversion. Routing itself is
// NativeBmwRouting::RoutingMatrix (NativeBmwRouting.h); per-output gain/polarity/mute are plain
// fields on OutputRuntime (NativeBmwCrossovers.h), applied by the processor's frame loop.

#include <array>
#include <cmath>
#include "NativeBmwDspMath.h"

namespace NativeBmwDsp {

enum : unsigned { kDelayLineCapacity = 256 };
// Stage-centering L/R alignment delay on the summed stereo bus (post master limiter). Sized
// for a wider range than the sub-millisecond crossover/driver alignment: 10 ms cap -> 480
// samples @ 48 kHz, 960 @ 96 kHz. 1024 keeps the full 10 ms available up to ~102 kHz. Kept
// separate from kDelayLineCapacity so the per-output / limiter Delay instances stay 256.
enum : unsigned { kStageDelayCapacity = 1024 };

struct Delay {
    std::array<float, kDelayLineCapacity> data{};
    unsigned write = 0;
    float delay = 0;
    float run(float x);
    void clear();
};
// Same fractional-delay line as Delay (see Delay::run in the .cpp), with a larger ring
// buffer for the multi-millisecond stage-centering range. Its own type -- rather than
// templating Delay on capacity -- so none of the existing Delay users change.
struct StageDelay {
    std::array<float, kStageDelayCapacity> data{};
    unsigned write = 0;
    float delay = 0;
    float run(float x) {
        // See Delay::run in the .cpp: write and advance unconditionally, even at delay<=0,
        // so the ring is never stale/silent the moment this delay is first enabled.
        const unsigned w = write;
        data[w] = x;
        write = (w + 1) % data.size();
        if (delay <= 0) {
            return x;
        }
        float read = static_cast<float>(w) - delay;
        while (read < 0) {
            read += data.size();
        }
        unsigned i0 = static_cast<unsigned>(read) % data.size(), i1 = (i0 + 1) % data.size();
        float f = read - std::floor(read), y = data[i0] + (data[i1] - data[i0]) * f;
        return y;
    }
    void clear() {
        data.fill(0);
        write = 0;
    }
};

// ---- Time-alignment delay (per-output and stage-centering) ------------------------------------
// Delay/StageDelay above interpolate linearly, which is a low-pass whose depth depends on the
// fractional part: at half a sample, -2 dB at 10 kHz and -6 dB at 16 kHz. Fine for the master
// limiter's integer lookahead, but on the per-output time alignment it changed each tweeter's
// treble by a different amount L vs R. This line uses a 16-tap Kaiser-windowed sinc instead:
// within 0.01 dB to 18 kHz and -0.6 dB at 20 kHz (48 kHz, worst-case fraction).
//
// The price is a fixed kAlignmentLatency samples (7, ~0.15 ms at 48 kHz) on top of the requested
// delay, so the kernel has samples on both sides of the read point. Every output and both stage
// sides carry it, so relative alignment is unchanged; bypassed bands carry it via
// FixedAlignmentLatency below. Where an undelayed path is summed back in (the virtual-centre
// feeds), setDelayNoLatency() takes the kernel's latency out of the delay itself instead. The
// master limiter's lookahead keeps Delay: it must stay sample-exact against its gain path.
constexpr unsigned kAlignmentTaps = 16;
constexpr unsigned kAlignmentLatency = kAlignmentTaps / 2 - 1;
// Kernel for a fractional part in [0, 1): taps[k] multiplies x[n - whole - k]. A zero fraction
// gives an exact unit impulse at kAlignmentLatency, so integer delays stay bit-exact.
void buildAlignmentTaps(float fraction, std::array<float, kAlignmentTaps>& taps);

template <unsigned Capacity>
struct AlignmentDelay {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
    static_assert(Capacity > kAlignmentTaps, "Capacity must exceed the kernel length");
    std::array<float, Capacity> data{};
    std::array<float, kAlignmentTaps> taps{};
    unsigned write = 0;
    // Samples skipped before the kernel's first tap: taps[k] multiplies x[n - base - k].
    unsigned base = 0;
    // Requested delay in samples, excluding kAlignmentLatency (what the truth snapshot reports).
    float delay = 0;

    AlignmentDelay() {
        setDelay(0);
    }
    // Config thread. Total delay = samples + kAlignmentLatency. Clamped so the kernel's oldest
    // tap stays inside the ring.
    void setDelay(float samples) {
        delay = clampf(samples, 0.f, static_cast<float>(Capacity - kAlignmentTaps));
        base = static_cast<unsigned>(delay);
        buildAlignmentTaps(delay - static_cast<float>(base), taps);
    }
    // Config thread. Total delay = samples exactly, for a line summed with an undelayed path
    // (the virtual-centre feeds). The kernel borrows its latency from the delay itself, so it
    // needs samples >= kAlignmentLatency; shorter delays fall back to linear interpolation
    // (the old behaviour, still exact for whole samples).
    void setDelayNoLatency(float samples) {
        delay = clampf(samples, 0.f, static_cast<float>(Capacity - kAlignmentTaps));
        const auto whole = static_cast<unsigned>(delay);
        const float fraction = delay - static_cast<float>(whole);
        if (whole >= kAlignmentLatency) {
            base = whole - kAlignmentLatency;
            buildAlignmentTaps(fraction, taps);
        } else {
            base = whole;
            taps.fill(0.f);
            taps[0] = 1.f - fraction;
            taps[1] = fraction;
        }
    }
    float run(float x) {
        constexpr unsigned mask = Capacity - 1;
        data[write] = x;
        const unsigned newest = (write + Capacity - base) & mask;
        float y = 0;
        for (unsigned k = 0; k < kAlignmentTaps; ++k) {
            y += taps[k] * data[(newest + Capacity - k) & mask];
        }
        write = (write + 1) & mask;
        return y;
    }
    void clear() {
        data.fill(0);
        write = 0;
    }
};

// A whole-sample delay of exactly kAlignmentLatency, for a band whose crossover is bypassed
// (lpfPass/hpfPass): it skips the band chain and its AlignmentDelay, so without this it would
// arrive kAlignmentLatency samples ahead of the bands that are processed.
struct FixedAlignmentLatency {
    std::array<float, kAlignmentLatency> data{};
    unsigned pos = 0;
    float run(float x) {
        const float y = data[pos];
        data[pos] = x;
        pos = pos + 1 == kAlignmentLatency ? 0 : pos + 1;
        return y;
    }
    void clear() {
        data.fill(0);
        pos = 0;
    }
};

// Delay in ms -> fractional samples, clamped to what a ring of `capacity` can hold.
inline float delaySamples(float ms, float sampleRate, unsigned capacity) {
    return clampf(ms * sampleRate * .001f, 0, capacity - 1.f);
}

// One-pole DC blocker on each input channel (~10 Hz, see DcBlocker::coefficient).
struct DcBlocker {
    float x = 0, y = 0;
    static float coefficient(float sampleRate) {
        return std::exp(-2 * PI * 10 / sampleRate);
    }
    float run(float in, float r) {
        float out = in - x + r * y;
        x = in;
        y = ftz(out);
        return y;
    }
    void clear() {
        x = y = 0;
    }
};

}  // namespace NativeBmwDsp

#endif
