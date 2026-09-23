// NEON parity harness for NativeBmwDspProcessor.
//
// Renders a fixed set of scenarios through the public API (configure / configurePeq / process)
// and prints one FNV-1a hash of the raw output bytes per scenario. The scenarios are picked to
// hit every NEON-paired stage AND every scalar fallback next to it: uneven L/R PEQ band counts,
// mixed crossover topologies (BW1/BW3 one-pole stages vs Svf2), subsonic on one side only,
// all-pass sections enabled on one side / both sides / with mixed 1st/2nd order, tilt, the
// 3-way Mid upper corner, every measurement-mute bus and the multiband compressor (linked,
// unlinked and disabled bands), plus tiny (denormal-range), silent,
// NaN and over-full-scale input.
//
// Not part of native_tests: the value is comparing two BUILDS of the same scenarios, e.g. the
// arm64 NEON build against the same source built with -DSIPHON_DISABLE_NEON, or against the
// previous release's source. Identical hashes = bit-identical output. See
// scripts/run-neon-parity.sh.

#include "NativeBmwDspProcessor.h"
#include "NativeBmwDspSchema.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <limits>
#include <string>
#include <vector>

namespace {

constexpr float kSampleRate = 48000.0f;
constexpr std::size_t kFrames = 96000;
constexpr std::size_t kBlockFrames = 480;
using Config = std::array<float, nbschema::kSize>;

bool loadDefaultConfig(const char* path, Config& cfg) {
    std::ifstream in(path);
    if (!in.is_open()) {
        return false;
    }
    std::size_t count = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (const auto hash = line.find('#'); hash != std::string::npos) {
            line.erase(hash);
        }
        const auto first = line.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) {
            continue;
        }
        if (count >= cfg.size()) {
            return false;
        }
        cfg[count++] = std::stof(line.substr(first));
    }
    return count == cfg.size();
}

// Deterministic stereo test signal: noise + two sines, with stretches of silence, denormal-range
// values, over-full-scale peaks and a few NaN/inf samples (the input stage flushes those).
std::vector<float> makeInput() {
    std::vector<float> buf(kFrames * 2);
    std::uint32_t seed = 0x12345678u;
    auto noise = [&seed]() {
        seed = seed * 1664525u + 1013904223u;
        return static_cast<float>(seed >> 8) / static_cast<float>(1u << 24) - 0.5f;
    };
    for (std::size_t n = 0; n < kFrames; ++n) {
        const double t = static_cast<double>(n) / kSampleRate;
        float l = 0.3f * noise() + 0.3f * static_cast<float>(std::sin(2 * M_PI * 55.0 * t));
        float r = 0.3f * noise() + 0.3f * static_cast<float>(std::sin(2 * M_PI * 3100.0 * t));
        const std::size_t segment = (n / 4000) % 6;
        if (segment == 2) {
            l *= 1e-25f;  // denormal-range once it's inside the filters
            r *= 1e-25f;
        } else if (segment == 3) {
            l = r = 0.f;  // silence: filter state decays through the flush thresholds
        } else if (segment == 4) {
            l *= 5.f;  // over full scale: limiters/compressors engage
            r *= 5.f;
        }
        buf[2 * n] = l;
        buf[2 * n + 1] = r;
    }
    buf[2 * 12345] = std::numeric_limits<float>::quiet_NaN();
    buf[2 * 23456 + 1] = std::numeric_limits<float>::infinity();
    return buf;
}

std::uint64_t fnv1a(const std::vector<float>& data) {
    std::uint64_t h = 1469598103934665603ull;
    const auto* p = reinterpret_cast<const unsigned char*>(data.data());
    for (std::size_t i = 0; i < data.size() * sizeof(float); ++i) {
        h = (h ^ p[i]) * 1099511628211ull;
    }
    return h;
}

// Band = {freq, gainDb, Q, type, channel(0 both / 1 left / 2 right)}.
using Bands = std::vector<double>;
void band(Bands& b, double f, double g, double q, int type, int ch) {
    b.insert(b.end(), {f, g, q, static_cast<double>(type), static_cast<double>(ch)});
}

