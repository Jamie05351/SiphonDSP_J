#include "test_support.h"

#include "NativeBmwCrossovers.h"
#include "NativeBmwDspSchema.h"

#include <limits>

using namespace nbtest;
namespace sch = nbschema;
using NativeBmwDsp::VirtualBass;

// Virtual Bass: the sub below the cut rebuilt as 2nd..4th harmonics the woofers can play, right
// after Mono Bass, optionally with the original sub high-passed away.

namespace {

// Runs a VirtualBass at cut on a centred sine of amplitude amp at freqHz and returns the left
// output (interleaved L/R) after a warm-up long enough for the envelope and filters to settle.
std::vector<float> runStage(float cut, double freqHz, double amp, float removeMix, float gain, float character = .5f) {
    VirtualBass vb;
    vb.rebuild(cut, kSampleRate);
    const std::size_t warm = 48000, measure = 48000;
    const double w = 2.0 * kPi * freqHz / kSampleRate;
    std::vector<float> out(2 * measure);
    for (std::size_t n = 0; n < warm + measure; ++n) {
        const float s = static_cast<float>(amp * std::sin(w * static_cast<double>(n)));
        float l = s, r = s;
        vb.process(l, r, 1.f, removeMix, gain, character);
        if (n >= warm) {
            out[2 * (n - warm)] = l;
            out[2 * (n - warm) + 1] = r;
        }
    }
    return out;
}

// dB of the left channel at freqHz relative to amp.
double relDb(const std::vector<float>& out, double freqHz, double amp) {
    return linToDb(channelMagnitudeAt(out, 0, freqHz) / amp);
}

std::array<float, kConfigSize> config(bool on, float cut = 50.f, float amount = 50.f, bool removeSub = false,
                                      float character = 50.f) {
    auto c = defaultConfig();
    c[sch::kVirtualBassCharacter] = character;
    c[sch::kVirtualBassEnabled] = on ? 1.f : 0.f;
    c[sch::kVirtualBassFreq] = cut;
    c[sch::kVirtualBassAmount] = amount;
    c[sch::kVirtualBassRemoveSub] = removeSub ? 1.f : 0.f;
    return c;
}

// Interleaved centred sine of amplitude amp, starting at sample pos.
std::vector<float> sine(double freqHz, double amp, std::size_t pos, std::size_t frames) {
    std::vector<float> buf(2 * frames);
    const double w = 2.0 * kPi * freqHz / kSampleRate;
    for (std::size_t n = 0; n < frames; ++n) {
        const float s = static_cast<float>(amp * std::sin(w * static_cast<double>(pos + n)));
        buf[2 * n] = s;
        buf[2 * n + 1] = s;
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

// The engine's left output at freqHz (dB re amp) for a centred sine through config c.
double engineDb(const std::array<float, kConfigSize>& c, double toneHz, double probeHz, double amp) {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));
    auto warm = sine(toneHz, amp, 0, 48000);
    proc.process(warm.data(), warm.size());
    auto win = sine(toneHz, amp, 48000, 48000);
    proc.process(win.data(), win.size());
    return relDb(win, probeHz, amp);
}

}  // namespace

TEST_CASE("virtual bass rebuilds a 30 Hz tone as its 60 and 90 Hz harmonics") {
    const double amp = 0.3;
    const auto out = runStage(50.f, 30.0, amp, 0.f, 1.f);
    const double h2 = relDb(out, 60.0, amp), h3 = relDb(out, 90.0, amp), fund = relDb(out, 30.0, amp);
    INFO("30 Hz ", fund, " dB   60 Hz ", h2, " dB   90 Hz ", h3, " dB");
    // Weights 1 : 0.6 : 0.3 normalised to sum to 1 (-5.6 dB), then the band-pass.
    CHECK(h2 > -14.0);
    CHECK(h3 > -16.0);
    // The fundamental itself is kept (sub removal off).
    CHECK(std::fabs(fund) < 0.5);
    // Both channels get the same harmonics.
    CHECK(std::fabs(linToDb(channelMagnitudeAt(out, 0, 60.0)) - linToDb(channelMagnitudeAt(out, 1, 60.0))) < 1e-3);
}

TEST_CASE("virtual bass harmonics scale with the bass level, not a fixed distortion colour") {
    // 18 dB less bass in: 18 dB less harmonics out, at every harmonic.
    const auto loud = runStage(50.f, 30.0, 0.4, 0.f, 1.f);
    const auto quiet = runStage(50.f, 30.0, 0.05, 0.f, 1.f);
    for (double h : {60.0, 90.0, 120.0}) {
        const double drop = linToDb(channelMagnitudeAt(loud, 0, h)) - linToDb(channelMagnitudeAt(quiet, 0, h));
        INFO(h, " Hz: drop ", drop, " dB for an 18.06 dB quieter input");
        CHECK(std::fabs(drop - 18.06) < 0.5);
    }
}

