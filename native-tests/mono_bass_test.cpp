#include "test_support.h"

#include "NativeBmwCrossovers.h"
#include "NativeBmwDspSchema.h"

#include <limits>

using namespace nbtest;
namespace sch = nbschema;
using NativeBmwDsp::MonoBass;

// Mono Bass: a complementary LR4 split right after the input DC blocker. Below the split both
// channels carry (L+R)/2, above it stereo is untouched, and a centred signal sums flat.

namespace {

// Runs a MonoBass at fc on a sine of amplitude ampL on the left and ampR on the right, and
// returns each output channel's magnitude at freqHz relative to 1.
struct Levels {
    double left, right;
};
Levels splitLevels(float fc, double freqHz, double ampL, double ampR) {
    MonoBass mb;
    mb.rebuild(fc, static_cast<float>(kSampleRate));
    const std::size_t warm = 48000, measure = 32768;
    const double w = 2.0 * kPi * freqHz / kSampleRate;
    std::vector<float> out(2 * measure);
    for (std::size_t n = 0; n < warm + measure; ++n) {
        const double s = std::sin(w * static_cast<double>(n));
        float l = static_cast<float>(ampL * s), r = static_cast<float>(ampR * s);
        mb.process(l, r, 1.f);
        if (n >= warm) {
            out[2 * (n - warm)] = l;
            out[2 * (n - warm) + 1] = r;
        }
    }
    return {channelMagnitudeAt(out, 0, freqHz), channelMagnitudeAt(out, 1, freqHz)};
}

std::array<float, kConfigSize> config(bool monoBass, float freqHz = 90.f) {
    auto c = defaultConfig();
    c[sch::kMonoBassEnabled] = monoBass ? 1.f : 0.f;
    c[sch::kMonoBassFreq] = freqHz;
    return c;
}

// Interleaved sine of amplitude ampL / ampR, starting at sample pos.
std::vector<float> sine(double freqHz, double ampL, double ampR, std::size_t pos, std::size_t frames) {
    std::vector<float> buf(2 * frames);
    const double w = 2.0 * kPi * freqHz / kSampleRate;
    for (std::size_t n = 0; n < frames; ++n) {
        const double s = std::sin(w * static_cast<double>(pos + n));
        buf[2 * n] = static_cast<float>(ampL * s);
        buf[2 * n + 1] = static_cast<float>(ampR * s);
    }
    return buf;
}

// Largest sample-to-sample step on the left channel, continuing from last.
float maxStep(const std::vector<float>& b, float& last) {
    float worst = 0.f;
    for (std::size_t n = 0; n < b.size(); n += 2) {
        worst = std::max(worst, std::fabs(b[n] - last));
        last = b[n];
    }
    return worst;
}

}  // namespace

TEST_CASE("mono bass sums a centred signal flat at every split frequency") {
    for (float fc : {60.f, 90.f, 120.f}) {
        for (double f = 20.0; f < 20000.0; f *= 1.25) {
            const Levels lv = splitLevels(fc, f, 0.5, 0.5);
            INFO("fc ", fc, "  f ", f, "  L ", linToDb(lv.left / 0.5), " dB  R ", linToDb(lv.right / 0.5), " dB");
            CHECK(std::fabs(linToDb(lv.left / 0.5)) < 0.05);
            CHECK(std::fabs(linToDb(lv.right / 0.5)) < 0.05);
        }
    }
}

TEST_CASE("mono bass puts left-only bass equally in both channels, each at half") {
    for (double f : {20.0, 30.0}) {
        const Levels lv = splitLevels(90.f, f, 1.0, 0.0);
        INFO("f ", f, "  L ", linToDb(lv.left), " dB  R ", linToDb(lv.right), " dB");
        CHECK(std::fabs(linToDb(lv.left) - linToDb(0.5)) < 0.3);
        CHECK(std::fabs(linToDb(lv.right) - linToDb(0.5)) < 0.3);
    }
}

TEST_CASE("mono bass leaves stereo above the split alone") {
    for (double f : {500.0, 1000.0, 8000.0}) {
        const Levels lv = splitLevels(90.f, f, 1.0, 0.0);
        INFO("f ", f, "  L ", linToDb(lv.left), " dB  R ", linToDb(lv.right), " dB");
        CHECK(std::fabs(linToDb(lv.left)) < 0.05);
        CHECK(linToDb(lv.right) < -45.0);
    }
}

TEST_CASE("mono bass removes anti-phase content well below the split") {
    const Levels lv = splitLevels(90.f, 20.0, 0.5, -0.5);
    INFO("L ", linToDb(lv.left / 0.5), " dB  R ", linToDb(lv.right / 0.5), " dB");
    CHECK(linToDb(lv.left / 0.5) < -40.0);
    CHECK(linToDb(lv.right / 0.5) < -40.0);
}

