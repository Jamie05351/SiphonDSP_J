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

// Regression test for the DF2T->SVF biquad migration (PEQ Notch type): makePeq's type==3 branch
// briefly computed the Allpass mixing (m1 = -2*k) instead of Notch's (m1 = -k) after that
// migration -- both are unity-gain far from fc, so nothing away from the notch frequency caught
// it, but Allpass is *also* unity-gain (by construction) right at fc, where a Notch band must
// dip hard. A "NO" band the on-screen graph (BiquadUtils.kt, a separate Kotlin implementation
// never touched by the migration) drew as a deep null was silently playing back inaudibly flat.
TEST_CASE("PEQ Notch band actually nulls at its center frequency") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = flatConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));

    constexpr double kNotchFreq = 1000.0;
    constexpr double kQ = 4.0;
    // full-bank band: [freq, gainDb (unused by Notch), Q, type=3 (Notch), channel=0 (both)].
    const double band[5] = {kNotchFreq, 0.0, kQ, 3.0, 0.0};
    REQUIRE(proc.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0));

    const double amp = 0.1;
    std::vector<float> warm = stereoSine(kNotchFreq, amp, 24000);
    proc.process(warm.data(), warm.size());
    std::vector<float> atFc = stereoSine(kNotchFreq, amp, 16384);
    proc.process(atFc.data(), atFc.size());
    const double dbAtFc = linToDb(channelMagnitudeAt(atFc, 0, kNotchFreq));

    // A true notch must sit well below unity at its own center frequency; an Allpass (the bug)
    // measures ~0 dB here since its magnitude is flat everywhere by definition.
    INFO("gain at fc=", kNotchFreq, " Hz: ", dbAtFc, " dB");
    CHECK(dbAtFc < -20.0);

    // Away from fc the band must be a no-op -- confirms this isn't just a broadband attenuation
    // bug wearing a Notch's clothes. channelMagnitudeAt reads back an absolute signal amplitude
    // (~amp for an untouched frequency, i.e. ~-20 dB here, not 0 dB), so -- same "compare against
    // a reference measurement" approach every sibling test in this file uses -- this compares
    // with-notch against a without-notch baseline rather than asserting an absolute dB figure.
    constexpr double kFarFreq = 200.0;
    NativeBmwDspProcessor procFar, procBaseline;
    procFar.setSampleRate(kSampleRate);
    procBaseline.setSampleRate(kSampleRate);
    REQUIRE(procFar.configure(cfg.data(), cfg.size()));
    REQUIRE(procFar.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0));
    REQUIRE(procBaseline.configure(cfg.data(), cfg.size()));

    const double dbFar = linToDb(channelMagnitudeAt(renderSteadyState(procFar, cfg, kFarFreq, amp), 0, kFarFreq));
    const double dbBaseline = linToDb(channelMagnitudeAt(renderSteadyState(procBaseline, cfg, kFarFreq, amp), 0, kFarFreq));
    INFO("gain away from fc=", kFarFreq, " Hz: with-notch=", dbFar, " dB  baseline=", dbBaseline, " dB");
    CHECK(std::fabs(dbFar - dbBaseline) < 0.5);
}

// Measures a full-bank PEQ band's own contribution at one frequency: (with the band) minus
// (without it), both against the same flatConfig() baseline -- same differential technique as the
// Notch test above, since channelMagnitudeAt reads an absolute signal amplitude, not a gain.
static double peqBandGainAt(double freq, double bandFreq, double gainDb, double q, int type, double amp = 0.1) {
    const auto cfg = flatConfig();
    const double band[5] = {bandFreq, gainDb, q, static_cast<double>(type), 0.0};

    NativeBmwDspProcessor withBand;
    withBand.setSampleRate(kSampleRate);
    REQUIRE(withBand.configure(cfg.data(), cfg.size()));
    REQUIRE(withBand.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0));
    const double dbWith = linToDb(channelMagnitudeAt(renderSteadyState(withBand, cfg, freq, amp), 0, freq));

    NativeBmwDspProcessor baseline;
    baseline.setSampleRate(kSampleRate);
    REQUIRE(baseline.configure(cfg.data(), cfg.size()));
    const double dbBaseline = linToDb(channelMagnitudeAt(renderSteadyState(baseline, cfg, freq, amp), 0, freq));

    return dbWith - dbBaseline;
}

