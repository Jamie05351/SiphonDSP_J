// The measurement tools: the crossover bypass (v[309]), measurement neutral (v[310..311]), the
// engine status (readEngineStatus() / the truth snapshot's last block) and the self-test.
#include "test_support.h"

#include <cmath>
#include <cstdint>

#include "NativeBmwDspSchema.h"
#include "NativeBmwSelfTest.h"

using namespace nbtest;
namespace sch = nbschema;

namespace {
constexpr std::uint32_t bit(int stage) { return 1u << stage; }

// Every stage the neutral set covers switched on (or away from its identity value), the
// crossover in 3-way, with PEQ in every bank.
std::array<float, kConfigSize> everythingOn() {
    auto c = defaultConfig();
    c[sch::kHighXoPass] = 0.f;
    for (int slot = 0; slot < 2; ++slot) {
        c[sch::kMidUpperXo + slot * sch::kMidUpperXoWidth + sch::kMidUpperXoEnabled] = 1.f;
        c[sch::kHighOutputConfigBase + slot * sch::kOutputConfigWidth + sch::kOutMuted] = 0.f;
    }
    c[sch::kLowGainL] = -2.f;
    c[sch::kMidDelayR] = 1.f;
    c[sch::kOutputConfigBase + 1 * sch::kOutputConfigWidth + sch::kOutPolarityInverted] = 1.f;
    c[sch::kAllPassBase] = 1.f;  // Low Left section 0
    c[sch::kRoutingBase + 1] = 0.5f;
    c[sch::kBusLimLowEnabled] = 1.f;
    c[sch::kMonoBassEnabled] = 1.f;
    c[sch::kVirtualEnabled] = 1.f;
    c[sch::kVirtualBassEnabled] = 1.f;
    c[sch::kMbcEnabled] = 1.f;
    c[sch::kTiltEnabled] = 1.f;
    c[sch::kMasterLimiterEnabled] = 1.f;
    c[sch::kLoudnessEnabled] = 1.f;
    c[sch::kMasterVolumeDb] = -20.f;
    c[sch::kHeadroom] = -6.f;
    c[sch::kPostGainL] = 2.f;
    c[sch::kMeasurementMute] = 0.f;
    return c;
}

void peqEverywhere(NativeBmwDspProcessor& p) {
    double full[5] = {1000.0, 3.0, 0.7, 0.0, 0.0};
    double low[5] = {80.0, 4.0, 1.0, 0.0, 0.0};
    double mid[5] = {2000.0, -3.0, 1.0, 0.0, 0.0};
    double high[5] = {8000.0, 2.0, 1.0, 0.0, 0.0};
    REQUIRE(p.configurePeq(true, -2.f, full, 5, low, 5, mid, 5, high, 5));
}

std::array<float, kConfigSize> neutral(std::array<float, kConfigSize> c, std::uint32_t allow = 0) {
    c[sch::kMeasurementNeutral] = 1.f;
    c[sch::kNeutralAllow] = static_cast<float>(allow);
    return c;
}

std::vector<float> impulseResponse(NativeBmwDspProcessor& p, std::size_t frames, std::size_t at = 32) {
    std::vector<float> warm(2 * 4096, 0.f);
    p.process(warm.data(), warm.size());
    p.notifyStreamStart();
    std::vector<float> buf(2 * frames, 0.f);
    buf[2 * at] = 0.25f;
    buf[2 * at + 1] = -0.125f;
    p.process(buf.data(), buf.size());
    return buf;
}

float outLevelDbAt(NativeBmwDspProcessor& proc, const std::array<float, kConfigSize>& cfg, double freqHz) {
    constexpr double amp = 0.05;
    auto out = renderSteadyState(proc, cfg, freqHz, amp);
    return static_cast<float>(linToDb(channelMagnitudeAt(out, 0, freqHz) / amp));
}

std::array<float, NativeBmwDspProcessor::kEngineStatusSize> status(NativeBmwDspProcessor& p) {
    std::array<float, NativeBmwDspProcessor::kEngineStatusSize> s{};
    p.readEngineStatus(s.data(), s.size());
    return s;
}
}  // namespace