TEST_CASE("virtual bass leaves content above the cut alone") {
    for (double f : {200.0, 1000.0}) {
        const double amp = 0.5;
        const auto out = runStage(50.f, f, amp, 0.f, 1.f);
        INFO(f, " Hz: itself ", relDb(out, f, amp), " dB   2nd ", relDb(out, 2 * f, amp), " dB   3rd ",
             relDb(out, 3 * f, amp), " dB");
        CHECK(std::fabs(relDb(out, f, amp)) < 0.05);
        CHECK(relDb(out, 2 * f, amp) < -55.0);
        CHECK(relDb(out, 3 * f, amp) < -55.0);
    }
}

TEST_CASE("virtual bass sub removal high-passes the original sub at the cut") {
    const double amp = 0.3;
    const auto kept = runStage(50.f, 30.0, amp, 0.f, 1.f);
    const auto removed = runStage(50.f, 30.0, amp, 1.f, 1.f);
    INFO("30 Hz kept ", relDb(kept, 30.0, amp), " dB, removed ", relDb(removed, 30.0, amp), " dB");
    // LR4 at 50 Hz: about -19 dB at 30 Hz, and the harmonics stay.
    CHECK(relDb(removed, 30.0, amp) < -15.0);
    CHECK(relDb(removed, 60.0, amp) > -14.0);
    // Well above the cut the removal is transparent.
    const auto high = runStage(50.f, 1000.0, 0.5, 1.f, 1.f);
    CHECK(std::fabs(relDb(high, 1000.0, 0.5)) < 0.05);
}

TEST_CASE("virtual bass is off by default and then leaves the signal bit-identical") {
    auto stock = defaultConfig();
    CHECK(stock[sch::kVirtualBassEnabled] == 0.f);
    CHECK(stock[sch::kVirtualBassFreq] == 50.f);
    CHECK(stock[sch::kVirtualBassAmount] == 50.f);
    CHECK(stock[sch::kVirtualBassRemoveSub] == 0.f);
    CHECK(stock[sch::kVirtualBassCharacter] == 50.f);
    // Different (inactive) settings change nothing while it's off.
    NativeBmwDspProcessor a, b;
    auto ca = config(false), cb = config(false, 80.f, 100.f, true, 0.f);
    a.setSampleRate(kSampleRate);
    b.setSampleRate(kSampleRate);
    REQUIRE(a.configure(ca.data(), ca.size()));
    REQUIRE(b.configure(cb.data(), cb.size()));
    auto outA = sine(35.0, 0.4, 0, 16384), outB = outA;
    a.process(outA.data(), outA.size());
    b.process(outB.data(), outB.size());
    CHECK(outA == outB);
}

TEST_CASE("virtual bass in the engine adds harmonics to a sub tone, scaled by the amount") {
    // Schema agreement: these are the slots configure() reads.
    const double amp = 0.2;
    const double off = engineDb(config(false), 30.0, 60.0, amp);
    const double half = engineDb(config(true, 50.f, 50.f), 30.0, 60.0, amp);
    const double full = engineDb(config(true, 50.f, 100.f), 30.0, 60.0, amp);
    INFO("60 Hz from a 30 Hz tone: off ", off, " dB, 50% ", half, " dB, 100% ", full, " dB");
    CHECK(half > off + 30.0);
    CHECK(std::fabs((full - half) - 6.02) < 0.5);
    // And the sub removal reaches the engine too.
    const double kept = engineDb(config(true, 50.f, 50.f, false), 30.0, 30.0, amp);
    const double removed = engineDb(config(true, 50.f, 50.f, true), 30.0, 30.0, amp);
    INFO("30 Hz: kept ", kept, " dB, removed ", removed, " dB");
    CHECK(removed < kept - 15.0);
}

