#include "test_support.h"

#include <vector>

using namespace nbtest;

// Soft-knee downward-compression gain computer, transcribed from mbcBandGain / processCompressor
// (mirrored in Kotlin MultibandCompressorTree.targetGainReductionDb): slope = 1 - 1/max(1.001,
// ratio); quadratic blend across the +/- knee/2 window around threshold. The native detector adds
// RMS/peak smoothing, so the meter's reported GR tracks this to within ~1.5 dB, not exactly.
static double expectedGrDb(double detDb, double thrDb, double ratio, double kneeDb) {
    const double slope = 1.0 - 1.0 / std::max(1.001, ratio);
    const double over = detDb - thrDb;
    if (kneeDb > 0.0 && over > -kneeDb / 2.0 && over < kneeDb / 2.0) {
        const double x = over + kneeDb / 2.0;
        return slope * x * x / (2.0 * kneeDb);
    }
    return over <= -kneeDb / 2.0 ? 0.0 : slope * over;
}

static void enableMbcBand(std::array<float, kConfigSize>& c, int band, float threshold, float ratio,
                          float knee) {
    c[144] = 1.f;  // MBC global enable
    const int base = 149 + band * 8;
    c[base + 0] = 1.f;         // band enabled
    c[base + 1] = threshold;
    c[base + 2] = ratio;
    c[base + 3] = knee;
    c[base + 4] = 5.f;         // fast attack ms
    c[base + 5] = 50.f;        // fast release ms
    c[base + 6] = 0.f;         // makeup dB
    c[base + 7] = 1.f;         // stereo link
}

static std::array<float, 3> bandMeterAfter(double toneHz, double amp, float thr, float ratio,
                                           float knee) {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[5] = 0.f;                                              // headroom 0 dB
    c[25] = 0.f;                                             // tilt off
    c[28] = c[35] = c[93] = c[106] = c[119] = c[132] = 0.f;  // no other dynamics
    enableMbcBand(c, 2, thr, ratio, knee);                   // band 2 spans 500..4000 Hz

    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    auto warm = stereoSine(toneHz, amp, 48000);  // ~1 s to settle attack/release
    proc.process(warm.data(), warm.size());
    auto tail = stereoSine(toneHz, amp, 8192);
    proc.process(tail.data(), tail.size());

    float m[12] = {};
    proc.readMbcMeter(m, 12);
    return {m[6], m[7], m[8]};  // band 2: inputDb, outputDb, gainReductionDb
}

TEST_CASE("MBC band gain reduction follows the soft-knee gain computer") {
    const float thr = -18.f, ratio = 4.f, knee = 6.f;
    const double levelsDb[] = {-34.0, -24.0, -19.0, -15.0, -8.0};

    std::vector<double> gr;
    for (double ampDb : levelsDb) {
        const auto m = bandMeterAfter(1000.0, std::pow(10.0, ampDb / 20.0), thr, ratio, knee);
        const double inDb = m[0], outDb = m[1], grDb = m[2];
        const double expect = expectedGrDb(inDb, thr, ratio, knee);
        INFO("ampDb=", ampDb, "  meter in=", inDb, " out=", outDb, " GR=", grDb, "  expect~", expect);

        CHECK(std::fabs(grDb - (inDb - outDb)) < 0.1);   // GR is in - out by construction
        CHECK(grDb >= -0.02);                            // downward comp never adds gain
        CHECK(std::fabs(grDb - expect) < 1.5);           // tracks the soft-knee curve
        gr.push_back(grDb);
    }

    // Well below threshold -> essentially no reduction; well above -> clear reduction; monotone.
    CHECK(gr.front() < 0.5);
    CHECK(gr.back() > 4.0);
    for (std::size_t i = 1; i < gr.size(); ++i) CHECK(gr[i] >= gr[i - 1] - 0.1);
}

TEST_CASE("MBC meter is idle while the compressor is globally disabled") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();  // MBC off by default
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    auto sig = stereoSine(1000.0, 0.3, 16000);
    proc.process(sig.data(), sig.size());

    float m[12] = {};
    proc.readMbcMeter(m, 12);
    for (int b = 0; b < 4; ++b) {
        CHECK(m[b * 3 + 0] == doctest::Approx(-60.0f));
        CHECK(m[b * 3 + 1] == doctest::Approx(-60.0f));
        CHECK(m[b * 3 + 2] == doctest::Approx(0.0f));
    }
}
