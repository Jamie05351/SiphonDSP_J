#include "test_support.h"

#include "NativeBmwDspSchema.h"
#include "NativeBmwFilters.h"

using namespace nbtest;
namespace sch = nbschema;

// An edit made while audio plays must retune the running filters, not drop their state. Clearing
// the state mid-waveform turned every slider drag event into a step in the output (a click,
// worst on bass, where the filter state is largest). These tests apply an edit between two
// buffers of one continuous sine and compare what follows against a second processor that ran
// the edited settings from the start: a retune lands within a few percent of it at once, while a
// cleared filter restarts from silence and is off by about the whole signal.

namespace {

constexpr double kToneHz = 60.0, kAmp = 0.25;
constexpr std::size_t kBlock = 4096;
constexpr int kWarmBlocks = 12;  // ~1 s: crossovers, limiter and DC blocker settle

// Continuous-phase stereo sine: frames [start, start + frames).
std::vector<float> sineBlock(std::size_t start, std::size_t frames) {
    std::vector<float> buf(frames * 2);
    const double w = 2.0 * kPi * kToneHz / kSampleRate;
    for (std::size_t n = 0; n < frames; ++n) {
        const float s = static_cast<float>(kAmp * std::sin(w * static_cast<double>(start + n)));
        buf[2 * n] = s;
        buf[2 * n + 1] = s;
    }
    return buf;
}

// Renders kWarmBlocks of the tone, then one more block, and returns that last block.
template <class Between>
std::vector<float> renderLastBlock(NativeBmwDspProcessor& proc, Between betweenWarmAndLast) {
    std::size_t pos = 0;
    for (int i = 0; i < kWarmBlocks; ++i, pos += kBlock) {
        auto warm = sineBlock(pos, kBlock);
        proc.process(warm.data(), warm.size());
    }
    betweenWarmAndLast();
    auto last = sineBlock(pos, kBlock);
    proc.process(last.data(), last.size());
    return last;
}

// Worst |edited - reference| over the block, relative to the reference's peak.
float relativeError(const std::vector<float>& edited, const std::vector<float>& reference) {
    float worst = 0.f;
    for (std::size_t i = 0; i < edited.size(); ++i) {
        worst = std::max(worst, std::fabs(edited[i] - reference[i]));
    }
    return worst / std::max(peakAbs(reference), 1e-9f);
}

std::array<float, kConfigSize> baseConfig() {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    return c;
}

constexpr float kMaxRelativeError = 0.2f;

}  // namespace

TEST_CASE("moving the Low crossover corner mid-stream retunes instead of restarting") {
    auto before = baseConfig();
    auto after = before;
    for (int out = 0; out < 2; ++out) {
        after[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverFreq] =
            170.f;
    }
    NativeBmwDspProcessor edited, reference;
    edited.setSampleRate(kSampleRate);
    reference.setSampleRate(kSampleRate);
    REQUIRE(edited.configure(before.data(), before.size()));
    REQUIRE(reference.configure(after.data(), after.size()));
    const auto editedOut = renderLastBlock(edited, [&] {
        REQUIRE(edited.configure(after.data(), after.size()));
    });
    const auto referenceOut = renderLastBlock(reference, [] {});
    CHECK(relativeError(editedOut, referenceOut) < kMaxRelativeError);
}

TEST_CASE("editing a PEQ band mid-stream retunes instead of restarting") {
    // [freq, gain dB, Q, type (0 bell), channel (0 both)]: a bass bell on the tone, plus a
    // second band so the bank holds more than one running section.
    std::vector<double> before = {60.0, 4.0, 1.0, 0.0, 0.0, 250.0, -2.0, 1.4, 0.0, 0.0};
    auto after = before;
    after[1] = 4.5;  // nudge the bell's gain, as one drag event would
    const std::vector<double> none;
    auto cfg = baseConfig();
    NativeBmwDspProcessor edited, reference;
    edited.setSampleRate(kSampleRate);
    reference.setSampleRate(kSampleRate);
    REQUIRE(edited.configure(cfg.data(), cfg.size()));
    REQUIRE(reference.configure(cfg.data(), cfg.size()));
    REQUIRE(edited.configurePeq(true, -3.f, before.data(), before.size(), none.data(), 0,
                                none.data(), 0, none.data(), 0));
    REQUIRE(reference.configurePeq(true, -3.f, after.data(), after.size(), none.data(), 0,
                                   none.data(), 0, none.data(), 0));
    const auto editedOut = renderLastBlock(edited, [&] {
        REQUIRE(edited.configurePeq(true, -3.f, after.data(), after.size(), none.data(), 0,
                                    none.data(), 0, none.data(), 0));
    });
    const auto referenceOut = renderLastBlock(reference, [] {});
    CHECK(relativeError(editedOut, referenceOut) < kMaxRelativeError);
}

