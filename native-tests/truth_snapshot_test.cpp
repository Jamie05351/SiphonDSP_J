#include "test_support.h"

#include "NativeBmwDspSchema.h"

// Proves NativeBmwDspProcessor::captureTruthSnapshot() -- the native-truth debug screen's one
// data source -- actually reflects the installed filter topology/coefficients and PEQ bands,
// not just the requested config enum, and never adopts a rejected configuration.

using namespace nbtest;
namespace sch = nbschema;

namespace {

constexpr std::size_t kHeaderWidth = 3;
constexpr std::size_t kOutputBlockWidth = NativeBmwDspProcessor::kTruthOutputWidth;
constexpr std::size_t kScalarFieldsPerOutput = 8;
constexpr std::size_t kStageWidth = 8;
// 6 output blocks now (Low/Mid/High x L/R), not 4 -- see docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
constexpr std::size_t kPeqSectionOffset =
    kHeaderWidth + NativeBmwRouting::kOutputCount * kOutputBlockWidth;

// Slot values, matching NativeBmwDspValues.CROSSOVER_TYPE_* / OutputConfig::CrossoverType.
constexpr float kBw2 = 0.f, kBw3 = 1.f, kLr4 = 2.f, kBw1 = 3.f;

struct StageView {
    int topology;
    double a1, a2, a3, m0, m1, m2, opA;
};

StageView stageAt(const std::vector<double>& snap, std::size_t outputIndex, int stageNumber) {
    const std::size_t base = kHeaderWidth + outputIndex * kOutputBlockWidth +
                             kScalarFieldsPerOutput + static_cast<std::size_t>(stageNumber - 1) * kStageWidth;
    return StageView{
        static_cast<int>(snap[base]),   snap[base + 1], snap[base + 2], snap[base + 3],
        snap[base + 4],                 snap[base + 5], snap[base + 6], snap[base + 7],
    };
}

// Exactly NativeBmwDspProcessor::makeIdentity()'s installed shape -- the discriminator this test
// file and NativeBiquadStage.isIdentity (Kotlin) both use to prove a crossover's unused second
// stage isn't a lingering, previously-active filter.
bool isIdentity(const StageView& s) {
    return s.topology == 0 && s.a1 == 0.0 && s.a2 == 0.0 && s.a3 == 0.0 && s.m0 == 1.0 &&
           s.m1 == 0.0 && s.m2 == 0.0;
}

std::array<float, kConfigSize> lowOnlyConfig(float fc, float type) {
    auto c = defaultConfig();
    for (int out = 2; out < 4; ++out) {  // mute Mid L/R, isolate the Low crossover
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    }
    for (int out = 0; out < 2; ++out) {  // Low L, Low R
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverFreq] = fc;
        c[sch::kOutputConfigBase + out * sch::kOutputConfigWidth + sch::kOutCrossoverType] = type;
    }
    return c;
}

}  // namespace

TEST_CASE("Truth snapshot: LR4 reports two active biquad stages") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = lowOnlyConfig(200.f, kLr4);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    CHECK_FALSE(isIdentity(stageAt(snap, 0, 1)));
    CHECK_FALSE(isIdentity(stageAt(snap, 0, 2)));
}

TEST_CASE("Truth snapshot: BW3 reports the one-pole + second-order cascade") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = lowOnlyConfig(200.f, kBw3);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    const auto s1 = stageAt(snap, 0, 1);
    const auto s2 = stageAt(snap, 0, 2);
    CHECK(s1.topology == 2);  // Biquad::Topology::OnePoleLowpass
    CHECK(s2.topology == 0);  // Biquad::Topology::Svf2
    CHECK_FALSE(isIdentity(s2));
}

TEST_CASE("Truth snapshot: BW2 reports one second-order stage with the unused stage identity") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = lowOnlyConfig(200.f, kBw2);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    CHECK_FALSE(isIdentity(stageAt(snap, 0, 1)));
    CHECK(isIdentity(stageAt(snap, 0, 2)));
}

TEST_CASE("Truth snapshot: BW4 reports two second-order stages with different Qs") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = lowOnlyConfig(200.f, 4.f);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    const auto s1 = stageAt(snap, 0, 1);
    const auto s2 = stageAt(snap, 0, 2);
    CHECK(s1.topology == 0);  // Biquad::Topology::Svf2
    CHECK(s2.topology == 0);
    CHECK_FALSE(isIdentity(s1));
    CHECK_FALSE(isIdentity(s2));
    // LR4 uses the same Q twice; BW4 must not.
    CHECK(s1.a1 != doctest::Approx(s2.a1));
}

