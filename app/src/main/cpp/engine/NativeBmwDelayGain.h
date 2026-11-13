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
// Ring size for the multi-millisecond delays (the virtual-centre feeds, up to 10 ms): 480 samples
// @ 48 kHz, 960 @ 96 kHz; 1024 keeps the full 10 ms up to ~102 kHz. Kept separate from
// kDelayLineCapacity so the per-output / limiter Delay instances stay 256.
enum : unsigned { kStageDelayCapacity = 1024 };

struct Delay {
    std::array<float, kDelayLineCapacity> data{};
    unsigned write = 0;
    float delay = 0;
    float run(float x);
    void clear();
};
// ---- Time-alignment delay (per-output and virtual-centre feeds) ------------------------------------
// Delay above interpolates linearly, which is a low-pass whose depth depends on the
// fractional part: at half a sample, -2 dB at 10 kHz and -6 dB at 16 kHz. Fine for the master
// limiter's integer lookahead, but on the per-output time alignment it changed each tweeter's
// treble by a different amount L vs R. This line uses a 16-tap Kaiser-windowed sinc instead:
// within 0.01 dB to 18 kHz and -0.6 dB at 20 kHz (48 kHz, worst-case fraction).
//
// The price is a fixed kAlignmentLatency samples (7, ~0.15 ms at 48 kHz) on top of the requested
// delay, so the kernel has samples on both sides of the read point. Every output and both stage
// sides carry it, so relative alignment is unchanged; bypassed bands carry it in the processor's
// bypassLatency_ lines. Where an undelayed path is summed back in (the virtual-centre
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
    // A delay change crossfades from the old read point to the new one over fadeLength samples
    // (0 = switch at once). Jumping the read point mid-waveform is a step in the output (a click).
    unsigned fadeLength = 0;
    unsigned fadeRemaining = 0;
    unsigned fadeBase = 0;
    std::array<float, kAlignmentTaps> fadeTaps{};
    // unitTap() of taps / fadeTaps.
    int unit = -1, fadeUnit = -1;
    // A setDelay() landing mid-fade is held here and started when the current fade ends, so each
    // fade begins exactly where the output is (no jump back to a previous target mid-drag).
    bool pendingSet = false;
    float pendingDelay = 0;

    AlignmentDelay() {
        buildAlignmentTaps(0.f, taps);  // a zero delay (setDelay(0) would skip: delay is already 0)
        unit = unitTap(taps);
    }
    // Config thread. Total delay = samples + kAlignmentLatency. Clamped so the kernel's oldest
    // tap stays inside the ring.
    void setDelay(float samples) {
        const float next = clampf(samples, 0.f, static_cast<float>(Capacity - kAlignmentTaps));
        if (fadeRemaining > 0) {
            pendingSet = true;  // started from run() once the current fade lands
            pendingDelay = next;
            return;
        }
        pendingSet = false;
        if (next == delay) {
            return;  // unchanged: no rebuild, no fade
        }
        beginFade();
        retarget(next);
    }
    // Config thread. Total delay = samples exactly, for a line summed with an undelayed path
    // (the virtual-centre feeds). The kernel borrows its latency from the delay itself, so it
    // needs samples >= kAlignmentLatency; shorter delays fall back to linear interpolation
    // (the old behaviour, still exact for whole samples).
    void setDelayNoLatency(float samples) {
        beginFade();
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
        unit = unitTap(taps);
    }
    float run(float x) {
        data[write] = x;
        float y = read(base, taps, unit);
        if (fadeRemaining > 0) {
            // Linear crossfade from the old read point (fadeBase/fadeTaps) to the new one.
            const float oldWeight = std::min(1.f, static_cast<float>(fadeRemaining) /
                                                      static_cast<float>(std::max(1u, fadeLength)));
            y += (read(fadeBase, fadeTaps, fadeUnit) - y) * oldWeight;
            if (--fadeRemaining == 0 && pendingSet) {
                // The fade has landed on the current target: start the held change from there.
                pendingSet = false;
                if (pendingDelay != delay) {
                    beginFade();
                    retarget(pendingDelay);
                }
            }
        }
        write = (write + 1) & (Capacity - 1);
        return y;
    }
    void clear() {
        data.fill(0);
        write = 0;
        snap();
    }
    // Ends any fade in progress and applies any held change at once (start-up, full rebuilds).
    void snap() {
        fadeRemaining = 0;
        if (pendingSet) {
            pendingSet = false;
            retarget(pendingDelay);
        }
    }

private:
    // The tap index of a kernel that is a unit impulse (a whole-sample delay: one tap exactly 1,
    // the rest exactly 0), else -1.
    static int unitTap(const std::array<float, kAlignmentTaps>& kernel) {
        int at = -1;
        for (unsigned k = 0; k < kAlignmentTaps; ++k) {
            if (kernel[k] == 1.f && at < 0) {
                at = static_cast<int>(k);
            } else if (kernel[k] != 0.f) {
                return -1;
            }
        }
        return at;
    }
    float read(unsigned from, const std::array<float, kAlignmentTaps>& kernel, int unitAt) const {
        constexpr unsigned mask = Capacity - 1;
        const unsigned newest = (write + Capacity - from) & mask;
        if (unitAt >= 0) {
            // A whole-sample delay: the one sample, as the kernel sum gives it (the zero taps add
            // +0, which only turns a -0 into +0) -- bit-identical, without the 16 multiplies.
            return data[(newest + Capacity - static_cast<unsigned>(unitAt)) & mask] + 0.f;
        }
        float y = 0;
        for (unsigned k = 0; k < kAlignmentTaps; ++k) {
            y += kernel[k] * data[(newest + Capacity - k) & mask];
        }
        return y;
    }
    void retarget(float d) {
        delay = d;
        base = static_cast<unsigned>(delay);
        buildAlignmentTaps(delay - static_cast<float>(base), taps);
        unit = unitTap(taps);
    }
    // Remembers the current read point as the one to fade from (only ever called with no fade in
    // progress, so it is exactly where the output is).
    void beginFade() {
        if (fadeLength == 0) {
            return;
        }
        fadeBase = base;
        fadeTaps = taps;
        fadeUnit = unit;
        fadeRemaining = fadeLength;
    }

public:
};

// Delay in ms -> fractional samples, clamped to what a ring of `capacity` can hold.
inline float delaySamples(float ms, float sampleRate, unsigned capacity) {
    return clampf(ms * sampleRate * .001f, 0, capacity - 1.f);
}

// One-pole DC blocker on each input channel (3 Hz, see DcBlocker::coefficient). Only there to
// strip DC: at the old 10 Hz it already cost -1 dB at 20 Hz and -0.5 dB at 30 Hz, stacked on the
// subsonic filter; at 3 Hz it is -0.1 dB at 20 Hz.
struct DcBlocker {
    static constexpr float kCornerHz = 3.f;
    float x = 0, y = 0;
    static float coefficient(float sampleRate) {
        return std::exp(-2 * PI * kCornerHz / sampleRate);
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