// Regression coverage for the SVF migration's remaining PEQ types (Bell/LowShelf/HighShelf) --
// only Notch had a native test before this. All three were re-verified by hand (DC/Nyquist limit
// analysis against the Cytomic reference mixing table) after the Notch bug turned up in the same
// function, but that bug is exactly why "verified by hand once" isn't the same as "covered."
TEST_CASE("PEQ Bell band boosts/cuts by its designed gain at fc, unity away from it") {
    constexpr double kFreq = 1000.0, kQ = 2.0, kGainDb = 6.0;
    const double atFc = peqBandGainAt(kFreq, kFreq, kGainDb, kQ, /*type=*/0);
    const double farAway = peqBandGainAt(200.0, kFreq, kGainDb, kQ, /*type=*/0);
    INFO("Bell at fc=", kFreq, " Hz: ", atFc, " dB (want ~", kGainDb, ")  away: ", farAway, " dB (want ~0)");
    CHECK(std::fabs(atFc - kGainDb) < 0.5);
    CHECK(std::fabs(farAway) < 0.5);

    // Cut (negative gain) exercises the other half of makePeq's k = 1/(Q*A) term (A < 1).
    constexpr double kCutDb = -6.0;
    const double atFcCut = peqBandGainAt(kFreq, kFreq, kCutDb, kQ, /*type=*/0);
    INFO("Bell cut at fc=", kFreq, " Hz: ", atFcCut, " dB (want ~", kCutDb, ")");
    CHECK(std::fabs(atFcCut - kCutDb) < 0.5);
}

TEST_CASE("PEQ Low Shelf band shifts the low end by its designed gain, unity at Nyquist-ish") {
    constexpr double kFreq = 500.0, kQ = 0.7071067812, kGainDb = 6.0;
    // Deep in the shelf's low plateau (well below fc) vs deep in its high plateau (well above fc,
    // still comfortably under the 48 kHz sample rate's Nyquist).
    const double lowPlateau = peqBandGainAt(40.0, kFreq, kGainDb, kQ, /*type=*/1);
    const double highPlateau = peqBandGainAt(15000.0, kFreq, kGainDb, kQ, /*type=*/1);
    INFO("Low Shelf low-plateau: ", lowPlateau, " dB (want ~", kGainDb, ")  high-plateau: ", highPlateau, " dB (want ~0)");
    CHECK(std::fabs(lowPlateau - kGainDb) < 0.5);
    CHECK(std::fabs(highPlateau) < 0.5);

    // The two plateaus alone pass even if the transition (governed by Q, m1, and the effective
    // corner) is wrong -- a Q the code silently ignores, or an m1 sized wrong, can still land
    // both asymptotes right. At fc itself this shelf's magnitude is exactly half the designed
    // gain in dB regardless of Q (a property of this SVF shelf derivation, confirmed numerically
    // outside this test) -- a real check that the corner frequency itself is right, not just the
    // far ends.
    const double atFc = peqBandGainAt(kFreq, kFreq, kGainDb, kQ, /*type=*/1);
    INFO("Low Shelf at fc=", kFreq, " Hz: ", atFc, " dB (want ~", kGainDb / 2, ")");
    CHECK(std::fabs(atFc - kGainDb / 2) < 0.5);

    // One octave below fc, two different Q values give genuinely different transition depths
    // (higher Q overshoots closer to -- here, past -- the full plateau gain) -- values derived
    // from this exact SVF shelf math via independent simulation, not a linear guess. Catches a
    // supplied Q the code ignores (both readings would match) or an m1/corner sized wrong (either
    // reading, or both, would miss).
    const double atOctaveLowQ = peqBandGainAt(kFreq / 2.0, kFreq, kGainDb, 0.5, /*type=*/1);
    const double atOctaveHighQ = peqBandGainAt(kFreq / 2.0, kFreq, kGainDb, 2.0, /*type=*/1);
    INFO("Low Shelf one octave below fc: Q=0.5 -> ", atOctaveLowQ, " dB (want ~4.78)  Q=2.0 -> ",
         atOctaveHighQ, " dB (want ~7.52)");
    CHECK(std::fabs(atOctaveLowQ - 4.78) < 0.5);
    CHECK(std::fabs(atOctaveHighQ - 7.52) < 0.5);
}

TEST_CASE("PEQ High Shelf band shifts the high end by its designed gain, unity at DC-ish") {
    constexpr double kFreq = 2000.0, kQ = 0.7071067812, kGainDb = 6.0;
    const double lowPlateau = peqBandGainAt(40.0, kFreq, kGainDb, kQ, /*type=*/2);
    const double highPlateau = peqBandGainAt(15000.0, kFreq, kGainDb, kQ, /*type=*/2);
    INFO("High Shelf low-plateau: ", lowPlateau, " dB (want ~0)  high-plateau: ", highPlateau, " dB (want ~", kGainDb, ")");
    CHECK(std::fabs(lowPlateau) < 0.5);
    CHECK(std::fabs(highPlateau - kGainDb) < 0.5);

    // Same corner/off-corner/Q-sensitivity coverage as the Low Shelf test above, mirrored.
    const double atFc = peqBandGainAt(kFreq, kFreq, kGainDb, kQ, /*type=*/2);
    INFO("High Shelf at fc=", kFreq, " Hz: ", atFc, " dB (want ~", kGainDb / 2, ")");
    CHECK(std::fabs(atFc - kGainDb / 2) < 0.5);

    const double atOctaveLowQ = peqBandGainAt(kFreq * 2.0, kFreq, kGainDb, 0.5, /*type=*/2);
    const double atOctaveHighQ = peqBandGainAt(kFreq * 2.0, kFreq, kGainDb, 2.0, /*type=*/2);
    INFO("High Shelf one octave above fc: Q=0.5 -> ", atOctaveLowQ, " dB (want ~4.82)  Q=2.0 -> ",
         atOctaveHighQ, " dB (want ~7.48)");
    CHECK(std::fabs(atOctaveLowQ - 4.82) < 0.5);
    CHECK(std::fabs(atOctaveHighQ - 7.48) < 0.5);
}

