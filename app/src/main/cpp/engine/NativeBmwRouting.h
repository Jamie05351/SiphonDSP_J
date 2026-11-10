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
 * Six logical outputs: Low/Mid/High, each Left and Right. The model is generic so the
 * processor no longer needs to encode those bands directly in its routing logic, while the
 * default audible stereo reconstruction stays a straight sum of all three bands.
 *
 * `kLegacyOutputCount` (4) is deliberately kept separate from `kOutputCount` (6): the persisted
 * schema's routing/all-pass/output-config block offsets in NativeBmwDspProcessor.h
 * (kRoutingBase/kAllPassBase/kOutputConfigBase/kOutputConfigWidth) are derived from
 * kLegacyOutputCount, not kOutputCount, so that growing the in-memory output count never shifts
 * an index an existing saved config already relies on. High's own persisted block (added in a
 * later phase) lives entirely in the schema's tail instead. See
 * docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
 */
namespace NativeBmwRouting {

constexpr std::size_t kInputCount = 2;
constexpr std::size_t kLegacyOutputCount = 4;
constexpr std::size_t kOutputCount = 6;
constexpr std::size_t kAllPassSectionsPerOutput = 2;

enum class OutputId : std::size_t {
    LowLeft = 0,
    LowRight = 1,
    MidLeft = 2,
    MidRight = 3,
    HighLeft = 4,
    HighRight = 5,
};

/** The three crossover bands an OutputId belongs to. */
enum class Band {
    Low,
    Mid,
    High,
};

/**
 * Maps an output to its band. This is the one place that band/id mapping is allowed to live --
 * call sites used to spell "is this a Low output" as an ordinal comparison (`index <= 1`, relying
 * on declaration order), which silently breaks if OutputId's declaration order ever changes
 * without every call site being found and updated.
 */
constexpr Band band(OutputId id) {
    switch (id) {
        case OutputId::LowLeft:
        case OutputId::LowRight:
            return Band::Low;
        case OutputId::MidLeft:
        case OutputId::MidRight:
            return Band::Mid;
        case OutputId::HighLeft:
        case OutputId::HighRight:
        default:
            return Band::High;
    }
}

constexpr Band band(std::size_t index) {
    return band(static_cast<OutputId>(index));
}

/**
 * True for the two Low-band outputs. Predates the three-way `band()` classifier above and is
 * kept for the existing Low-vs-everything-else call sites (e.g. the measurement-mute bus, which
 * is still a binary Low/not-Low concept) -- equivalent to `band(id) == Band::Low`.
 */
constexpr bool isLowBandOutput(OutputId id) {
    return band(id) == Band::Low;
}

constexpr bool isLowBandOutput(std::size_t index) {
    return isLowBandOutput(static_cast<OutputId>(index));
}

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
 * L -> Low L / Mid L and R -> Low R / Mid R, all cross-channel terms zero.
 */
struct RoutingMatrix {
    std::array<Route, kOutputCount> outputs{{
        {1.0f, 0.0f},
        {0.0f, 1.0f},
        {1.0f, 0.0f},
        {0.0f, 1.0f},
    }};

