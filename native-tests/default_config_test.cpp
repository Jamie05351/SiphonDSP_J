#include "test_support.h"

using namespace nbtest;

TEST_CASE("default config is accepted and the size guard rejects a wrong length") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);

    auto cfg = defaultConfig();
    CHECK(proc.configure(cfg.data(), cfg.size()));

    // NativeBmwDspProcessor::kConfigSize must equal NativeBmwDspValues.SIZE; anything else is
    // a torn/stale array from the Kotlin side and must be rejected wholesale.
    CHECK_FALSE(proc.configure(cfg.data(), cfg.size() - 1));
    CHECK_FALSE(proc.configure(cfg.data(), cfg.size() + 1));
}

// A config with everything that colours the response switched off: the LR4 low/mid split, unity
// gains, no subsonic, no tilt, no compressors, no mono bass, no MBC. What's left is just the
// crossover reconstructing to flat.
static std::array<float, kConfigSize> flatConfig() {
    auto c = defaultConfig();
    c[5] = 0.f;                       // headroom 0 dB
    c[8] = c[9] = 0.f;                // mid gain L/R -> 0 dB (default is -1, which is a real step at XO)
    c[12] = 0.f;                      // global subsonic off
    c[25] = 0.f;                      // tilt off
    c[28] = c[35] = 0.f;             // legacy low/mid compressor off
    c[89] = c[102] = 0.f;           // per-output subsonic off (Low L/R)
    c[93] = c[106] = 0.f;           // per-output compressor off (Low L/R)
    c[119] = c[132] = 0.f;          // per-output compressor off (Mid L/R)
    return c;
}

TEST_CASE("LR4 low/mid crossover sums flat through the handoff") {
    NativeBmwDspProcessor proc;
    auto cfg = flatConfig();

    const double freqs[] = {60, 90, 130, 150, 175, 220, 400, 1000, 4000, 12000};
    const double amp = 0.05;  // well clear of the -1 dBFS master limiter

    double ref = 0.0;
    std::vector<double> db;
    for (double f : freqs) {
        auto out = renderSteadyState(proc, cfg, f, amp);
        const double m = channelMagnitudeAt(out, 0, f);
        db.push_back(linToDb(m));
        if (f == 1000.0) ref = linToDb(m);
    }

    // Normalise to the 1 kHz (pure mid-band) point and require the whole sweep -- crucially the
    // 130/150/175 Hz points straddling the 150 Hz crossover -- to sit within +/-0.6 dB of it.
    for (std::size_t i = 0; i < db.size(); ++i) {
        const double rel = db[i] - ref;
        INFO("f=", freqs[i], " Hz  rel=", rel, " dB");
        CHECK(std::fabs(rel) < 0.6);
    }
}

