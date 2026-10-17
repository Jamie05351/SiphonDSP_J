#include "test_support.h"

#include "NativeBmwDspSchema.h"

using namespace nbtest;
namespace sch = nbschema;

// Loudness compensation: bass and a little treble lift that follows the master volume, applied
// after it. No effect at full volume; the lift is always smaller than the volume cut.

namespace {

std::array<float, kConfigSize> config(float volumeDb, bool loudness, float strengthPct = 100.f) {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    c[sch::kMasterVolumeDb] = volumeDb;
    c[sch::kLoudnessEnabled] = loudness ? 1.f : 0.f;
    c[sch::kLoudnessStrength] = strengthPct;
    return c;
}

double levelDb(const std::array<float, kConfigSize>& c, double freqHz, double amp = 0.05) {
    NativeBmwDspProcessor proc;
    auto out = renderSteadyState(proc, c, freqHz, amp);
    return linToDb(channelMagnitudeAt(out, 0, freqHz) / amp);
}

}  // namespace

TEST_CASE("loudness does nothing at full volume") {
    for (double f : {40.0, 1000.0, 12000.0}) {
        INFO("f = ", f);
        CHECK(std::fabs(levelDb(config(0.f, true), f) - levelDb(config(0.f, false), f)) < 0.01);
    }
}

TEST_CASE("loudness lifts bass a lot and treble a little as the volume drops") {
    // -30 dB: bass shelf +9 dB (0.3 dB per dB of cut), treble shelf +3 dB (0.1 dB per dB).
    const double bass = levelDb(config(-30.f, true), 40.0) - levelDb(config(-30.f, false), 40.0);
    const double mid = levelDb(config(-30.f, true), 1000.0) - levelDb(config(-30.f, false), 1000.0);
    const double treble = levelDb(config(-30.f, true), 14000.0) - levelDb(config(-30.f, false), 14000.0);
    INFO("lift at 40 Hz ", bass, " dB, 1 kHz ", mid, " dB, 14 kHz ", treble, " dB");
    CHECK(bass > 7.0);
    CHECK(bass < 9.5);
    CHECK(std::fabs(mid) < 1.0);
    CHECK(treble > 2.0);
    CHECK(treble < 3.5);
}

TEST_CASE("loudness strength scales the lift") {
    const double full = levelDb(config(-30.f, true, 100.f), 40.0) - levelDb(config(-30.f, false), 40.0);
    const double half = levelDb(config(-30.f, true, 50.f), 40.0) - levelDb(config(-30.f, false), 40.0);
    INFO("100% ", full, " dB, 50% ", half, " dB");
    CHECK(std::fabs(half - full / 2) < 0.6);
}

TEST_CASE("loudness never lifts anything above its full-volume level") {
    for (float volumeDb : {-3.f, -10.f, -40.f}) {
        for (double f : {30.0, 60.0, 15000.0}) {
            INFO("volume ", volumeDb, " dB, f = ", f);
            CHECK(levelDb(config(volumeDb, true), f) <= levelDb(config(0.f, false), f) + 0.01);
        }
    }
}
