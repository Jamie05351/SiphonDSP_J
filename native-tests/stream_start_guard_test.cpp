#include "test_support.h"

#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

using namespace nbtest;
namespace sch = nbschema;

// The stream-start reset, the output guard's fault reset and the output fade-in (see
// NativeBmwDspProcessor::notifyStreamStart() / kFaultLevel / kFadeInSeconds).
namespace {
constexpr std::size_t kFadeFrames = static_cast<std::size_t>(NativeBmwDspProcessor::kFadeInSeconds * kSampleRate);

std::array<float, kConfigSize> limiterOff() {
    auto c = defaultConfig();
    c[sch::kMasterLimiterEnabled] = 0.f;
    return c;
}

double rms(const std::vector<float>& v, std::size_t fromFrame, std::size_t toFrame) {
    double sum = 0.0;
    for (std::size_t i = 2 * fromFrame; i < 2 * toFrame; ++i) {
        sum += double(v[i]) * v[i];
    }
    return std::sqrt(sum / double(2 * (toFrame - fromFrame)));
}

std::array<float, kConfigSize> measuringNeutral() {
    auto c = defaultConfig();
    c[sch::kMeasurementNeutral] = 1.f;
    return c;
}

NativeBmwDsp::ConvolverIr deltaIr(double gain, std::size_t at) {
    NativeBmwDsp::ConvolverIr ir;
    ir.sampleRate = kSampleRate;
    ir.channels = 1;
    ir.left.assign(at + 64, 0.0);
    ir.left[at] = gain;
    return ir;
}
}  // namespace

TEST_CASE("A stream start clears the chain: nothing of the last stream replays") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = defaultConfig();
    REQUIRE(proc.configure(c.data(), c.size()));
    auto loud = stereoSine(60.0, 0.8, 24000);
    proc.process(loud.data(), loud.size());

    // Control: silence straight after the music still carries the chain's tail.
    {
        std::vector<float> tail(2 * 256, 0.f);
        NativeBmwDspProcessor same;
        same.setSampleRate(kSampleRate);
        REQUIRE(same.configure(c.data(), c.size()));
        auto again = stereoSine(60.0, 0.8, 24000);
        same.process(again.data(), again.size());
        same.process(tail.data(), tail.size());
        CHECK(peakAbs(tail) > 1e-3f);
    }

    proc.notifyStreamStart();
    std::vector<float> silence(2 * 4096, 0.f);
    proc.process(silence.data(), silence.size());
    for (float v : silence) {
        REQUIRE(v == 0.f);
    }
    const auto g = proc.guardCounters();
    CHECK(g.streamStarts == 1);
    CHECK(g.fadeIns == 1);  // music: a stream start fades in (see below)
    CHECK(g.nonFiniteFaults == 0);
    CHECK(g.overRangeFaults == 0);
}

// Music: a stream's first audio ramps up from silence over kFadeInSeconds instead of arriving as
// a step, then plays exactly as a fresh engine does.
TEST_CASE("A stream start fades the output in from silence") {
    auto c = limiterOff();
    NativeBmwDspProcessor used, fresh;
    for (auto* p : {&used, &fresh}) {
        p->setSampleRate(kSampleRate);
        REQUIRE(p->configure(c.data(), c.size()));
    }
    auto music = stereoSine(60.0, 0.8, 24000);
    used.process(music.data(), music.size());

    used.notifyStreamStart();
    auto x = stereoSine(1000.0, 0.5, 2 * kFadeFrames + 4800), reference = x;
    used.process(x.data(), x.size());
    fresh.process(reference.data(), reference.size());
    const auto g = used.guardCounters();
    CHECK(g.streamStarts == 1);
    CHECK(g.fadeIns == 1);
    // A ramp: quiet at first, the full level of a fresh engine only once the fade is through.
    CHECK(rms(x, 0, kFadeFrames / 10) < 0.15 * rms(reference, 0, kFadeFrames / 10));
    CHECK(rms(x, kFadeFrames / 2 - 48, kFadeFrames / 2 + 48) ==
          doctest::Approx(0.5 * rms(reference, kFadeFrames / 2 - 48, kFadeFrames / 2 + 48)).epsilon(0.05));
    for (std::size_t i = 2 * (kFadeFrames + 1); i < x.size(); ++i) {
        REQUIRE(x[i] == reference[i]);
    }
    // Never louder than the unfaded output, anywhere in the ramp.
    for (std::size_t i = 0; i < 2 * kFadeFrames; ++i) {
        REQUIRE(std::fabs(x[i]) <= std::fabs(reference[i]) + 1e-6f);
    }
}

