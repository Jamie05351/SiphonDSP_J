#include "NativePeqProcessor.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kMinQ = 0.05;
constexpr double kMaxQ = 30.0;
constexpr double kUnityGainEpsilonDb = 0.0001;
constexpr std::size_t kValuesPerBand = 5;

inline bool isFiniteValue(double value) {
    return std::isfinite(value);
}

inline float flushTiny(float value) {
    return (!std::isfinite(value) || std::abs(value) < 1.0e-20f) ? 0.0f : value;
}

template <typename T>
inline T clampInteger(float value) {
    const double lo = static_cast<double>(std::numeric_limits<T>::lowest());
    const double hi = static_cast<double>(std::numeric_limits<T>::max());
    const double bounded = std::max(lo, std::min(hi, static_cast<double>(value)));
    return static_cast<T>(std::llround(bounded));
}
}

void NativePeqProcessor::clearBank(ChannelBank& bank) {
    for(std::size_t i = 0; i < bank.count; ++i) {
        bank.sections[i].z1 = 0.0f;
        bank.sections[i].z2 = 0.0f;
    }
    bank.count = 0;
}

void NativePeqProcessor::disable() {
    active_ = false;
    preampLinear_ = 1.0f;
    clearBank(left_);
    clearBank(right_);
}

bool NativePeqProcessor::buildSection(double frequency, double gain, double q, int type,
                                      double sampleRate, Section& section) {
    if (!isFiniteValue(frequency) || !isFiniteValue(gain) || !isFiniteValue(q) ||
        !isFiniteValue(sampleRate) || sampleRate < 8000.0 || frequency < 20.0 ||
        frequency >= sampleRate * 0.5 || gain < -30.0 || gain > 30.0 ||
        q < kMinQ || q > kMaxQ || type < 0 || type > 2) {
        return false;
    }

    const double a = std::pow(10.0, gain / 40.0);
    const double omega = 2.0 * kPi * frequency / sampleRate;
    const double sinOmega = std::sin(omega);
    const double cosOmega = std::cos(omega);
    const double alpha = sinOmega / (2.0 * q);

    double b0;
    double b1;
    double b2;
    double a0;
    double a1;
    double a2;

    if (type == 0) {
        b0 = 1.0 + alpha * a;
        b1 = -2.0 * cosOmega;
        b2 = 1.0 - alpha * a;
        a0 = 1.0 + alpha / a;
        a1 = -2.0 * cosOmega;
        a2 = 1.0 - alpha / a;
    } else {
        const double sqrtA = std::sqrt(a);
        const double twoSqrtAAlpha = 2.0 * sqrtA * alpha;
        if (type == 1) {
            b0 = a * ((a + 1.0) - (a - 1.0) * cosOmega + twoSqrtAAlpha);
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosOmega);
            b2 = a * ((a + 1.0) - (a - 1.0) * cosOmega - twoSqrtAAlpha);
            a0 = (a + 1.0) + (a - 1.0) * cosOmega + twoSqrtAAlpha;
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosOmega);
            a2 = (a + 1.0) + (a - 1.0) * cosOmega - twoSqrtAAlpha;
        } else {
            b0 = a * ((a + 1.0) + (a - 1.0) * cosOmega + twoSqrtAAlpha);
            b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosOmega);
            b2 = a * ((a + 1.0) + (a - 1.0) * cosOmega - twoSqrtAAlpha);
            a0 = (a + 1.0) - (a - 1.0) * cosOmega + twoSqrtAAlpha;
            a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosOmega);
            a2 = (a + 1.0) - (a - 1.0) * cosOmega - twoSqrtAAlpha;
        }
    }

    if (!isFiniteValue(a0) || a0 == 0.0) return false;
    const double invA0 = 1.0 / a0;
    section.b0 = static_cast<float>(b0 * invA0);
    section.b1 = static_cast<float>(b1 * invA0);
    section.b2 = static_cast<float>(b2 * invA0);
    section.a1 = static_cast<float>(a1 * invA0);
    section.a2 = static_cast<float>(a2 * invA0);
    section.z1 = 0.0f;
    section.z2 = 0.0f;

    return std::isfinite(section.b0) && std::isfinite(section.b1) &&
           std::isfinite(section.b2) && std::isfinite(section.a1) &&
           std::isfinite(section.a2);
}

