#include "NativeBmwFilters.h"
#include <algorithm>
#include <cmath>
#include "NativeBmwDspMath.h"

namespace NativeBmwDsp {

float Biquad::run(float x) {
    const double xd = static_cast<double>(x);
    switch (topology) {
        case Topology::OnePoleAllpass: {
            // Untouched 1-pole all-pass recursion (RBJ cookbook): y = a*x + z1; z1' = x - a*y.
            const double y = op_a * xd + op_z1;
            op_z1 = ftzd(xd - op_a * y);
            return static_cast<float>(ftzd(y));
        }
        case Topology::OnePoleLowpass:
        case Topology::OnePoleHighpass: {
            // TPT one-pole (Zavalishin 2.2): single trapezoidal integrator, a1 = g/(1+g).
            const double v = (xd - ic1eq) * a1;
            const double lp = v + ic1eq;
            ic1eq = ftzd(lp + v);
            return static_cast<float>(ftzd(topology == Topology::OnePoleLowpass ? lp : xd - lp));
        }
        case Topology::Svf2:
        default: {
            // Trapezoidal-integrated SVF (Andy Simper / Cytomic), reference form -- see svf2Step().
            return static_cast<float>(svf2Step(xd, a1, a2, a3, m0, m1, m2, ic1eq, ic2eq));
        }
    }
}
void Biquad::runPair(Biquad& l, Biquad& r, float& xl, float& xr) {
#if SIPHON_NEON
    if (l.topology == Topology::Svf2 && r.topology == Topology::Svf2) {
        // NEON: gather the two Biquads' coefficients/state into {l, r} lanes, run one
        // svf2StepX2(), scatter the state back.
        float64x2_t ic1 = lanes(l.ic1eq, r.ic1eq), ic2 = lanes(l.ic2eq, r.ic2eq);
        const float32x2_t y =
            svf2StepX2(lanes(xl, xr), lanes(l.a1, r.a1), lanes(l.a2, r.a2), lanes(l.a3, r.a3),
                       lanes(l.m0, r.m0), lanes(l.m1, r.m1), lanes(l.m2, r.m2), ic1, ic2);
        l.ic1eq = vgetq_lane_f64(ic1, 0);
        r.ic1eq = vgetq_lane_f64(ic1, 1);
        l.ic2eq = vgetq_lane_f64(ic2, 0);
        r.ic2eq = vgetq_lane_f64(ic2, 1);
        xl = vget_lane_f32(y, 0);
        xr = vget_lane_f32(y, 1);
        return;
    }
#endif
    xl = l.run(xl);
    xr = r.run(xr);
}
void Biquad::clear() {
    op_z1 = 0;
    ic1eq = ic2eq = 0;
}
void Biquad::loadAllPass(const NativeBmwRouting::BiquadCoefficients& c) {
    topology = c.firstOrder ? Topology::OnePoleAllpass : Topology::Svf2;
    op_a = c.opA;
    a1 = c.a1;
    a2 = c.a2;
    a3 = c.a3;
    m0 = c.m0;
    m1 = c.m1;
    m2 = c.m2;
    clear();
}
void PeqBank::append(std::size_t lane, const Biquad& q) {
    std::size_t& count = lane == kLeftLane ? leftCount : rightCount;
    Section& s = sections[count++];
    s.a1[lane] = q.a1;
    s.a2[lane] = q.a2;
    s.a3[lane] = q.a3;
    s.m0[lane] = q.m0;
    s.m1[lane] = q.m1;
    s.m2[lane] = q.m2;
    s.ic1eq[lane] = 0;
    s.ic2eq[lane] = 0;
}
void PeqBank::process(float& left, float& right) {
    std::size_t i = 0;
#if SIPHON_NEON
    // NEON: the sections both channels have run as {left, right} pairs, loaded straight from the
    // SoA arrays.
    const std::size_t paired = std::min(leftCount, rightCount);
    if (paired > 0) {
        float32x2_t x = lanes(left, right);
        for (; i < paired; ++i) {
            Section& s = sections[i];
            float64x2_t ic1 = vld1q_f64(s.ic1eq), ic2 = vld1q_f64(s.ic2eq);
            x = svf2StepX2(x, vld1q_f64(s.a1), vld1q_f64(s.a2), vld1q_f64(s.a3), vld1q_f64(s.m0),
                           vld1q_f64(s.m1), vld1q_f64(s.m2), ic1, ic2);
            vst1q_f64(s.ic1eq, ic1);
            vst1q_f64(s.ic2eq, ic2);
        }
        left = vget_lane_f32(x, 0);
        right = vget_lane_f32(x, 1);
    }
#endif
    // Scalar: the longer channel's remaining sections (or every section, without NEON).
    auto runLane = [this](std::size_t lane, std::size_t from, std::size_t to, float sample) {
        for (std::size_t j = from; j < to; ++j) {
            Section& s = sections[j];
            sample = static_cast<float>(svf2Step(static_cast<double>(sample), s.a1[lane],
                                                 s.a2[lane], s.a3[lane], s.m0[lane], s.m1[lane],
                                                 s.m2[lane], s.ic1eq[lane], s.ic2eq[lane]));
        }
        return sample;
    };
    left = runLane(kLeftLane, i, leftCount, left);
    right = runLane(kRightLane, i, rightCount, right);
}
void PeqBank::clear() {
    for (auto& s : sections) {
        s.ic1eq[kLeftLane] = s.ic1eq[kRightLane] = 0;
        s.ic2eq[kLeftLane] = s.ic2eq[kRightLane] = 0;
    }
}

// Trapezoidal SVF, Andy Simper/Cytomic form: g = tan(w/2) (w = 2*pi*fc/sr, the usual bilinear
// prewarp), k = 1/Q. a1/a2/a3 are shared by every 2nd-order type below; m0/m1/m2 pick the type by
// mixing the input with the two integrator outputs (v1, v2) in Biquad::run. Verified against this
// file's previous RBJ DF2T formulas by direct time-domain simulation (magnitude+phase match to
// machine precision across every filter type/fc/Q/gain this engine uses) before this migration --
// see PR description, not reproduced as a runtime check.
void makeLowPass(Biquad& q, float fc, float Q, float sr) {
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5), k = 1. / Q;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    q.topology = Biquad::Topology::Svf2;
    q.a1 = a1;
    q.a2 = a2;
    q.a3 = a3;
    q.m0 = 0;
    q.m1 = 0;
    q.m2 = 1;
    q.clear();
}
void makeHighPass(Biquad& q, float fc, float Q, float sr) {
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5), k = 1. / Q;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    q.topology = Biquad::Topology::Svf2;
    q.a1 = a1;
    q.a2 = a2;
    q.a3 = a3;
    q.m0 = 1;
    q.m1 = -k;
    q.m2 = -1;
    q.clear();
}
// True 1-pole (6 dB/oct) TPT lowpass/highpass -- BW3's low-order stage (see kButterworth3Q).
// Same bilinear prewarp as every 2nd-order builder above (g=tan(w/2)); the coefficient is the
// classic single-integrator one-pole form (Zavalishin 2.2) rather than SVF's two-integrator one.
void makeLowPass1(Biquad& q, float fc, float sr) {
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5);
    q.topology = Biquad::Topology::OnePoleLowpass;
    q.a1 = g / (1. + g);
    q.clear();
}
void makeHighPass1(Biquad& q, float fc, float sr) {
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5);
    q.topology = Biquad::Topology::OnePoleHighpass;
    q.a1 = g / (1. + g);
    q.clear();
}
// Identity pass-through. BW2's crossover only needs one 2nd-order stage; the second Biquad slot
// (crossover2) is forced inert here rather than skipped per-sample in processLowCrossover/
// processMidCrossover, so those stay branch-free on the audio-thread hot path.
void makeIdentity(Biquad& q) {
    q.topology = Biquad::Topology::Svf2;
    q.a1 = q.a2 = q.a3 = 0;
    q.m0 = 1;
    q.m1 = 0;
    q.m2 = 0;
    q.clear();
}
// fc clamp matches makeLowPass/makeHighPass/makeAllPass2: every coefficient builder in this file
// keeps its corner inside [20 Hz, 0.49*sr] so w stays well below pi -- g=tan(w/2) then stays
// large-but-finite instead of approaching the asymptote at w/2=pi/2 (fc -> Nyquist). Callers
// already clamp (tilt freq is [200,2000]); this is belt-and-braces so a future caller can't feed
// it a near-Nyquist corner and get non-finite coefficients with no identity fallback.
void makeLowShelf(Biquad& q, float fc, float gainDb, float sr) {
    double A = std::pow(10., static_cast<double>(gainDb) / 40.),
           w = 2 * PI * clampf(fc, 20.f, sr * .49f) / sr, g = std::tan(w * .5) / std::sqrt(A),
           k = 1. / BW;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    q.topology = Biquad::Topology::Svf2;
    q.a1 = a1;
    q.a2 = a2;
    q.a3 = a3;
    q.m0 = 1;
    q.m1 = k * (A - 1);
    q.m2 = A * A - 1;
    q.clear();
}
void makeHighShelf(Biquad& q, float fc, float gainDb, float sr) {
    double A = std::pow(10., static_cast<double>(gainDb) / 40.),
           w = 2 * PI * clampf(fc, 20.f, sr * .49f) / sr, g = std::tan(w * .5) * std::sqrt(A),
           k = 1. / BW;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    q.topology = Biquad::Topology::Svf2;
    q.a1 = a1;
    q.a2 = a2;
    q.a3 = a3;
    q.m0 = A * A;
    q.m1 = k * (1 - A) * A;
    q.m2 = 1 - A * A;
    q.clear();
}
// SVF all-pass, Q = 1/sqrt(2). Unity magnitude everywhere, -360 deg phase sweep through fc.
// Matches NativeBmwRouting::AllPassSection::rebuild's second-order branch; used only by the MBC
// tree.
void makeAllPass2(Biquad& q, float fc, float sr) {
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5), k = 1. / BW;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    q.topology = Biquad::Topology::Svf2;
    q.a1 = a1;
    q.a2 = a2;
    q.a3 = a3;
    q.m0 = 1;
    q.m1 = -2 * k;
    q.m2 = 0;
    q.clear();
}
bool makePeq(Biquad& q, double f, double gainDb, double Q, int type, float sr) {
    if (!std::isfinite(f) || !std::isfinite(gainDb) || !std::isfinite(Q) || f < 20 || f >= sr * .5 ||
        Q < .1 || Q > 30 || type < 0 || type > 3) {
        return false;
    }
    double A = std::pow(10., gainDb / 40.), w = 2. * PI * f / sr, g = std::tan(w * .5), k, m0, m1, m2;
    if (type == 0) {
        // Bell/peak: k folds A into the Q term rather than into g (the shelf types below do the
        // opposite) -- this is the standard Cytomic bell derivation, confirmed against the old
        // RBJ bell formula's response, not read off intuition.
        k = 1. / (Q * A);
        m0 = 1;
        m1 = k * (A * A - 1);
        m2 = 0;
    } else if (type == 1) {
        g /= std::sqrt(A);
        k = 1. / Q;
        m0 = 1;
        m1 = k * (A - 1);
        m2 = A * A - 1;
    } else if (type == 2) {
        g *= std::sqrt(A);
        k = 1. / Q;
        m0 = A * A;
        m1 = k * (1 - A) * A;
        m2 = 1 - A * A;
    } else {
        // Notch (band-reject), type 3 ("NO"): m1 = -k, not -2*k. Notch = LP + HP by definition
        // (their m0/m1/m2 mixes add component-wise: (0,0,1) + (1,-k,-1) = (1,-k,0)) -- -2*k is the
        // Allpass mixing instead (see makeAllPass2/AllPassSection::rebuild), which has unity
        // magnitude at every frequency by construction. With -2*k here, a user-selected Notch band
        // played back with no audible cut at all (just a phase twist) while the on-screen graph
        // (BiquadUtils.kt, a separate Kotlin implementation never touched by this migration) kept
        // showing the correct deep notch -- silently wrong audio behind a correct-looking curve.
        k = 1. / Q;
        m0 = 1;
        m1 = -k;
        m2 = 0;
    }
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    // makePeq is the one coefficient builder here with fc unclamped up to true Nyquist (arbitrary
    // user-edited PEQ bands), so it's the one that keeps an explicit finite-result guard -- g can
    // grow very large as f approaches sr/2 (g=tan(w/2) has no asymptote-avoiding margin here the
    // way the 0.49*sr-clamped builders above do). m0/m1/m2 need the same guard as a1/a2/a3: an
    // extreme-but-finite gainDb can underflow A to exactly 0 (bell's k=1/(Q*A) then overflows to
    // +Infinity, making m1 non-finite) while g stays small enough that a1/a2/a3 alone still pass
    // -- checking only those would let a non-finite m1/m2 through to Biquad::run, where
    // m1*v1 = -Infinity*0 = NaN gets silently flushed to 0 by ftzd(), zeroing that band's output
    // instead of rejecting the malformed config.
    if (!std::isfinite(a1) || !std::isfinite(a2) || !std::isfinite(a3) || !std::isfinite(m0) ||
        !std::isfinite(m1) || !std::isfinite(m2)) {
        return false;
    }
    q.topology = Biquad::Topology::Svf2;
    q.a1 = a1;
    q.a2 = a2;
    q.a3 = a3;
    q.m0 = m0;
    q.m1 = m1;
    q.m2 = m2;
    q.clear();
    return true;
}
bool peqBandSkipped(int type, double gain) {
    return type != 3 && std::fabs(gain) < 1e-9;
}

}  // namespace NativeBmwDsp
