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

TEST_CASE("kHighGainL/kHighGainR slots shape the High band's level") {
    // Isolate High (mute Low/Mid, un-bypass/un-mute High), then prove kHighGainL/R actually
    // shape its level -- not just that some field at those indices exists.
    auto base = defaultConfig();
    base[sch::kTiltEnabled] = 0.f;
    for (int out = 0; out < 4; ++out) {
        base[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    for (int slot = 0; slot < 2; ++slot) {
        base[sch::kHighOutputConfigBase + slot * sch::kOutputConfigWidth + sch::kOutMuted] = 0.f;
    }
    base[sch::kHighXoPass] = 0.f;

    auto boosted = base;
    boosted[sch::kHighGainL] = 6.f;
    boosted[sch::kHighGainR] = 6.f;
    NativeBmwDspProcessor unityProc, boostedProc;
    unityProc.setSampleRate(kSampleRate);
    boostedProc.setSampleRate(kSampleRate);
    const float unityDb = outLevelDbAt(unityProc, base, 3000.0);
    const float boostedDb = outLevelDbAt(boostedProc, boosted, 3000.0);
    CHECK(boostedDb - unityDb == doctest::Approx(6.f).epsilon(0.05));
}

TEST_CASE("kHighXoPass alone fully silences High, unlike kLpfPass/kHpfPass's filter-only bypass") {
    // Deliberately NOT the same contract as the existing kLpfPass/kHpfPass flags (see
    // processFrame()'s own comment on that, and the design doc's note on why this flag's
    // semantics differ for High specifically): a tweeter with no HPF ahead of it is a real
    // speaker-damage risk from raw bass, and kHighXoPass doubles as the 3-way master-off
    // switch's single write for High, so it must silence the band on its own -- proven here with
    // mute left explicitly off.
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    for (int out = 0; out < 4; ++out) {
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    for (int slot = 0; slot < 2; ++slot) {
        c[sch::kHighOutputConfigBase + slot * sch::kOutputConfigWidth + sch::kOutMuted] = 0.f;
    }
    c[sch::kHighXoPass] = 1.f;
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));
    auto buf = stereoSine(3000.0, 0.3, 8192, kSampleRate);
    proc.process(buf.data(), buf.size());
    CHECK(peakAbs(buf) < 1e-4f);
}

TEST_CASE("kHighRoutingBase locates High's routing coefficients") {
    // Isolate High, then zero just its routing coefficients (215..218) -- if those are really
    // what feeds High's input, the output must go silent despite High's crossover/gain/mute
    // otherwise being fully "on".
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    for (int out = 0; out < 4; ++out) {
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    c[sch::kHighXoPass] = 0.f;
    for (int slot = 0; slot < 2; ++slot) {
        c[sch::kHighOutputConfigBase + slot * sch::kOutputConfigWidth + sch::kOutMuted] = 0.f;
    }
    for (int i = 0; i < 4; ++i) {
        c[sch::kHighRoutingBase + i] = 0.f;
    }
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));
    auto buf = stereoSine(3000.0, 0.3, 8192, kSampleRate);
    proc.process(buf.data(), buf.size());
    CHECK(peakAbs(buf) < 1e-4f);
}