bool NativePeqProcessor::configure(bool enable, const double* bands, std::size_t valueCount,
                                   float preampDb, float sampleRate) {
    if (!enable) {
        disable();
        return true;
    }
    if (bands == nullptr || valueCount == 0 || valueCount % kValuesPerBand != 0 ||
        !std::isfinite(preampDb) || preampDb < -30.0f || preampDb > 0.0f ||
        !std::isfinite(sampleRate) || sampleRate < 8000.0f) {
        disable();
        return false;
    }

    ChannelBank newLeft{};
    ChannelBank newRight{};

    for (std::size_t i = 0; i < valueCount; i += kValuesPerBand) {
        const double frequency = bands[i];
        const double gain = bands[i + 1];
        const double q = bands[i + 2];
        const int type = static_cast<int>(bands[i + 3]);
        const int channel = static_cast<int>(bands[i + 4]);
        if (channel < 0 || channel > 2) {
            disable();
            return false;
        }
        if (std::abs(gain) <= kUnityGainEpsilonDb) continue;

        Section section;
        if (!buildSection(frequency, gain, q, type, sampleRate, section)) {
            disable();
            return false;
        }

        if (channel != 2) {
            if (newLeft.count >= kMaxSectionsPerChannel) {
                disable();
                return false;
            }
            newLeft.sections[newLeft.count++] = section;
        }
        if (channel != 1) {
            if (newRight.count >= kMaxSectionsPerChannel) {
                disable();
                return false;
            }
            newRight.sections[newRight.count++] = section;
        }
    }

    left_ = newLeft;
    right_ = newRight;
    preampLinear_ = std::pow(10.0f, preampDb / 20.0f);
    active_ = left_.count != 0 || right_.count != 0 || preampLinear_ != 1.0f;
    return true;
}

float NativePeqProcessor::processBank(float input, ChannelBank& bank) {
    float value = input;
    for (std::size_t i = 0; i < bank.count; ++i) {
        Section& section = bank.sections[i];
        const float output = section.b0 * value + section.z1;
        section.z1 = flushTiny(section.b1 * value - section.a1 * output + section.z2);
        section.z2 = flushTiny(section.b2 * value - section.a2 * output);
        value = output;
    }
    return std::isfinite(value) ? value : 0.0f;
}

float NativePeqProcessor::processLeft(float input) {
    return processBank(input * preampLinear_, left_);
}

float NativePeqProcessor::processRight(float input) {
    return processBank(input * preampLinear_, right_);
}

void NativePeqProcessor::process(int16_t* samples, std::size_t sampleCount) {
    if (!active_ || samples == nullptr) return;
    for (std::size_t i = 0; i + 1 < sampleCount; i += 2) {
        samples[i] = clampInteger<int16_t>(processLeft(static_cast<float>(samples[i])));
        samples[i + 1] = clampInteger<int16_t>(processRight(static_cast<float>(samples[i + 1])));
    }
}

void NativePeqProcessor::process(int32_t* samples, std::size_t sampleCount) {
    if (!active_ || samples == nullptr) return;
    for (std::size_t i = 0; i + 1 < sampleCount; i += 2) {
        samples[i] = clampInteger<int32_t>(processLeft(static_cast<float>(samples[i])));
        samples[i + 1] = clampInteger<int32_t>(processRight(static_cast<float>(samples[i + 1])));
    }
}

void NativePeqProcessor::process(float* samples, std::size_t sampleCount) {
    if (!active_ || samples == nullptr) return;
    for (std::size_t i = 0; i + 1 < sampleCount; i += 2) {
        samples[i] = processLeft(samples[i]);
        samples[i + 1] = processRight(samples[i + 1]);
    }
}