void setOutput(Config& c, int base, int slot, int field, float value) {
    c[base + slot * nbschema::kOutputConfigWidth + field] = value;
}
void setAllPass(Config& c, int base, int slot, int section, bool on, int order, float f, float q) {
    const int i = base + (slot * nbschema::kAllPassSectionsPerOutput + section) *
                             nbschema::kAllPassSectionWidth;
    c[i] = on ? 1.f : 0.f;
    c[i + 1] = static_cast<float>(order);
    c[i + 2] = f;
    c[i + 3] = q;
}

void enableThreeWay(Config& c) {
    c[nbschema::kHighXoPass] = 0;
    c[nbschema::kMidUpperXo + nbschema::kMidUpperXoFreq] = 3000;
    c[nbschema::kMidUpperXo + nbschema::kMidUpperXoEnabled] = 1;
    c[nbschema::kMidUpperXo + nbschema::kMidUpperXoWidth + nbschema::kMidUpperXoFreq] = 3200;
    c[nbschema::kMidUpperXo + nbschema::kMidUpperXoWidth + nbschema::kMidUpperXoEnabled] = 1;
}

void mixedCrossovers(Config& c) {
    const int b = nbschema::kOutputConfigBase;
    setOutput(c, b, 0, nbschema::kOutCrossoverType, 1);  // LowLeft  BW3 (one-pole + Svf2)
    setOutput(c, b, 1, nbschema::kOutCrossoverType, 2);  // LowRight LR4
    setOutput(c, b, 2, nbschema::kOutCrossoverType, 3);  // MidLeft  BW1 (one-pole only)
    setOutput(c, b, 3, nbschema::kOutCrossoverType, 4);  // MidRight BW4
    setOutput(c, b, 0, nbschema::kOutSubsonicEnabled, 1);  // subsonic on LowLeft only
    setOutput(c, nbschema::kHighOutputConfigBase, 0, nbschema::kOutCrossoverType, 0);  // BW2
    setOutput(c, nbschema::kHighOutputConfigBase, 1, nbschema::kOutCrossoverType, 1);  // BW3
}

void allPasses(Config& c) {
    const int b = nbschema::kAllPassBase;
    setAllPass(c, b, 0, 0, true, 2, 90, 0.7f);    // LowLeft s0: 2nd order
    setAllPass(c, b, 1, 0, true, 1, 110, 0.7f);   // LowRight s0: 1st order -> mixed pair
    setAllPass(c, b, 2, 1, true, 2, 700, 1.2f);   // MidLeft s1 only
    setAllPass(c, b, 3, 0, true, 2, 500, 0.9f);   // MidRight s0 + s1
    setAllPass(c, b, 3, 1, true, 2, 1500, 0.6f);
    setAllPass(c, nbschema::kHighAllPassBase, 0, 0, true, 2, 6000, 0.7f);  // High pair
    setAllPass(c, nbschema::kHighAllPassBase, 1, 0, true, 2, 6500, 0.8f);
}

// Multiband compressor on (it ships off): stereo-linked, unlinked and disabled bands, partial mix.
void multiband(Config& c) {
    c[nbschema::kMbcEnabled] = 1;
    c[nbschema::kMbcMix] = 80;
    c[nbschema::kMbcXo0] = 120;
    c[nbschema::kMbcXo1] = 900;
    c[nbschema::kMbcXo2] = 5000;
    auto bandCfg = [&c](int b, bool on, float thresh, float ratio, bool linked) {
        const int i = nbschema::kMbcBandsBase + b * nbschema::kMbcBandWidth;
        c[i + nbschema::kMbcBandEnabled] = on ? 1.f : 0.f;
        c[i + nbschema::kMbcBandThreshold] = thresh;
        c[i + nbschema::kMbcBandRatio] = ratio;
        c[i + nbschema::kMbcBandKnee] = 6;
        c[i + nbschema::kMbcBandAttack] = 10;
        c[i + nbschema::kMbcBandRelease] = 150;
        c[i + nbschema::kMbcBandMakeup] = 2;
        c[i + nbschema::kMbcBandStereoLink] = linked ? 1.f : 0.f;
    };
    bandCfg(0, true, -20, 4, true);
    bandCfg(1, true, -18, 3, false);
    bandCfg(2, false, -24, 2, true);
    bandCfg(3, true, -30, 6, true);
}

