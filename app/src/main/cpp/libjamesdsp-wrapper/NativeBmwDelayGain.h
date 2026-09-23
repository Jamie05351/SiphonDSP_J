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