// Measurements play REW's own sweep into the stream, so while measuring a stream start must not
// shape it: after the reset the output is exactly what a freshly started engine plays -- no fade,
// nothing left over.
TEST_CASE("While measuring, after a stream start the output matches a fresh engine sample for sample") {
    auto c = measuringNeutral();
    NativeBmwDspProcessor used, fresh;
    for (auto* p : {&used, &fresh}) {
        p->setSampleRate(kSampleRate);
        REQUIRE(p->configure(c.data(), c.size()));
    }
    auto music = stereoSine(60.0, 0.8, 24000);
    used.process(music.data(), music.size());

    used.notifyStreamStart();
    auto sweep = stereoSine(1000.0, 0.5, 9600), reference = sweep;
    used.process(sweep.data(), sweep.size());
    fresh.process(reference.data(), reference.size());
    REQUIRE(sweep.size() == reference.size());
    for (std::size_t i = 0; i < sweep.size(); ++i) {
        REQUIRE(sweep[i] == reference[i]);
    }
    CHECK(used.guardCounters().fadeIns == 0);
}

TEST_CASE("The measurement generator on: a stream start doesn't fade") {
    auto c = defaultConfig();
    c[sch::kMeasGenType] = 1.f;  // the sweep: deterministic, so two engines can be compared
    // Same history for both; only the stream start differs: plain (decided by the settings)
    // against one that is unfaded by request. With the generator on they must match exactly.
    NativeBmwDspProcessor used, unfaded;
    for (auto* p : {&used, &unfaded}) {
        p->setSampleRate(kSampleRate);
        REQUIRE(p->configure(c.data(), c.size()));
        std::vector<float> warm(2 * 4800, 0.f);
        p->process(warm.data(), warm.size());
    }
    used.notifyStreamStart();
    unfaded.notifyStreamStartUnfaded();
    std::vector<float> buf(2 * 9600, 0.f), reference = buf;
    used.process(buf.data(), buf.size());
    unfaded.process(reference.data(), reference.size());
    REQUIRE(peakAbs(reference) > 1e-3f);  // the generator is playing
    for (std::size_t i = 0; i < buf.size(); ++i) {
        REQUIRE(buf[i] == reference[i]);
    }
    const auto g = used.guardCounters();
    CHECK(g.streamStarts == 1);
    CHECK(g.fadeIns == 0);
}

TEST_CASE("An unfaded stream start (the self-test's) never fades, and wins over a plain one") {
    auto c = defaultConfig();
    NativeBmwDspProcessor used, fresh;
    for (auto* p : {&used, &fresh}) {
        p->setSampleRate(kSampleRate);
        REQUIRE(p->configure(c.data(), c.size()));
    }
    auto music = stereoSine(60.0, 0.8, 24000);
    used.process(music.data(), music.size());
    used.notifyStreamStart();
    used.notifyStreamStartUnfaded();  // both before one buffer: served once, unfaded
    auto x = stereoSine(1000.0, 0.5, 9600), reference = x;
    used.process(x.data(), x.size());
    fresh.process(reference.data(), reference.size());
    for (std::size_t i = 0; i < x.size(); ++i) {
        REQUIRE(x[i] == reference[i]);
    }
    CHECK(used.guardCounters().streamStarts == 1);
    CHECK(used.guardCounters().fadeIns == 0);
}

