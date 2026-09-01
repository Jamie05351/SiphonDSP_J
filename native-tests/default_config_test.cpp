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
