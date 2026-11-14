// The block paths of the crossovers, tilt and the MBC tree must be bit-identical to running the
// same stages frame by frame -- for every crossover slope (the one-pole BW1/BW3 sections take the
// non-SVF path), subsonic on one side or both, and the MBC's Mix moving to and from 100%.
#include "test_support.h"

#include <cstring>

#include "NativeBmwCrossovers.h"
#include "NativeBmwDynamics.h"

using namespace nbtest;
using namespace NativeBmwDsp;

namespace {
void signal(std::vector<float>& l, std::vector<float>& r, std::size_t n) {
    l.resize(n);
    r.resize(n);
    for (std::size_t i = 0; i < n; ++i) {
        l[i] = 0.4f * std::sin(0.031f * i) + 0.2f * std::sin(0.71f * i);
        r[i] = 0.3f * std::sin(0.017f * i + 1.f) + 0.25f * std::sin(1.3f * i);
    }
}
template <class Frame, class Block>
void compare(Frame frame, Block block) {
    std::vector<float> l1, r1, l2, r2;
    signal(l1, r1, 4000);
    l2 = l1;
    r2 = r1;
    for (std::size_t i = 0; i < l1.size(); ++i) frame(l1[i], r1[i]);
    const std::size_t sizes[] = {64, 1, 63, 7, 64, 2};
    for (std::size_t pos = 0, k = 0; pos < l2.size(); ++k) {
        const std::size_t n = std::min<std::size_t>(sizes[k % 6], l2.size() - pos);
        block(l2.data() + pos, r2.data() + pos, n);
        pos += n;
    }
    CHECK(std::memcmp(l1.data(), l2.data(), l1.size() * sizeof(float)) == 0);
    CHECK(std::memcmp(r1.data(), r2.data(), r1.size() * sizeof(float)) == 0);
}
constexpr OutputConfig::CrossoverType kTypes[] = {
    OutputConfig::CrossoverType::Butterworth1, OutputConfig::CrossoverType::Butterworth2,
    OutputConfig::CrossoverType::Butterworth3, OutputConfig::CrossoverType::LinkwitzRiley4,
    OutputConfig::CrossoverType::Butterworth4};
}  // namespace

TEST_CASE("crossover block paths: bit-identical to frame by frame, every slope, subsonic on either side") {
    for (auto type : kTypes) {
        for (int sub = 0; sub < 4; ++sub) {  // none, left only, right only, both
            CAPTURE(static_cast<int>(type));
            CAPTURE(sub);
            OutputConfig cl, cr;
            cl.crossoverType = cr.crossoverType = type;
            cl.crossoverFreq = 120.f;
            cr.crossoverFreq = 140.f;
            cl.subsonicEnabled = (sub & 1) != 0;
            cr.subsonicEnabled = (sub & 2) != 0;
            OutputRuntime al, ar;
            buildSubsonic(al, cl, kSampleRate);
            buildSubsonic(ar, cr, kSampleRate);
            buildLowCrossover(al, cl, kSampleRate);
            buildLowCrossover(ar, cr, kSampleRate);
            OutputRuntime bl = al, br = ar;
            compare([&](float& l, float& r) { processLowCrossover(al, cl, ar, cr, l, r); },
                    [&](float* l, float* r, std::size_t n) { processLowCrossoverBlock(bl, cl, br, cr, l, r, n); });
        }
        OutputConfig m;
        m.crossoverType = type;
        m.upperCrossoverEnabled = true;
        m.upperCrossoverFreq = 2500.f;
        m.upperCrossoverType = type;
        OutputRuntime ml, mr;
        buildMidCrossover(ml, m, kSampleRate);
        buildMidCrossover(mr, m, kSampleRate);
        OutputRuntime nl = ml, nr = mr;
        compare([&](float& l, float& r) { processMidCrossover(ml, mr, l, r); },
                [&](float* l, float* r, std::size_t n) { processMidCrossoverBlock(nl, nr, l, r, n); });
        OutputConfig h;
        h.crossoverType = type;
        h.crossoverFreq = 3000.f;
        OutputRuntime hl, hr;
        buildHighCrossover(hl, h, kSampleRate);
        buildHighCrossover(hr, h, kSampleRate);
        OutputRuntime gl = hl, gr = hr;
        compare([&](float& l, float& r) { processHighCrossover(hl, hr, l, r); },
                [&](float* l, float* r, std::size_t n) { processHighCrossoverBlock(gl, gr, l, r, n); });
    }
}

TEST_CASE("tilt block path: bit-identical to frame by frame") {
    Tilt a;
    a.rebuild(4.f, 700.f, kSampleRate);
    Tilt b = a;
    compare([&](float& l, float& r) { a.process(l, r); }, [&](float* l, float* r, std::size_t n) { b.processBlock(l, r, n); });
}

TEST_CASE("MBC block path: bit-identical to frame by frame, Mix moving to and from 100%") {
    MbcBands bands;
    for (int i = 0; i < kMbcBandCount; ++i) {
        bands[i].enabled = true;
        bands[i].threshold = -30.f;
        bands[i].ratio = 3.f;
        bands[i].stereoLink = i % 2 == 0;
    }
    const float splits[3] = {90.f, 600.f, 4500.f};
    DetectorTiming detector;
    detector.rebuild(kSampleRate);
    // Each phase runs on both copies, the Mix set between phases as configure() would.
    MultibandCompressor a, b;
    for (auto* m : {&a, &b}) {
        m->rebuild(splits, 1.f, bands, kSampleRate);
        m->snapMix();
    }
    for (const float mix : {1.f, .5f, 1.f, 0.f, 1.f}) {
        CAPTURE(mix);
        for (auto* m : {&a, &b}) m->rebuildTiming(mix, bands, kSampleRate);
        compare([&](float& l, float& r) { a.process(l, r, bands, detector); },
                [&](float* l, float* r, std::size_t n) { b.processBlock(l, r, n, bands, detector); });
    }
}
