#include "test_support.h"

#include "NativeBmwDspSchema.h"

#include <algorithm>

using namespace nbtest;
namespace sch = nbschema;

// These tests prove NativeBmwDspSchema.h's indices are the ones configure() actually consumes:
// set config slot N (via the header constant), configure(), and observe an effect that only that
// field can produce. NativeBmwSchemaAgreementTest.kt then bridges the header to the Kotlin
// INDEX_* constants, so Kotlin <-> C++ agreement is covered end to end.

static float outLevelDbAt(NativeBmwDspProcessor& proc, const std::array<float, kConfigSize>& cfg,
                          double freqHz, double amp = 0.05) {
    auto out = renderSteadyState(proc, cfg, freqHz, amp);
    return static_cast<float>(linToDb(channelMagnitudeAt(out, 0, freqHz) / amp));
}

TEST_CASE("kSize agrees with the processor's compile-time config size") {
    static_assert(sch::kSize == NativeBmwDspProcessor::kConfigSize);
    CHECK(sch::kSize == kConfigSize);
}

TEST_CASE("kEnabled slot gates the whole chain") {
    NativeBmwDspProcessor bypass, active;
    auto cOff = defaultConfig();
    cOff[sch::kEnabled] = 0.f;
    auto cOn = defaultConfig();
    cOn[sch::kEnabled] = 1.f;

    bypass.setSampleRate(kSampleRate);
    active.setSampleRate(kSampleRate);
    REQUIRE(bypass.configure(cOff.data(), cOff.size()));
    REQUIRE(active.configure(cOn.data(), cOn.size()));

    auto in = stereoSine(500.0, 0.1, 4096);
    auto passthru = in, processed = in;
    bypass.process(passthru.data(), passthru.size());
    active.process(processed.data(), processed.size());

    CHECK(passthru == in);                       // disabled -> byte-exact passthrough
    CHECK(peakAbs(processed) != doctest::Approx(peakAbs(in)));  // enabled -> headroom etc. applied
}

TEST_CASE("kHeadroom and kPostGain slots are level trims at their own stages") {
    NativeBmwDspProcessor a, b, c;
    auto flat = defaultConfig();
    flat[sch::kTiltEnabled] = 0.f;
    flat[sch::kMidGainL] = flat[sch::kMidGainR] = 0.f;

    auto h0 = flat;
    h0[sch::kHeadroom] = 0.f;
    auto h6 = flat;
    h6[sch::kHeadroom] = -6.f;
    CHECK(outLevelDbAt(a, h0, 1000.0) - outLevelDbAt(b, h6, 1000.0) == doctest::Approx(6.0).epsilon(0.1));

    auto p6 = h0;
    p6[sch::kPostGainL] = p6[sch::kPostGainR] = 6.f;
    CHECK(outLevelDbAt(c, p6, 1000.0) - outLevelDbAt(a, h0, 1000.0) == doctest::Approx(6.0).epsilon(0.1));
}

TEST_CASE("kChannelMute slot silences one physical output") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[sch::kChannelMute] = 1.f;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    auto buf = stereoSine(500.0, 0.2, 8192);
    proc.process(buf.data(), buf.size());

    float evenPk = 0.f, oddPk = 0.f;
    for (std::size_t i = 0; i < buf.size(); ++i) {
        (i % 2 == 0 ? evenPk : oddPk) = std::max(i % 2 == 0 ? evenPk : oddPk, std::fabs(buf[i]));
    }
    // channelMute == 1 zeroes one side (which one is post the deliberate L/R swap); the other lives.
    CHECK(std::min(evenPk, oddPk) < 1e-4f);
    CHECK(std::max(evenPk, oddPk) > 0.01f);
}

TEST_CASE("kMeasurementMute slot drops the low band") {
    NativeBmwDspProcessor off, on;
    auto c0 = defaultConfig();
    c0[sch::kMeasurementMute] = 0.f;
    auto c1 = defaultConfig();
    c1[sch::kMeasurementMute] = 1.f;  // mute-low

    const float lowOff = outLevelDbAt(off, c0, 50.0);
    const float lowOn = outLevelDbAt(on, c1, 50.0);
    INFO("50 Hz: measmute off ", lowOff, " dB   on ", lowOn, " dB");
    CHECK(lowOff - lowOn > 20.f);
}