    std::array<float, kOutputCount> process(const StereoFrame& input) const {
        // Sanitize each input channel once, before it's used in any mix: 0.0f * NaN is NaN, not
        // 0.0f, so without this an output whose routing coefficient for a channel is exactly
        // zero (i.e. one this file's own contract says is fully isolated from that channel) can
        // still get silenced by a NaN on it -- the per-output isfinite(sum) check below alone
        // doesn't provide that isolation. Falling back to silence for the poisoned sample itself
        // (rather than letting NaN propagate into an output that *does* depend on it) matches
        // the same graceful-degradation choice already made for a malformed coefficient.
        const float safeLeft = std::isfinite(input.left) ? input.left : 0.0f;
        const float safeRight = std::isfinite(input.right) ? input.right : 0.0f;
        std::array<float, kOutputCount> result{};
        for (std::size_t i = 0; i < outputs.size(); ++i) {
            const float sum = safeLeft * outputs[i].inputLeft + safeRight * outputs[i].inputRight;
            // A malformed/NaN routing coefficient must never reach the mix -- fall back
            // to silence for that one output rather than poisoning the whole frame.
            result[i] = std::isfinite(sum) ? sum : 0.0f;
        }
        return result;
    }
};

/**
 * Flush a non-finite or sub-denormal double to exact zero. Used after every SVF/one-pole state
 * update on the audio thread to keep denormals (which are catastrophically slow on most FPUs)
 * from ever reaching the next multiply, and to stop a stray NaN/Infinity propagating through
 * filter state indefinitely. Was defined twice, identically, in NativeBmwDspProcessor.cpp
 * (`ftzd`) and NativeBmwMeasurementGenerator.h (`flushDenormal`) -- consolidated here since both
 * headers already include this one.
 */
inline double flushDenormal(double x) {
    return (!std::isfinite(x) || std::fabs(x) < 1e-30) ? 0.0 : x;
}

/**
 * The a1/a2/a3 half of the trapezoidal SVF core (Andy Simper/Cytomic form), given the prewarped
 * gain g = tan(w/2) and damping k = 1/Q. Every 2nd-order filter type (lowpass, highpass, the two
 * shelves, all-pass, peak/notch) differs only in how it mixes the input with the two integrator
 * outputs via m0/m1/m2 -- a1/a2/a3 themselves were the same three-line formula copy-pasted at
 * every builder (six in NativeBmwDspProcessor.cpp, plus this file's own AllPassSection::rebuild),
 * which is exactly the kind of formula where a copy that silently drifts (a transcription slip in
 * just one of the seven) would be very hard to notice from the audio alone.
 */
struct SvfCore {
    double a1 = 0.0;
    double a2 = 0.0;
    double a3 = 0.0;
};
inline SvfCore svfCore(double g, double k) {
    const double a1 = 1.0 / (1.0 + g * (g + k));
    const double a2 = g * a1;
    const double a3 = g * a2;
    return {a1, a2, a3};
}

// SVF (trapezoidal-integrated state variable filter) coefficients -- see
// NativeBmwDspProcessor::Biquad for the matching state/run() this feeds via loadAllPass(). The
// default (firstOrder=false, a1=a2=a3=0, m0=1/m1=0/m2=0) is an identity pass-through, same
// contract `rebuild()`'s failure paths below rely on ("coefficients = {}" degrades to no-op).
struct BiquadCoefficients {
    bool firstOrder = false;
    double opA = 0.0;    // 1-pole coefficient, only meaningful when firstOrder
    double a1 = 0.0;
    double a2 = 0.0;
    double a3 = 0.0;
    double m0 = 1.0;
    double m1 = 0.0;
    double m2 = 0.0;

    // Named constructors instead of assigning firstOrder and its matching fields separately at
    // each call site: the tag and the fields it governs are set together in exactly one place
    // here, so a future edit can't set opA without firstOrder=true (or the reverse) the way
    // independent field-by-field assignment at a call site could.
    static BiquadCoefficients onePole(double opA) {
        BiquadCoefficients c;
        c.firstOrder = true;
        c.opA = opA;
        return c;
    }
    static BiquadCoefficients svf2(double a1, double a2, double a3, double m0, double m1, double m2) {
        BiquadCoefficients c;
        c.firstOrder = false;
        c.a1 = a1;
        c.a2 = a2;
        c.a3 = a3;
        c.m0 = m0;
        c.m1 = m1;
        c.m2 = m2;
        return c;
    }
};

/**
 * A single first- or second-order all-pass section. `rebuild()` is only ever
 * called from configure()/setSampleRate() (never the audio callback) and
 * always leaves `coefficients` at a safe identity pass-through on failure, so
 * a bad frequency/Q pair degrades to "no effect" instead of corrupting state
 * or forcing the caller to special-case a partially-built filter.
 */
struct AllPassSection {
    bool enabled = false;
    bool secondOrder = true;
    float frequencyHz = 150.0f;
    float q = 0.70710678f;
    BiquadCoefficients coefficients{};