// Regression test for the DF2T->SVF biquad migration (Low band): the subsonic HPF is a 2nd-order
// Butterworth (Q=1/sqrt(2)), so |H(f)|^2 = (f/fc)^4 / (1+(f/fc)^4) is its exact theoretical
// response. Measuring with-subsonic vs without-subsonic on otherwise-identical configs cancels
// out everything else in the chain (crossover, headroom, ...), isolating just the HPF's own
// contribution -- so this only passes if the migrated SVF high-pass still matches the same
// Butterworth corner the old RBJ DF2T high-pass did.
//
// Enables subsonic on BOTH Low outputs (indices 0 and 1), not just one: NativeBmwDspProcessor's
// final output stage does a deliberate L/R swap correcting the target vehicle's reversed speaker
// harness ("DO NOT REMOVE OR FIX THIS", see processFrame), so channel 0 of the interleaved output
// carries the *Right*-side chain -- enabling only Low Left and measuring channel 0 would silently
// measure the untouched side. Symmetric L/R config, like flatConfig()'s own subsonic-off lines,
// sidesteps needing to know which physical side maps to which buffer channel.
TEST_CASE("Low subsonic HPF matches the theoretical 2nd-order Butterworth corner") {
    auto cfgOn = flatConfig();
    auto cfgOff = flatConfig();
    constexpr float kSubsonicFreq = 32.f;
    const int lowLeftBase = nbschema::kOutputConfigBase;
    const int lowRightBase = nbschema::kOutputConfigBase + nbschema::kOutputConfigWidth;
    for (int base : {lowLeftBase, lowRightBase}) {
        cfgOn[base + nbschema::kOutSubsonicEnabled] = 1.f;
        cfgOn[base + nbschema::kOutSubsonicFreq] = kSubsonicFreq;
    }

    const double freqs[] = {12, 16, 24, 32, 48, 64, 96, 130};
    const double amp = 0.05;

    for (double f : freqs) {
        NativeBmwDspProcessor withSub, withoutSub;
        const double magOn = channelMagnitudeAt(renderSteadyState(withSub, cfgOn, f, amp), 0, f);
        const double magOff = channelMagnitudeAt(renderSteadyState(withoutSub, cfgOff, f, amp), 0, f);
        const double measuredDb = linToDb(magOn) - linToDb(magOff);

        const double ratio = f / static_cast<double>(kSubsonicFreq);
        const double r4 = ratio * ratio * ratio * ratio;
        const double expectedDb = 10.0 * std::log10(r4 / (1.0 + r4));

        INFO("f=", f, " Hz  measured=", measuredDb, " dB  expected=", expectedDb, " dB");
        CHECK(std::fabs(measuredDb - expectedDb) < 0.5);
    }
}

// Regression test for the DF2T->SVF biquad migration (Mid band): the Mid crossover HPF is two
// cascaded identical 2nd-order Butterworth stages (LR4), so its magnitude-squared response is the
// single-stage Butterworth response *squared* -- in dB, exactly double the single-stage dB. At the
// crossover frequency itself that works out to the well-known LR4 signature of -6 dB (not -3 dB),
// which is what lets the Low and Mid branches sum back to flat through the handoff (see the
// sibling "sums flat" test above).
//
// Both Low outputs are muted so only the Mid HPF crossover shapes the final output; the crossover
// frequency defaults to the same 150 Hz on both Mid Left and Mid Right (flatConfig() doesn't
// touch it), so unlike the subsonic test above there's no need to account for the deliberate
// output L/R swap here -- both buffer channels show the identical Mid-only response either way.
TEST_CASE("Mid crossover HPF matches the theoretical LR4 (cascaded Butterworth) rolloff") {
    auto cfg = flatConfig();
    const int lowLeftBase = nbschema::kOutputConfigBase;
    const int lowRightBase = nbschema::kOutputConfigBase + nbschema::kOutputConfigWidth;
    cfg[lowLeftBase + nbschema::kOutMuted] = 1.f;
    cfg[lowRightBase + nbschema::kOutMuted] = 1.f;

    constexpr double kMidCrossoverFreq = 150.0;  // flatConfig()'s untouched per-output default
    const double freqs[] = {40, 60, 90, 130, 150, 175, 250, 400, 1000, 4000};
    const double amp = 0.05;

    std::vector<double> measured, expected;
    for (double f : freqs) {
        NativeBmwDspProcessor proc;
        auto out = renderSteadyState(proc, cfg, f, amp);
        measured.push_back(linToDb(channelMagnitudeAt(out, 0, f)));

        const double ratio = f / kMidCrossoverFreq;
        const double r4 = ratio * ratio * ratio * ratio;
        const double singleStageDb = 10.0 * std::log10(r4 / (1.0 + r4));
        expected.push_back(2.0 * singleStageDb);
    }

    // Normalise both curves to the 4 kHz point (last entry above, deep in the passband, ~0 dB
    // either way) rather than asserting an absolute level -- same "compare shapes, not absolute
    // gain" approach the sibling flat-sum test uses, so this doesn't need to know about every
    // other stage's exact gain contribution (postGain, the DC blocker's own gentle rolloff, ...).
    const double refMeasured = measured.back(), refExpected = expected.back();

    for (std::size_t i = 0; i < measured.size(); ++i) {
        const double relMeasured = measured[i] - refMeasured;
        const double relExpected = expected[i] - refExpected;
        INFO("f=", freqs[i], " Hz  measuredRel=", relMeasured, " dB  expectedRel=", relExpected, " dB");
        CHECK(std::fabs(relMeasured - relExpected) < 0.6);
    }
}

