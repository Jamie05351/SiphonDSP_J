#include "test_support.h"

#include <algorithm>
#include <cmath>
#include <vector>

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
    CHECK(pk <= kCeiling * 1.001f);      // lookahead peak hold: no overshoot past the ceiling
    CHECK(pk > 0.6f);                    // ...but it is clearly still limiting, not gating to silence
}

// Drives a bare MasterLimiter (-1 dBFS) with the same signal on both channels, returns its output.
static std::vector<float> runMasterLimiter(const std::vector<float>& mono) {
    NativeBmwDsp::MasterLimiter lim;
    lim.rebuild(kSampleRate, -1.f);
    lim.clear();
    std::vector<float> out(mono.size());
    for (std::size_t i = 0; i < mono.size(); ++i) {
        float l = mono[i], r = mono[i];
        lim.process(l, r);
        out[i] = std::max(std::fabs(l), std::fabs(r));
    }
    return out;
}

TEST_CASE("master limiter catches a single-sample spike") {
    std::vector<float> in(4800, 0.f);
    in[2000] = 2.f;   // +7 dB over the ceiling for one sample
    auto out = runMasterLimiter(in);
    const float pk = *std::max_element(out.begin(), out.end());
    INFO("peak out = ", pk);
    CHECK(pk <= kCeiling * 1.001f);
    CHECK(pk > 0.5f);  // turned down, not removed
}

TEST_CASE("master limiter catches a 1 ms burst shorter than its lookahead") {
    std::vector<float> in(9600, 0.f);
    for (int i = 0; i < 48; ++i) {
        in[3000 + i] = 2.f * static_cast<float>(std::sin(2 * 3.14159265358979 * 1000.0 * i / kSampleRate));
    }
    auto out = runMasterLimiter(in);
    const float pk = *std::max_element(out.begin(), out.end());
    INFO("peak out = ", pk);
    CHECK(pk <= kCeiling * 1.001f);
}

TEST_CASE("master limiter catches inter-sample (true) peaks") {
    // fs/4 sine at 45 degrees: every sample is at 0.707 of the waveform's real peak, so a
    // sample-peak limiter sees 0.85 (under the 0.891 ceiling) while the DAC outputs 1.2.
    const double amp = 1.2;
    std::vector<float> in(9600);
    for (std::size_t i = 0; i < in.size(); ++i) {
        in[i] = static_cast<float>(amp * std::sin(3.14159265358979 / 2 * i + 3.14159265358979 / 4));
    }
    NativeBmwDsp::MasterLimiter lim;
    lim.rebuild(kSampleRate, -1.f);
    lim.clear();
    float worstTruePeak = 0.f;
    float prev = 0.f;
    for (std::size_t i = 0; i < in.size(); ++i) {
        float l = in[i], r = in[i];
        lim.process(l, r);
        // At fs/4 consecutive samples are 90 degrees apart, so they give the sine's amplitude.
        if (i > 2400) {
            worstTruePeak = std::max(worstTruePeak, std::sqrt(l * l + prev * prev));
        }
        prev = l;
    }
    INFO("reconstructed peak out = ", worstTruePeak, "  ceiling = ", kCeiling);
    CHECK(worstTruePeak <= kCeiling);  // the detector's margin covers its own interpolation error
    CHECK(worstTruePeak > 0.8f);
}

TEST_CASE("master limiter holds the ceiling on a near-Nyquist true peak") {
    // Review case: 19.2 kHz (0.4 fs) at phase 3pi/2. A 4-phase detector read at most 0.951 of its
    // real peak here and let the output sit ~0.44 dB over the ceiling.
    const double fs = kSampleRate, f = 19200.0, amp = 1.2;
    const double kPi = 3.14159265358979;
    NativeBmwDsp::MasterLimiter lim;
    lim.rebuild(kSampleRate, -1.f);
    lim.clear();
    // The output's amplitude at f, from its projection over whole cycles (0.4 fs -> 2 cycles
    // every 5 samples) once the limiter has settled.
    const std::size_t total = 19200, settle = 9600;
    double sinSum = 0, cosSum = 0;
    for (std::size_t i = 0; i < total; ++i) {
        float l = static_cast<float>(amp * std::sin(2 * kPi * f / fs * i + 3 * kPi / 2)), r = l;
        lim.process(l, r);
        if (i >= settle) {
            sinSum += l * std::sin(2 * kPi * f / fs * i);
            cosSum += l * std::cos(2 * kPi * f / fs * i);
        }
    }
    const double n = static_cast<double>(total - settle);
    const double outAmp = 2 * std::sqrt(sinSum * sinSum + cosSum * cosSum) / n;
    INFO("reconstructed amplitude out = ", outAmp, "  ceiling = ", kCeiling);
    CHECK(outAmp <= kCeiling);
    CHECK(outAmp > 0.8);
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

TEST_CASE("per-bus limiter: hold keeps gain flat across a bass cycle (no in-cycle modulation)") {
    // Low-bus limiter driven hot by 40 Hz at the fastest release. Without the hold, the target
    // gain swung back to 1 at every zero crossing and the 20 ms release let the gain recover
    // between crests: ~0.7 dB of gain ripple per cycle, i.e. added harmonics. With the 25 ms
    // hold the gain sits still once settled.
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    quietUpstream(c);
    c[182] = 1.f; c[183] = -6.f; c[184] = 20.f;   // low bus limiter on, fastest release
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    // One continuous tone (no phase resets between chunks), processed in 32-frame chunks so the
    // meter -- republished whenever the gain moves -- is sampled ~37 times per 40 Hz cycle.
    auto sig = stereoSine(40.0, 0.9, 96000);
    constexpr std::size_t kWarmFrames = 48000, kChunkFrames = 32;
    proc.process(sig.data(), kWarmFrames * 2);
    float grMin = 1e9f, grMax = -1e9f;
    for (std::size_t f = kWarmFrames; f + kChunkFrames <= 96000; f += kChunkFrames) {
        proc.process(sig.data() + f * 2, kChunkFrames * 2);
        float m[2] = {0.f, 0.f};
        proc.readBusLimiterMeter(m, 2);
        grMin = std::min(grMin, m[0]);
        grMax = std::max(grMax, m[0]);
    }
    INFO("low-bus GR range over 1 s: ", grMin, " .. ", grMax, " dB");
    CHECK(grMin > 1.0f);                // it is limiting
    CHECK(grMax - grMin < 0.05f);       // and the gain is flat across the cycle
}