TEST_CASE("While measuring, a stream start ends a fade still running from the last stream") {
    auto c = measuringNeutral();
    c[sch::kMasterLimiterEnabled] = 0.f;
    NativeBmwDspProcessor used, fresh;
    for (auto* p : {&used, &fresh}) {
        p->setSampleRate(kSampleRate);
        REQUIRE(p->configure(c.data(), c.size()));
    }
    auto music = stereoSine(220.0, 0.5, 9600);
    music[2 * 9000] = 1e6f;  // a fault right at the end: its fade-in is still running
    used.process(music.data(), music.size());
    REQUIRE(used.guardCounters().overRangeFaults == 1);

    used.notifyStreamStart();
    auto sweep = stereoSine(1000.0, 0.5, 4800), reference = sweep;
    used.process(sweep.data(), sweep.size());
    fresh.process(reference.data(), reference.size());
    for (std::size_t i = 0; i < sweep.size(); ++i) {
        REQUIRE(sweep[i] == reference[i]);
    }
}

TEST_CASE("A bad ramp state is cleared by the fault reset, not tripped on forever") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = defaultConfig();
    REQUIRE(proc.configure(c.data(), c.size()));
    auto warm = stereoSine(220.0, 0.5, 4800);
    proc.process(warm.data(), warm.size());

    NativeBmwDspProcessorTestAccess::poisonMasterGain(proc);
    auto buf = stereoSine(220.0, 0.5, 2 * kFadeFrames + 4800);
    proc.process(buf.data(), buf.size());
    CHECK(proc.guardCounters().nonFiniteFaults == 1);  // one fault, then recovered
    CHECK(rms(buf, 2 * kFadeFrames, 2 * kFadeFrames + 4800) > 0.1);
}

TEST_CASE("A NaN ahead of the band split is a counted fault and the chain recovers") {
    // The band lanes silence a non-finite sample, so without the lane check it would never
    // reach the output guard: a stage stuck on NaN ahead of the split would play silence with
    // no fault counted. Both ways in: a bad ramp state, and a NaN in the input stream.
    SUBCASE("a bad headroom ramp") {
        NativeBmwDspProcessor proc;
        proc.setSampleRate(kSampleRate);
        auto c = defaultConfig();
        REQUIRE(proc.configure(c.data(), c.size()));
        auto warm = stereoSine(220.0, 0.5, 4800);
        proc.process(warm.data(), warm.size());
        NativeBmwDspProcessorTestAccess::poisonHeadroom(proc);
        auto buf = stereoSine(220.0, 0.5, 2 * kFadeFrames + 4800);
        proc.process(buf.data(), buf.size());
        CHECK(proc.guardCounters().nonFiniteFaults == 1);
        CHECK(rms(buf, 2 * kFadeFrames, 2 * kFadeFrames + 4800) > 0.1);
    }
    SUBCASE("a NaN input sample") {
        NativeBmwDspProcessor proc;
        proc.setSampleRate(kSampleRate);
        auto c = defaultConfig();
        REQUIRE(proc.configure(c.data(), c.size()));
        auto warm = stereoSine(220.0, 0.5, 4800);
        proc.process(warm.data(), warm.size());
        auto buf = stereoSine(220.0, 0.5, 2 * kFadeFrames + 4800);
        buf[2 * 100] = std::numeric_limits<float>::quiet_NaN();
        proc.process(buf.data(), buf.size());
        // The first stage it meets flushes it to 0, so it is no fault: nothing bad ever reached
        // the split. Either way it must not leave the chain stuck silent.
        CHECK(proc.guardCounters().nonFiniteFaults == 0);
        for (float v : buf) {
            CHECK(std::isfinite(v));
        }
        CHECK(rms(buf, 2 * kFadeFrames, 2 * kFadeFrames + 4800) > 0.1);
    }
}

TEST_CASE("Without a stream start nothing fades: the output is untouched") {
    NativeBmwDspProcessor a, b;
    for (auto* p : {&a, &b}) {
        p->setSampleRate(kSampleRate);
        auto c = defaultConfig();
        REQUIRE(p->configure(c.data(), c.size()));
    }
    auto x = stereoSine(440.0, 0.5, 4096), y = x;
    a.process(x.data(), x.size());
    b.process(y.data(), y.size());
    CHECK(x == y);
    CHECK(a.guardCounters().fadeIns == 0);
}