TEST_CASE("kMidUpperXo block enables and locates Mid's upper (Mid/High) bandpass corner") {
    // Isolate Mid (mute Low), enable the upper corner well below a test tone, and check that tone
    // comes back down heavily -- only kMidUpperXoEnabled/kMidUpperXoFreq can do that, since the
    // (untouched) lower HPF corner stays at its default 150 Hz and would otherwise pass it.
    NativeBmwDspProcessor off, on;
    auto base = defaultConfig();
    base[sch::kTiltEnabled] = 0.f;
    for (int out = 0; out < 2; ++out) {  // Low Left, Low Right
        base[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    auto enabled = base;
    for (int out = 2; out < 4; ++out) {  // Mid Left, Mid Right
        const int slot = out - 2;
        enabled[sch::kMidUpperXo + slot * sch::kMidUpperXoWidth + sch::kMidUpperXoFreq] = 1000.f;
        enabled[sch::kMidUpperXo + slot * sch::kMidUpperXoWidth + sch::kMidUpperXoEnabled] = 1.f;
    }

    const float at4kOff = outLevelDbAt(off, base, 4000.0);
    const float at4kOn = outLevelDbAt(on, enabled, 4000.0);
    INFO("4 kHz: upper XO off ", at4kOff, " dB   upper XO@1kHz on ", at4kOn, " dB");
    CHECK(at4kOff - at4kOn > 10.f);
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

TEST_CASE("kMasterLimiterEnabled / kMasterLimiterThreshold slots") {
    auto run = [](float enabled, float threshold) {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        c[sch::kHeadroom] = 0.f;
        c[sch::kTiltEnabled] = 0.f;
        c[sch::kPostGainL] = c[sch::kPostGainR] = 6.f;  // drive well past any ceiling
        c[sch::kMasterLimiterEnabled] = enabled;
        c[sch::kMasterLimiterThreshold] = threshold;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        auto warm = stereoSine(220.0, 0.9, 48000);
        proc.process(warm.data(), warm.size());
        auto win = stereoSine(220.0, 0.9, 32000);
        proc.process(win.data(), win.size());
        float m[1] = {};
        proc.readMasterLimiterMeter(m, 1);
        struct R {
            float peak, gr;
        };
        return R{peakAbs(win), m[0]};
    };

    const auto off = run(0.f, -6.f);
    const auto on6 = run(1.f, -6.f);   // ceiling ~0.501
    const auto on1 = run(1.f, -1.f);   // ceiling ~0.891

    INFO("bypass peak ", off.peak, " GR ", off.gr, " | on@-6 peak ", on6.peak, " GR ", on6.gr,
         " | on@-1 peak ", on1.peak, " GR ", on1.gr);
    CHECK(off.gr == doctest::Approx(0.0f));   // bypassed -> meter idle
    CHECK(off.peak > 1.0f);                   // bypassed -> nothing holds the level
    CHECK(on6.gr > 3.f);                      // enabled -> limiting, meter live
    CHECK(on6.peak < 0.6f);                   // held near the -6 dBFS ceiling
    CHECK(on1.peak > on6.peak + 0.2f);        // a higher threshold lets more through
}

TEST_CASE("kStageDelayLeftMs / kStageDelayRightMs delay one summed-bus side") {
    // Impulse through the chain; cross-correlate the two output channels. The per-channel
    // filters are identical, so any lag between ch0 and ch1 is pure sample delay from the
    // stage-centering lines. Note the deliberate final L/R swap: stageDelayLeft acts on oL,
    // which is written to output r (interleaved index 1), so an L delay makes ch1 lag ch0.
    auto lag = [](float stageL, float stageR) {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        c[sch::kStageDelayLeftMs] = stageL;
        c[sch::kStageDelayRightMs] = stageR;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        const std::size_t frames = 4096;
        std::vector<float> buf(frames * 2, 0.f);
        buf[0] = buf[1] = 1.f;
        proc.process(buf.data(), buf.size());
        int bestLag = 0;
        double best = -1e300;
        for (int L = -600; L <= 600; ++L) {
            double acc = 0;
            for (std::size_t n = 0; n < frames; ++n) {
                const long m = static_cast<long>(n) - L;
                if (m < 0 || static_cast<std::size_t>(m) >= frames) continue;
                acc += static_cast<double>(buf[n * 2 + 1]) * static_cast<double>(buf[m * 2 + 0]);
            }
            if (acc > best) { best = acc; bestLag = L; }
        }
        return bestLag;
    };
    const int ms4 = static_cast<int>(std::lround(4.0 * kSampleRate / 1000.0));  // 192 @ 48k
    INFO("lag 0/0 ", lag(0.f, 0.f), "  4/0 ", lag(4.f, 0.f), "  0/4 ", lag(0.f, 4.f));
    CHECK(std::abs(lag(0.f, 0.f)) <= 1);              // aligned
    CHECK(std::abs(lag(4.f, 0.f) - ms4) <= 1);        // L delay -> ch1 lags ch0
    CHECK(std::abs(lag(0.f, 4.f) + ms4) <= 1);        // R delay -> ch0 lags ch1
}

TEST_CASE("kMeasGenType slot injects a signal that fully replaces the real input") {
    // startHz == endHz degenerates the sweep to a pure tone -- lets this reuse the existing
    // steady-state magnitude helpers without needing a moving-frequency probe.
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    c[sch::kMeasGenType] = 1.f;
    c[sch::kMeasGenSweepStartHz] = 5000.f;
    c[sch::kMeasGenSweepEndHz] = 5000.f;
    c[sch::kMeasGenSweepDurationS] = 5.f;
    c[sch::kMeasGenSweepLevelDb] = 0.f;

    // Feed a loud 300 Hz tone as the "real" input -- if the generator is wired in correctly it
    // never reaches the output at all.
    auto out = renderSteadyState(proc, c, 300.0, 0.5);
    const double at300 = channelMagnitudeAt(out, 0, 300.0);
    const double at5000 = channelMagnitudeAt(out, 0, 5000.0);
    INFO("300 Hz (fed input) magnitude ", at300, "   5000 Hz (generator) magnitude ", at5000);
    CHECK(at300 < 0.01);   // fed input did not get through
    CHECK(at5000 > 0.1);   // generator's tone did
}

TEST_CASE("kMeasGenSweep* slots actually sweep frequency over time") {
    NativeBmwDspProcessor early, late;
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    c[sch::kMeasGenType] = 1.f;
    c[sch::kMeasGenSweepStartHz] = 200.f;
    c[sch::kMeasGenSweepEndHz] = 8000.f;
    c[sch::kMeasGenSweepDurationS] = 2.f;
    c[sch::kMeasGenSweepLevelDb] = 0.f;

    early.setSampleRate(kSampleRate);
    REQUIRE(early.configure(c.data(), c.size()));
    auto earlySkip = stereoSine(0.0, 0.0, 500);  // content irrelevant; the generator overrides it
    early.process(earlySkip.data(), earlySkip.size());
    auto earlyWindow = stereoSine(0.0, 0.0, 4096);
    early.process(earlyWindow.data(), earlyWindow.size());

    late.setSampleRate(kSampleRate);
    REQUIRE(late.configure(c.data(), c.size()));
    auto lateSkip = stereoSine(0.0, 0.0, static_cast<std::size_t>(1.9 * kSampleRate));
    late.process(lateSkip.data(), lateSkip.size());
    auto lateWindow = stereoSine(0.0, 0.0, 4096);
    late.process(lateWindow.data(), lateWindow.size());

    const double earlyAt200 = channelMagnitudeAt(earlyWindow, 0, 200.0);
    const double earlyAt8000 = channelMagnitudeAt(earlyWindow, 0, 8000.0);
    const double lateAt200 = channelMagnitudeAt(lateWindow, 0, 200.0);
    const double lateAt8000 = channelMagnitudeAt(lateWindow, 0, 8000.0);
    INFO("early: 200Hz=", earlyAt200, " 8000Hz=", earlyAt8000, "   late: 200Hz=", lateAt200,
         " 8000Hz=", lateAt8000);
    CHECK(earlyAt200 > earlyAt8000 * 4.0);  // starts near 200 Hz
    CHECK(lateAt8000 > lateAt200 * 4.0);    // ends near 8000 Hz
}

TEST_CASE("kMeasGenType pink noise slot injects a signal that fully replaces the real input") {
    // Pink noise legitimately carries broadband energy at 300 Hz -- that's the point of it being
    // pink, not silence -- so an absolute "magnitude at 300 Hz must be near zero" threshold is the
    // wrong kind of check here: configurePink() reseeds its RNG every call (std::random_device),
    // so a different run can land a noisier draw at that one bin purely by chance and trip a tight
    // absolute bound (this happened in CI: 0.0274 against a 0.01 threshold, not a real bug).
    // Comparing against a generator-off control on the same config sidesteps the RNG entirely: if
    // the fed tone actually got through, its magnitude would sit close to the (deterministic,
    // sine-passthrough) off-case regardless of what the noise realization happened to look like.
    NativeBmwDspProcessor withGenerator, withoutGenerator;
    auto cOn = defaultConfig();
    cOn[sch::kTiltEnabled] = 0.f;
    cOn[sch::kMeasGenType] = 2.f;
    cOn[sch::kMeasGenPinkPeriodS] = 0.5f;
    cOn[sch::kMeasGenPinkLevelDb] = 0.f;
    auto cOff = defaultConfig();
    cOff[sch::kTiltEnabled] = 0.f;  // generator stays at its default (off)

    // A longer measurement window narrows the DFT bin (and so the noise power it captures),
    // further reducing the on-case's run-to-run variance on top of the relative-comparison fix.
    const std::size_t longWindow = 65536;
    const double magnitudeOn = channelMagnitudeAt(
        renderSteadyState(withGenerator, cOn, 300.0, 0.5, 24000, longWindow), 0, 300.0);
    const double magnitudeOff = channelMagnitudeAt(
        renderSteadyState(withoutGenerator, cOff, 300.0, 0.5, 24000, longWindow), 0, 300.0);
    INFO("300 Hz magnitude -- generator on ", magnitudeOn, "   generator off ", magnitudeOff);
    CHECK(magnitudeOn < magnitudeOff * 0.4);  // nowhere near as strong as when the tone gets through
}

TEST_CASE("kMeasGenPink* slots produce a signal that repeats every period") {
    // Periodicity is the whole point (it's what lets the capture side average synchronously), so
    // this checks two consecutive periods, well after any filter-startup transient (DC blocker,
    // crossover, allpass, limiters) has settled into its own periodic steady state, are
    // near-identical.
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    c[sch::kMeasGenType] = 2.f;
    c[sch::kMeasGenPinkPeriodS] = 0.1f;  // short period -> fast test, plenty of repeats to settle
    c[sch::kMeasGenPinkLevelDb] = 0.f;

    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));
    const std::size_t periodFrames = static_cast<std::size_t>(0.1 * kSampleRate);

    auto warm = stereoSine(0.0, 0.0, periodFrames * 10);  // content irrelevant; generator overrides
    proc.process(warm.data(), warm.size());

    auto periodA = stereoSine(0.0, 0.0, periodFrames);
    proc.process(periodA.data(), periodA.size());
    auto periodB = stereoSine(0.0, 0.0, periodFrames);
    proc.process(periodB.data(), periodB.size());

    double sumSq = 0.0, diffSq = 0.0;
    for (std::size_t i = 0; i < periodA.size(); ++i) {
        sumSq += static_cast<double>(periodA[i]) * periodA[i];
        const double d = periodA[i] - periodB[i];
        diffSq += d * d;
    }
    const double relError = std::sqrt(diffSq / std::max(sumSq, 1e-12));
    INFO("period-to-period relative error ", relError);
    CHECK(relError < 0.01);  // two consecutive periods are (near-)identical
}

// Timing-reference cycle geometry (must track NativeBmwMeasurementGenerator's own constants):
// lead silence, then the first timing chirp.
constexpr double kTimingRefLeadSilenceS = 2.9016;
constexpr double kTimingRefChirpDurationS = 0.340813;

static float peakAbsChannel(const std::vector<float>& interleaved, int channel) {
    float pk = 0.f;
    for (std::size_t i = channel; i < interleaved.size(); i += 2) {
        pk = std::max(pk, std::fabs(interleaved[i]));
    }
    return pk;
}

TEST_CASE("kMeasGenTimingRefEnabled starts silent, then produces a timing chirp") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    c[sch::kMeasGenType] = 1.f;
    c[sch::kMeasGenTimingRefEnabled] = 1.f;
    c[sch::kMeasGenSweepLevelDb] = 0.f;

    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    // Well inside the lead silence (which runs ~2.9 s) -- content irrelevant, generator overrides.
    auto leadWindow = stereoSine(0.0, 0.0, 4096);
    proc.process(leadWindow.data(), leadWindow.size());
    const float leadPeak = peakAbs(leadWindow);
    INFO("peak during lead silence ", leadPeak);
    CHECK(leadPeak < 0.01f);  // still silent -- the sequence hasn't reached the chirp yet

    // Skip to just past the lead silence, then land solidly inside the first chirp (skipping its
    // own short fade-in).
    const std::size_t leadSamples = static_cast<std::size_t>(kTimingRefLeadSilenceS * kSampleRate);
    auto skipToChirp = stereoSine(0.0, 0.0, leadSamples + 500);
    proc.process(skipToChirp.data(), skipToChirp.size());
    auto chirpWindow = stereoSine(0.0, 0.0, 2048);
    proc.process(chirpWindow.data(), chirpWindow.size());
    const float chirpPeak = peakAbs(chirpWindow);
    INFO("peak inside first chirp ", chirpPeak);
    CHECK(chirpPeak > 0.1f);  // the chirp is playing now
}

