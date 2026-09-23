#include "NativeBmwDelayGain.h"

namespace NativeBmwDsp {

float Delay::run(float x) {
    // Write and advance unconditionally, even at delay<=0 -- otherwise the ring never records
    // audio while the delay sits at its default zero, so enabling it later either plays back
    // silence (buffer still all-zero from clear()) or stale audio from a previous enabled period,
    // instead of the audio that actually just played.
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
void Delay::clear() {
    data.fill(0);
    write = 0;
}

}  // namespace NativeBmwDsp