TEST_CASE("switching virtual bass or its sub removal crossfades instead of stepping") {
    // 25 dB headroom on a 40 Hz tone below the 50 Hz cut, so the stage has something to do.
    for (int which : {0, 1, 2, 3}) {  // on, off, removal on, removal off
        const bool startOn = which != 0;
        const bool startRemove = which == 3;
        NativeBmwDspProcessor proc;
        auto c = config(startOn, 50.f, 100.f, startRemove);
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        std::size_t pos = 0;
        auto warm = sine(40.0, 0.3, pos, 48000);
        pos += 48000;
        proc.process(warm.data(), warm.size());
        float last = warm[warm.size() - 2];
        auto steady = sine(40.0, 0.3, pos, 4096);
        pos += 4096;
        proc.process(steady.data(), steady.size());
        const float steadyStep = maxStep(steady, last);
        if (which == 0) c[sch::kVirtualBassEnabled] = 1.f;
        if (which == 1) c[sch::kVirtualBassEnabled] = 0.f;
        if (which == 2) c[sch::kVirtualBassRemoveSub] = 1.f;
        if (which == 3) c[sch::kVirtualBassRemoveSub] = 0.f;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto after = sine(40.0, 0.3, pos, 4096);
        pos += 4096;
        proc.process(after.data(), after.size());
        const float edit = maxStep(after, last);
        // The harmonics sit 2..5x the tone's frequency, so the processed signal's own steps are
        // larger: judge the transition against whichever steady state is busier.
        auto settled = sine(40.0, 0.3, pos + 48000, 4096);
        {
            auto gap = sine(40.0, 0.3, pos, 48000);
            proc.process(gap.data(), gap.size());
            last = gap[gap.size() - 2];
        }
        proc.process(settled.data(), settled.size());
        const float settledStep = maxStep(settled, last);
        INFO("case ", which, ": steady step ", steadyStep, "  settled ", settledStep, "  step after ", edit);
        CHECK(edit < std::max(steadyStep, settledStep) * 1.5f);
    }
}

TEST_CASE("changing the virtual bass cut or amount while it plays doesn't click") {
    struct Edit {
        int slot;
        float from, to;
    };
    for (Edit e : {Edit{sch::kVirtualBassFreq, 50.f, 55.f}, Edit{sch::kVirtualBassFreq, 30.f, 80.f},
                   Edit{sch::kVirtualBassAmount, 0.f, 100.f}, Edit{sch::kVirtualBassCharacter, 0.f, 100.f}}) {
        NativeBmwDspProcessor proc;
        auto c = config(true);
        c[e.slot] = e.from;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), c.size()));
        std::size_t pos = 0;
        auto warm = sine(28.0, 0.3, pos, 48000);
        pos += 48000;
        proc.process(warm.data(), warm.size());
        float last = warm[warm.size() - 2];
        auto steady = sine(28.0, 0.3, pos, 4096);
        pos += 4096;
        proc.process(steady.data(), steady.size());
        const float steadyStep = maxStep(steady, last);
        c[e.slot] = e.to;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto after = sine(28.0, 0.3, pos, 4096);
        pos += 4096;
        proc.process(after.data(), after.size());
        const float edit = maxStep(after, last);
        // As above: more harmonics means bigger steady-state steps, so compare with both sides.
        {
            auto gap = sine(28.0, 0.3, pos, 48000);
            pos += 48000;
            proc.process(gap.data(), gap.size());
            last = gap[gap.size() - 2];
        }
        auto settled = sine(28.0, 0.3, pos, 4096);
        proc.process(settled.data(), settled.size());
        const float settledStep = maxStep(settled, last);
        INFO("slot ", e.slot, " ", e.from, " -> ", e.to, ": steady step ", steadyStep, "  settled ", settledStep,
             "  step after ", edit);
        // One screen step must be seamless; the whole cut range in one go (a preset load) retunes
        // six filters at once and is allowed a little more, as Mono Bass's full-range jump is.
        const bool wholeRange = e.slot == sch::kVirtualBassFreq && e.to - e.from > 10.f;
        CHECK(edit < std::max(steadyStep, settledStep) * (wholeRange ? 2.f : 1.5f));
    }
}

TEST_CASE("virtual bass settings outside their ranges are clamped, non-finite ones rejected") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = config(true, 500.f, 400.f);
    CHECK(proc.configure(c.data(), c.size()));
    for (int slot : {sch::kVirtualBassEnabled, sch::kVirtualBassFreq, sch::kVirtualBassAmount,
                     sch::kVirtualBassRemoveSub, sch::kVirtualBassCharacter}) {
        auto bad = config(true);
        bad[slot] = std::numeric_limits<float>::quiet_NaN();
        INFO("slot ", slot);
        CHECK_FALSE(proc.configure(bad.data(), bad.size()));
    }
}

