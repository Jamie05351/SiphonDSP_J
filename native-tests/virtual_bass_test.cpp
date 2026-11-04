#include "test_support.h"

#include "NativeBmwCrossovers.h"
#include "NativeBmwDspSchema.h"

#include <limits>
#include <random>
#include <vector>

using namespace nbtest;
namespace sch = nbschema;
using NativeBmwDsp::VirtualBass;

// Virtual Bass: notes below the woofers' reach (the cut) rebuilt as 2nd..4th harmonics the woofers
// can play, right after Mono Bass, optionally with what lies below the reach gently high-passed.

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

TEST_CASE("virtual bass sub removal only thins what lies below the woofers' reach") {
    // Reach 40 Hz: a gentle HP2 at 32 Hz. What the woofers play keeps its level; what they can't
    // is thinned (the harmonics stand in for it).
    const double amp = 0.3;
    auto removedDb = [&](double f) { return relDb(runStage(40.f, f, amp, 1.f, 0.f), f, amp); };
    const double at20 = removedDb(20.0), at40 = removedDb(40.0), at60 = removedDb(60.0), at80 = removedDb(80.0);
    INFO("removal at a 40 Hz reach: 20 Hz ", at20, "  40 Hz ", at40, "  60 Hz ", at60, "  80 Hz ", at80, " dB");
    CHECK(at20 < -6.0);
    CHECK(at40 > -2.0);
    CHECK(at60 > -0.6);
    CHECK(at80 > -0.25);
    CHECK(std::fabs(relDb(runStage(40.f, 1000.0, 0.5, 1.f, 1.f), 1000.0, 0.5)) < 0.05);
}

TEST_CASE("notes the woofers can play generate (almost) no harmonics") {
    // Reach 40 Hz: a 60 or 80 Hz bass note is the woofers' own job, so it mustn't grow a fuzz of
    // harmonics on top -- that was the mud. The LR8 input band keeps it far down.
    const double amp = 0.3;
    for (double f : {50.0, 60.0, 80.0}) {
        const auto out = runStage(40.f, f, amp, 0.f, 1.f);
        const double h2 = relDb(out, 2 * f, amp), h3 = relDb(out, 3 * f, amp);
        INFO("reach 40 Hz, ", f, " Hz note: 2nd ", h2, " dB, 3rd ", h3, " dB, itself ", relDb(out, f, amp), " dB");
        const double limit = f < 55.0 ? -25.0 : -35.0;
        CHECK(h2 < limit);
        CHECK(h3 < limit);
        CHECK(std::fabs(relDb(out, f, amp)) < 0.1);
    }
}

TEST_CASE("at the lowest reach the octave below it is rebuilt as strongly as at the default") {
    // The input band is the octave under the reach. At a 25 Hz reach it starts at 12.5 Hz, below
    // the 20 Hz the other filters stop at; clamped there, a 12.5 Hz note lost 8.8 dB instead of 3
    // and its harmonics came out ~6 dB weak. A note at half the reach must make the same harmonics
    // at any reach, as every filter scales with it.
    const double amp = 0.3;
    for (double ratio : {0.5, 0.6}) {
        const auto low = runStage(25.f, 25.0 * ratio, amp, 0.f, 1.f);
        const auto stock = runStage(40.f, 40.0 * ratio, amp, 0.f, 1.f);
        for (int h : {2, 3}) {
            const double atLow = relDb(low, h * 25.0 * ratio, amp), atStock = relDb(stock, h * 40.0 * ratio, amp);
            INFO("note at ", ratio, " x reach, harmonic ", h, ": 25 Hz reach ", atLow, " dB, 40 Hz reach ", atStock, " dB");
            CHECK(std::fabs(atLow - atStock) < 0.75);
        }
    }
}