TEST_CASE("kRoutingBase / kRoutingStride locate the routing matrix") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    for (int i = 0; i < 4 * sch::kRoutingStride; ++i) c[sch::kRoutingBase + i] = 0.f;  // route nothing
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    auto buf = stereoSine(500.0, 0.3, 8192);
    proc.process(buf.data(), buf.size());
    CHECK(peakAbs(buf) < 1e-4f);
}

TEST_CASE("per-output block base/width/field offsets: crossover and mute") {
    // Mute the mid outputs, push the LOW crossover of both low outputs up to 320 Hz, and check a
    // 260 Hz tone -- which is ~24 dB down with the default 150 Hz corner -- comes back near unity.
    NativeBmwDspProcessor deflt, moved;
    auto base = defaultConfig();
    base[sch::kTiltEnabled] = 0.f;
    for (int out = 2; out < 4; ++out) {  // Mid Left, Mid Right
        base[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    auto up = base;
    for (int out = 0; out < 2; ++out) {  // Low Left, Low Right
        up[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverFreq] = 320.f;
    }

    const float at260Default = outLevelDbAt(deflt, base, 260.0);
    const float at260Moved = outLevelDbAt(moved, up, 260.0);
    INFO("260 Hz: default XO ", at260Default, " dB   XO@320 ", at260Moved, " dB");
    // Only the low crossover slot can lift a 260 Hz tone this much with the mid band muted.
    CHECK(at260Moved - at260Default > 5.f);
}

TEST_CASE("kMonoBassEnabled / kMonoBassFreq slots") {
    // Decorrelated (L = -R) 50 Hz: with Mono Bass on and its corner above 50 Hz, the mono sum is
    // ~0 there, so the tone collapses. Moving the corner below 50 lets it back through.
    auto probe = [](float enabled, float freq) {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        c[sch::kMonoBassEnabled] = enabled;
        c[sch::kMonoBassFreq] = freq;
        c[sch::kMonoBassBlend] = 100.f;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        auto warm = stereoSinePhased(50.0, 0.05, 24000, 0.0, kPi);  // L = -R
        proc.process(warm.data(), warm.size());
        auto win = stereoSinePhased(50.0, 0.05, 16384, 0.0, kPi);
        proc.process(win.data(), win.size());
        return linToDb(channelMagnitudeAt(win, 0, 50.0) / 0.05);
    };
    const double onHi = probe(1.f, 80.f);   // corner above 50 -> killed
    const double onLo = probe(1.f, 40.f);   // corner below 50 -> passes
    const double offv = probe(0.f, 80.f);   // disabled -> passes
    INFO("50 Hz decorrelated: off ", offv, "  on@40 ", onLo, "  on@80 ", onHi);
    CHECK(offv - onHi > 12.0);
    CHECK(onLo - onHi > 12.0);
}

TEST_CASE("kTiltEnabled / kTiltAmount / kTiltFreq slots") {
    auto balanceDb = [](float enabled, float amount, float freq) {
        // Low-vs-high balance: enable + amount move this a lot.
        NativeBmwDspProcessor a, b;
        auto c = defaultConfig();
        c[sch::kTiltEnabled] = enabled;
        c[sch::kTiltAmount] = amount;
        c[sch::kTiltFreq] = freq;
        return outLevelDbAt(a, c, 80.0) - outLevelDbAt(b, c, 6000.0);
    };
    const float flat = balanceDb(0.f, 0.f, 550.f);
    const float tilted = balanceDb(1.f, 6.f, 550.f);
    INFO("low-minus-high dB:  flat ", flat, "  tilt+6@550 ", tilted);
    CHECK(tilted - flat > 4.f);

    // kTiltFreq: probe at 900 Hz, which sits ABOVE a 550 Hz pivot (cut shelf) but BELOW a
    // 1500 Hz pivot (boost shelf) -- so the same +6 tilt reads several dB louder there when the
    // pivot moves up. 80/6000 Hz can't show this: they're saturated on both shelves either way.
    NativeBmwDspProcessor lo, hi;
    auto c550 = defaultConfig();
    c550[sch::kTiltEnabled] = 1.f;
    c550[sch::kTiltAmount] = 6.f;
    c550[sch::kTiltFreq] = 550.f;
    auto c1500 = c550;
    c1500[sch::kTiltFreq] = 1500.f;
    const float at900_550 = outLevelDbAt(lo, c550, 900.0);
    const float at900_1500 = outLevelDbAt(hi, c1500, 900.0);
    INFO("900 Hz: pivot 550 ", at900_550, " dB   pivot 1500 ", at900_1500, " dB");
    CHECK(at900_1500 - at900_550 > 3.f);
}

TEST_CASE("MBC block base/width/field offsets drive band 2 gain reduction") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[sch::kMbcEnabled] = 1.f;
    const int b2 = sch::kMbcBandsBase + 2 * sch::kMbcBandWidth;
    c[b2 + sch::kMbcBandEnabled] = 1.f;
    c[b2 + sch::kMbcBandThreshold] = -30.f;
    c[b2 + sch::kMbcBandRatio] = 6.f;
    c[b2 + sch::kMbcBandKnee] = 2.f;
    c[b2 + sch::kMbcBandAttack] = 5.f;
    c[b2 + sch::kMbcBandRelease] = 40.f;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    auto warm = stereoSine(1000.0, 0.2, 48000);  // 1 kHz -> band 2 (500..4000)
    proc.process(warm.data(), warm.size());
    auto tail = stereoSine(1000.0, 0.2, 8192);
    proc.process(tail.data(), tail.size());

    float m[12] = {};
    proc.readMbcMeter(m, 12);
    INFO("band2 GR = ", m[8], " dB");
    CHECK(m[8] > 3.f);            // clearly compressing
    CHECK(m[0 * 3 + 2] < 0.5f);   // band 0 untouched -> right band, right field offsets
}

TEST_CASE("kBusLimLowEnabled / kBusLimLowThreshold slots") {
    auto lowBusGr = [](float enabled, float threshold) {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        c[sch::kHeadroom] = 0.f;  // default -6 dB would drop 60 Hz under a -3 dBFS ceiling
        c[sch::kTiltEnabled] = 0.f;
        c[sch::kOutputConfigBase + 0 * sch::kOutputConfigWidth + sch::kOutCompressor] = 0.f;
        c[sch::kOutputConfigBase + 1 * sch::kOutputConfigWidth + sch::kOutCompressor] = 0.f;
        c[sch::kBusLimLowEnabled] = enabled;
        c[sch::kBusLimLowThreshold] = threshold;
        c[sch::kBusLimLowRelease] = 120.f;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        // ~ -0.4 dBFS at 60 Hz -> comfortably over even a -3 dBFS ceiling.
        auto warm = stereoSine(60.0, 0.95, 48000);
        proc.process(warm.data(), warm.size());
        float worst = 0.f;
        for (int i = 0; i < 16; ++i) {  // poll the tail so the per-cycle GR ripple can't fool us
            auto b = stereoSine(60.0, 0.95, 3000);
            proc.process(b.data(), b.size());
            float m[2] = {};
            proc.readBusLimiterMeter(m, 2);
            worst = std::max(worst, m[0]);
        }
        return worst;
    };
    CHECK(lowBusGr(0.f, -3.f) == doctest::Approx(0.0f));  // disabled -> no GR
    const float grHi = lowBusGr(1.f, -3.f);
    const float grLo = lowBusGr(1.f, -18.f);
    INFO("low bus GR: thr -3 -> ", grHi, "   thr -18 -> ", grLo);
    CHECK(grHi > 1.f);
    CHECK(grLo > grHi + 2.f);  // lower threshold -> more reduction
}
