#include "test_support.h"

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

using namespace nbtest;

// Nothing after the chain bounds the output any more (the JamesDSP core and its post limiter are
// gone), so the processor's own output guard is what keeps a NaN, an infinity or an over-range
// sample off the device when the master limiter is off.
namespace {
std::array<float, kConfigSize> limiterOffBoosted() {
    auto c = defaultConfig();
    c[nbschema::kMasterLimiterEnabled] = 0.f;
    c[5] = 0.f;            // headroom 0 dB
    c[10] = c[11] = 12.f;  // +12 dB post gain: well past full scale with the limiter off
    return c;
}
}  // namespace

TEST_CASE("Float output stays within full scale with the master limiter off") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = limiterOffBoosted();
    REQUIRE(proc.configure(c.data(), c.size()));
    auto warm = stereoSine(220.0, 0.9, 48000);
    proc.process(warm.data(), warm.size());
    auto win = stereoSine(220.0, 0.9, 16000);
    proc.process(win.data(), win.size());
    for (float v : win) {
        REQUIRE(std::isfinite(v));
        REQUIRE(std::fabs(v) <= 1.f);
    }
}

TEST_CASE("Non-finite input never reaches the output") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = limiterOffBoosted();
    REQUIRE(proc.configure(c.data(), c.size()));
    std::vector<float> buf = stereoSine(220.0, 0.5, 4096);
    buf[100] = std::numeric_limits<float>::quiet_NaN();
    buf[201] = std::numeric_limits<float>::infinity();
    buf[302] = -std::numeric_limits<float>::infinity();
    proc.process(buf.data(), buf.size());
    for (float v : buf) {
        REQUIRE(std::isfinite(v));
        REQUIRE(std::fabs(v) <= 1.f);
    }
}

TEST_CASE("Int16 output saturates rather than wrapping with the master limiter off") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto c = limiterOffBoosted();
    REQUIRE(proc.configure(c.data(), c.size()));
    std::vector<std::int16_t> buf(8192);
    for (std::size_t i = 0; i < buf.size(); i += 2) {
        const auto s = static_cast<std::int16_t>(29000.0 * std::sin(2.0 * M_PI * 220.0 * double(i / 2) / kSampleRate));
        buf[i] = buf[i + 1] = s;
    }
    proc.process(buf.data(), buf.size());
    int peak = 0;
    for (auto v : buf) peak = std::max(peak, std::abs(int(v)));
    CHECK(peak >= 32767);  // boosted 12 dB: saturated at full scale, not wrapped round
}