TEST_CASE("PEQ state follows its band when an earlier band drops out of the bank") {
    using NativeBmwDsp::Biquad;
    using NativeBmwDsp::PeqBank;
    Biquad a, b;
    REQUIRE(NativeBmwDsp::makePeq(a, 80.0, 3.0, 1.0, 0, 48000.f));
    REQUIRE(NativeBmwDsp::makePeq(b, 900.0, -4.0, 2.0, 0, 48000.f));
    PeqBank running;
    running.append(PeqBank::kLeftLane, a, 0, 0);
    running.append(PeqBank::kLeftLane, b, 1, 0);
    running.append(PeqBank::kRightLane, a, 0, 0);
    running.append(PeqBank::kRightLane, b, 1, 0);
    auto tone = sineBlock(0, 512);
    for (std::size_t n = 0; n < tone.size(); n += 2) {
        running.process(tone[n], tone[n + 1]);
    }
    REQUIRE(running.sections[1].ic1eq[PeqBank::kLeftLane] != 0.0);

    // Band 0 dragged to 0 dB is skipped from the rebuilt bank, so band 1 moves to section 0.
    PeqBank rebuilt;
    rebuilt.append(PeqBank::kLeftLane, b, 1, 0);
    rebuilt.append(PeqBank::kRightLane, b, 1, 0);
    rebuilt.inheritState(running);
    for (std::size_t lane : {PeqBank::kLeftLane, PeqBank::kRightLane}) {
        CHECK(rebuilt.sections[0].ic1eq[lane] == running.sections[1].ic1eq[lane]);
        CHECK(rebuilt.sections[0].ic2eq[lane] == running.sections[1].ic2eq[lane]);
    }

    // A band that changed type is a different filter: it starts clean.
    Biquad notch;
    REQUIRE(NativeBmwDsp::makePeq(notch, 900.0, 0.0, 2.0, 3, 48000.f));
    PeqBank retyped;
    retyped.append(PeqBank::kLeftLane, notch, 1, 3);
    retyped.inheritState(running);
    CHECK(retyped.sections[0].ic1eq[PeqBank::kLeftLane] == 0.0);
    CHECK(retyped.sections[0].ic2eq[PeqBank::kLeftLane] == 0.0);
}

TEST_CASE("Biquad::retune keeps state only for the same running recursion") {
    using NativeBmwDsp::Biquad;
    Biquad q;
    NativeBmwDsp::makeLowPass(q, 150.f, NativeBmwDsp::BW, 48000.f);
    for (int i = 0; i < 64; ++i) {
        q.run(0.5f);
    }
    REQUIRE(q.ic2eq != 0.0);
    const double held = q.ic2eq;
    NativeBmwDsp::makeLowPass(q, 170.f, NativeBmwDsp::BW, 48000.f);
    CHECK(q.ic2eq == held);  // same topology: retuned in place

    NativeBmwDsp::makeLowPass1(q, 170.f, 48000.f);
    CHECK(q.ic1eq == 0.0);  // different topology: clean start
    CHECK(q.ic2eq == 0.0);

    NativeBmwDsp::makeIdentity(q);
    NativeBmwDsp::makeLowPass(q, 170.f, NativeBmwDsp::BW, 48000.f);
    CHECK(q.ic1eq == 0.0);  // from identity: clean start
    CHECK(q.ic2eq == 0.0);
}
