// libFuzzer harness for configure() / configurePeq() (Clang, -fsanitize=fuzzer,address,undefined).
//
// Each input edits the canonical default config -- (slot, value) pairs, so most inputs are
// near-valid and reach deep into parsing and the rebuilds -- and optionally carries PEQ bands.
// Whatever configure() / configurePeq() make of it (accept or reject), the processor must then
// produce finite output within full scale for loud input, with the master limiter off and every
// optional stage reachable. ASan/UBSan catch memory and undefined-behaviour errors on the way.
//
//   fuzz_config -max_total_time=60
#define DOCTEST_CONFIG_DISABLE
#include "test_support.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace nbtest;

namespace {
std::array<float, kConfigSize> gDefaults;

template <class T>
bool take(const std::uint8_t*& data, std::size_t& size, T& out) {
    if (size < sizeof(T)) {
        return false;
    }
    std::memcpy(&out, data, sizeof(T));
    data += sizeof(T);
    size -= sizeof(T);
    return true;
}
}  // namespace

extern "C" int LLVMFuzzerInitialize(int*, char***) {
    gDefaults = defaultConfig();
    return 0;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);

    std::uint8_t header = 0;
    take(data, size, header);
    auto cfg = gDefaults;
    // Low bits: how many (slot, value) edits; 0x80: a PEQ update follows the edits.
    const unsigned edits = header & 0x3f;
    for (unsigned e = 0; e < edits; ++e) {
        std::uint16_t slot = 0;
        float value = 0.f;
        if (!take(data, size, slot) || !take(data, size, value)) {
            break;
        }
        cfg[slot % kConfigSize] = value;
    }
    proc.configure(cfg.data(), cfg.size());

    if (header & 0x80) {
        std::uint8_t counts = 0;
        take(data, size, counts);
        std::vector<double> bands;
        double v = 0.0;
        while (bands.size() < 16 * 5 && take(data, size, v)) {
            bands.push_back(v);
        }
        const std::size_t n = bands.size();
        const std::size_t full = std::min<std::size_t>((counts & 3) * 5, n);
        proc.configurePeq((counts & 4) != 0, static_cast<float>(bands.empty() ? 0.0 : bands[0]),
                          bands.data(), full, bands.data(), n, bands.data(), n / 2, bands.data(), n / 5 * 5);
    }

    // Loud input, a buffer long enough for the ramps and limiter lookahead to move.
    std::vector<float> buf = stereoSine(110.0, 0.99, 2048, kSampleRate);
    proc.process(buf.data(), buf.size());
    proc.process(buf.data(), buf.size());
    for (std::size_t i = 0; i < buf.size(); ++i) {
        if (!std::isfinite(buf[i]) || std::fabs(buf[i]) > 1.f) {
            std::fprintf(stderr, "output[%zu] = %g out of range\n", i, static_cast<double>(buf[i]));
            std::abort();
        }
    }
    return 0;
}
