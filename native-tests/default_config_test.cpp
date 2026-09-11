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
