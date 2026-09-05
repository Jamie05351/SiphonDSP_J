#include "test_support.h"

using namespace nbtest;

// flatConfig() equivalent, local copy -- crossover-only chain so Mono Bass is the sole variable.
static std::array<float, kConfigSize> baseConfig() {
    auto c = defaultConfig();
    c[5] = 0.f;                     // headroom 0 dB
    c[8] = c[9] = 0.f;              // mid gain L/R 0 dB
    c[12] = 0.f;                    // global subsonic off
    c[25] = 0.f;                    // tilt off
    c[28] = c[35] = 0.f;
    c[89] = c[102] = 0.f;
    c[93] = c[106] = 0.f;
    c[119] = c[132] = 0.f;
    return c;
}

static void setMonoBass(std::array<float, kConfigSize>& c, bool on, float freq, float makeupDb) {
    c[42] = on ? 1.f : 0.f;
    c[43] = freq;      // mono-below Hz
    c[44] = 100.f;     // blend %
    c[45] = makeupDb;  // makeup dB
}

TEST_CASE("mono bass at 0 dB makeup is magnitude-flat on the sum for correlated content") {
    // Correlated (L==R) input: the Low recombination + Mid compensation should collapse to the
    // same all-pass on both branches, so enabling Mono Bass may not move the summed magnitude.
    const double freqs[] = {60, 80, 110, 150, 200, 400, 1000};
    const double amp = 0.05;

    for (double f : freqs) {
        NativeBmwDspProcessor off, on;
        auto cOff = baseConfig();
        setMonoBass(cOff, false, 80.f, 0.f);
        auto cOn = baseConfig();
        setMonoBass(cOn, true, 80.f, 0.f);

        const double dbOff = linToDb(channelMagnitudeAt(renderSteadyState(off, cOff, f, amp), 0, f));
        const double dbOn = linToDb(channelMagnitudeAt(renderSteadyState(on, cOn, f, amp), 0, f));

        INFO("f=", f, " Hz  delta=", dbOn - dbOff, " dB");
        CHECK(std::fabs(dbOn - dbOff) < 0.3);
    }
}

TEST_CASE("mono bass leaves decorrelated stereo untouched above its corner, through the XO") {
    // This is the PR #218 fix: the Mid compensation now runs its low/makeup half on the Mid
    // mono sum, so a genuinely stereo signal keeps summing flat at the 150 Hz Low/Mid crossover
    // -- not only a mono one. Corner at 60 Hz so 150-300 Hz is comfortably in the pass region;
    // any Mono-Bass-induced delta there is the bug.
    const double corner = 60.0;
    const double freqs[] = {150, 185, 230, 300, 600};
    const double amp = 0.05;
    const double phaseL = 0.0, phaseR = kPi / 2.0;  // 90 deg apart -> not mono

    for (double makeup : {0.0, 4.0}) {
        for (double f : freqs) {
            NativeBmwDspProcessor off, on;
            auto cOff = baseConfig();
            setMonoBass(cOff, false, static_cast<float>(corner), static_cast<float>(makeup));
            auto cOn = baseConfig();
            setMonoBass(cOn, true, static_cast<float>(corner), static_cast<float>(makeup));

            off.setSampleRate(kSampleRate);
            on.setSampleRate(kSampleRate);
            REQUIRE(off.configure(cOff.data(), cOff.size()));
            REQUIRE(on.configure(cOn.data(), cOn.size()));

            auto warmOff = stereoSinePhased(f, amp, 24000, phaseL, phaseR);
            auto warmOn = warmOff;
            off.process(warmOff.data(), warmOff.size());
            on.process(warmOn.data(), warmOn.size());

            auto winOff = stereoSinePhased(f, amp, 16384, phaseL, phaseR);
            auto winOn = winOff;
            off.process(winOff.data(), winOff.size());
            on.process(winOn.data(), winOn.size());

            const double dOff = linToDb(channelMagnitudeAt(winOff, 0, f));
            const double dOn = linToDb(channelMagnitudeAt(winOn, 0, f));

            INFO("makeup=", makeup, " dB  f=", f, " Hz  delta=", dOn - dOff, " dB");
            CHECK(std::fabs(dOn - dOff) < 0.4);
        }
    }
}

TEST_CASE("mono bass keeps decorrelated stereo flat through the Low/Mid crossover handoff") {
    // The existing "leaves decorrelated stereo untouched above its corner" case only samples
    // 150 Hz and up -- at or above the default Low/Mid crossover (150 Hz), but never *between*
    // the mono-bass corner (80 Hz default) and that crossover. That gap is exactly the band
    // where Low is still rolling off (LR4 lowpass) and Mid is still rolling up (LR4 highpass)
    // while BOTH are simultaneously running their own mono-bass recombination (Low fed by the
    // Low mono sum, Mid compensation fed by the Mid mono sum) -- the region the processFrame
    // comment calls out as where Low and Mid could "stop summing flat right at the crossover"
    // if the two recombinations didn't cancel. Same on/off-delta methodology as the sibling case.
    const double corner = 80.0;  // matches NativeBmwDspValues' default mono-bass freq
    const double freqs[] = {90, 100, 120, 140, 150, 160, 185};
    const double amp = 0.05;
    const double phaseL = 0.0, phaseR = kPi / 2.0;  // 90 deg apart -> not mono

    for (double makeup : {0.0, 4.0}) {
        for (double f : freqs) {
            NativeBmwDspProcessor off, on;
            auto cOff = baseConfig();
            setMonoBass(cOff, false, static_cast<float>(corner), static_cast<float>(makeup));
            auto cOn = baseConfig();
            setMonoBass(cOn, true, static_cast<float>(corner), static_cast<float>(makeup));

            off.setSampleRate(kSampleRate);
            on.setSampleRate(kSampleRate);
            REQUIRE(off.configure(cOff.data(), cOff.size()));
            REQUIRE(on.configure(cOn.data(), cOn.size()));

            auto warmOff = stereoSinePhased(f, amp, 24000, phaseL, phaseR);
            auto warmOn = warmOff;
            off.process(warmOff.data(), warmOff.size());
            on.process(warmOn.data(), warmOn.size());

            auto winOff = stereoSinePhased(f, amp, 16384, phaseL, phaseR);
            auto winOn = winOff;
            off.process(winOff.data(), winOff.size());
            on.process(winOn.data(), winOn.size());

            const double dOff = linToDb(channelMagnitudeAt(winOff, 0, f));
            const double dOn = linToDb(channelMagnitudeAt(winOn, 0, f));

            INFO("makeup=", makeup, " dB  f=", f, " Hz  delta=", dOn - dOff, " dB");
            CHECK(std::fabs(dOn - dOff) < 0.4);
        }
    }
}
