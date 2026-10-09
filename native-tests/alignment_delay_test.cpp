#include "test_support.h"

#include <cmath>
#include <complex>
#include <vector>

#include "NativeBmwDelayGain.h"

using namespace nbtest;
using NativeBmwDsp::AlignmentDelay;
using NativeBmwDsp::kAlignmentLatency;

// Hann-windowed single-bin DFT of a mono signal over [start, end).
static std::complex<double> binAt(const std::vector<float>& x, std::size_t start, std::size_t end,
                                  double freqHz) {
    const double w = 2.0 * kPi * freqHz / kSampleRate;
    std::complex<double> acc{0.0, 0.0};
    const double len = static_cast<double>(end - start - 1);
    for (std::size_t n = start; n < end; ++n) {
        const double hann = 0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(n - start) / len);
        acc += static_cast<double>(x[n]) * hann * std::polar(1.0, -w * static_cast<double>(n));
    }
    return acc;
}

TEST_CASE("alignment delay: integer delays are exact, shifted by the fixed kernel latency") {
    AlignmentDelay<256> d;
    d.setDelay(5.f);
    for (int n = 0; n < 40; ++n) {
        const float y = d.run(n == 0 ? 1.f : 0.f);
        CHECK(y == (n == 5 + static_cast<int>(kAlignmentLatency) ? 1.f : 0.f));
    }
}

TEST_CASE("alignment delay: half-sample delay is flat to 18 kHz with the exact phase") {
    // Linear interpolation lost 6 dB at 16 kHz here; the windowed sinc should not.
    constexpr float kDelay = 10.5f;
    constexpr std::size_t kFrames = 8192, kStart = 1024;
    for (double f : {1000.0, 10000.0, 16000.0, 18000.0}) {
        AlignmentDelay<256> d;
        d.setDelay(kDelay);
        std::vector<float> in(kFrames), out(kFrames);
        for (std::size_t n = 0; n < kFrames; ++n) {
            in[n] = static_cast<float>(0.5 * std::sin(2.0 * kPi * f * static_cast<double>(n) / kSampleRate));
            out[n] = d.run(in[n]);
        }
        const auto bi = binAt(in, kStart, kFrames, f), bo = binAt(out, kStart, kFrames, f);
        const double gainDb = 20.0 * std::log10(std::abs(bo) / std::abs(bi));
        // Expected phase lag of (delay + latency) samples, compared on the unit circle.
        const double lag = 2.0 * kPi * f / kSampleRate * (kDelay + kAlignmentLatency);
        const double phaseErr = std::abs(std::arg(bo / bi * std::polar(1.0, lag)));
        INFO(f, " Hz: gain ", gainDb, " dB, phase error ", phaseErr, " rad");
        CHECK(std::fabs(gainDb) < 0.1);
        CHECK(phaseErr < 0.01);
    }
}