TEST_CASE("virtual bass character moves the mix from even (warm) to the 3rd (punchy)") {
    // The 3rd harmonic against the 2nd: low at Warm, high at Punchy, in between at 50%.
    const double amp = 0.3;
    auto thirdOverSecond = [&](float character) {
        const auto out = runStage(50.f, 30.0, amp, 0.f, 1.f, character);
        return relDb(out, 90.0, amp) - relDb(out, 60.0, amp);
    };
    auto fourthOverSecond = [&](float character) {
        const auto out = runStage(50.f, 30.0, amp, 0.f, 1.f, character);
        return relDb(out, 120.0, amp) - relDb(out, 60.0, amp);
    };
    const double warm = thirdOverSecond(0.f), mid = thirdOverSecond(.5f), punchy = thirdOverSecond(1.f);
    INFO("3rd re 2nd: warm ", warm, " dB, 50% ", mid, " dB, punchy ", punchy, " dB");
    CHECK(punchy > warm + 10.0);
    CHECK(mid > warm);
    CHECK(mid < punchy);
    // And the 4th, the other even harmonic, the other way round.
    const double warm4 = fourthOverSecond(0.f), punchy4 = fourthOverSecond(1.f);
    INFO("4th re 2nd: warm ", warm4, " dB, punchy ", punchy4, " dB");
    CHECK(warm4 > punchy4 + 6.0);
}

TEST_CASE("the engine applies the virtual bass character setting") {
    // Only slot 303 differs: the engine's own output must move from even-heavy to 3rd-heavy, so a
    // processor that ignored the setting would fail here (the click test alone wouldn't).
    auto thirdOverSecond = [](float character) {
        const auto c = config(true, 50.f, 100.f, false, character);
        return engineDb(c, 30.0, 90.0, 0.2) - engineDb(c, 30.0, 60.0, 0.2);
    };
    const double warm = thirdOverSecond(0.f), mid = thirdOverSecond(50.f), punchy = thirdOverSecond(100.f);
    INFO("engine 3rd re 2nd: warm ", warm, " dB, 50% ", mid, " dB, punchy ", punchy, " dB");
    CHECK(punchy > warm + 10.0);
    CHECK(mid > warm + 3.0);
    CHECK(punchy > mid + 3.0);
}

TEST_CASE("virtual bass harmonics never peak above the sub they come from") {
    // Full Amount, sub kept: the added signal (output minus input) stays within the input's peak
    // for a range of sub tones, so the stage can't add more than the sub's own level -- at either
    // end of Character and in between.
    for (float character : {0.f, .5f, 1.f})
    for (double f : {20.0, 30.0, 45.0}) {
        VirtualBass vb;
        vb.rebuild(80.f, kSampleRate);
        const double amp = 0.7, w = 2.0 * kPi * f / kSampleRate;
        float worst = 0.f;
        for (int n = 0; n < 96000; ++n) {
            const float s = static_cast<float>(amp * std::sin(w * n));
            float l = s, r = s;
            vb.process(l, r, 1.f, 0.f, 1.f, character);
            if (n > 48000) worst = std::max(worst, std::fabs(l - s));
        }
        INFO("character ", character, ", ", f, " Hz: largest added sample ", worst, " for a ", amp, " peak sub");
        CHECK(worst < amp * 1.05f);
    }
}

TEST_CASE("a non-finite sample doesn't latch virtual bass") {
    VirtualBass vb;
    vb.rebuild(50.f, kSampleRate);
    const double w = 2.0 * kPi * 30.0 / kSampleRate;
    const int measureFrom = 48000, total = 96000;
    std::vector<float> out(2 * (total - measureFrom));
    for (int n = 0; n < total; ++n) {
        float s = static_cast<float>(0.3 * std::sin(w * n));
        if (n == 1000) s = std::numeric_limits<float>::quiet_NaN();
        if (n == 1001) s = std::numeric_limits<float>::infinity();
        float l = s, r = s;
        vb.process(l, r, 1.f, 0.f, 1.f, .5f);
        REQUIRE(std::isfinite(l));
        REQUIRE(std::isfinite(r));
        if (n >= measureFrom) {
            out[2 * (n - measureFrom)] = l;
            out[2 * (n - measureFrom) + 1] = r;
        }
    }
    // The same instance still works afterwards: harmonics present, the sub passed through.
    INFO("after the bad samples: 60 Hz ", relDb(out, 60.0, 0.3), " dB, 30 Hz ", relDb(out, 30.0, 0.3), " dB");
    CHECK(relDb(out, 60.0, 0.3) > -14.0);
    CHECK(std::fabs(relDb(out, 30.0, 0.3)) < 0.5);
}

TEST_CASE("virtual bass stays silent on silence") {
    VirtualBass vb;
    vb.rebuild(50.f, kSampleRate);
    for (int n = 0; n < 48000; ++n) {
        float l = 0.f, r = 0.f;
        vb.process(l, r, 1.f, 1.f, 1.f, .5f);
        REQUIRE(l == 0.f);
        REQUIRE(r == 0.f);
    }
}
