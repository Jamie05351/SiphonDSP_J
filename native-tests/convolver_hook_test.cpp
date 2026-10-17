#include "test_support.h"

#include <algorithm>
#include <cmath>
#include <vector>

using namespace nbtest;

// The host stage before the master limiter (BlockHook): where the JamesDSP convolver runs.

namespace {

// -1 dBFS, matching NativeBmwDspProcessor::kLimiterCeilingLin.
constexpr float kCeiling = 0.891251f;

struct HookProbe {
    float gain = 1.f;
    std::size_t calls = 0;
    std::size_t frames = 0;
    double leftEnergy = 0.0;
    double rightEnergy = 0.0;
};

void probeHook(void* context, float* x, std::size_t frames) {
    auto& probe = *static_cast<HookProbe*>(context);
    ++probe.calls;
    probe.frames = frames;
    for (std::size_t i = 0; i < frames; ++i) {
        probe.leftEnergy += double(x[2 * i]) * x[2 * i];
        probe.rightEnergy += double(x[2 * i + 1]) * x[2 * i + 1];
        x[2 * i] *= probe.gain;
        x[2 * i + 1] *= probe.gain;
    }
}

// Everything upstream of the limiter that would otherwise cut the level (as in limiter_test).
void quietUpstream(std::array<float, kConfigSize>& c) {
    c[5] = 0.f;                                  // headroom 0 dB
    c[8] = c[9] = 0.f;                           // mid gain 0 dB
    c[25] = 0.f;                                 // tilt off
    c[28] = c[35] = 0.f;                         // legacy global compressors off
    c[93] = c[106] = c[119] = c[132] = 0.f;      // legacy per-output compressors off
}

} // namespace

TEST_CASE("a pass-through hook leaves the output bit-identical to no hook") {
    auto c = defaultConfig();
    NativeBmwDspProcessor plain, hooked;
    plain.setSampleRate(kSampleRate);
    hooked.setSampleRate(kSampleRate);
    REQUIRE(plain.configure(c.data(), c.size()));
    REQUIRE(hooked.configure(c.data(), c.size()));

    auto a = stereoSine(330.0, 0.5, 9600);
    auto b = a;
    HookProbe probe;
    for (std::size_t off = 0; off < a.size(); off += 960) {
        plain.process(a.data() + off, 960);
        hooked.process(b.data() + off, 960, &probeHook, &probe);
    }
    CHECK(probe.calls == a.size() / 960);
    CHECK(probe.frames == 480);
    CHECK(a == b);
}

TEST_CASE("the master limiter catches a boost added by the hook") {
    auto c = defaultConfig();
    quietUpstream(c);
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    HookProbe probe;
    probe.gain = 2.f;   // +6 dB in the host stage: ~0.8 in -> ~1.6 before the limiter
    auto warm = stereoSine(220.0, 0.8, 48000);
    proc.process(warm.data(), warm.size(), &probeHook, &probe);
    auto win = stereoSine(220.0, 0.8, 32000);
    proc.process(win.data(), win.size(), &probeHook, &probe);

    const float pk = peakAbs(win);
    INFO("peak out = ", pk, "  ceiling = ", kCeiling);
    CHECK(pk <= kCeiling * 1.001f);
    CHECK(pk > 0.6f);
}

TEST_CASE("the hook sees the logical channels, before the hardware L/R swap") {
    auto c = defaultConfig();
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));

    // Signal on the left input only.
    auto x = stereoSine(440.0, 0.5, 24000);
    for (std::size_t i = 1; i < x.size(); i += 2) {
        x[i] = 0.f;
    }
    HookProbe probe;
    proc.process(x.data(), x.size(), &probeHook, &probe);

    double outLeft = 0.0, outRight = 0.0;
    for (std::size_t i = 0; i + 1 < x.size(); i += 2) {
        outLeft += double(x[i]) * x[i];
        outRight += double(x[i + 1]) * x[i + 1];
    }
    INFO("hook L/R energy ", probe.leftEnergy, " / ", probe.rightEnergy,
         "  output L/R energy ", outLeft, " / ", outRight);
    CHECK(probe.leftEnergy > 100.0 * probe.rightEnergy);   // logical left, as the controls label it
    CHECK(outRight > 100.0 * outLeft);                     // then swapped for the reversed harness
}

TEST_CASE("a capture records the same input and output with a hook as without") {
    auto c = defaultConfig();
    NativeBmwDspProcessor plain, hooked;
    plain.setSampleRate(kSampleRate);
    hooked.setSampleRate(kSampleRate);
    REQUIRE(plain.configure(c.data(), c.size()));
    REQUIRE(hooked.configure(c.data(), c.size()));
    plain.startCapture();
    hooked.startCapture();

    auto a = stereoSine(330.0, 0.5, 4800);
    auto b = a;
    HookProbe probe;
    for (std::size_t off = 0; off < a.size(); off += 960) {
        plain.process(a.data() + off, 960);
        hooked.process(b.data() + off, 960, &probeHook, &probe);
    }
    auto plainTake = plain.takeCaptureSnapshot();
    auto hookedTake = hooked.takeCaptureSnapshot();
    REQUIRE(plainTake);
    REQUIRE(hookedTake);
    CHECK(hookedTake->frames == plainTake->frames);
    CHECK(hookedTake->frames == a.size() / 2);
    CHECK(hookedTake->rawInL == plainTake->rawInL);
    CHECK(hookedTake->rawInR == plainTake->rawInR);
    CHECK(hookedTake->outL == plainTake->outL);
    CHECK(hookedTake->outR == plainTake->outR);
}

TEST_CASE("a non-finite sample from the host stage never reaches the output, limiter off") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[nbschema::kMasterLimiterEnabled] = 0.f;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(c.data(), c.size()));
    auto poison = [](void*, float* x, std::size_t frames) {
        x[0] = std::numeric_limits<float>::quiet_NaN();
        x[1] = std::numeric_limits<float>::infinity();
        if (frames > 1) x[2] = 1e-40f;  // denormal
    };
    std::vector<float> buf(2 * 256, 0.1f);
    proc.process(buf.data(), buf.size(), poison, nullptr);
    for (float v : buf) {
        CHECK(std::isfinite(v));
        CHECK((v == 0.f || std::fabs(v) >= 1e-20f));
    }
}
