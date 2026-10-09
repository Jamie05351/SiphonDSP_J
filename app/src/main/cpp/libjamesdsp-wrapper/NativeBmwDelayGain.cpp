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

namespace {
// Modified Bessel function of the first kind, order 0 (for the Kaiser window).
double besselI0(double x) {
    double sum = 1, term = 1;
    for (int k = 1; k < 32; ++k) {
        term *= (x / (2 * k)) * (x / (2 * k));
        sum += term;
    }
    return sum;
}
}  // namespace

void buildAlignmentTaps(float fraction, std::array<float, kAlignmentTaps>& taps) {
    taps.fill(0.f);
    if (!(fraction > 1e-6f)) {
        taps[kAlignmentLatency] = 1.f;
        return;
    }
    // Sinc centred on kAlignmentLatency + fraction, Kaiser window (beta 6) centred on the same
    // point with a half-width of half the kernel, normalised to unity DC gain.
    constexpr double kPi = 3.14159265358979323846, kBeta = 6.0;
    const double centre = kAlignmentLatency + static_cast<double>(fraction);
    const double halfWidth = kAlignmentTaps / 2.0, norm = besselI0(kBeta);
    std::array<double, kAlignmentTaps> h{};
    double sum = 0;
    for (unsigned k = 0; k < kAlignmentTaps; ++k) {
        const double d = static_cast<double>(k) - centre;
        const double r = d / halfWidth;
        const double window = besselI0(kBeta * std::sqrt(std::max(0.0, 1 - r * r))) / norm;
        h[k] = std::sin(kPi * d) / (kPi * d) * window;
        sum += h[k];
    }
    for (unsigned k = 0; k < kAlignmentTaps; ++k) {
        taps[k] = static_cast<float>(h[k] / sum);
    }
}

}  // namespace NativeBmwDsp
