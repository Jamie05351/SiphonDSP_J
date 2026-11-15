#include "test_support.h"

#include "NativeBmwDspSchema.h"

using namespace nbtest;
namespace sch = nbschema;

// Deep bass: a bell after the master volume that grows 0.3 dB per dB of volume cut (capped at Max
// boost, scaled by Strength) and slides from 50 Hz down to 32 Hz as the volume drops. No effect at
// full volume, nothing above the bass, and the boost is always smaller than the volume cut.

namespace {

std::array<float, kConfigSize> config(float volumeDb, bool deepBass, float strengthPct = 100.f,
                                      float maxDb = 12.f) {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    c[sch::kMasterVolumeDb] = volumeDb;
    c[sch::kDeepBassEnabled] = deepBass ? 1.f : 0.f;
    c[sch::kDeepBassStrength] = strengthPct;
    c[sch::kDeepBassMaxDb] = maxDb;
    return c;
}

double levelDb(const std::array<float, kConfigSize>& c, double freqHz, double amp = 0.05) {
    NativeBmwDspProcessor proc;
    auto out = renderSteadyState(proc, c, freqHz, amp);
    return linToDb(channelMagnitudeAt(out, 0, freqHz) / amp);
}

// The boost Deep bass adds at freqHz, at this volume / strength / cap.
double boostDb(float volumeDb, double freqHz, float strengthPct = 100.f, float maxDb = 12.f) {
    return levelDb(config(volumeDb, true, strengthPct, maxDb), freqHz) - levelDb(config(volumeDb, false), freqHz);
}

}  // namespace

TEST_CASE("deep bass does nothing at full volume") {
    for (double f : {32.0, 50.0, 1000.0}) {
        INFO("f = ", f);
        CHECK(std::fabs(boostDb(0.f, f)) < 0.01);
    }
}

TEST_CASE("deep bass boosts the low bass and leaves the mids and treble alone") {
    // -20 dB: +6 dB (0.3 dB per dB of cut) centred near 40 Hz.
    const double bass = boostDb(-20.f, 40.0);
    const double mid = boostDb(-20.f, 1000.0);
    const double treble = boostDb(-20.f, 12000.0);
    INFO("boost at 40 Hz ", bass, " dB, 1 kHz ", mid, " dB, 12 kHz ", treble, " dB");
    CHECK(bass > 5.0);
    CHECK(bass < 6.3);
    CHECK(std::fabs(mid) < 0.1);
    CHECK(std::fabs(treble) < 0.01);
}

TEST_CASE("the boost slides deeper as the volume drops") {
    // Loud-ish: centred near 47 Hz, so 55 Hz gets more than 28 Hz. Quiet: centred at 32 Hz.
    const double loud28 = boostDb(-5.f, 28.0), loud55 = boostDb(-5.f, 55.0);
    const double quiet28 = boostDb(-40.f, 28.0), quiet55 = boostDb(-40.f, 55.0);
    INFO("-5 dB: 28 Hz ", loud28, " / 55 Hz ", loud55, "   -40 dB: 28 Hz ", quiet28, " / 55 Hz ", quiet55);
    CHECK(loud55 > loud28);
    CHECK(quiet28 > quiet55);
    // At -40 dB the full 12 dB lands on the centre.
    CHECK(std::fabs(boostDb(-40.f, 32.0) - 12.0) < 0.5);
}

TEST_CASE("max boost caps the deep bass") {
    const double capped = boostDb(-60.f, 32.0, 100.f, 6.f);
    INFO("-60 dB with a 6 dB cap: ", capped, " dB");
    CHECK(std::fabs(capped - 6.0) < 0.3);
    // The cap only bites once the volume asks for more: -10 dB wants +3, under a 6 dB cap.
    CHECK(std::fabs(boostDb(-10.f, 45.0, 100.f, 6.f) - boostDb(-10.f, 45.0)) < 0.01);
}

TEST_CASE("deep bass strength scales the boost") {
    const double full = boostDb(-30.f, 35.0, 100.f);
    const double half = boostDb(-30.f, 35.0, 50.f);
    INFO("100% ", full, " dB, 50% ", half, " dB");
    CHECK(std::fabs(half - full / 2) < 0.3);
}

TEST_CASE("deep bass never lifts anything above its full-volume level") {
    for (float volumeDb : {-3.f, -10.f, -40.f, -60.f}) {
        for (double f : {25.0, 32.0, 45.0, 60.0}) {
            INFO("volume ", volumeDb, " dB, f = ", f);
            CHECK(levelDb(config(volumeDb, true, 100.f, 15.f), f) <= levelDb(config(0.f, false), f) + 0.01);
        }
    }
}

TEST_CASE("a volume step down with deep bass on never spikes above full-volume level") {
    // The master volume ramps over ~10 ms; the deep bass boost must follow that ramp, not jump to
    // the new volume's boost while the gain is still near full -- that would boost bass above
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

TEST_CASE("switching deep bass on or off at low volume fades instead of stepping") {
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
        c[sch::kDeepBassEnabled] = turnOn ? 1.f : 0.f;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto after = block(pos, 4096);
        proc.process(after.data(), after.size());
        const float edit = maxStep(after, last);
        INFO((turnOn ? "on" : "off"), ": steady step ", steadyStep, "  step after ", edit);
        CHECK(edit < steadyStep * 3.f);
    }
}

TEST_CASE("a max boost change at a deep cut glides instead of stepping") {
    // -60 dB asks for the full cap: 15 -> 3 dB would drop the bell 12 dB at once without the ramp.
    const double w = 2.0 * kPi * 32.0 / kSampleRate;
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
    for (bool down : {true, false}) {
        NativeBmwDspProcessor proc;
        auto c = config(-60.f, true, 100.f, down ? 15.f : 3.f);
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        std::size_t pos = 0;
        auto warm = block(pos, 48000);
        pos += 48000;
        proc.process(warm.data(), warm.size());
        float last = warm[warm.size() - 2];
        auto steady = block(pos, 4096);
        pos += 4096;
        proc.process(steady.data(), steady.size());
        const float steadyStep = maxStep(steady, last);
        c[sch::kDeepBassMaxDb] = down ? 3.f : 15.f;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto after = block(pos, 4096);
        pos += 4096;
        proc.process(after.data(), after.size());
        const float edit = maxStep(after, last);
        auto settled = block(pos, 4096);
        proc.process(settled.data(), settled.size());
        const float settledStep = maxStep(settled, last);
        // A step jumps far past both the old and the new level's own sample-to-sample movement
        // (an unramped 12 dB cap change measures 70..150x); the ramp stays within ~1.2x.
        INFO((down ? "15 -> 3 dB" : "3 -> 15 dB"), ": before ", steadyStep, "  across the change ", edit,
             "  settled ", settledStep);
        CHECK(edit < std::max(steadyStep, settledStep) * 1.5f);
    }
}