// Regression coverage for the SVF migration's tilt shelf (rebuildTilt): two cascaded makeLowShelf
// stages (each +g dB) plus two cascaded makeHighShelf stages (each -g dB) at the same corner --
// a classic tilt EQ. Cascaded identical shelves add their dB directly at each end's asymptote, so
// the low end should land at +2g and the high end at -2g, independent of the migration's mixing
// coefficients being right. makeLowShelf/makeHighShelf's only callers are these 8 tilt sections
// (see rebuildTilt) -- PEQ Low/High Shelf bands go through makePeq's own inline shelf derivation
// instead, and Gains & Delay is plain gain, no shelf filter at all -- so this test covers tilt
// specifically, not a shared path those other features also depend on.
TEST_CASE("Tilt shelf lands at +/-2x its per-stage gain at the low/high asymptotes") {
    auto cfgOn = flatConfig();
    auto cfgOff = flatConfig();  // c[25] already 0 (tilt off) from flatConfig() itself.
    constexpr float kTiltAmount = 4.0f;      // g = tiltAmount * 0.75 per rebuildTilt().
    constexpr float kTiltFreq = 1000.0f;
    constexpr double kExpectedPerEndDb = 2.0 * (kTiltAmount * 0.75);
    cfgOn[25] = 1.f;
    cfgOn[26] = kTiltAmount;
    cfgOn[27] = kTiltFreq;

    const double amp = 0.05;
    for (double f : {40.0, 15000.0}) {
        NativeBmwDspProcessor procOn, procOff;
        procOn.setSampleRate(kSampleRate);
        procOff.setSampleRate(kSampleRate);
        const double dbOn = linToDb(channelMagnitudeAt(renderSteadyState(procOn, cfgOn, f, amp), 0, f));
        const double dbOff = linToDb(channelMagnitudeAt(renderSteadyState(procOff, cfgOff, f, amp), 0, f));
        const double delta = dbOn - dbOff;
        // Low end tilts up (+2g), high end tilts down (-2g).
        const double expected = (f < kTiltFreq) ? kExpectedPerEndDb : -kExpectedPerEndDb;
        INFO("f=", f, " Hz  measured delta=", delta, " dB  expected=", expected, " dB");
        CHECK(std::fabs(delta - expected) < 0.5);
    }

    // The far asymptotes above stay right even with a wrong kTiltFreq, bandwidth, or SVF
    // denominator coefficient -- those only reshape the transition between the two plateaus.
    // Add the corner itself and one octave either side, whose expected values were derived from
    // this exact cascaded-shelf math via independent simulation (not a linear guess): at the
    // pivot frequency, the two low-shelf stages' +g/2 and the two high-shelf stages' -g/2 cancel
    // exactly, so a wrong kTiltFreq shows up immediately as a nonzero reading right here, even
    // though the far ends above still land on +/-2g.
    struct TransitionPoint { double freq, expectedDb; };
    const TransitionPoint transitionPoints[] = {
        {static_cast<double>(kTiltFreq) / 2.0, 5.28},
        {kTiltFreq, 0.0},
        {static_cast<double>(kTiltFreq) * 2.0, -5.30},
    };
    for (const auto& p : transitionPoints) {
        NativeBmwDspProcessor procOn, procOff;
        procOn.setSampleRate(kSampleRate);
        procOff.setSampleRate(kSampleRate);
        const double dbOn = linToDb(channelMagnitudeAt(renderSteadyState(procOn, cfgOn, p.freq, amp), 0, p.freq));
        const double dbOff = linToDb(channelMagnitudeAt(renderSteadyState(procOff, cfgOff, p.freq, amp), 0, p.freq));
        const double delta = dbOn - dbOff;
        INFO("f=", p.freq, " Hz  measured delta=", delta, " dB  expected=", p.expectedDb, " dB");
        CHECK(std::fabs(delta - p.expectedDb) < 0.5);
    }
}