// Regression test for the DF2T->SVF biquad migration (Full-range PEQ, 3x16 bands): exercises
// makePeq's all 4 types (bell, low shelf, high shelf, all-pass) through the full-range bank
// (inputPeq_ -- applied before the low/mid split, so no crossover shaping to account for). The
// low/mid PEQ banks use this exact same makePeq/Biquad machinery on the exact same 5-value band
// format, just applied post-split -- their correctness follows from this bank's, not tested
// separately here.
//
// Uses configurePeq() directly (its own array format, [freq, gainDb, Q, type, channel] per band
// -- not part of configure()'s v[] array) rather than going through the Kotlin-facing config
// array, since that's the API surface this exercises. Each band type gets its own configurePeq()
// call with exactly one band, not a combined multi-band config: with several bands active
// together, every measurement reflects their *combined* response (confirmed with a throwaway
// model before writing this -- a bell only ~2.5x away from another band's centre frequency
// shifted that band's measured gain by close to 1 dB), which would make this test either flaky
// or need per-pair-of-bands-specific tolerances. Isolating each type keeps every check exact and
// independent of how the others are tuned.
static void configureSingleBand(NativeBmwDspProcessor& proc, double freq, double gainDb, double q, int type) {
    const double band[5] = {freq, gainDb, q, static_cast<double>(type), 0.0 /* both channels */};
    REQUIRE(proc.configurePeq(true, 0.0f, band, 5, nullptr, 0, nullptr, 0));
}

TEST_CASE("Full-range PEQ bell bands hit their designed gain at their own centre frequency") {
    auto cfg = flatConfig();
    const double amp = 0.05;
    // Gain at fc is exactly the designed dB, independent of Q -- a well-known exact property of
    // the peaking-EQ transfer function, and a direct check the migrated SVF bell coefficients
    // still hit the same designed gain the RBJ ones did.
    struct Case { double freq, gainDb, q; };
    const Case cases[] = {
        {150.0, 6.0, 1.0},
        {2000.0, -8.0, 2.5},
    };
    for (const auto& c : cases) {
        NativeBmwDspProcessor proc;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(cfg.data(), cfg.size()));
        configureSingleBand(proc, c.freq, c.gainDb, c.q, 0);

        const double measuredDb = linToDb(channelMagnitudeAt(renderSteadyState(proc, cfg, c.freq, amp), 0, c.freq));
        INFO("bell fc=", c.freq, " Hz  measured=", measuredDb, " dB  expected=", c.gainDb, " dB");
        CHECK(std::fabs(measuredDb - c.gainDb) < 0.3);
    }
}

TEST_CASE("Full-range PEQ shelf bands settle to their designed gain away from the corner") {
    auto cfg = flatConfig();
    const double amp = 0.05;
    // Low shelf probed a decade below fc, high shelf a factor of 5 above -- comfortably into the
    // flat part of the shelf in both cases (the high shelf's fc is kept low enough that 5x still
    // clears Nyquist at 48 kHz with headroom).
    struct Case { double freq, gainDb, q, probe; int type; };
    const Case cases[] = {
        {60.0, 5.0, 0.7071067812, 6.0, 1},
        {3000.0, -6.0, 0.7071067812, 15000.0, 2},
    };
    for (const auto& c : cases) {
        NativeBmwDspProcessor proc;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(cfg.data(), cfg.size()));
        configureSingleBand(proc, c.freq, c.gainDb, c.q, c.type);

        const double measuredDb = linToDb(channelMagnitudeAt(renderSteadyState(proc, cfg, c.probe, amp), 0, c.probe));
        INFO((c.type == 1 ? "low" : "high"), " shelf fc=", c.freq, " Hz probe=", c.probe,
             " Hz  measured=", measuredDb, " dB  expected=", c.gainDb, " dB");
        CHECK(std::fabs(measuredDb - c.gainDb) < 0.5);
    }
}