TEST_CASE("a bass line the woofers can play stays clean (no intermodulation mud)") {
    // Two notes above a 40 Hz reach, as a bass line with a kick under it: the sum/difference
    // products that made the old stage sound like distortion must stay far down.
    VirtualBass vb;
    vb.rebuild(40.f, kSampleRate);
    const double f1 = 55.0, f2 = 73.4, amp = 0.25;
    const std::size_t warm = 48000, measure = 48000;
    std::vector<float> out(2 * measure);
    for (std::size_t n = 0; n < warm + measure; ++n) {
        const double t = static_cast<double>(n) / kSampleRate;
        const float s = static_cast<float>(amp * (std::sin(2 * kPi * f1 * t) + std::sin(2 * kPi * f2 * t)));
        float l = s, r = s;
        vb.process(l, r, 1.f, 0.f, 1.f, 0.f);
        if (n >= warm) {
            out[2 * (n - warm)] = l;
            out[2 * (n - warm) + 1] = r;
        }
    }
    for (double p : {f1 + f2, 2 * f1, 2 * f2, 2 * f1 + f2, f1 + 2 * f2, f2 - f1 + f2}) {
        INFO("product at ", p, " Hz: ", relDb(out, p, amp), " dB");
        CHECK(relDb(out, p, amp) < -30.0);
    }
}

TEST_CASE("in a car whose woofers reach 35 Hz, virtual bass keeps the woofer band intact") {
    // The reported setup with the reach set where the subsonic HPF starts: Remove sub on, 60%,
    // Warm. Everything the woofers play (40 Hz and up) must come out within a dB of Virtual Bass
    // off -- the old stage took 12 dB out of 60 Hz here.
    const double amp = 0.2;
    for (double f : {40.0, 50.0, 60.0, 80.0, 100.0, 150.0}) {
        const double off = engineDb(config(false), f, f, amp);
        const double on = engineDb(config(true, 35.f, 60.f, true, 0.f), f, f, amp);
        INFO(f, " Hz: off ", off, " dB, on ", on, " dB");
        CHECK(std::fabs(on - off) < 1.0);
    }
}

