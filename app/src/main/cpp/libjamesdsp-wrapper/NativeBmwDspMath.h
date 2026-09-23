#ifndef SIPHONDSP_NATIVE_BMW_DSP_MATH_H
#define SIPHONDSP_NATIVE_BMW_DSP_MATH_H

// Pure math helpers shared by every NativeBmwDsp module: flush-to-zero, clamps, dB conversion,
// the soft-knee curve and the SVF step (scalar + NEON). No state, no allocation.

#include <algorithm>
#include <cmath>
#include <limits>
#include "NativeBmwRouting.h"

// NEON path: arm64 only (the only ABI the app ships -- see SUPPORTED_ABIS in app/build.gradle.kts).
// Every other target (the x86-64 host running native-tests, CI) builds the scalar path alone.
// -DSIPHON_DISABLE_NEON forces scalar on arm64 too, for NEON-vs-scalar parity runs
// (scripts/run-neon-parity.sh).
#if defined(__aarch64__) && defined(__ARM_NEON) && !defined(SIPHON_DISABLE_NEON)
#include <arm_neon.h>
#define SIPHON_NEON 1
#else
#define SIPHON_NEON 0
#endif

namespace NativeBmwDsp {

constexpr float PI = 3.14159265358979323846f, BW = 0.7071067812f;
// Q of the quadratic factor in the 3rd-order Butterworth polynomial (s^2+s+1 -> Q=1 exactly,
// from matching s^2 + (1/Q)s + 1). Paired with a 1st-order (6 dB/oct) stage at the same corner,
// this gives the 18 dB/oct BW3 crossover; see buildLowCrossover/buildMidCrossover.
constexpr float kButterworth3Q = 1.f;
// Qs of the two quadratic factors in the 4th-order Butterworth polynomial: Q_k = 1/(2cos(theta_k))
// for theta = pi/8 and 3pi/8 -> 0.5412 and 1.3066. Cascading these (not two Q=1/sqrt(2) stages,
// which is LR4) gives the BW4 crossover's maximally-flat 24 dB/oct with a -3 dB corner.
constexpr float kButterworth4QLow = 0.5411961f;
constexpr float kButterworth4QHigh = 1.3065630f;

inline float ftz(float x) {
    return (!std::isfinite(x) || std::fabs(x) < 1e-20f) ? 0.f : x;
}
inline double ftzd(double x) {
    return NativeBmwRouting::flushDenormal(x);
}
// a*b + c. On arm64 this is pinned to one fused multiply-add: exactly the fmadd/fnmsub clang's
// default -ffp-contract=on already emitted for Biquad::run's Svf2 expressions before the NEON
// path existed (checked in the arm64 disassembly). Pinning it explicitly makes the scalar and
// NEON (vfmaq_f64) paths round identically by construction, not by compiler choice. Other
// targets keep the plain expression, i.e. whatever they computed before.
inline double mulAdd(double a, double b, double c) {
#if defined(__aarch64__)
    return __builtin_fma(a, b, c);
#else
    return a * b + c;
#endif
}
// One step of the trapezoidal SVF (Andy Simper / Cytomic) -- Biquad::run's Svf2 case and the
// scalar lanes of PeqBank. Same operations and fused-rounding points as svf2StepX2() below.
inline double svf2Step(double xd, double a1, double a2, double a3, double m0, double m1,
                       double m2, double& ic1eq, double& ic2eq) {
    const double v3 = xd - ic2eq;
    const double v1 = mulAdd(a1, ic1eq, a2 * v3);              // a1*ic1eq + a2*v3
    const double v2 = mulAdd(a3, v3, mulAdd(a2, ic1eq, ic2eq));  // ic2eq + a2*ic1eq + a3*v3
    ic1eq = ftzd(mulAdd(2.0, v1, -ic1eq));                      // 2*v1 - ic1eq
    ic2eq = ftzd(mulAdd(2.0, v2, -ic2eq));                      // 2*v2 - ic2eq
    return ftzd(mulAdd(m2, v2, mulAdd(m0, xd, m1 * v1)));       // m0*x + m1*v1 + m2*v2
}
#if SIPHON_NEON
// ---- NEON (arm64) --------------------------------------------------------------------------
// The filter core is double precision, so a NEON register holds 2 lanes: lane 0 = left channel,
// lane 1 = right channel of the same frame. (IIR recursion makes each sample depend on the
// previous one, so consecutive frames can't share a register.) Every lane op below is the same
// IEEE double operation the scalar path does, with vfmaq_f64 exactly where svf2Step() uses
// mulAdd(), so each lane is bit-identical to svf2Step().

// ftzd() on both lanes: keeps x only when 1e-30 <= |x| < inf. NaN fails both compares, so NaN,
// +/-inf and tiny values (including -0.0) all become +0.0, same as the scalar ftzd().
inline float64x2_t ftzdX2(float64x2_t x) {
    const float64x2_t ax = vabsq_f64(x);
    const uint64x2_t keep =
        vandq_u64(vcgeq_f64(ax, vdupq_n_f64(1e-30)),
                  vcltq_f64(ax, vdupq_n_f64(std::numeric_limits<double>::infinity())));
    return vreinterpretq_f64_u64(vandq_u64(vreinterpretq_u64_f64(x), keep));
}
// svf2Step() on both channels at once. x holds {left, right} as float, and the float<->double
// conversions at each end match the scalar static_casts (exact widen, round-to-nearest narrow).
inline float32x2_t svf2StepX2(float32x2_t x, float64x2_t a1, float64x2_t a2, float64x2_t a3,
                              float64x2_t m0, float64x2_t m1, float64x2_t m2,
                              float64x2_t& ic1eq, float64x2_t& ic2eq) {
    const float64x2_t xd = vcvt_f64_f32(x);
    const float64x2_t two = vdupq_n_f64(2.0);
    const float64x2_t v3 = vsubq_f64(xd, ic2eq);
    const float64x2_t v1 = vfmaq_f64(vmulq_f64(a2, v3), a1, ic1eq);
    const float64x2_t v2 = vfmaq_f64(vfmaq_f64(ic2eq, a2, ic1eq), a3, v3);
    ic1eq = ftzdX2(vfmaq_f64(vnegq_f64(ic1eq), two, v1));
    ic2eq = ftzdX2(vfmaq_f64(vnegq_f64(ic2eq), two, v2));
    const float64x2_t y = vfmaq_f64(vfmaq_f64(vmulq_f64(m1, v1), m0, xd), m2, v2);
    return vcvt_f32_f64(ftzdX2(y));
}
inline float64x2_t lanes(double left, double right) {
    return vcombine_f64(vdup_n_f64(left), vdup_n_f64(right));
}
inline float32x2_t lanes(float left, float right) {
    return vset_lane_f32(right, vdup_n_f32(left), 1);
}
#endif
template<class T>
T clampInt(float x) {
    const double lo = static_cast<double>(std::numeric_limits<T>::min()),
                 hi = static_cast<double>(std::numeric_limits<T>::max());
    return static_cast<T>(std::llrint(std::max(lo, std::min(hi, static_cast<double>(x)))));
}
inline float clampf(float x, float lo, float hi) {
    return std::max(lo, std::min(hi, x));
}
inline bool changed(float a, float b) {
    return std::fabs(a - b) > 1e-6f;
}
inline float dbToLin(float db) {
    return std::pow(10.f, db / 20.f);
}
// Soft-knee gain reduction (dB, <= 0) for a detector level `overDb` dB above threshold, given
// `ratio` and knee width `kneeDb`. Shared by processCompressor's per-output path and the MBC's
// per-band path -- deliberately identical math, one place for a knee-shape fix.
inline float softKneeGainReductionDb(float overDb, float ratio, float kneeDb) {
    const float slope = 1 - 1 / std::max(1.001f, ratio);
    if (kneeDb > 0) {
        const float kh = kneeDb * .5f;
        if (overDb >= kh) {
            return -overDb * slope;
        }
        if (overDb > -kh) {
            const float x = overDb + kh;
            return -slope * x * x / (2 * kneeDb);
        }
        return 0.f;
    }
    return overDb > 0 ? -overDb * slope : 0.f;
}

}  // namespace NativeBmwDsp

#endif