TEST_CASE("An over-range sample after the limiter resets the chain and fades back in") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = limiterOff();
    REQUIRE(proc.configure(c.data(), c.size()));
    auto warm = stereoSine(220.0, 0.5, 9600);
    proc.process(warm.data(), warm.size());

    auto buf = stereoSine(220.0, 0.5, 4096);
    buf[2 * 1000] = 1e6f;  // a runaway: far past kFaultLevel, finite
    proc.process(buf.data(), buf.size());
    for (float v : buf) {
        REQUIRE(std::isfinite(v));
        REQUIRE(std::fabs(v) <= 1.f);
    }
    const auto g = proc.guardCounters();
    CHECK(g.overRangeFaults == 1);
    CHECK(g.nonFiniteFaults == 0);
    CHECK(g.fadeIns == 1);
    // The fault's chunk goes silent from the faulting frame on (allowing for the chain's latency
    // before the spike reaches the output), and the output is back to full level after the fade.
    auto after = stereoSine(220.0, 0.5, 2 * kFadeFrames + 4800);
    proc.process(after.data(), after.size());
    CHECK(rms(after, 2 * kFadeFrames, 2 * kFadeFrames + 4800) > 0.1);
    CHECK(proc.guardCounters().overRangeFaults == 1);  // one fault, not a fault per sample
}

TEST_CASE("A loud but legitimate setting is clamped, counted as clipping, never a fault") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = limiterOff();
    c[5] = 0.f;            // headroom 0 dB
    c[10] = c[11] = 12.f;  // +12 dB post gain: past full scale, far below kFaultLevel
    REQUIRE(proc.configure(c.data(), c.size()));
    auto buf = stereoSine(220.0, 0.9, 16000);
    proc.process(buf.data(), buf.size());
    const auto g = proc.guardCounters();
    CHECK(g.overRangeFaults == 0);
    CHECK(g.nonFiniteFaults == 0);
    CHECK(g.clippedSamples > 0);
}

TEST_CASE("Swapping the IR while the convolver plays crossfades, without a dip or an output fade") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = defaultConfig();
    c[sch::kConvolverEnabled] = 1.f;
    REQUIRE(proc.configure(c.data(), c.size()));
    std::string error;
    REQUIRE(proc.loadConvolverIr(deltaIr(0.5, 0), error));
    auto warm = stereoSine(1000.0, 0.5, 24000);
    proc.process(warm.data(), warm.size());
    const auto fadesBefore = proc.guardCounters().fadeIns;
    auto ref = stereoSine(1000.0, 0.5, 4800);
    proc.process(ref.data(), ref.size());
    const double steadyRms = rms(ref, 0, 4800);

    REQUIRE(proc.loadConvolverIr(deltaIr(0.5, 10), error));
    auto buf = stereoSine(1000.0, 0.5, 2 * kFadeFrames + 4800);
    proc.process(buf.data(), buf.size());
    CHECK(proc.guardCounters().fadeIns == fadesBefore);
    // Same level throughout: the old IR plays through the new one's first block, then they
    // crossfade (a 10-sample shift of a 1 kHz tone barely changes the sum's level).
    for (std::size_t from = 0; from + 480 <= buf.size() / 2; from += 480) {
        CAPTURE(from);
        CHECK(rms(buf, from, from + 480) > 0.8 * steadyRms);
    }
}

TEST_CASE("Loading an IR while the convolver is off changes nothing") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = defaultConfig();
    c[sch::kConvolverEnabled] = 0.f;
    REQUIRE(proc.configure(c.data(), c.size()));
    std::string error;
    REQUIRE(proc.loadConvolverIr(deltaIr(0.5, 0), error));
    auto buf = stereoSine(1000.0, 0.5, 4096);
    proc.process(buf.data(), buf.size());
    CHECK(proc.guardCounters().fadeIns == 0);
}
