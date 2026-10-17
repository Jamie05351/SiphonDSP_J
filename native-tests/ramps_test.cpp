#include "test_support.h"

#include "NativeBmwDspSchema.h"

using namespace nbtest;
namespace sch = nbschema;

// Mute, polarity, channel mute and delay edits made while audio plays must fade, not step: a
// step in the waveform is a click. Each test renders one continuous 60 Hz tone, applies the edit
// between two buffers, and checks the output never moves between samples by much more than the
// tone itself does.

namespace {

constexpr double kToneHz = 60.0, kAmp = 0.25;
constexpr std::size_t kBlock = 4096;

std::vector<float> sineBlock(std::size_t start, std::size_t frames) {
    std::vector<float> buf(frames * 2);
    const double w = 2.0 * kPi * kToneHz / kSampleRate;
    for (std::size_t n = 0; n < frames; ++n) {
        const float s = static_cast<float>(kAmp * std::sin(w * static_cast<double>(start + n)));
        buf[2 * n] = buf[2 * n + 1] = s;
    }
    return buf;
}

// Largest sample-to-sample change across both channels, continuing from `last` (L, R).
float maxStep(const std::vector<float>& block, float (&last)[2]) {
    float worst = 0.f;
    for (std::size_t n = 0; n < block.size(); n += 2) {
        for (int c = 0; c < 2; ++c) {
            worst = std::max(worst, std::fabs(block[n + c] - last[c]));
            last[c] = block[n + c];
        }
    }
    return worst;
}

// Steady-state step before the edit, and the worst step in the two blocks after it.
template <class Edit>
std::pair<float, float> stepsAcrossEdit(std::array<float, kConfigSize> cfg, Edit edit) {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    std::size_t pos = 0;
    float last[2] = {0.f, 0.f};
    for (int i = 0; i < 12; ++i, pos += kBlock) {
        auto warm = sineBlock(pos, kBlock);
        proc.process(warm.data(), warm.size());
        last[0] = warm[warm.size() - 2];
        last[1] = warm[warm.size() - 1];
    }
    auto steady = sineBlock(pos, kBlock);
    pos += kBlock;
    proc.process(steady.data(), steady.size());
    const float steadyStep = maxStep(steady, last);
    edit(cfg);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    float editStep = 0.f;
    for (int i = 0; i < 2; ++i, pos += kBlock) {
        auto after = sineBlock(pos, kBlock);
        proc.process(after.data(), after.size());
        editStep = std::max(editStep, maxStep(after, last));
    }
    return {steadyStep, editStep};
}

std::array<float, kConfigSize> baseConfig() {
    auto c = defaultConfig();
    c[sch::kTiltEnabled] = 0.f;
    return c;
}

int lowField(int out, int field) {
    return sch::kOutputConfigBase + out * sch::kOutputConfigWidth + field;
}

constexpr float kAllowed = 3.f;  // x the tone's own steady step

}  // namespace

TEST_CASE("flipping a band's polarity mid-signal fades through zero instead of stepping") {
    const auto [steady, edit] = stepsAcrossEdit(baseConfig(), [](auto& c) {
        c[lowField(0, sch::kOutPolarityInverted)] = 1.f;
        c[lowField(1, sch::kOutPolarityInverted)] = 1.f;
    });
    INFO("steady step ", steady, "  step after the flip ", edit);
    REQUIRE(steady > 0.f);
    CHECK(edit < steady * kAllowed);
}

TEST_CASE("muting a band mid-signal fades instead of stepping") {
    const auto [steady, edit] = stepsAcrossEdit(baseConfig(), [](auto& c) {
        c[lowField(0, sch::kOutMuted)] = 1.f;
        c[lowField(1, sch::kOutMuted)] = 1.f;
    });
    INFO("steady step ", steady, "  step after the mute ", edit);
    REQUIRE(steady > 0.f);
    CHECK(edit < steady * kAllowed);
}

TEST_CASE("muting a channel mid-signal fades instead of stepping") {
    const auto [steady, edit] = stepsAcrossEdit(baseConfig(), [](auto& c) { c[3] = 1.f; });
    INFO("steady step ", steady, "  step after the channel mute ", edit);
    REQUIRE(steady > 0.f);
    CHECK(edit < steady * kAllowed);
}

TEST_CASE("changing a band's delay mid-signal crossfades instead of jumping") {
    const auto [steady, edit] = stepsAcrossEdit(baseConfig(), [](auto& c) {
        c[sch::kLowDelayL] = 2.5f;
        c[sch::kLowDelayR] = 2.5f;
    });
    INFO("steady step ", steady, "  step after the delay change ", edit);
    REQUIRE(steady > 0.f);
    CHECK(edit < steady * kAllowed);
}

TEST_CASE("two delay changes in quick succession don't jump mid-fade") {
    // A drag can land a second delay change while the first 10 ms crossfade is still running.
    NativeBmwDspProcessor proc;
    auto c = baseConfig();
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));
    std::size_t pos = 0;
    float last[2] = {0.f, 0.f};
    for (int i = 0; i < 12; ++i, pos += kBlock) {
        auto warm = sineBlock(pos, kBlock);
        proc.process(warm.data(), warm.size());
        last[0] = warm[warm.size() - 2];
        last[1] = warm[warm.size() - 1];
    }
    auto steady = sineBlock(pos, kBlock);
    pos += kBlock;
    proc.process(steady.data(), steady.size());
    const float steadyStep = maxStep(steady, last);
    float worst = 0.f;
    for (float d : {1.0f, 2.0f, 2.8f}) {  // each lands ~4 ms into the previous fade
        c[sch::kLowDelayL] = c[sch::kLowDelayR] = d;
        REQUIRE(proc.configure(c.data(), c.size()));
        auto chunk = sineBlock(pos, 192);
        pos += 192;
        proc.process(chunk.data(), chunk.size());
        worst = std::max(worst, maxStep(chunk, last));
    }
    auto tail = sineBlock(pos, kBlock);
    proc.process(tail.data(), tail.size());
    worst = std::max(worst, maxStep(tail, last));
    INFO("steady step ", steadyStep, "  worst step across the burst ", worst);
    CHECK(worst < steadyStep * kAllowed);
}