    bool isValid(float sampleRate) const {
        return std::isfinite(sampleRate) && sampleRate >= 8000.0f && std::isfinite(frequencyHz) &&
               frequencyHz >= 20.0f && frequencyHz < sampleRate * 0.5f && std::isfinite(q) &&
               q >= 0.1f && q <= 30.0f;
    }

    /**
     * Returns false (and resets to identity) on invalid frequency/Q/sample rate. Both current
     * call sites discard this deliberately (an invalid section degrades to identity rather than
     * needing to fail the whole caller) -- [[nodiscard]] plus an explicit (void) at each of them
     * makes that a visible, intentional choice instead of a silently-ignored return value that
     * looks the same as a future call site forgetting to check it for a real reason.
     */
    [[nodiscard]] bool rebuild(float sampleRate) {
        coefficients = {};
        if (!enabled) {
            return true;
        }
        if (!isValid(sampleRate)) {
            return false;
        }

        constexpr double pi = 3.14159265358979323846;
        const double omega = 2.0 * pi * static_cast<double>(frequencyHz) / static_cast<double>(sampleRate);

        if (!secondOrder) {
            // Untouched 1-pole all-pass derivation -- doesn't map onto the 2nd-order SVF form
            // below, so it keeps its own coefficient (opA) and Biquad::run() branches on
            // firstOrder to use the matching untouched 1-pole recursion.
            const double tangent = std::tan(omega * 0.5);
            const double denom = tangent + 1.0;
            // isValid() keeps omega/2 strictly inside (0, pi/2), so tangent >= 0 and denom >= 1
            // always -- denom can never approach the near-zero case an epsilon guard would
            // catch. The real failure mode near Nyquist is tangent (and so denom) blowing up to
            // +Infinity, which isfinite() here already catches; a `fabs(denom) < 1e-12`
            // disjunct that could never be true was removed rather than kept as misleading dead
            // code implying a "Nyquist pole guard" that isn't actually what stops anything.
            if (!std::isfinite(denom)) {
                return false;
            }
            const double a = (tangent - 1.0) / denom;
            if (!std::isfinite(a)) {
                return false;
            }
            coefficients = BiquadCoefficients::onePole(a);
            return true;
        }

        // SVF second-order all-pass: g = tan(omega/2), k = 1/q. Same derivation as
        // NativeBmwDspProcessor::makeAllPass2, just Q-adjustable here (that one is fixed at
        // 1/sqrt(2)).
        const double g = std::tan(omega * 0.5);
        const double k = 1.0 / static_cast<double>(q);
        const SvfCore core = svfCore(g, k);
        if (!std::isfinite(core.a1) || !std::isfinite(core.a2) || !std::isfinite(core.a3)) {
            // coefficients is already {} from the unconditional reset at the top of this
            // function; nothing between there and here has touched it (only these local
            // doubles), so resetting it again here would be a no-op repeating that guarantee --
            // same as the other three failure returns in this function, which don't repeat it.
            return false;
        }
        coefficients = BiquadCoefficients::svf2(core.a1, core.a2, core.a3, 1.0, -2.0 * k, 0.0);
        return true;
    }
};

/** Reconstruct the final stereo output after per-output processing, summing all three bands. */
inline StereoFrame sumToStereo(const std::array<float, kOutputCount>& outputs) {
    const float left = outputs[static_cast<std::size_t>(OutputId::LowLeft)] +
                       outputs[static_cast<std::size_t>(OutputId::MidLeft)] +
                       outputs[static_cast<std::size_t>(OutputId::HighLeft)];
    const float right = outputs[static_cast<std::size_t>(OutputId::LowRight)] +
                        outputs[static_cast<std::size_t>(OutputId::MidRight)] +
                        outputs[static_cast<std::size_t>(OutputId::HighRight)];
    return {
        std::isfinite(left) ? left : 0.0f,
        std::isfinite(right) ? right : 0.0f,
    };
}

}  // namespace NativeBmwRouting

#endif
