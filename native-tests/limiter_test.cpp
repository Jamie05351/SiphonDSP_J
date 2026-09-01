#include "test_support.h"

using namespace nbtest;

// -1 dBFS, matching NativeBmwDspProcessor::kLimiterCeilingLin (0.891251).
constexpr float kCeiling = 0.891251f;

TEST_CASE("master brick-wall limiter never lets the output exceed -1 dBFS") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[5] = 0.f;               // headroom 0 dB
    c[10] = c[11] = 6.f;      // post gain L/R +6 dB -> pushes well past the ceiling pre-limiter
    c[25] = 0.f;              // tilt off (keep the overdrive predictable)

    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    auto warm = stereoSine(220.0, 0.9, 24000);   // ~ -0.9 dBFS in, +6 dB post -> hard clip territory
    proc.process(warm.data(), warm.size());

    auto win = stereoSine(220.0, 0.9, 32000);
    proc.process(win.data(), win.size());

    const float pk = peakAbs(win);
    INFO("peak out = ", pk, "  ceiling = ", kCeiling);
    CHECK(pk <= kCeiling + 1e-3f);
    CHECK(pk > 0.5f);  // it should still be *loud*, not gated to silence
}

TEST_CASE("master limiter does not touch a signal already under the ceiling") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[5] = 0.f;
    c[8] = c[9] = 0.f;   // unity mid gain
    c[25] = 0.f;
    c[28] = c[35] = c[93] = c[106] = c[119] = c[132] = 0.f;  // no compression

    const double amp = 0.05;  // ~ -26 dBFS, nowhere near -1
    auto out = renderSteadyState(proc, c, 500.0, amp, 24000, 16384);

    // No limiting -> the 500 Hz tone comes through at ~unity (minus whatever flat trim the
    // chain applies); crucially it is not pumped or gained-down by a wrongly-active limiter.
    const double relDb = linToDb(channelMagnitudeAt(out, 0, 500.0) / amp);
    INFO("through-level rel = ", relDb, " dB");
    CHECK(std::fabs(relDb) < 1.0);
}

TEST_CASE("per-bus limiter reads zero GR when disabled and positive GR only when its bus is hot") {
    // Disabled (default): meter is pinned at 0 no matter how hot the bus.
    {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        c[5] = 0.f; c[10] = c[11] = 6.f;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        auto sig = stereoSine(60.0, 0.9, 24000);
        proc.process(sig.data(), sig.size());
        float m[2] = {-1.f, -1.f};
        proc.readBusLimiterMeter(m, 2);
        CHECK(m[0] == doctest::Approx(0.0f));
        CHECK(m[1] == doctest::Approx(0.0f));
    }

    // Low-bus limiter enabled, threshold -3 dBFS, driven hot at 60 Hz -> low bus limits, mid doesn't.
    {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        c[5] = 0.f; c[10] = c[11] = 6.f;
        c[182] = 1.f; c[183] = -3.f; c[184] = 120.f;   // low bus limiter on
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));

        auto hot = stereoSine(60.0, 0.9, 48000);
        proc.process(hot.data(), hot.size());
        float m[2] = {0.f, 0.f};
        proc.readBusLimiterMeter(m, 2);
        INFO("low GR = ", m[0], " dB   mid GR = ", m[1], " dB");
        CHECK(m[0] > 1.0f);
        CHECK(m[1] == doctest::Approx(0.0f));

        // Now feed it quiet -> the low-bus GR releases back toward 0.
        auto quiet = stereoSine(60.0, 0.02, 96000);
        proc.process(quiet.data(), quiet.size());
        proc.readBusLimiterMeter(m, 2);
        INFO("low GR after quiet = ", m[0], " dB");
        CHECK(m[0] < 0.5f);
    }
}
