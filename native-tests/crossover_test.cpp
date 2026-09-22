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
constexpr float kBw2 = 0.f, kBw3 = 1.f, kLr4 = 2.f, kBw4 = 4.f;

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

std::array<float, kConfigSize> highOnlyConfig(float fc, float type) {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    for (int out = 0; out < 4; ++out) {  // mute Low L/R, Mid L/R, isolate the High crossover
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    // High ships muted/bypassed by default -- un-mute and un-bypass it, then set its corner.
    c[sch::kHighXoPass] = 0.f;
    for (int slot = 0; slot < 2; ++slot) {  // High Left, High Right
        const std::size_t base = sch::kHighOutputConfigBase + slot * sch::kOutputConfigWidth;
        c[base + sch::kOutMuted] = 0.f;
        c[base + sch::kOutCrossoverFreq] = fc;
        c[base + sch::kOutCrossoverType] = type;
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

// Same isolated Mid setup as midOnlyConfig(), plus Mid's optional upper (Mid/High) bandpass
// corner enabled at fcHigh -- turns Mid from HPF-only into a true bandpass. Both corners share
// the same crossoverType, matching how the UI presents one slope selector per band.
std::array<float, kConfigSize> midBandpassConfig(float fcLow, float fcHigh, float type) {
    auto c = midOnlyConfig(fcLow, type);
    for (int out = 2; out < 4; ++out) {  // Mid L, Mid R
        const int slot = out - 2;  // Mid's upper-corner block is 2-wide (Left, Right), not 4-wide.
        c[sch::kMidUpperXo + slot * sch::kMidUpperXoWidth + sch::kMidUpperXoFreq] = fcHigh;
        c[sch::kMidUpperXo + slot * sch::kMidUpperXoWidth + sch::kMidUpperXoEnabled] = 1.f;
    }
    return c;
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
    // Unknown high IDs retain the LR4 fallback; only explicit IDs 3 / 4 select BW1 / BW4.
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

TEST_CASE("First-order crossovers roll off at 6 dB per octave") {
    constexpr float fc = 200.f;
    CHECK(stopbandSlopeDbPerOctave(lowOnlyConfig(fc, 3.f), fc * 4, fc * 8) ==
          doctest::Approx(-6.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(midOnlyConfig(fc, 3.f), fc / 4, fc / 8) ==
          doctest::Approx(-6.f).epsilon(0.05));
}

TEST_CASE("BW4 crossovers roll off at 24 dB per octave on both bands") {
    constexpr float fc = 200.f;
    CHECK(stopbandSlopeDbPerOctave(lowOnlyConfig(fc, kBw4), fc * 4, fc * 8) ==
          doctest::Approx(-24.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(midOnlyConfig(fc, kBw4), fc / 4, fc / 8) ==
          doctest::Approx(-24.f).epsilon(0.05));
}

TEST_CASE("BW4 sits 3 dB above LR4 at the corner (-3 dB vs -6 dB; proves it is not just LR4 again)") {
    constexpr float fc = 200.f;
    for (bool low : {true, false}) {
        auto cfgFor = [&](float type) { return low ? lowOnlyConfig(fc, type) : midOnlyConfig(fc, type); };
        // Compare the two types at the same frequency in the same config, so any absolute level
        // offset (headroom, gain, low-frequency rolloff elsewhere in the chain) cancels out.
        NativeBmwDspProcessor bw4Proc, lr4Proc;
        const float bw4 = outLevelDbAt(bw4Proc, cfgFor(kBw4), fc);
        const float lr4 = outLevelDbAt(lr4Proc, cfgFor(kLr4), fc);
        CHECK(bw4 - lr4 == doctest::Approx(3.01f).epsilon(0.05));
    }
}

// Mid's upper (Mid/High) bandpass corner -- Phase 2 of the 2-way -> 3-way output crossover work.
// Disabled (defaultConfig()'s shipped state) is covered implicitly: every test above this one
// uses midOnlyConfig(), which never touches the upper-corner indices, so Mid stays HPF-only
// exactly as before this feature existed.
TEST_CASE("Mid's upper crossover corner rolls off above it at its real dB/octave slope") {
    // fcHigh kept low enough that fcHigh*8 stays well under the 48 kHz test sample rate's
    // Nyquist (24 kHz) -- a test point at or above Nyquist aliases and reads as ~0 dB slope
    // regardless of the real filter response. Even well under Nyquist, fcHigh*8 (8 kHz) is a
    // much larger fraction of it (33%) than the other tests' fc*8 (fc=200 -> 1.6 kHz, 6.7%), so
    // the bilinear transform's frequency warping measurably steepens the apparent slope here
    // (observed ~11% high). A wider epsilon than the other slope tests' 0.05 is expected and
    // correct, not a loosened correctness bar -- BW2 (12) vs LR4 (24) stay unambiguous at 0.15.
    constexpr float fcLow = 200.f, fcHigh = 1000.f;
    CHECK(stopbandSlopeDbPerOctave(midBandpassConfig(fcLow, fcHigh, kBw2), fcHigh * 4, fcHigh * 8) ==
          doctest::Approx(-12.f).epsilon(0.15));
    CHECK(stopbandSlopeDbPerOctave(midBandpassConfig(fcLow, fcHigh, kLr4), fcHigh * 4, fcHigh * 8) ==
          doctest::Approx(-24.f).epsilon(0.15));
}

TEST_CASE("Mid's upper crossover corner still rolls off correctly at the lower corner too (true bandpass)") {
    // Confirms enabling the upper corner didn't disturb the existing lower (Low/Mid) HPF slope --
    // the two corners' filter stages are independent cascade stages, not a shared/overwritten one.
    constexpr float fcLow = 200.f, fcHigh = 1000.f;
    CHECK(stopbandSlopeDbPerOctave(midBandpassConfig(fcLow, fcHigh, kLr4), fcLow / 4, fcLow / 8) ==
          doctest::Approx(-24.f).epsilon(0.05));
}

// High band -- Phase 3 of the 2-way -> 3-way output crossover work. HPF-only, same shape as Mid
// was before Phase 2. Ships muted/bypassed by default; that default (not exercised here) is
// covered implicitly the same way as Mid's disabled upper corner: every other test file in this
// suite builds on defaultConfig() and would fail if High's default state produced audible output.
TEST_CASE("High crossover BW2/BW3/LR4 each roll off at their real dB/octave slope") {
    constexpr float fc = 3000.f;
    CHECK(stopbandSlopeDbPerOctave(highOnlyConfig(fc, kBw2), fc / 4, fc / 8) ==
          doctest::Approx(-12.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(highOnlyConfig(fc, kBw3), fc / 4, fc / 8) ==
          doctest::Approx(-18.f).epsilon(0.05));
    CHECK(stopbandSlopeDbPerOctave(highOnlyConfig(fc, kLr4), fc / 4, fc / 8) ==
          doctest::Approx(-24.f).epsilon(0.05));
}

TEST_CASE("High: kHighXoPass alone fully silences it, independent of the per-output mute field") {
    // Deliberately NOT the same contract as kLpfPass/kHpfPass (which only bypass the crossover
    // filter, letting the raw routed signal through -- an accepted pre-existing risk for
    // Low/Mid). A tweeter with no HPF ahead of it is a real speaker-damage risk from raw bass,
    // and kHighXoPass is also the 3-way master-off switch's single write for High, so it must be
    // sufficient on its own -- proven here by leaving mute explicitly OFF and confirming bypass
    // alone still produces silence, not just their combination.
    constexpr float fc = 3000.f;
    auto bypassed = highOnlyConfig(fc, kLr4);
    bypassed[sch::kHighXoPass] = 1.f;  // re-bypass despite highOnlyConfig() clearing it; mute stays off
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(bypassed.data(), bypassed.size()));
    auto buf = stereoSine(fc, 0.3, 8192, kSampleRate);
    proc.process(buf.data(), buf.size());
    CHECK(peakAbs(buf) < 1e-4f);
}
