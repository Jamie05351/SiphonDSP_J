// PeqBank::processBlock(): the whole block at once, sections skewed in groups of three, must be
// bit-identical to process() frame by frame -- for any section count, unequal left/right counts
// (a band on one channel only), any block length, and carried across blocks.
#include "test_support.h"

#include <cstring>
#include <random>

#include "NativeBmwFilters.h"

using namespace nbtest;
using NativeBmwDsp::Biquad;
using NativeBmwDsp::PeqBank;

namespace {
PeqBank bank(std::size_t leftBands, std::size_t rightBands, unsigned seed) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<double> f(30.0, 15000.0), g(-9.0, 9.0), q(0.4, 4.0);
    PeqBank b;
    for (std::size_t lane = 0; lane < 2; ++lane) {
        const std::size_t n = lane == 0 ? leftBands : rightBands;
        for (std::size_t i = 0; i < n; ++i) {
            Biquad s;
            REQUIRE(NativeBmwDsp::makePeq(s, f(rng), g(rng), q(rng), static_cast<int>(i % 3), kSampleRate));
            PeqBank::BandKey key;
            key.index = static_cast<int>(i);
            b.append(lane, s, key);
        }
    }
    return b;
}
}  // namespace

TEST_CASE("PEQ bank: processBlock is bit-identical to process frame by frame") {
    for (const auto counts : {std::pair<std::size_t, std::size_t>{0, 0}, {1, 1}, {2, 2}, {3, 3}, {4, 4},
                              {14, 14}, {16, 16}, {14, 9}, {5, 12}, {0, 7}}) {
        CAPTURE(counts.first);
        CAPTURE(counts.second);
        PeqBank a = bank(counts.first, counts.second, 17), b = a;
        const auto x = stereoSine(523.0, 0.4, 3000, kSampleRate);
        std::vector<float> l1(3000), r1(3000), l2(3000), r2(3000);
        for (std::size_t i = 0; i < 3000; ++i) {
            l1[i] = l2[i] = x[2 * i] + 0.1f * std::sin(0.37f * i);
            r1[i] = r2[i] = x[2 * i + 1];
        }
        for (std::size_t i = 0; i < 3000; ++i) {
            a.process(l1[i], r1[i]);
        }
        // Blocks of assorted lengths, as the processor's chunks and partial chunks come.
        const std::size_t sizes[] = {64, 1, 63, 2, 64, 17, 3};
        for (std::size_t pos = 0, k = 0; pos < 3000; ++k) {
            const std::size_t n = std::min<std::size_t>(sizes[k % 7], 3000 - pos);
            b.processBlock(l2.data() + pos, r2.data() + pos, n);
            pos += n;
        }
        CHECK(std::memcmp(l1.data(), l2.data(), l1.size() * sizeof(float)) == 0);
        CHECK(std::memcmp(r1.data(), r2.data(), r1.size() * sizeof(float)) == 0);
    }
}
