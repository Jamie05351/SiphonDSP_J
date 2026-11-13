// Per-stage timing (NativeBmwProfiler.h): off by default, counts from zero when switched on,
// accounts for (nearly) all the engine time, and changes nothing about the output.
#include "test_support.h"

#include <cstring>

#include "NativeBmwDspSchema.h"

using namespace nbtest;
using P = NativeBmwDsp::StageProfiler;

namespace {
std::array<double, P::kSize> read(const NativeBmwDspProcessor& p) {
    std::array<double, P::kSize> v{};
    p.readStageProfile(v.data(), v.size());
    return v;
}
}  // namespace

TEST_CASE("stage profiler: off by default, counts while on, from zero each time") {
    NativeBmwDspProcessor p;
    p.setSampleRate(kSampleRate);
    auto c = defaultConfig();
    REQUIRE(p.configure(c.data(), c.size()));
    auto buf = stereoSine(1000.0, 0.3, 4096, kSampleRate);
    p.process(buf.data(), buf.size());
    CHECK(read(p)[P::kEnabled] == 0.0);
    CHECK(read(p)[P::kBuffers] == 0.0);

    p.setStageProfiling(true);
    for (int i = 0; i < 5; ++i) {
        auto b = stereoSine(1000.0, 0.3, 4096, kSampleRate);
        p.process(b.data(), b.size());
    }
    auto v = read(p);
    CHECK(v[P::kEnabled] == 1.0);
    CHECK(v[P::kBuffers] == 5.0);
    CHECK(v[P::kFrames] == 5.0 * 4096);
    CHECK(v[P::kWallNs] > 0.0);
    double stages = 0;
    for (std::size_t s = 0; s < P::kStageCount; ++s) {
        stages += v[P::kHeader + 2 * s];
        CHECK(v[P::kHeader + 2 * s + 1] <= v[P::kHeader + 2 * s] + 1);  // a buffer's max <= the total
    }
    // The stages cover the chunk loop; only the buffer's own set-up is outside them.
    CHECK(stages <= v[P::kWallNs]);
    CHECK(stages >= 0.8 * v[P::kWallNs]);
    CHECK(v[P::kHeader + 2 * P::Crossovers] > 0.0);  // the default chain splits

    p.setStageProfiling(false);
    p.process(buf.data(), buf.size());
    CHECK(read(p)[P::kEnabled] == 0.0);
    CHECK(read(p)[P::kBuffers] == 5.0);  // left as it was
    p.setStageProfiling(true);
    p.process(buf.data(), buf.size());
    CHECK(read(p)[P::kBuffers] == 1.0);  // from zero again
}

TEST_CASE("stage profiler: the output is the same with it on") {
    auto c = defaultConfig();
    c[nbschema::kMbcEnabled] = 1.f;
    NativeBmwDspProcessor a, b;
    for (auto* p : {&a, &b}) {
        p->setSampleRate(kSampleRate);
        REQUIRE(p->configure(c.data(), c.size()));
    }
    b.setStageProfiling(true);
    auto x = stereoSine(700.0, 0.5, 9000, kSampleRate), y = x;
    a.process(x.data(), x.size());
    b.process(y.data(), y.size());
    CHECK(std::memcmp(x.data(), y.data(), x.size() * sizeof(float)) == 0);
}

TEST_CASE("a band muted on both outputs isn't run, and restarts clean when unmuted") {
    // Low muted: its chain is skipped (no crossover time charged to it), the output is Mid alone,
    // exactly as with the chain running and multiplied by 0.
    auto c = defaultConfig();
    for (int o = 0; o < 2; ++o) {
        c[nbschema::kOutputConfigBase + o * nbschema::kOutputConfigWidth + nbschema::kOutMuted] = 1.f;
    }
    NativeBmwDspProcessor p;
    p.setSampleRate(kSampleRate);
    REQUIRE(p.configure(c.data(), c.size()));
    auto low = stereoSine(60.0, 0.5, 24000, kSampleRate);
    p.process(low.data(), low.size());
    // 60 Hz sits far below Mid's high-pass: with Low muted almost nothing comes through.
    std::vector<float> tail(low.end() - 4096, low.end());
    CHECK(peakAbs(tail) < 0.05f);
    // Unmuted: Low comes back from a clean state, under its gain ramp -- no burst above the
    // steady level it then settles at.
    for (int o = 0; o < 2; ++o) {
        c[nbschema::kOutputConfigBase + o * nbschema::kOutputConfigWidth + nbschema::kOutMuted] = 0.f;
    }
    REQUIRE(p.configure(c.data(), c.size()));
    auto back = stereoSine(60.0, 0.5, 24000, kSampleRate);
    p.process(back.data(), back.size());
    std::vector<float> settled(back.end() - 4096, back.end());
    std::vector<float> onset(back.begin(), back.begin() + 2 * 2400);
    CHECK(peakAbs(settled) > 0.1f);
    CHECK(peakAbs(onset) <= peakAbs(settled) * 1.05f);
}
