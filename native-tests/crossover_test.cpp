#include "test_support.h"

#include "NativeBmwDspSchema.h"

using namespace nbtest;
namespace sch = nbschema;

// Proves the selectable crossover slope (FIELD_CROSSOVER_TYPE / kOutCrossoverType) actually
// changes the real filter, not just the on-screen graph: BW2 = 12 dB/oct (one 2nd-order stage),
// BW3 = 18 dB/oct (1st-order + 2nd-order Q=1 cascade), LR4 = 24 dB/oct (two cascaded 2nd-order
// Butterworth stages) -- unchanged from before slope selection existed.

namespace {

float outLevelDbAt(NativeBmwDspProcessor& proc, const std::array<float, kConfigSize>& cfg,
                   double freqHz, double amp = 0.05) {
    auto out = renderSteadyState(proc, cfg, freqHz, amp);
    return static_cast<float>(linToDb(channelMagnitudeAt(out, 0, freqHz) / amp));
}

// Slot values, matching NativeBmwDspValues.CROSSOVER_TYPE_* / OutputConfig::CrossoverType.
constexpr float kBw2 = 0.f, kBw3 = 1.f, kLr4 = 2.f;

std::array<float, kConfigSize> lowOnlyConfig(float fc, float type) {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    for (int out = 2; out < 4; ++out) {  // mute Mid L/R, isolate the Low crossover
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    for (int out = 0; out < 2; ++out) {  // Low L, Low R
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverFreq] = fc;
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverType] = type;
    }
    return c;
}

std::array<float, kConfigSize> midOnlyConfig(float fc, float type) {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    for (int out = 0; out < 2; ++out) {  // mute Low L/R, isolate the Mid crossover
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    for (int out = 2; out < 4; ++out) {  // Mid L, Mid R
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverFreq] = fc;
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverType] = type;
    }
    return c;
}

// dB over one octave, between two points both 2-3 octaves deep in the stopband (fNearHz closer to
// the corner, fFarHz one octave further from it) -- far enough in that a real 2nd/3rd/4th-order
// filter has converged to its asymptotic slope, so this reads very close to -6*order dB/oct.
// Always negative regardless of whether the crossover is a lowpass or a highpass, since fFarHz is
// defined as "further into the stopband than fNearHz" rather than as a raw frequency comparison.
float stopbandSlopeDbPerOctave(const std::array<float, kConfigSize>& cfg, double fNearHz,
                               double fFarHz) {
    NativeBmwDspProcessor a, b;
    const float near = outLevelDbAt(a, cfg, fNearHz);
    const float far = outLevelDbAt(b, cfg, fFarHz);
    return far - near;
}

}  // namespace

TEST_CASE("Low crossover BW2/BW3/LR4 each roll off at their real dB/octave slope") {
    constexpr float fc = 200.f;
    CHECK(stopbandSlopeDbPerOctave(lowOnlyConfig(fc, kBw2), fc * 4, fc * 8) ==
          doctest::Approx(-12.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(lowOnlyConfig(fc, kBw3), fc * 4, fc * 8) ==
          doctest::Approx(-18.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(lowOnlyConfig(fc, kLr4), fc * 4, fc * 8) ==
          doctest::Approx(-24.f).epsilon(0.05));
}

TEST_CASE("Mid crossover BW2/BW3/LR4 each roll off at their real dB/octave slope") {
    constexpr float fc = 200.f;
    CHECK(stopbandSlopeDbPerOctave(midOnlyConfig(fc, kBw2), fc / 4, fc / 8) ==
          doctest::Approx(-12.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(midOnlyConfig(fc, kBw3), fc / 4, fc / 8) ==
          doctest::Approx(-18.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(midOnlyConfig(fc, kLr4), fc / 4, fc / 8) ==
          doctest::Approx(-24.f).epsilon(0.05));
}

TEST_CASE("An out-of-range/garbage crossover-type value safely falls back to LR4") {
    // configure()'s threshold read (< .5 -> BW2, < 1.5 -> BW3, else LR4) means any unrecognized
    // value -- not just the in-range 0/1/2 -- lands on the steepest, most conservative option
    // rather than on undefined behaviour or a silent 1st-order-only response.
    constexpr float fc = 200.f;
    const float garbage = stopbandSlopeDbPerOctave(lowOnlyConfig(fc, 7.f), fc * 4, fc * 8);
    const float lr4 = stopbandSlopeDbPerOctave(lowOnlyConfig(fc, kLr4), fc * 4, fc * 8);
    CHECK(garbage == doctest::Approx(lr4).epsilon(0.01));
}

TEST_CASE("Default config resolves to LR4 on both bands (unchanged behaviour pre-selectable-slope)") {
    constexpr float fc = 200.f;
    auto c = defaultConfig();
    c[sch::kOutputConfigBase + 0 * sch::kOutputConfigWidth + sch::kOutCrossoverFreq] = fc;
    c[sch::kOutputConfigBase + 1 * sch::kOutputConfigWidth + sch::kOutCrossoverFreq] = fc;
    for (int out = 2; out < 4; ++out) {
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    c[sch::kTiltEnabled] = 0.f;
    CHECK(stopbandSlopeDbPerOctave(c, fc * 4, fc * 8) == doctest::Approx(-24.f).epsilon(0.05));
}