void tilt(Config& c) {
    c[nbschema::kTiltEnabled] = 1;
    c[nbschema::kTiltAmount] = 3;
    c[nbschema::kTiltFreq] = 800;
}

struct Peq {
    bool enabled = false;
    Bands full, low, mid, high;
};

Peq unevenPeq() {
    Peq p;
    p.enabled = true;
    band(p.full, 60, 4, 1.0, 0, 0);
    band(p.full, 250, -3, 2.0, 0, 0);
    band(p.full, 1000, 2, 0.7, 1, 0);
    band(p.full, 4000, -2, 0.7, 2, 0);
    band(p.full, 8000, 0, 5.0, 3, 0);   // notch: never skipped at 0 dB
    band(p.full, 120, 6, 4.0, 0, 1);    // left-only
    band(p.full, 3000, -6, 3.0, 0, 1);  // left-only
    band(p.full, 500, 3, 1.5, 0, 2);    // right-only
    band(p.full, 700, 0, 1.0, 0, 0);    // 0 dB bell: skipped on both
    band(p.low, 40, 5, 0.8, 0, 1);
    band(p.low, 80, -4, 2.0, 0, 1);
    band(p.low, 100, 2, 1.0, 1, 1);
    for (double f : {300.0, 600.0, 1200.0, 2400.0}) {
        band(p.mid, f, -2.5, 2.0, 0, 0);
    }
    band(p.mid, 900, 4, 6.0, 0, 2);
    band(p.high, 5000, 3, 1.0, 0, 0);
    band(p.high, 12000, -4, 0.7, 2, 0);
    return p;
}

bool render(const char* name, const Config& cfg, const Peq& peq, const std::vector<float>& input) {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    if (!proc.configure(cfg.data(), cfg.size())) {
        std::printf("%-22s CONFIGURE-FAILED\n", name);
        return false;
    }
    if (!proc.configurePeq(peq.enabled, -3.f, peq.full.data(), peq.full.size(), peq.low.data(),
                           peq.low.size(), peq.mid.data(), peq.mid.size(), peq.high.data(),
                           peq.high.size())) {
        std::printf("%-22s PEQ-FAILED\n", name);
        return false;
    }
    std::vector<float> out = input;
    for (std::size_t f = 0; f < kFrames; f += kBlockFrames) {
        proc.process(out.data() + 2 * f, 2 * kBlockFrames);
    }
    std::printf("%-22s %016llx\n", name, static_cast<unsigned long long>(fnv1a(out)));
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <default_config.txt>\n", argv[0]);
        return 2;
    }
    Config base{};
    if (!loadDefaultConfig(argv[1], base)) {
        std::fprintf(stderr, "cannot load %s\n", argv[1]);
        return 2;
    }
    base[nbschema::kEnabled] = 1;
    const std::vector<float> input = makeInput();
    const Peq noPeq, peq = unevenPeq();

    struct Scenario {
        const char* name;
        std::function<void(Config&)> edit;
        bool withPeq;
    };
    const Scenario scenarios[] = {
        {"default", [](Config&) {}, false},
        {"peq-uneven", [](Config&) {}, true},
        {"crossovers-mixed", [](Config& c) { mixedCrossovers(c); enableThreeWay(c); }, false},
        {"allpass-tilt", [](Config& c) { allPasses(c); tilt(c); enableThreeWay(c); }, false},
        {"measmute-mid", [](Config& c) { enableThreeWay(c); c[nbschema::kMeasurementMute] = 1; },
         false},
        {"measmute-low", [](Config& c) { c[nbschema::kMeasurementMute] = 2; }, false},
        {"measmute-high", [](Config& c) { enableThreeWay(c); c[nbschema::kMeasurementMute] = 3; },
         false},
        {"multiband", [](Config& c) { multiband(c); }, true},
        {"everything",
         [](Config& c) {
             mixedCrossovers(c);
             setOutput(c, nbschema::kOutputConfigBase, 1, nbschema::kOutSubsonicEnabled, 1);
             allPasses(c);
             tilt(c);
             enableThreeWay(c);
         },
         true},
    };
    bool ok = true;
    for (const auto& s : scenarios) {
        Config c = base;
        s.edit(c);
        ok = render(s.name, c, s.withPeq ? peq : noPeq, input) && ok;
    }
    return ok ? 0 : 1;
}
