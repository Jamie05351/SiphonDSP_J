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

TEST_CASE("a volume step down with loudness on never spikes above full-volume level") {
    // The master volume ramps over ~10 ms; the loudness lift must follow that ramp, not jump to
    // the new volume's lift while the gain is still near full -- that would boost bass above
    // full-volume level, after the limiter.
    auto peakOf = [](NativeBmwDspProcessor& proc, std::size_t frames, std::size_t& pos) {
        const double w = 2.0 * kPi * 40.0 / kSampleRate;
        std::vector<float> buf(2 * frames);
        for (std::size_t n = 0; n < frames; ++n) {
            buf[2 * n] = buf[2 * n + 1] = static_cast<float>(0.5 * std::sin(w * (pos + n)));
        }
        pos += frames;
        proc.process(buf.data(), buf.size());
        return peakAbs(buf);
    };
    std::size_t pos = 0;
    NativeBmwDspProcessor ref;
    auto c0 = config(0.f, false);
    ref.setSampleRate(kSampleRate);
    REQUIRE(ref.configure(c0.data(), c0.size()));
    peakOf(ref, 48000, pos);
    const float fullVolumePeak = peakOf(ref, 9600, pos);

    pos = 0;
    NativeBmwDspProcessor proc;
    auto c = config(0.f, true);
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));
    peakOf(proc, 48000, pos);
    c[sch::kMasterVolumeDb] = -30.f;  // a big step down mid-signal
    REQUIRE(proc.configure(c.data(), c.size()));
    const float duringStep = peakOf(proc, 9600, pos);
    INFO("full-volume peak ", fullVolumePeak, "  peak across the step ", duringStep);
    CHECK(duringStep <= fullVolumePeak * 1.01f);
}

TEST_CASE("switching loudness on or off at low volume fades instead of stepping") {
    const double w = 2.0 * kPi * 50.0 / kSampleRate;
    auto block = [&](std::size_t pos, std::size_t frames) {
        std::vector<float> buf(2 * frames);
        for (std::size_t n = 0; n < frames; ++n) {
            buf[2 * n] = buf[2 * n + 1] = static_cast<float>(0.8 * std::sin(w * (pos + n)));
        }
        return buf;
    };
    auto maxStep = [](const std::vector<float>& b, float& last) {
        float worst = 0.f;
        for (std::size_t n = 0; n < b.size(); n += 2) {
            worst = std::max(worst, std::fabs(b[n] - last));
            last = b[n];
        }
        return worst;
    };
    for (bool turnOn : {true, false}) {
        NativeBmwDspProcessor proc;
        auto c = config(-30.f, !turnOn);
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        std::size_t pos = 0;
        float last = 0.f;
        auto warm = block(pos, 48000);
        pos += 48000;
        proc.process(warm.data(), warm.size());
        last = warm[warm.size() - 2];
        auto steady = block(pos, 4096);
        pos += 4096;
        proc.process(steady.data(), steady.size());
        const float steadyStep = maxStep(steady, last);
        c[sch::kLoudnessEnabled] = turnOn ? 1.f : 0.f;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto after = block(pos, 4096);
        proc.process(after.data(), after.size());
        const float edit = maxStep(after, last);
        INFO((turnOn ? "on" : "off"), ": steady step ", steadyStep, "  step after ", edit);
        CHECK(edit < steadyStep * 3.f);
    }
}
