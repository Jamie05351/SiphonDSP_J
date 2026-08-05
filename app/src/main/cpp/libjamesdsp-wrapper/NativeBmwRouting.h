#ifndef SIPHONDSP_NATIVE_BMW_ROUTING_H
#define SIPHONDSP_NATIVE_BMW_ROUTING_H

#include <array>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

/**
 * Generic stereo-to-output routing model for the native BMW DSP path.
 *
 * The first integration target intentionally remains four logical outputs:
 * Low Left, Low Right, Mid Left and Mid Right. The model is generic so the
 * processor no longer needs to encode those bands directly in its routing
 * logic, while the current audible stereo reconstruction can remain unchanged.
 */
namespace NativeBmwRouting {

constexpr std::size_t kInputCount = 2;
constexpr std::size_t kOutputCount = 4;
constexpr std::size_t kMaxAllPassSections = 4;

enum class InputId : std::size_t {
    Left = 0,
    Right = 1,
};

enum class OutputId : std::size_t {
    LowLeft = 0,
    LowRight = 1,
    MidLeft = 2,
    MidRight = 3,
};

struct StereoFrame {
    float left = 0.0f;
    float right = 0.0f;
};

struct Route {
    float inputLeft = 0.0f;
    float inputRight = 0.0f;
};

/**
 * Default routing preserves the existing BMW two-way stereo topology:
 * L -> Low L / Mid L and R -> Low R / Mid R.
 */
struct RoutingMatrix {
    std::array<Route, kOutputCount> outputs{{
        {1.0f, 0.0f},
        {0.0f, 1.0f},
        {1.0f, 0.0f},
        {0.0f, 1.0f},
    }};

    std::array<float, kOutputCount> process(const StereoFrame& input) const {
        std::array<float, kOutputCount> result{};
        for (std::size_t i = 0; i < outputs.size(); ++i) {
            result[i] = input.left * outputs[i].inputLeft
                      + input.right * outputs[i].inputRight;
        }
        return result;
    }
};

/**
 * Dedicated source-correction controls. These are deliberately separated from
 * output/speaker PEQ so factory head-unit correction is not mixed with cabin
 * and driver correction.
 */
struct InputCorrectionState {
    bool enabled = false;
    float trimDbLeft = 0.0f;
    float trimDbRight = 0.0f;
};

struct BiquadCoefficients {
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
};

struct AllPassSection {
    bool enabled = false;
    bool secondOrder = true;
    float frequencyHz = 150.0f;
    float q = 0.70710678f;
    BiquadCoefficients coefficients{};

    bool rebuild(float sampleRate) {
        if (!enabled) {
            coefficients = {};
            return true;
        }
        if (!std::isfinite(sampleRate) || sampleRate < 8000.0f ||
            !std::isfinite(frequencyHz) || frequencyHz < 20.0f ||
            frequencyHz >= sampleRate * 0.5f ||
            !std::isfinite(q) || q < 0.1f || q > 30.0f) {
            return false;
        }

        constexpr float pi = 3.14159265358979323846f;
        const float omega = 2.0f * pi * frequencyHz / sampleRate;

        if (!secondOrder) {
            const float tangent = std::tan(omega * 0.5f);
            const float a = (tangent - 1.0f) / (tangent + 1.0f);
            coefficients.b0 = a;
            coefficients.b1 = 1.0f;
            coefficients.b2 = 0.0f;
            coefficients.a1 = a;
            coefficients.a2 = 0.0f;
            return std::isfinite(a);
        }

        const float cosine = std::cos(omega);
        const float sine = std::sin(omega);
        const float alpha = sine / (2.0f * q);
        const float a0 = 1.0f + alpha;
        if (!std::isfinite(a0) || std::fabs(a0) < 1.0e-12f) {
            return false;
        }

        // RBJ second-order all-pass: numerator is reversed denominator.
        coefficients.b0 = (1.0f - alpha) / a0;
        coefficients.b1 = (-2.0f * cosine) / a0;
        coefficients.b2 = 1.0f;
        coefficients.a1 = (-2.0f * cosine) / a0;
        coefficients.a2 = (1.0f - alpha) / a0;
        return std::isfinite(coefficients.b0) &&
               std::isfinite(coefficients.b1) &&
               std::isfinite(coefficients.a1) &&
               std::isfinite(coefficients.a2);
    }
};

struct OutputChannelDescriptor {
    bool enabled = true;
    bool polarityInverted = false;
    float gainDb = 0.0f;
    float delayMs = 0.0f;
    std::array<AllPassSection, kMaxAllPassSections> allPass{};
};

struct OutputBank {
    std::array<OutputChannelDescriptor, kOutputCount> channels{};
};

/** Reconstruct the existing final stereo output after per-output processing. */
inline StereoFrame sumToStereo(const std::array<float, kOutputCount>& outputs) {
    return {
        outputs[static_cast<std::size_t>(OutputId::LowLeft)]
            + outputs[static_cast<std::size_t>(OutputId::MidLeft)],
        outputs[static_cast<std::size_t>(OutputId::LowRight)]
            + outputs[static_cast<std::size_t>(OutputId::MidRight)],
    };
}

} // namespace NativeBmwRouting

#endif