TEST_CASE("Truth snapshot: BW1 reports one one-pole stage with the unused stage identity") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = lowOnlyConfig(200.f, kBw1);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    const auto s1 = stageAt(snap, 0, 1);
    CHECK(s1.topology == 2);  // Biquad::Topology::OnePoleLowpass
    CHECK(isIdentity(stageAt(snap, 0, 2)));
}

TEST_CASE("Truth snapshot topology changes when the crossover type changes (LR4 -> BW3 -> BW2 -> BW1)") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);

    auto lr4 = lowOnlyConfig(200.f, kLr4);
    REQUIRE(proc.configure(lr4.data(), lr4.size()));
    const auto lr4Snap = proc.captureTruthSnapshot();

    auto bw3 = lowOnlyConfig(200.f, kBw3);
    REQUIRE(proc.configure(bw3.data(), bw3.size()));
    const auto bw3Snap = proc.captureTruthSnapshot();

    auto bw2 = lowOnlyConfig(200.f, kBw2);
    REQUIRE(proc.configure(bw2.data(), bw2.size()));
    const auto bw2Snap = proc.captureTruthSnapshot();

    auto bw1 = lowOnlyConfig(200.f, kBw1);
    REQUIRE(proc.configure(bw1.data(), bw1.size()));
    const auto bw1Snap = proc.captureTruthSnapshot();

    // Each transition must actually change stage 2's installed shape -- proves an old stage
    // never lingers active after a config change (LR4 -> BW3: stage2 stays a real 2nd-order
    // filter but its coefficients change topology-relevant meaning; BW3 -> BW2: stage2 goes
    // active -> identity; BW2 -> BW1: stage1 changes from Svf2 to a genuine one-pole).
    CHECK_FALSE(isIdentity(stageAt(lr4Snap, 0, 2)));
    CHECK_FALSE(isIdentity(stageAt(bw3Snap, 0, 2)));
    CHECK(isIdentity(stageAt(bw2Snap, 0, 2)));
    CHECK(isIdentity(stageAt(bw1Snap, 0, 2)));
    CHECK(stageAt(bw2Snap, 0, 1).topology == 0);
    CHECK(stageAt(bw1Snap, 0, 1).topology == 2);
}

TEST_CASE("Truth snapshot: crossover disabled (muted output) still reports the real installed filter") {
    // "Enabled -> disabled" in this processor means the output bus is muted downstream
    // (OutputRuntime::muted), not that the crossover filter itself is torn down -- the filter
    // keeps running so re-enabling doesn't need a rebuild. The truth screen must report that
    // faithfully: filter topology unaffected, mute flag true.
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = lowOnlyConfig(200.f, kLr4);
    cfg[sch::kOutputConfigBase + 0 * sch::kOutputConfigWidth + sch::kOutMuted] = 1.f;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    const std::size_t base = kHeaderWidth + 0 * kOutputBlockWidth;
    CHECK(snap[base + 4] == 1.0);  // muted
    CHECK_FALSE(isIdentity(stageAt(snap, 0, 1)));
    CHECK_FALSE(isIdentity(stageAt(snap, 0, 2)));
}

TEST_CASE("Truth snapshot reports mute/polarity/freq for the actual configured output") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = lowOnlyConfig(180.f, kLr4);
    cfg[sch::kOutputConfigBase + 0 * sch::kOutputConfigWidth + sch::kOutPolarityInverted] = 1.f;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    const std::size_t base = kHeaderWidth + 0 * kOutputBlockWidth;
    CHECK(snap[base + 0] == doctest::Approx(180.0));
    CHECK(snap[base + 5] == 1.0);  // polarityInverted
}

