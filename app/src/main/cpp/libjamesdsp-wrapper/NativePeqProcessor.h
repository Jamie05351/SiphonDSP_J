#ifndef SIPHONDSP_NATIVE_PEQ_PROCESSOR_H
#define SIPHONDSP_NATIVE_PEQ_PROCESSOR_H

#include <array>
#include <cstddef>
#include <cstdint>

/**
 * Fixed-size stereo PEQ.
 *
 * Left and right use completely separate coefficient/state banks. Configuration
 * may copy identical coefficients into both banks for L+R bands, but z1/z2 are
 * never shared. The audio path performs no allocation, resizing or scratch
 * buffering.
 */
class NativePeqProcessor {
public:
    static constexpr std::size_t kMaxSectionsPerChannel = 32;

    bool configure(bool enable, const double* bands, std::size_t valueCount,
                   float preampDb, float sampleRate);
    void disable();
    bool isActive() const { return active_; }

    // Preserve the wrapper's original call shape: processing is in-place and
    // the original input pointer is always returned.
    const int16_t* process(const int16_t* samples, std::size_t sampleCount);
    const int32_t* process(const int32_t* samples, std::size_t sampleCount);
    const float* process(const float* samples, std::size_t sampleCount);

private:
    struct Section {
        float b0 = 1.0f;
        float b1 = 0.0f;
        float b2 = 0.0f;
        float a1 = 0.0f;
        float a2 = 0.0f;
        float z1 = 0.0f;
        float z2 = 0.0f;
    };

    struct ChannelBank {
        std::array<Section, kMaxSectionsPerChannel> sections{};
        std::size_t count = 0;
    };

    static bool buildSection(double frequency, double gain, double q, int type,
                             double sampleRate, Section& section);
    static float processBank(float input, ChannelBank& bank);
    static void clearBank(ChannelBank& bank);

    float processLeft(float input);
    float processRight(float input);

    ChannelBank left_{};
    ChannelBank right_{};
    float preampLinear_ = 1.0f;
    bool active_ = false;
};

#endif