TEST_CASE("kMeasGenTimingRefSplitChannels routes the chirp and sweep to different channels") {
    NativeBmwDspProcessor combined, split;
    auto cCombined = defaultConfig();
    cCombined[sch::kTiltEnabled] = 0.f;
    cCombined[sch::kMeasGenType] = 1.f;
    cCombined[sch::kMeasGenTimingRefEnabled] = 1.f;
    cCombined[sch::kMeasGenTimingRefSplitChannels] = 0.f;
    cCombined[sch::kMeasGenSweepLevelDb] = 0.f;
    auto cSplit = cCombined;
    cSplit[sch::kMeasGenTimingRefSplitChannels] = 1.f;

    combined.setSampleRate(kSampleRate);
    split.setSampleRate(kSampleRate);
    REQUIRE(combined.configure(cCombined.data(), cCombined.size()));
    REQUIRE(split.configure(cSplit.data(), cSplit.size()));

    const std::size_t leadSamples = static_cast<std::size_t>(kTimingRefLeadSilenceS * kSampleRate);
    auto skip1 = stereoSine(0.0, 0.0, leadSamples + 500);
    combined.process(skip1.data(), skip1.size());
    auto skip2 = stereoSine(0.0, 0.0, leadSamples + 500);
    split.process(skip2.data(), skip2.size());

    auto combinedChirp = stereoSine(0.0, 0.0, 2048);
    combined.process(combinedChirp.data(), combinedChirp.size());
    auto splitChirp = stereoSine(0.0, 0.0, 2048);
    split.process(splitChirp.data(), splitChirp.size());

    // processFrame() ends with a deliberate hardware L/R swap (see its own "DO NOT REMOVE OR
    // FIX THIS" comment, correcting the target vehicle's physically-reversed speaker harness):
    // content fed into the generator's `l` parameter (the app's own "Left"/reference-channel
    // convention, matching every other Left-labeled control) ends up written to raw output
    // buffer index 1, not 0. So "reference channel" below means buffer index 1, the "sweep
    // channel" buffer index 0 -- the opposite of the raw index a naive stereo-file reading
    // would suggest, but correct for the app's logical Left/Right, which is what matters for
    // matching the refL convention and the Channel Isolation control.
    const float combinedIdx0 = peakAbsChannel(combinedChirp, 0), combinedIdx1 = peakAbsChannel(combinedChirp, 1);
    const float splitRef = peakAbsChannel(splitChirp, 1), splitSweepCh = peakAbsChannel(splitChirp, 0);
    INFO("combined: idx0=", combinedIdx0, " idx1=", combinedIdx1, "   split: ref(idx1)=", splitRef,
         " sweep(idx0)=", splitSweepCh);
    CHECK(combinedIdx0 > 0.1f);
    CHECK(combinedIdx1 > 0.1f);  // combined design: chirp plays on both channels
    CHECK(splitRef > 0.1f);
    CHECK(splitSweepCh < 0.01f);  // split design: chirp is reference-channel only

    // Skip ahead into the Mid sweep segment (past chirp1 + its gap) and confirm the split design
    // puts the sweep on the opposite channel from the chirp.
    const std::size_t chirpSamples = static_cast<std::size_t>(kTimingRefChirpDurationS * kSampleRate);
    const std::size_t gapBeforeSweep = static_cast<std::size_t>(0.32 * kSampleRate);
    // Already consumed leadSamples+500+2048 frames; the chirp segment itself is chirpSamples long,
    // so advance the remainder of the chirp plus the gap plus a fade-in margin to land inside the
    // sweep.
    const std::size_t alreadyConsumed = leadSamples + 500 + 2048;
    const std::size_t chirpEnd = leadSamples + chirpSamples;
    const std::size_t sweepStart = chirpEnd + gapBeforeSweep;
    REQUIRE(sweepStart > alreadyConsumed);
    auto skip3 = stereoSine(0.0, 0.0, sweepStart - alreadyConsumed + 500);
    split.process(skip3.data(), skip3.size());
    auto splitSweep = stereoSine(0.0, 0.0, 2048);
    split.process(splitSweep.data(), splitSweep.size());
    // Same swap as above: reference channel is buffer index 1, sweep channel is index 0.
    const float splitSweepRef = peakAbsChannel(splitSweep, 1), splitSweepSweep = peakAbsChannel(splitSweep, 0);
    INFO("split during sweep: ref(idx1)=", splitSweepRef, " sweep(idx0)=", splitSweepSweep);
    CHECK(splitSweepRef < 0.01f);   // reference channel is silent during the sweep
    CHECK(splitSweepSweep > 0.1f);  // sweep plays on the other channel
}
