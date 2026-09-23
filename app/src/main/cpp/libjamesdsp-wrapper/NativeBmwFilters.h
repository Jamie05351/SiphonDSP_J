#ifndef SIPHONDSP_NATIVE_BMW_FILTERS_H
#define SIPHONDSP_NATIVE_BMW_FILTERS_H

// Biquad filter section, the stereo PEQ bank, and the coefficient builders every other module
// uses (crossovers, tilt, MBC tree, measurement-mute bus).

#include <array>
#include <cstddef>
#include <cstdint>
#include "NativeBmwDspMath.h"
#include "NativeBmwRouting.h"

namespace NativeBmwDsp {

enum : std::size_t { kMaxPeqSectionsPerChannel = 16, kPeqBandWidth = 5 };

// Trapezoidal-integrated state variable filter (Andy Simper / "Cytomic" topology), not RBJ
// Direct Form II Transposed. DF2T's a1/a2 coefficients cluster near the unit circle at low
// corner frequencies relative to the sample rate -- the subsonic HPF (~32 Hz), the low-band
// crossover, the 40-65 Hz phase-alignment all-pass sections -- which is exactly the case SVF
// is designed for: its two trapezoidal integrator states (ic1eq/ic2eq) stay well-conditioned
// there by construction, rather than needing double precision (still used throughout, see
// below) to paper over an ill-conditioned realization. Every coefficient-design function
// below (makeLowPass/makeHighPass/makeLowShelf/makeHighShelf/makeAllPass2/makePeq) derives
// the identical designed transfer function the old RBJ formulas did -- verified by direct
// time-domain simulation against those formulas before this migration, machine-precision
// match on magnitude and phase across every filter type/fc/Q/gain this engine uses. Don't
// simplify this back to DF2T or to float.
struct Biquad {
    // topology picks run()'s recursion. Svf2 is the default 2nd-order Cytomic/Simper form
    // every LP/HP/shelf/bell/notch/allpass2 builder below produces. OnePoleAllpass is the
    // exception carried over from before the SVF migration: a true 1-pole design (used only
    // by NativeBmwRouting::AllPassSection's optional 1st-order phase-align case) doesn't map
    // onto SVF's 2-integrator form, so it keeps its original 1-pole recursion untouched.
    // OnePoleLowpass/OnePoleHighpass are a second, genuine 1-pole TPT design (Zavalishin,
    // "The Art of VA Filter Design" 2.2) -- the 6 dB/oct stage a 3rd-order (BW3) crossover
    // cascades with a 2nd-order Q=1 SVF section (see buildLowCrossover/buildMidCrossover).
    // Not the same math as OnePoleAllpass (unity magnitude by construction); these two
    // actually roll off. Still per-instance state, no sharing -- just a branch in run().
    enum class Topology : std::uint8_t {
        Svf2 = 0,
        OnePoleAllpass = 1,
        OnePoleLowpass = 2,
        OnePoleHighpass = 3,
    };
    Topology topology = Topology::Svf2;
    double op_z1 = 0, op_a = 0;
    // SVF state (trapezoidal integrators).
    double ic1eq = 0, ic2eq = 0;
    // SVF coefficients: a1/a2/a3 from g=tan(pi*fc/fs), k=1/Q; m0/m1/m2 select the filter type
    // (LP/HP/shelf/bell/all-pass) as a mix of the two integrator outputs plus the input.
    // Defaults (a1=a2=a3=0, m0=1/m1=0/m2=0) are an identity pass-through -- matching the old
    // DF2T struct's b0=1-rest-0 default -- so a Biquad that somehow runs before its owning
    // rebuild ever fires (e.g. process() called before the first configure()) is inert
    // instead of silent.
    double a1 = 0, a2 = 0, a3 = 0, m0 = 1, m1 = 0, m2 = 0;
    float run(float x);
    // Runs l on xl and r on xr -- bit-identical to xl = l.run(xl); xr = r.run(xr). On arm64,
    // when both are Svf2, the two channels go through one NEON pass (one float64x2 lane
    // each); any other topology mix falls back to the two scalar run() calls.
    static void runPair(Biquad& l, Biquad& r, float& xl, float& xr);
    void clear();
    void loadAllPass(const NativeBmwRouting::BiquadCoefficients& c);
};

// One Svf2 section for both channels, in structure-of-arrays form: every coefficient/state
// field is a {left, right} pair, so the whole section loads straight into NEON float64x2
// registers (lane 0 = left, lane 1 = right) with no gather. Each lane is bit-identical to a
// Biquad running the same coefficients (both go through svf2Step's math). Svf2 only -- the
// one-pole topologies stay on Biquad.
struct alignas(16) SvfPair {
    enum : std::size_t { kLeftLane = 0, kRightLane = 1 };
    double a1[2] = {0, 0}, a2[2] = {0, 0}, a3[2] = {0, 0};
    double m0[2] = {1, 1}, m1[2] = {0, 0}, m2[2] = {0, 0};
    double ic1eq[2] = {0, 0}, ic2eq[2] = {0, 0};
    // Copies an Svf2 Biquad's coefficients (from a make*() builder) into one lane, state cleared.
    void load(std::size_t lane, const Biquad& q);
    // Both lanes from the same Biquad (same filter on L and R), state cleared.
    void loadBoth(const Biquad& q) {
        load(kLeftLane, q);
        load(kRightLane, q);
    }
    // Clears both lanes' state; coefficients stay.
    void clear();
    // The run functions are defined inline here (not in the .cpp) so the per-sample hot paths
    // in other modules -- the PEQ cascade, the MBC tree -- inline them instead of calling out.