TEST_CASE("Truth snapshot reflects actual configured PEQ bands") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    double full[5] = {1000.0, 3.0, 0.7, 0.0, 0.0};  // freq, gain, Q, type=bell, channel=both
    REQUIRE(proc.configurePeq(true, 0.f, full, 5, nullptr, 0, nullptr, 0));

    auto snap = proc.captureTruthSnapshot();
    CHECK(snap[1] == 1.0);  // peqEnabled
    CHECK(snap[kPeqSectionOffset + 0] == 1.0);  // rawBandCount
    CHECK(snap[kPeqSectionOffset + 1] == 1.0);  // leftActiveCount
    CHECK(snap[kPeqSectionOffset + 2] == 1.0);  // rightActiveCount
    CHECK(snap[kPeqSectionOffset + 3] == doctest::Approx(1000.0));  // freq
    CHECK(snap[kPeqSectionOffset + 4] == doctest::Approx(3.0));     // gain
    CHECK(snap[kPeqSectionOffset + 8] == 1.0);  // active
}

TEST_CASE("Truth snapshot reports a near-zero-gain non-notch band as inactive") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    double band[5] = {1000.0, 0.0, 0.7, 0.0, 0.0};  // bell, ~0 dB -> configurePeqLocked drops it
    REQUIRE(proc.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0));

    auto snap = proc.captureTruthSnapshot();
    CHECK(snap[kPeqSectionOffset + 0] == 1.0);  // raw value still reported
    CHECK(snap[kPeqSectionOffset + 1] == 0.0);  // leftActiveCount: no Biquad installed
    CHECK(snap[kPeqSectionOffset + 2] == 0.0);  // rightActiveCount
    CHECK(snap[kPeqSectionOffset + 8] == 0.0);  // active flag false
}

TEST_CASE("Truth snapshot never adopts a rejected PEQ configuration") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    double good[5] = {1000.0, 3.0, 0.7, 0.0, 0.0};
    REQUIRE(proc.configurePeq(true, 0.f, good, 5, nullptr, 0, nullptr, 0));
    auto before = proc.captureTruthSnapshot();

    // preampDb outside [-30, 12] -> configurePeqLocked rejects before touching any state.
    double rejected[5] = {2000.0, 6.0, 1.0, 0.0, 0.0};
    CHECK_FALSE(proc.configurePeq(false, 999.f, rejected, 5, nullptr, 0, nullptr, 0));
    auto after = proc.captureTruthSnapshot();

    CHECK(before[1] == after[1]);  // peqEnabled unchanged (still true, not the rejected false)
    CHECK(after[kPeqSectionOffset + 3] == doctest::Approx(1000.0));  // still the accepted band
    CHECK(after[kPeqSectionOffset + 4] == doctest::Approx(3.0));
}

TEST_CASE("Truth snapshot: Mid's upper crossover corner reports real stage3/stage4, not identity") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    for (int out = 2; out < 4; ++out) {  // Mid Left, Mid Right (outputIndex 2, 3)
        const int slot = out - 2;
        cfg[sch::kMidUpperXo + slot * sch::kMidUpperXoWidth + sch::kMidUpperXoFreq] = 3000.f;
        cfg[sch::kMidUpperXo + slot * sch::kMidUpperXoWidth + sch::kMidUpperXoEnabled] = 1.f;
    }
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    // Mid Left is truth-snapshot output index 2 (Low L, Low R, Mid L, ...).
    CHECK_FALSE(isIdentity(stageAt(snap, 2, 3)));
    CHECK_FALSE(isIdentity(stageAt(snap, 2, 4)));
}

TEST_CASE("Truth snapshot: High is its own output block with its own crossover corner") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    // Un-mute/un-bypass High so its crossover is actually built and worth inspecting.
    cfg[sch::kHighXoPass] = 0.f;
    cfg[sch::kHighOutputConfigBase + sch::kOutMuted] = 0.f;
    cfg[sch::kHighOutputConfigBase + sch::kOutCrossoverFreq] = 3500.f;
    cfg[sch::kHighOutputConfigBase + sch::kOutCrossoverType] = kLr4;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    auto snap = proc.captureTruthSnapshot();
    // High Left is truth-snapshot output index 4 (Low L, Low R, Mid L, Mid R, High L, ...).
    const std::size_t base = kHeaderWidth + 4 * kOutputBlockWidth;
    CHECK(snap[base + 0] == doctest::Approx(3500.0));  // crossoverFreqHz
    CHECK(snap[base + 4] == 0.0);                      // muted (explicitly un-muted above)
    CHECK_FALSE(isIdentity(stageAt(snap, 4, 1)));
    CHECK_FALSE(isIdentity(stageAt(snap, 4, 2)));
}
