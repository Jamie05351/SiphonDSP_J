#include "test_support.h"

#include "NativeBmwDspSchema.h"
#include "NativeBmwVirtual.h"

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

using namespace nbtest;
namespace sch = nbschema;

// Virtual-source stage (the virtual centre): docs/NATIVE_BMW_VIRTUAL_CHANNELS.md.

namespace {

// Deterministic white noise in [-amp, amp].
struct Lcg {
    std::uint32_t s;
    explicit Lcg(std::uint32_t seed) : s(seed) {}
    float next(float amp) {
        s = s * 1664525u + 1013904223u;
        return amp * (static_cast<float>(s >> 8) / 8388608.f - 1.f);
    }
};

// Interleaved stereo: a centred (mono) part plus independent noise on each side.
std::vector<float> mixedSignal(std::size_t frames, float monoAmp, float sideAmp) {
    std::vector<float> buf(frames * 2);
    Lcg mono(1), left(2), right(3);
    for (std::size_t n = 0; n < frames; ++n) {
        const float m = mono.next(monoAmp);
        buf[2 * n] = m + left.next(sideAmp);
        buf[2 * n + 1] = m + right.next(sideAmp);
    }
    return buf;
}

NativeBmwDsp::VirtualConfig neutralConfig() {
    NativeBmwDsp::VirtualConfig c;
    c.enabled = true;
    return c;
}

// Runs the stage alone over an interleaved buffer, in place.
void runStage(NativeBmwDsp::VirtualSourceStage& stage, NativeBmwDsp::VirtualConfig cfg,
              std::vector<float>& buf) {
    stage.rebuild(cfg, static_cast<float>(kSampleRate), true);
    for (std::size_t i = 0; i + 1 < buf.size(); i += 2) {
        stage.process(buf[i], buf[i + 1]);
    }
}

float meterWeight(const NativeBmwDsp::VirtualSourceStage& stage) {
    float m[2] = {-1.f, -1.f};
    stage.readMeter(m, 2);
    return m[0];
}

float maxAbsDiff(const std::vector<float>& a, const std::vector<float>& b) {
    float d = 0.f;
    for (std::size_t i = 0; i < a.size(); ++i) d = std::max(d, std::fabs(a[i] - b[i]));
    return d;
}

}  // namespace

TEST_CASE("virtual stage: disabled is bit-identical whatever its other settings") {
    auto base = defaultConfig();
    auto tweaked = base;
    // Disabled, but every other virtual slot far from neutral: must make no difference at all.
    tweaked[sch::kVirtualCentreLevel] = -12.f;
    tweaked[sch::kVirtualSideLevel] = 6.f;
    tweaked[sch::kVirtualFeedBase + sch::kVirtualFeedDelay] = 3.f;
    tweaked[sch::kVirtualFeedBase + sch::kVirtualFeedApEnabled] = 1.f;
    REQUIRE(tweaked[sch::kVirtualEnabled] == 0.f);

    NativeBmwDspProcessor a, b;
    a.setSampleRate(kSampleRate);
    b.setSampleRate(kSampleRate);
    REQUIRE(a.configure(base.data(), base.size()));
    REQUIRE(b.configure(tweaked.data(), tweaked.size()));
    auto in = mixedSignal(8192, 0.1f, 0.05f);
    auto outA = in, outB = in;
    a.process(outA.data(), outA.size());
    b.process(outB.data(), outB.size());
    CHECK(outA == outB);
}

TEST_CASE("virtual stage: enabled with neutral feeds reconstructs the input") {
    // (L - C) + C == L up to float rounding, through the whole processor.
    auto off = defaultConfig();
    auto on = off;
    on[sch::kVirtualEnabled] = 1.f;
    NativeBmwDspProcessor a, b;
    a.setSampleRate(kSampleRate);
    b.setSampleRate(kSampleRate);
    REQUIRE(a.configure(off.data(), off.size()));
    REQUIRE(b.configure(on.data(), on.size()));
    auto in = mixedSignal(16384, 0.1f, 0.05f);
    auto outA = in, outB = in;
    a.process(outA.data(), outA.size());
    b.process(outB.data(), outB.size());
    CHECK(maxAbsDiff(outA, outB) < 1e-5f);
}

TEST_CASE("virtual centre: a centred mono source is extracted, a hard-panned one is not") {
    auto cfg = neutralConfig();
    cfg.centreLevelDb = -24.f;  // turn the centre down so what was extracted shows as a drop

    SUBCASE("mono (L == R) -> weight ~1, nearly all of it goes with the centre") {
        NativeBmwDsp::VirtualSourceStage stage;
        auto buf = stereoSine(1000.0, 0.1, 24000);
        runStage(stage, cfg, buf);
        CHECK(meterWeight(stage) > 0.98f);
        std::vector<float> tail(buf.end() - 8192, buf.end());
        const double level = linToDb(channelMagnitudeAt(tail, 0, 1000.0) / 0.1);
        CHECK(level == doctest::Approx(-24.0).epsilon(0.05));
    }
    SUBCASE("left only -> weight 0, left passes untouched") {
        NativeBmwDsp::VirtualSourceStage stage;
        auto buf = stereoSine(1000.0, 0.1, 24000);
        for (std::size_t i = 1; i < buf.size(); i += 2) buf[i] = 0.f;
        const auto in = buf;
        runStage(stage, cfg, buf);
        CHECK(meterWeight(stage) == doctest::Approx(0.f));
        CHECK(maxAbsDiff(in, buf) < 1e-6f);
    }
}