    // Runs one lane alone (scalar).
    float runLane(std::size_t lane, float x) {
        return static_cast<float>(svf2Step(static_cast<double>(x), a1[lane], a2[lane], a3[lane],
                                           m0[lane], m1[lane], m2[lane], ic1eq[lane],
                                           ic2eq[lane]));
    }
#if SIPHON_NEON
    // NEON: both lanes in one svf2StepX2() pass, x = {left, right}. Coefficients and state load
    // straight from the SoA arrays, one register per field.
    float32x2_t runX2(float32x2_t x) {
        float64x2_t ic1 = vld1q_f64(ic1eq), ic2 = vld1q_f64(ic2eq);
        x = svf2StepX2(x, vld1q_f64(a1), vld1q_f64(a2), vld1q_f64(a3), vld1q_f64(m0),
                       vld1q_f64(m1), vld1q_f64(m2), ic1, ic2);
        vst1q_f64(ic1eq, ic1);
        vst1q_f64(ic2eq, ic2);
        return x;
    }
#endif
    // Runs both lanes -- same result as running left/right through two Biquads.
    void run(float& left, float& right) {
#if SIPHON_NEON
        const float32x2_t y = runX2(lanes(left, right));
        left = vget_lane_f32(y, 0);
        right = vget_lane_f32(y, 1);
#else
        left = runLane(kLeftLane, left);
        right = runLane(kRightLane, right);
#endif
    }
};

// Stereo PEQ bank: SvfPair section i holds band i of the left chain in lane 0 and band i of the
// right chain in lane 1. Every PEQ section is Svf2 (makePeq only builds that topology). Left and
// right can hold different band counts (a band's channel field picks L, R or both): sections
// [0, min(leftCount, rightCount)) run as NEON pairs, the longer channel's remainder runs scalar
// on its own lane. Same per-channel cascade order and the same math as per-channel Biquads.
struct PeqBank {
    using Section = SvfPair;
    enum : std::size_t { kLeftLane = SvfPair::kLeftLane, kRightLane = SvfPair::kRightLane };
    std::array<Section, kMaxPeqSectionsPerChannel> sections{};
    std::size_t leftCount = 0, rightCount = 0;
    // Appends an Svf2 section built by makePeq() to one lane (kLeftLane/kRightLane), with
    // cleared state.
    void append(std::size_t lane, const Biquad& q);
    void process(float& left, float& right);
    void clear();
};

// Coefficient builders. Each (re)writes q's topology + coefficients and clears its state.
void makeLowPass(Biquad& q, float fc, float Q, float sr);
void makeHighPass(Biquad& q, float fc, float Q, float sr);
void makeLowPass1(Biquad& q, float fc, float sr);
void makeHighPass1(Biquad& q, float fc, float sr);
void makeIdentity(Biquad& q);
void makeLowShelf(Biquad& q, float fc, float gainDb, float sr);
void makeHighShelf(Biquad& q, float fc, float gainDb, float sr);
void makeAllPass2(Biquad& q, float fc, float sr);
// Returns false (q untouched) for an out-of-range or non-finite band.
bool makePeq(Biquad& q, double frequency, double gainDb, double Q, int type, float sampleRate);
// True for a PEQ band the processor's PEQ build drops as a no-op: a non-notch band with ~0 dB
// gain has no audible effect, so it's never installed as a running Biquad. Shared with
// captureTruthSnapshot() so the "active" flag it reports can never drift from what the build
// actually does.
bool peqBandSkipped(int type, double gain);

}  // namespace NativeBmwDsp

#endif