TEST_CASE("mono bass is off by default and then leaves the signal bit-identical") {
    auto stock = defaultConfig();
    CHECK(stock[sch::kMonoBassEnabled] == 0.f);
    CHECK(stock[sch::kMonoBassFreq] == 90.f);
    // A different (inactive) frequency changes nothing while it's off.
    NativeBmwDspProcessor a, b;
    auto ca = config(false, 90.f), cb = config(false, 60.f);
    a.setSampleRate(kSampleRate);
    b.setSampleRate(kSampleRate);
    REQUIRE(a.configure(ca.data(), ca.size()));
    REQUIRE(b.configure(cb.data(), cb.size()));
    auto outA = sine(40.0, 0.4, 0.1, 0, 16384), outB = outA;
    a.process(outA.data(), outA.size());
    b.process(outB.data(), outB.size());
    CHECK(outA == outB);
}

TEST_CASE("mono bass in the engine makes left-only bass the same on both sides") {
    // Schema agreement: kMonoBassEnabled / kMonoBassFreq are the slots configure() reads. The
    // engine's own routing feeds some of each input to both sides, so look at the difference.
    auto sidesDb = [](const std::array<float, kConfigSize>& c, double f) {
        NativeBmwDspProcessor proc;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        auto warm = sine(f, 0.2, 0.0, 0, 48000);
        proc.process(warm.data(), warm.size());
        auto win = sine(f, 0.2, 0.0, 48000, 32768);
        proc.process(win.data(), win.size());
        return std::fabs(linToDb(channelMagnitudeAt(win, 0, f)) - linToDb(channelMagnitudeAt(win, 1, f)));
    };
    const double off = sidesDb(config(false), 30.0), on = sidesDb(config(true), 30.0);
    INFO("30 Hz left/right difference: off ", off, " dB, on ", on, " dB");
    CHECK(off > 1.0);
    // Not exactly 0: HP4(L) still leaks -38 dB at a third of the split.
    CHECK(on < 0.3);
    // 30 Hz is folded at any split; 110 Hz only with the split above it.
    const double split60 = sidesDb(config(true, 60.f), 110.0), split120 = sidesDb(config(true, 120.f), 110.0);
    INFO("110 Hz left/right difference: split 60 Hz ", split60, " dB, split 120 Hz ", split120, " dB");
    CHECK(split120 < split60 - 1.0);
}

TEST_CASE("switching mono bass on or off crossfades instead of stepping") {
    // A centred tone: the split passes it at full level but shifted in phase, so switching
    // without the crossfade would jump.
    for (bool turnOn : {true, false}) {
        NativeBmwDspProcessor proc;
        auto c = config(!turnOn);
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        std::size_t pos = 0;
        auto warm = sine(70.0, 0.6, 0.6, pos, 48000);
        pos += 48000;
        proc.process(warm.data(), warm.size());
        float last = warm[warm.size() - 2];
        auto steady = sine(70.0, 0.6, 0.6, pos, 4096);
        pos += 4096;
        proc.process(steady.data(), steady.size());
        const float steadyStep = maxStep(steady, last);
        c[sch::kMonoBassEnabled] = turnOn ? 1.f : 0.f;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto after = sine(70.0, 0.6, 0.6, pos, 4096);
        proc.process(after.data(), after.size());
        const float edit = maxStep(after, last);
        INFO((turnOn ? "on" : "off"), ": steady step ", steadyStep, "  step after ", edit);
        CHECK(edit < steadyStep * 1.5f);
    }
}

TEST_CASE("changing the mono bass frequency while it plays doesn't click") {
    // One 5 Hz step (the screen's increment) and the whole range in one go.
    for (float to : {95.f, 120.f}) {
        NativeBmwDspProcessor proc;
        auto c = config(true, to == 95.f ? 90.f : 60.f);
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        std::size_t pos = 0;
        auto warm = sine(80.0, 0.6, 0.6, pos, 48000);
        pos += 48000;
        proc.process(warm.data(), warm.size());
        float last = warm[warm.size() - 2];
        auto steady = sine(80.0, 0.6, 0.6, pos, 4096);
        pos += 4096;
        proc.process(steady.data(), steady.size());
        const float steadyStep = maxStep(steady, last);
        c[sch::kMonoBassFreq] = to;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto after = sine(80.0, 0.6, 0.6, pos, 4096);
        proc.process(after.data(), after.size());
        const float edit = maxStep(after, last);
        INFO("to ", to, " Hz: steady step ", steadyStep, "  step after ", edit);
        CHECK(edit < steadyStep * (to == 95.f ? 1.1f : 1.6f));
    }
}

TEST_CASE("mono bass frequency outside 60..120 Hz is clamped, a non-finite one rejected") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = config(true, 500.f);
    CHECK(proc.configure(c.data(), c.size()));
    c[sch::kMonoBassFreq] = std::numeric_limits<float>::quiet_NaN();
    CHECK_FALSE(proc.configure(c.data(), c.size()));
}