TEST_CASE("Full-range PEQ all-pass band has unity magnitude everywhere") {
    auto cfg = flatConfig();
    const double amp = 0.05;
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    configureSingleBand(proc, 500.0, 0.0, 1.0, 3);

    // Unity magnitude at every frequency is the defining property of an all-pass -- swept well
    // below to well above the section's own fc, not just near it.
    const double freqs[] = {30, 100, 500, 2000, 8000, 18000};
    for (double f : freqs) {
        const double measuredDb = linToDb(channelMagnitudeAt(renderSteadyState(proc, cfg, f, amp), 0, f));
        INFO("all-pass probe f=", f, " Hz  measured=", measuredDb, " dB (expected ~0 dB)");
        CHECK(std::fabs(measuredDb) < 0.3);
    }
}

// Regression test for the DF2T->SVF biquad migration (tonality tilt shelf): tilt is two cascaded
// low-shelf stages (+g dB each) and two cascaded high-shelf stages (-g dB each) at the same
// corner, all fixed Q=1/sqrt(2) (rebuildTilt: g = tiltAmount * 0.75f). Two identical cascaded
// shelf stages add their dB gains, so the asymptotic tilt -- well below/above tiltFreq, where
// each shelf pair has fully settled -- is exactly +/-2g dB. Verified with a throwaway model
// before writing this: at 1/10th and 10x tiltFreq, measured asymptotic gain was within 0.002 dB
// of the theoretical +/-2g.
//
// Tilt is applied identically to both final-output channels before the deliberate L/R swap (see
// the subsonic test above), so which physical side maps to which buffer channel doesn't matter
// here either. Uses the with/without differential like the subsonic test, cancelling out
// everything else in the chain rather than asserting an absolute level.
TEST_CASE("Tilt shelf pair matches its designed asymptotic gain") {
    auto cfgOn = flatConfig();
    auto cfgOff = flatConfig();
    constexpr float kTiltAmount = 4.f;
    constexpr float kTiltFreq = 500.f;
    cfgOn[nbschema::kTiltEnabled] = 1.f;
    cfgOn[nbschema::kTiltAmount] = kTiltAmount;
    cfgOn[nbschema::kTiltFreq] = kTiltFreq;

    const double lowFreq = static_cast<double>(kTiltFreq) / 10.0;
    const double highFreq = static_cast<double>(kTiltFreq) * 10.0;
    const double amp = 0.05;
    const double expectedAsymptoteDb = 2.0 * (static_cast<double>(kTiltAmount) * 0.75);

    struct Case { double freq, expectedDb; };
    const Case cases[] = {
        {lowFreq, expectedAsymptoteDb},
        {highFreq, -expectedAsymptoteDb},
    };
    for (const auto& c : cases) {
        NativeBmwDspProcessor on, off;
        const double onDb = linToDb(channelMagnitudeAt(renderSteadyState(on, cfgOn, c.freq, amp), 0, c.freq));
        const double offDb = linToDb(channelMagnitudeAt(renderSteadyState(off, cfgOff, c.freq, amp), 0, c.freq));
        const double measuredDb = onDb - offDb;
        INFO("f=", c.freq, " Hz  measured=", measuredDb, " dB  expected=", c.expectedDb, " dB");
        CHECK(std::fabs(measuredDb - c.expectedDb) < 0.3);
    }
}
