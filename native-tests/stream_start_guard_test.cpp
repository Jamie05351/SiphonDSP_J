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
    CHECK(g.fadeIns == 0);  // no fade on a stream start: see the next test
    CHECK(g.nonFiniteFaults == 0);
    CHECK(g.overRangeFaults == 0);
}

// Measurements play REW's own sweep into the stream, so a stream start must not shape it: after
// the reset the output is exactly what a freshly started engine plays -- no fade, nothing left
// over.
TEST_CASE("After a stream start the output matches a fresh engine sample for sample") {
    auto c = defaultConfig();
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
}

TEST_CASE("A stream start ends a fade still running from the last stream") {
    auto c = limiterOff();
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

TEST_CASE("Loading an IR while the convolver plays dips and fades the output back in") {
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
    CHECK(proc.guardCounters().fadeIns == fadesBefore + 1);
    // Down within ~5 ms, then rising: the stretch just after the dip is well below steady.
    const std::size_t dip = static_cast<std::size_t>(NativeBmwDspProcessor::kFadeOutSeconds * kSampleRate);
    CHECK(rms(buf, dip, dip + kFadeFrames / 10) < 0.3 * steadyRms);
    CHECK(rms(buf, dip + 2 * kFadeFrames, dip + 2 * kFadeFrames + 2400) ==
          doctest::Approx(steadyRms).epsilon(0.05));
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
