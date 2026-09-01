#include "test_support.h"

#include <algorithm>

using namespace nbtest;

// -1 dBFS, matching NativeBmwDspProcessor::kLimiterCeilingLin (0.891251).
constexpr float kCeiling = 0.891251f;

// Everything upstream of the limiter that would otherwise cut the level: legacy per-output
// compressors (on by default in the raw DEFAULTS, force-disabled by a Kotlin-side migration
// the tests skip), tilt, and non-unity mid gain.
static void quietUpstream(std::array<float, kConfigSize>& c) {
    c[5] = 0.f;                                              // headroom 0 dB
    c[8] = c[9] = 0.f;                                       // mid gain 0 dB
    c[25] = 0.f;                                             // tilt off
    c[28] = c[35] = 0.f;                                     // legacy global compressors off
    c[93] = c[106] = c[119] = c[132] = 0.f;                 // legacy per-output compressors off
}

TEST_CASE("master brick-wall limiter keeps the output near -1 dBFS and never clips") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    quietUpstream(c);
    c[10] = c[11] = 6.f;   // +6 dB post gain -> ~ +5 dBFS into the limiter, hard overdrive

    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    auto warm = stereoSine(220.0, 0.9, 48000);
    proc.process(warm.data(), warm.size());
    auto win = stereoSine(220.0, 0.9, 32000);
    proc.process(win.data(), win.size());

    const float pk = peakAbs(win);
    INFO("peak out = ", pk, "  ceiling = ", kCeiling);
    CHECK(pk < 1.0f);                    // never a digital clip
    CHECK(pk <= kCeiling * 1.07f);       // the smoothed limiter can sit ~0.5 dB over its nominal ceiling
    CHECK(pk > 0.6f);                    // ...but it is clearly still limiting, not gating to silence
}

TEST_CASE("master limiter does not touch a signal already under the ceiling") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    quietUpstream(c);

    const double amp = 0.05;  // ~ -26 dBFS, nowhere near -1
    auto out = renderSteadyState(proc, c, 500.0, amp, 24000, 16384);

    const double relDb = linToDb(channelMagnitudeAt(out, 0, 500.0) / amp);
    INFO("through-level rel = ", relDb, " dB");
    CHECK(std::fabs(relDb) < 1.0);
}

// Poll the meter across the tail of a render so a sine's per-cycle GR ripple can't land us on a
// zero crossing; return the largest gain reduction seen on the low bus.
static float maxLowBusGrOverTail(NativeBmwDspProcessor& proc, double freq, double amp) {
    float worst = 0.f;
    for (int chunk = 0; chunk < 16; ++chunk) {
        auto buf = stereoSine(freq, amp, 3000);
        proc.process(buf.data(), buf.size());
        float m[2] = {0.f, 0.f};
        proc.readBusLimiterMeter(m, 2);
        worst = std::max(worst, m[0]);
    }
    return worst;
}

TEST_CASE("per-bus limiter: zero GR when disabled, engages only when its bus runs hot") {
    // Disabled (default): meter pinned at 0 however hot the bus.
    {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        quietUpstream(c);
        c[10] = c[11] = 6.f;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        auto sig = stereoSine(60.0, 0.9, 24000);
        proc.process(sig.data(), sig.size());
        float m[2] = {-1.f, -1.f};
        proc.readBusLimiterMeter(m, 2);
        CHECK(m[0] == doctest::Approx(0.0f));
        CHECK(m[1] == doctest::Approx(0.0f));
    }

    // Low-bus limiter on at -3 dBFS, driven hot at 60 Hz -> the low bus limits, the mid does not.
    {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        quietUpstream(c);
        c[182] = 1.f; c[183] = -3.f; c[184] = 120.f;   // low bus limiter on
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));

        auto warm = stereoSine(60.0, 0.9, 48000);
        proc.process(warm.data(), warm.size());
        const float lowGr = maxLowBusGrOverTail(proc, 60.0, 0.9);

        float m[2] = {0.f, 0.f};
        proc.readBusLimiterMeter(m, 2);
        INFO("max low GR = ", lowGr, " dB   mid GR = ", m[1], " dB");
        CHECK(lowGr > 1.0f);
        CHECK(m[1] == doctest::Approx(0.0f));

        // Feed it quiet -> the low-bus GR releases back toward 0.
        auto quiet = stereoSine(60.0, 0.02, 96000);
        proc.process(quiet.data(), quiet.size());
        proc.readBusLimiterMeter(m, 2);
        INFO("low GR after quiet = ", m[0], " dB");
        CHECK(m[0] < 0.5f);
    }
}