TEST_CASE("crossover bypass: one full-range path, zero added latency, band settings ignored") {
    auto c = defaultConfig();
    c[sch::kCrossoverBypass] = 1.f;
    c[sch::kMasterLimiterEnabled] = 0.f;
    c[sch::kTiltEnabled] = 0.f;
    c[sch::kHeadroom] = 0.f;
    // Band settings that would all change the output if any band ran.
    c[sch::kLowGainL] = -6.f;
    c[sch::kMidGainR] = 6.f;
    c[sch::kLowDelayL] = 2.f;
    c[sch::kMidDelayL] = 1.f;
    for (int o = 0; o < 4; ++o) {
        c[sch::kOutputConfigBase + o * sch::kOutputConfigWidth + sch::kOutPolarityInverted] = 1.f;
        c[sch::kOutputConfigBase + o * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    for (int i = 0; i < 8; ++i) {
        c[sch::kRoutingBase + i] = 0.f;
    }
    c[sch::kBusLimLowEnabled] = c[sch::kBusLimMidEnabled] = 1.f;
    c[sch::kMeasurementMute] = 2.f;
    NativeBmwDspProcessor p;
    p.setSampleRate(kSampleRate);
    REQUIRE(p.configure(c.data(), c.size()));
    const auto out = impulseResponse(p, 512);
    // Peak at the input frame, on the swapped side (input L -> output channel 1); only the 3 Hz
    // DC blocker (not neutralised here) is left, a one-pole whose first sample is exactly 1.
    CHECK(out[2 * 32 + 1] == doctest::Approx(0.25f).epsilon(1e-6));
    CHECK(out[2 * 32] == doctest::Approx(-0.125f).epsilon(1e-6));
    for (std::size_t n = 0; n < 32; ++n) {
        CHECK(out[2 * n] == 0.f);
        CHECK(out[2 * n + 1] == 0.f);
    }
    const auto s = status(p);
    CHECK(s[0] == 1.f);
    CHECK(s[4] == 0.f);  // crossover: bypassed
    const auto active = static_cast<std::uint32_t>(s[1]);
    for (int stage : {sch::kStageCrossover, sch::kStageBandGain, sch::kStageBandDelay, sch::kStagePolarity,
                      sch::kStageRouting, sch::kStageBusLimiter, sch::kStageMeasurementMute}) {
        INFO("stage ", stage);
        CHECK((active & bit(stage)) == 0u);
    }
    CHECK((active & bit(sch::kStageDcBlocker)) != 0u);
}

TEST_CASE("crossover bypass on and off crossfades without a step, and the split comes back clean") {
    auto c = defaultConfig();
    c[sch::kMasterLimiterEnabled] = 0.f;
    c[sch::kLowDelayL] = c[sch::kLowDelayR] = 2.f;
    auto bypassed = c;
    bypassed[sch::kCrossoverBypass] = 1.f;
    NativeBmwDspProcessor p;
    p.setSampleRate(kSampleRate);
    REQUIRE(p.configure(c.data(), c.size()));
    // A 40 Hz tone: per sample it moves at most 2 pi 40 / 48000 x its level, ~0.5 % of it.
    auto run = [&p](std::size_t frames, std::vector<float>& carry, std::size_t& phase) {
        auto tone = stereoSine(40.0, 0.3, phase + frames);
        std::vector<float> chunk(tone.end() - static_cast<std::ptrdiff_t>(2 * frames), tone.end());
        p.process(chunk.data(), chunk.size());
        carry.insert(carry.end(), chunk.begin(), chunk.end());
        phase += frames;
    };
    std::vector<float> out;
    std::size_t phase = 0;
    run(24000, out, phase);
    REQUIRE(p.configure(bypassed.data(), bypassed.size()));
    run(24000, out, phase);
    REQUIRE(p.configure(c.data(), c.size()));
    run(24000, out, phase);
    float maxStep = 0.f;
    for (std::size_t i = 2 * 12000; i + 2 < out.size(); ++i) {
        maxStep = std::max(maxStep, std::fabs(out[i + 2] - out[i]));
    }
    INFO("largest sample-to-sample step across both toggles = ", maxStep);
    CHECK(maxStep < 0.02f);
}

TEST_CASE("measurement neutral: every stage on, and the output is the input, bit for bit") {
    const auto on = everythingOn();
    auto n = neutral(on);
    NativeBmwDspProcessor p;
    p.setSampleRate(kSampleRate);
    REQUIRE(p.configure(n.data(), n.size()));
    peqEverywhere(p);
    const auto out = impulseResponse(p, 2048);
    // -20 dB master volume still applies (it is the car's volume): exactly x 0.1.
    const float volume = std::pow(10.f, -20.f / 20.f);
    for (std::size_t i = 0; i < 2048; ++i) {
        const float inL = i == 32 ? 0.25f : 0.f, inR = i == 32 ? -0.125f : 0.f;
        INFO("frame ", i);
        CHECK(out[2 * i + 1] == doctest::Approx(inL * volume).epsilon(1e-6));
        CHECK(out[2 * i] == doctest::Approx(inR * volume).epsilon(1e-6));
    }
    const auto s = status(p);
    CHECK(s[2] == 1.f);
    CHECK(static_cast<std::uint32_t>(s[1]) == 0u);  // nothing running
    CHECK(s[9] == -20.f);
}

TEST_CASE("measurement neutral off restores exactly the chain that was running") {
    const auto on = everythingOn();
    auto n = neutral(on);
    NativeBmwDspProcessor reference, toggled;
    for (auto* p : {&reference, &toggled}) {
        p->setSampleRate(kSampleRate);
        REQUIRE(p->configure(on.data(), on.size()));
        peqEverywhere(*p);
    }
    std::vector<float> warm(2 * 4096, 0.f);
    toggled.process(warm.data(), warm.size());
    REQUIRE(toggled.configure(n.data(), n.size()));
    toggled.process(warm.data(), warm.size());
    REQUIRE(toggled.configure(on.data(), on.size()));
    const auto a = impulseResponse(reference, 8192);
    const auto b = impulseResponse(toggled, 8192);
    float diff = 0.f;
    for (std::size_t i = 0; i < a.size(); ++i) {
        diff = std::max(diff, std::fabs(a[i] - b[i]));
    }
    CHECK(diff == 0.f);
    CHECK(status(toggled)[2] == 0.f);
}

TEST_CASE("measurement neutral: an allowed stage runs as set, the rest stay bypassed") {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 1.f;
    c[sch::kTiltAmount] = 6.f;
    c[sch::kTiltFreq] = 1000.f;
    NativeBmwDspProcessor all, tiltOnly;
    auto n = neutral(c);
    auto t = neutral(c, bit(sch::kStageTilt));
    all.setSampleRate(kSampleRate);
    tiltOnly.setSampleRate(kSampleRate);
    const float flat = outLevelDbAt(all, n, 8000.0);
    const float tilted = outLevelDbAt(tiltOnly, t, 8000.0);
    INFO("8 kHz: neutral ", flat, " dB, neutral + tilt ", tilted, " dB");
    CHECK(std::fabs(flat) < 0.01f);
    CHECK(std::fabs(tilted - flat) > 2.f);
    CHECK(static_cast<std::uint32_t>(status(tiltOnly)[1]) == bit(sch::kStageTilt));
    CHECK(status(tiltOnly)[3] == static_cast<float>(bit(sch::kStageTilt)));
}

TEST_CASE("engine status reports the installed chain") {
    auto c = everythingOn();
    NativeBmwDspProcessor p;
    p.setSampleRate(kSampleRate);
    CHECK(status(p)[0] == 0.f);  // nothing installed yet
    REQUIRE(p.configure(c.data(), c.size()));
    peqEverywhere(p);
    std::vector<float> buf(2 * 256, 0.f);
    p.process(buf.data(), buf.size());
    const auto s = status(p);
    const auto active = static_cast<std::uint32_t>(s[1]);
    for (int stage : {sch::kStagePreEq, sch::kStageCrossover, sch::kStageBandEq, sch::kStageBandGain,
                      sch::kStageBandDelay, sch::kStagePolarity, sch::kStageAllPass, sch::kStageRouting,
                      sch::kStageBusLimiter, sch::kStageMonoBass, sch::kStageVirtualCentre,
                      sch::kStageVirtualBass, sch::kStageMbc, sch::kStageTilt, sch::kStageMasterLimiter,
                      sch::kStageLoudness, sch::kStageHeadroom, sch::kStagePostGain, sch::kStageDcBlocker}) {
        INFO("stage ", stage);
        CHECK((active & bit(stage)) != 0u);
    }
    // Enabled with no IR loaded: not running, not reported.
    CHECK((active & bit(sch::kStageConvolver)) == 0u);
    CHECK(s[4] == 3.f);
    CHECK(s[12] == kSampleRate);
    // The truth snapshot carries the same block at its end.
    const auto snap = p.captureTruthSnapshot();
    REQUIRE(snap.size() > NativeBmwDspProcessor::kEngineStatusSize);
    const std::size_t at = snap.size() - NativeBmwDspProcessor::kEngineStatusSize;
    CHECK(snap[at] == 1.0);
    CHECK(static_cast<std::uint32_t>(snap[at + 1]) == active);
}

TEST_CASE("self-test: neutral is verified flat, zero phase, zero latency") {
    auto c = neutral(everythingOn());
    NativeBmwDspProcessor p;
    p.setSampleRate(kSampleRate);
    REQUIRE(p.configure(c.data(), c.size()));
    peqEverywhere(p);
    NativeBmwDspProcessor::SelfTestSource source;
    REQUIRE(p.selfTestSource(source));
    const auto r = NativeBmwDsp::runSelfTest(source);
    REQUIRE(r.ran);
    INFO("mag ", r.magnitudeDevDb, " dB, phase ", r.phaseDevDeg, " deg, latency ", r.latencyFrames,
         ", crosstalk ", r.crosstalkDb, " dB");
    CHECK(r.verified);
    CHECK(r.magnitudeDevDb <= 0.01);
    CHECK(r.phaseDevDeg <= 0.1);
    CHECK(r.latencyFrames == 0);
    CHECK(r.activeStages == 0u);
    CHECK(r.alteringStages == 0u);
    CHECK(r.masterVolumeDb == -20.0);
}

TEST_CASE("self-test: names the stages still altering the signal") {
    auto c = defaultConfig();
    c[sch::kMasterLimiterEnabled] = 0.f;
    c[sch::kTiltEnabled] = 1.f;
    // Under neutral with tilt allowed back on: the only thing left is tilt.
    auto t = neutral(c, bit(sch::kStageTilt));
    {
        NativeBmwDspProcessor p;
        p.setSampleRate(kSampleRate);
        REQUIRE(p.configure(t.data(), t.size()));
        NativeBmwDspProcessor::SelfTestSource source;
        REQUIRE(p.selfTestSource(source));
        const auto r = NativeBmwDsp::runSelfTest(source);
        REQUIRE(r.ran);
        CHECK_FALSE(r.verified);
        CHECK(r.alteringStages == bit(sch::kStageTilt));
    }
    // The default chain: the crossover, its band gains, the headroom and the DC blocker at least.
    {
        NativeBmwDspProcessor p;
        p.setSampleRate(kSampleRate);
        REQUIRE(p.configure(c.data(), c.size()));
        NativeBmwDspProcessor::SelfTestSource source;
        REQUIRE(p.selfTestSource(source));
        const auto r = NativeBmwDsp::runSelfTest(source);
        REQUIRE(r.ran);
        CHECK_FALSE(r.verified);
        for (int stage : {sch::kStageCrossover, sch::kStageTilt, sch::kStageHeadroom, sch::kStageDcBlocker,
                          sch::kStageBandGain}) {
            INFO("stage ", stage);
            CHECK((r.alteringStages & bit(stage)) != 0u);
        }
        CHECK(r.latencyFrames > 0);  // the band chain's alignment kernel and limiter lookahead
    }
}

TEST_CASE("applyMeasurementNeutral leaves the saved config's own slots alone when a stage is allowed") {
    auto c = everythingOn();
    c[sch::kNeutralAllow] = static_cast<float>(bit(sch::kStageBandGain) | bit(sch::kStageMbc));
    std::array<float, kConfigSize> out{};
    NativeBmwDspProcessor::applyMeasurementNeutral(c.data(), out.data());
    CHECK(out[sch::kLowGainL] == -2.f);
    CHECK(out[sch::kMbcEnabled] == 1.f);
    CHECK(out[sch::kTiltEnabled] == 0.f);
    CHECK(out[sch::kCrossoverBypass] == 1.f);
    CHECK(out[sch::kMasterVolumeDb] == -20.f);  // never touched
}
