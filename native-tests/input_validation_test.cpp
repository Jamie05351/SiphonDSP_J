#include "test_support.h"

#include <limits>

using namespace nbtest;

// Three real input-validation gaps found in a code-review audit, all in the PEQ/MBC config path.

TEST_CASE("makePeq rejects an extreme-but-finite bell gain instead of silently zeroing the band") {
    // Bell (type 0): A = pow(10, gainDb/40). A sufficiently negative-but-finite gainDb underflows
    // A to exactly 0.0 in double precision, making k = 1/(Q*A) = +Infinity and m1 = k*(A*A-1)
    // non-finite, while g stays small enough that a1/a2/a3 alone still come out finite -- the
    // bug this test guards was checking only a1/a2/a3, letting a non-finite m1 through to
    // Biquad::run where m1*v1 becomes NaN every sample, silently flushed to 0 by ftzd() instead
    // of the malformed config being rejected.
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));

    constexpr double kExtremeGainDb = -20000.0;  // finite, but A underflows to 0.0
    const double band[5] = {1000.0, kExtremeGainDb, 2.0, 0.0, 0.0};
    CHECK_FALSE(proc.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0, nullptr, 0));
}

TEST_CASE("configurePeq rejects a non-finite band type or channel instead of casting it") {
    // v[i+3] (type) and v[i+4] (channel) are cast to int; casting NaN/Infinity to int is UB
    // ([conv.fpint]). Both must be validated before either cast, not left to makePeq()'s own
    // finite checks (which only cover f/gainDb/Q).
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));

    const double nanType[5] = {1000.0, 3.0, 1.0, std::numeric_limits<double>::quiet_NaN(), 0.0};
    CHECK_FALSE(proc.configurePeq(true, 0.f, nanType, 5, nullptr, 0, nullptr, 0, nullptr, 0));

    const double infChannel[5] = {1000.0, 3.0, 1.0, 0.0, std::numeric_limits<double>::infinity()};
    CHECK_FALSE(proc.configurePeq(true, 0.f, infChannel, 5, nullptr, 0, nullptr, 0, nullptr, 0));

    const double hugeType[5] = {1000.0, 3.0, 1.0, std::numeric_limits<double>::max(), 0.0};
    CHECK_FALSE(proc.configurePeq(true, 0.f, hugeType, 5, nullptr, 0, nullptr, 0, nullptr, 0));

    const double hugeChannel[5] = {1000.0, 3.0, 1.0, 0.0, -std::numeric_limits<double>::max()};
    CHECK_FALSE(proc.configurePeq(true, 0.f, hugeChannel, 5, nullptr, 0, nullptr, 0, nullptr, 0));
}

TEST_CASE("configure rejects a non-finite MBC crossover before sorting") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[146] = std::numeric_limits<float>::quiet_NaN();
    CHECK_FALSE(proc.configure(c.data(), c.size()));
}

TEST_CASE("MBC crossover splits sent out of order behave identically to the same splits sorted") {
    // configure() now sorts mbcXo ascending, so any permutation of the same 3 split frequencies
    // must produce byte-identical downstream crossover behavior. Before the fix, an out-of-order
    // triple reached rebuildMbc()'s sequential spacing-clamp chain as-is, which silently pushed
    // the later value up to fit -- a different (and confusing) result than sending the values
    // already sorted, even though the caller's intent (three split points) was the same set.
    auto meterFor = [](float xo0, float xo1, float xo2) {
        NativeBmwDspProcessor proc;
        auto c = defaultConfig();
        c[5] = 0.f;                                              // headroom 0 dB
        c[25] = 0.f;                                             // tilt off
        c[28] = c[35] = c[93] = c[106] = c[119] = c[132] = 0.f;  // no other dynamics
        c[144] = 1.f;                                            // MBC global enable
        c[146] = xo0;
        c[147] = xo1;
        c[148] = xo2;
        const int base = 149 + 2 * 8;  // band 2
        c[base + 0] = 1.f;             // band enabled
        c[base + 1] = -18.f;           // threshold
        c[base + 2] = 4.f;             // ratio
        c[base + 3] = 6.f;             // knee
        c[base + 4] = 5.f;             // attack ms
        c[base + 5] = 50.f;            // release ms
        c[base + 6] = 0.f;             // makeup
        c[base + 7] = 1.f;             // stereo link

        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        auto warm = stereoSine(1000.0, 0.1, 48000);
        proc.process(warm.data(), warm.size());
        auto tail = stereoSine(1000.0, 0.1, 8192);
        proc.process(tail.data(), tail.size());

        float m[12] = {};
        proc.readMbcMeter(m, 12);
        return m[8];  // band 2 gain reduction dB
    };

    const float sortedGr = meterFor(80.f, 500.f, 4000.f);
    const float swappedGr = meterFor(500.f, 80.f, 4000.f);  // same 3 values, first two swapped
    INFO("sorted-input GR=", sortedGr, " dB  swapped-input GR=", swappedGr, " dB");
    CHECK(sortedGr == doctest::Approx(swappedGr).epsilon(0.01));

    const float boundedSortedGr = meterFor(20.f, 30.f, 4000.f);
    const float boundedPermutedGr = meterFor(4000.f, 20.f, 30.f);
    INFO("bounded sorted-input GR=", boundedSortedGr,
         " dB  bounded permuted-input GR=", boundedPermutedGr, " dB");
    CHECK(boundedSortedGr == doctest::Approx(boundedPermutedGr).epsilon(0.01));
}
