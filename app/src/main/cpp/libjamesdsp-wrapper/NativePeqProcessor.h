#ifndef SIPHONDSP_NATIVE_PEQ_PROCESSOR_H
#define SIPHONDSP_NATIVE_PEQ_PROCESSOR_H

#include <cstddef>
#include <cstdint>
#include <vector>

class NativePeqProcessor {
public:
    bool configure(bool enable, const double* bands, std::size_t valueCount,
                   float preampDb, float sampleRate);
    void disable();
    bool isActive() const { return active_; }

    const int16_t* process(const int16_t* input, std::size_t sampleCount);
    const int32_t* process(const int32_t* input, std::size_t sampleCount);
    const float* process(const float* input, std::size_t sampleCount);

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

    static bool buildSection(double frequency, double gain, double q, int type,
                             double sampleRate, Section& section);
    static float processCascade(float input, std::vector<Section>& sections);

    float processLeft(float input);
    float processRight(float input);

    std::vector<Section> left_;
    std::vector<Section> right_;
    std::vector<int16_t> scratch16_;
    std::vector<int32_t> scratch32_;
    std::vector<float> scratchFloat_;
    float preampLinear_ = 1.0f;
    bool active_ = false;
};

#endif