TEST_CASE("virtual centre: uncorrelated and anti-phase content read as no centre") {
    SUBCASE("independent noise on each side") {
        NativeBmwDsp::VirtualSourceStage stage;
        auto buf = mixedSignal(48000, 0.f, 0.1f);
        runStage(stage, neutralConfig(), buf);
        CHECK(meterWeight(stage) < 0.2f);
    }
    SUBCASE("R = -L") {
        NativeBmwDsp::VirtualSourceStage stage;
        auto buf = stereoSine(1000.0, 0.1, 24000);
        for (std::size_t i = 1; i < buf.size(); i += 2) buf[i] = -buf[i - 1];
        runStage(stage, neutralConfig(), buf);
        CHECK(meterWeight(stage) == doctest::Approx(0.f));
    }
}

TEST_CASE("virtual centre: a per-side feed delay moves only the centre") {
    // Mono 1 kHz: the centre is (almost) everything. Delaying only the Left feed by half a
    // period puts the two sides in anti-phase; the Right side is untouched.
    auto cfg = neutralConfig();
    cfg.centreFeed[0].delayMs = 0.5f;
    NativeBmwDsp::VirtualSourceStage stage;
    auto buf = stereoSine(1000.0, 0.1, 24000);
    runStage(stage, cfg, buf);
    std::vector<float> tail(buf.end() - 8192, buf.end());
    double sumAbs = 0.0, rightPeak = 0.0;
    for (std::size_t i = 0; i + 1 < tail.size(); i += 2) {
        sumAbs = std::max(sumAbs, static_cast<double>(std::fabs(tail[i] + tail[i + 1])));
        rightPeak = std::max(rightPeak, static_cast<double>(std::fabs(tail[i + 1])));
    }
    CHECK(rightPeak == doctest::Approx(0.1).epsilon(0.02));
    CHECK(sumAbs < 0.01);  // L ~= -R
}

TEST_CASE("virtual centre: an all-pass feed keeps the level but shifts the phase") {
    auto cfg = neutralConfig();
    cfg.centreFeed[0].allPass.enabled = true;
    cfg.centreFeed[0].allPass.frequencyHz = 1000.f;
    cfg.centreFeed[0].allPass.secondOrder = true;
    NativeBmwDsp::VirtualSourceStage stage;
    auto buf = stereoSine(1000.0, 0.1, 24000);
    runStage(stage, cfg, buf);
    std::vector<float> tail(buf.end() - 8192, buf.end());
    // A 2nd-order all-pass is -180 degrees at its centre frequency: unity level, L ~= -R.
    CHECK(channelMagnitudeAt(tail, 0, 1000.0) == doctest::Approx(0.1).epsilon(0.03));
    double sumPeak = 0.0;
    for (std::size_t i = 0; i + 1 < tail.size(); i += 2) {
        sumPeak = std::max(sumPeak, static_cast<double>(std::fabs(tail[i] + tail[i + 1])));
    }
    CHECK(sumPeak < 0.01);
}

TEST_CASE("virtual stage: a non-finite input never reaches the output or sticks") {
    NativeBmwDsp::VirtualSourceStage stage;
    auto cfg = neutralConfig();
    stage.rebuild(cfg, static_cast<float>(kSampleRate), true);
    float l = std::numeric_limits<float>::quiet_NaN(), r = 0.1f;
    stage.process(l, r);
    CHECK(std::isfinite(l));
    CHECK(std::isfinite(r));
    auto buf = stereoSine(1000.0, 0.1, 4800);
    for (std::size_t i = 0; i + 1 < buf.size(); i += 2) stage.process(buf[i], buf[i + 1]);
    for (float x : buf) REQUIRE(std::isfinite(x));
}

TEST_CASE("virtual stage: configure() rejects a bad update in full") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto good = defaultConfig();
    good[sch::kVirtualEnabled] = 1.f;
    REQUIRE(proc.configure(good.data(), good.size()));

    auto nan = good;
    nan[sch::kVirtualFeedBase + sch::kVirtualFeedDelay] = std::numeric_limits<float>::quiet_NaN();
    CHECK_FALSE(proc.configure(nan.data(), nan.size()));

    auto inverted = good;
    inverted[sch::kVirtualDetectHpf] = 1000.f;
    inverted[sch::kVirtualDetectLpf] = 1000.f;
    CHECK_FALSE(proc.configure(inverted.data(), inverted.size()));
}

TEST_CASE("virtual meter: idle while disabled, live while enabled") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto buf = stereoSine(1000.0, 0.1, 24000);
    auto a = buf;
    proc.process(a.data(), a.size());
    float m[2] = {-1.f, 0.f};
    proc.readVirtualMeter(m, 2);
    CHECK(m[0] == 0.f);
    CHECK(m[1] == -60.f);

    cfg[sch::kVirtualEnabled] = 1.f;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto b = buf;
    proc.process(b.data(), b.size());
    proc.readVirtualMeter(m, 2);
    CHECK(m[0] > 0.9f);
    CHECK(m[1] > -60.f);
}