TEST_CASE("virtual bass is off by default and then leaves the signal bit-identical") {
    auto stock = defaultConfig();
    CHECK(stock[sch::kVirtualBassEnabled] == 0.f);
    CHECK(stock[sch::kVirtualBassFreq] == 40.f);
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
    // And the sub removal reaches the engine too (HP2 at 40 Hz for a 50 Hz reach: ~-8 dB at 25 Hz).
    const double kept = engineDb(config(true, 50.f, 50.f, false), 25.0, 25.0, amp);
    const double removed = engineDb(config(true, 50.f, 50.f, true), 25.0, 25.0, amp);
    INFO("25 Hz: kept ", kept, " dB, removed ", removed, " dB");
    CHECK(removed < kept - 5.0);
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
    for (Edit e : {Edit{sch::kVirtualBassFreq, 50.f, 55.f}, Edit{sch::kVirtualBassFreq, 25.f, 60.f},
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
        // One screen step must be seamless; the whole reach range in one go (a preset load) retunes
        // every filter at once and is allowed a little more, as Mono Bass's full-range jump is.
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
        vb.rebuild(60.f, kSampleRate);
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

TEST_CASE("virtual bass never raises a peak past the allowance auto headroom makes for it") {
    // Auto headroom turns the whole system down by 20 log10(1 + rise x Amount) for Virtual Bass, so
    // that must cover the real worst case -- but no more, or the system just gets quieter.
    auto peakRiseDb = [](float reach, double f, float amount, float removeMix, float character) {
        VirtualBass vb;
        vb.rebuild(reach, kSampleRate);
        double inPeak = 0, outPeak = 0;
        for (int n = 0; n < 144000; ++n) {
            const double s = 0.5 * std::sin(2 * kPi * f * n / kSampleRate);
            float l = static_cast<float>(s), r = l;
            vb.process(l, r, 1.f, removeMix, amount, character);
            if (n > 96000) {
                inPeak = std::max(inPeak, std::fabs(s));
                outPeak = std::max(outPeak, static_cast<double>(std::fabs(l)));
            }
        }
        return linToDb(outPeak / inPeak);
    };
    for (float removeMix : {0.f, 1.f}) {
        const float rise = removeMix > 0.f ? VirtualBass::kPeakRiseRemoved : VirtualBass::kPeakRiseKept;
        for (float amount : {0.5f, 1.f}) {
            const double allowance = linToDb(1.0 + rise * amount);
            double worst = -99.0;
            for (float reach : {25.f, 35.f, 60.f})
                for (float character : {0.f, 1.f})
                    for (double f = 14.0; f <= reach * 1.2; f += 3.0)
                        worst = std::max(worst, peakRiseDb(reach, f, amount, removeMix, character));
            INFO("remove ", removeMix, " amount ", amount, ": worst rise ", worst, " dB, allowance ", allowance, " dB");
            CHECK(worst <= allowance);
            // And the allowance isn't wildly generous either (that just lowers the volume).
            if (removeMix == 0.f) CHECK(worst > allowance - 0.6);
        }
    }
}

TEST_CASE("programme-like bass stays within the auto-headroom allowance too") {
    // Not just sines: two sub notes beating, a kick (falling 55 -> 30 Hz, fast decay) over a bass
    // line, and sub-heavy noise, at full Amount, with the sub kept and removed, and with Remove sub
    // half faded (the state it passes through while switching).
    auto riseDb = [](float reach, float removeMix, float character, auto gen) {
        VirtualBass vb;
        vb.rebuild(reach, kSampleRate);
        double inPeak = 0, outPeak = 0;
        for (int n = 0; n < 144000; ++n) {
            const float s = gen(n);
            float l = s, r = s;
            vb.process(l, r, 1.f, removeMix, 1.f, character);
            if (n > 48000) {
                inPeak = std::max(inPeak, static_cast<double>(std::fabs(s)));
                outPeak = std::max(outPeak, static_cast<double>(std::fabs(l)));
            }
        }
        return linToDb(outPeak / inPeak);
    };
    for (float removeMix : {0.f, 0.5f, 1.f}) {
        const float rise = removeMix >= 1.f ? VirtualBass::kPeakRiseRemoved : VirtualBass::kPeakRiseKept;
        const double allowance = linToDb(1.0 + rise);
        double worst = -99.0;
        for (float reach : {25.f, 35.f, 45.f, 60.f}) {
            for (float character : {0.f, 1.f}) {
                for (double f1 : {20.0, 24.0, 28.0}) {
                    for (double f2 : {26.0, 31.0, 37.0}) {
                        worst = std::max(worst, riseDb(reach, removeMix, character, [=](int n) {
                            const double t = n / static_cast<double>(kSampleRate);
                            return static_cast<float>(0.3 * std::sin(2 * kPi * f1 * t) + 0.3 * std::sin(2 * kPi * f2 * t));
                        }));
                    }
                }
                worst = std::max(worst, riseDb(reach, removeMix, character, [](int n) {
                    const double t = std::fmod(n / static_cast<double>(kSampleRate), 0.5);
                    const double phase = 2 * kPi * (30 * t + 25 * 0.03 * (1 - std::exp(-t / 0.03)));
                    return static_cast<float>(0.8 * std::exp(-t / 0.15) * std::sin(phase) +
                                              0.2 * std::sin(2 * kPi * 55 * n / static_cast<double>(kSampleRate)));
                }));
                std::mt19937 rng(7);
                std::normal_distribution<double> noise(0.0, 1.0);
                const double a = std::exp(-2 * kPi * 40 / static_cast<double>(kSampleRate));
                double y1 = 0, y2 = 0;
                worst = std::max(worst, riseDb(reach, removeMix, character, [&](int) {
                    y1 = a * y1 + (1 - a) * noise(rng);
                    y2 = a * y2 + (1 - a) * y1;
                    return static_cast<float>(6 * y2);
                }));
            }
        }
        INFO("removeMix ", removeMix, ": worst programme rise ", worst, " dB, allowance ", allowance, " dB");
        CHECK(worst <= allowance);
    }
}

TEST_CASE("the peak cap holds the harmonics to the allowance whatever lines up with them") {
    // A note carrying its own harmonic in step with the ones the stage adds (here the fundamental
    // and a 2nd..5th at every phase) is what pushes past the allowance without the cap. Against
    // the larger of the input's and the base's own peak (the base alone: the stage at gain 0),
    // the output must stay within 1 + rise x gain, kept, removed and half faded, at two Amounts.
    auto peaks = [](float reach, float removeMix, float gain, float character, auto gen) {
        VirtualBass vb;
        vb.rebuild(reach, kSampleRate);
        double peak = 0;
        for (int n = 0; n < 48000; ++n) {
            float l = gen(n), r = l;
            vb.process(l, r, 1.f, removeMix, gain, character);
            if (n > 14400) peak = std::max(peak, static_cast<double>(std::fabs(l)));
        }
        return peak;
    };
    double worstRatio = 0;
    for (float removeMix : {0.f, 0.5f, 1.f}) {
        const float riseFactor = removeMix > 0.f ? VirtualBass::kPeakRiseRemoved : VirtualBass::kPeakRiseKept;
        for (float gain : {0.6f, 1.f}) {
            for (float reach : {25.f, 35.f, 60.f}) {
                for (float character : {0.f, 1.f}) {
                    for (double f = 18.0; f <= 56.0; f += 6.0) {
                        for (int k : {2, 3, 4, 5}) {
                            for (double ph : {0.0, kPi / 2, kPi, 3 * kPi / 2}) {
                                auto gen = [=](int n) {
                                    const double t = n / static_cast<double>(kSampleRate);
                                    return static_cast<float>(0.4 * (std::sin(2 * kPi * f * t) +
                                                                     0.5 * std::sin(2 * kPi * k * f * t + ph)));
                                };
                                double in = 0;
                                for (int n = 14401; n < 48000; ++n) in = std::max(in, static_cast<double>(std::fabs(gen(n))));
                                const double base = peaks(reach, removeMix, 0.f, character, gen);
                                const double out = peaks(reach, removeMix, gain, character, gen);
                                const double ratio = out / (std::max(in, base) * (1.0 + riseFactor * gain));
                                worstRatio = std::max(worstRatio, ratio);
                            }
                        }
                    }
                }
            }
        }
    }
    INFO("worst output peak over its allowance: x", worstRatio);
    CHECK(worstRatio <= 1.0 + 1e-4);
}

TEST_CASE("the peak cap leaves a lone note's harmonics alone") {
    // A single sub note never reaches the allowance, so the cap must not touch it: the stage's
    // harmonics come out at the same level as with no cap to speak of (checked against the 2nd).
    for (float removeMix : {0.f, 1.f}) {
        VirtualBass vb;
        vb.rebuild(40.f, kSampleRate);
        std::vector<float> out(2 * 48000);
        for (int n = 0; n < 48000; ++n) {
            float l = 0.5f * static_cast<float>(std::sin(2 * kPi * 30.0 * n / kSampleRate)), r = l;
            vb.process(l, r, 1.f, removeMix, 1.f, 0.f);
            out[2 * n] = l;
            out[2 * n + 1] = r;
        }
        std::vector<float> tail(out.begin() + 2 * 24000, out.end());
        INFO("removeMix ", removeMix, ": 60 Hz harmonic at ", linToDb(channelMagnitudeAt(tail, 0, 60.0)), " dB");
        CHECK(linToDb(channelMagnitudeAt(tail, 0, 60.0) / 0.5) > -12.0);
    }
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
