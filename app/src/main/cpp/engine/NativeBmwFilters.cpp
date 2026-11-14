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
void Biquad::retune(const Biquad& next) {
    const bool keepState = next.topology == topology && !isIdentity() && !next.isIdentity();
    topology = next.topology;
    op_a = next.op_a;
    a1 = next.a1;
    a2 = next.a2;
    a3 = next.a3;
    m0 = next.m0;
    m1 = next.m1;
    m2 = next.m2;
    if (!keepState) {
        clear();
    }
}
void Biquad::loadAllPass(const NativeBmwRouting::BiquadCoefficients& c) {
    Biquad n;
    n.topology = c.firstOrder ? Topology::OnePoleAllpass : Topology::Svf2;
    n.op_a = c.opA;
    n.a1 = c.a1;
    n.a2 = c.a2;
    n.a3 = c.a3;
    n.m0 = c.m0;
    n.m1 = c.m1;
    n.m2 = c.m2;
    retune(n);
}
void SvfPair::load(std::size_t lane, const Biquad& q) {
    a1[lane] = q.a1;
    a2[lane] = q.a2;
    a3[lane] = q.a3;
    m0[lane] = q.m0;
    m1[lane] = q.m1;
    m2[lane] = q.m2;
    ic1eq[lane] = 0;
    ic2eq[lane] = 0;
}
void SvfPair::setCoefficients(std::size_t lane, const Biquad& q) {
    a1[lane] = q.a1;
    a2[lane] = q.a2;
    a3[lane] = q.a3;
    m0[lane] = q.m0;
    m1[lane] = q.m1;
    m2[lane] = q.m2;
}
void SvfPair::clear() {
    ic1eq[kLeftLane] = ic1eq[kRightLane] = 0;
    ic2eq[kLeftLane] = ic2eq[kRightLane] = 0;
}
void PeqBank::append(std::size_t lane, const Biquad& q, const BandKey& key) {
    std::size_t& count = lane == kLeftLane ? leftCount : rightCount;
    source[lane][count] = key;
    sections[count++].load(lane, q);
}
void PeqBank::inheritState(const PeqBank& previous) {
    for (std::size_t lane : {kLeftLane, kRightLane}) {
        const std::size_t count = lane == kLeftLane ? leftCount : rightCount;
        const std::size_t previousCount =
            lane == kLeftLane ? previous.leftCount : previous.rightCount;
        std::array<bool, kMaxPeqSectionsPerChannel> taken{}, used{};
        auto inherit = [&](std::size_t j, std::size_t k) {
            sections[j].ic1eq[lane] = previous.sections[k].ic1eq[lane];
            sections[j].ic2eq[lane] = previous.sections[k].ic2eq[lane];
            taken[j] = used[k] = true;
        };
        for (std::size_t j = 0; j < count; ++j) {
            for (std::size_t k = 0; k < previousCount; ++k) {
                if (!used[k] && previous.source[lane][k].sameFilter(source[lane][j])) {
                    inherit(j, k);
                    break;
                }
            }
        }
        if (rawBandCount != previous.rawBandCount) {
            continue;
        }
        for (std::size_t j = 0; j < count; ++j) {
            if (taken[j]) {
                continue;
            }
            for (std::size_t k = 0; k < previousCount; ++k) {
                if (!used[k] && previous.source[lane][k].index == source[lane][j].index &&
                    previous.source[lane][k].type == source[lane][j].type) {
                    inherit(j, k);
                    break;
                }
            }
        }
    }
}

namespace {
// K consecutive paired sections over a block, skewed by one sample each, in place (see
// PeqBank::processBlock()). Position i goes through section 0 at step i, section 1 at step i + 1
// and so on, so every section sees exactly the samples it would frame by frame, in order.
template <std::size_t K>
void runPairedGroup(SvfPair* const* sec, float* left, float* right, std::size_t n) {
#if SIPHON_NEON
    float64x2_t a1[K], a2[K], a3[K], m0[K], m1[K], m2[K], ic1[K], ic2[K];
    for (std::size_t k = 0; k < K; ++k) {
        a1[k] = vld1q_f64(sec[k]->a1);
        a2[k] = vld1q_f64(sec[k]->a2);
        a3[k] = vld1q_f64(sec[k]->a3);
        m0[k] = vld1q_f64(sec[k]->m0);
        m1[k] = vld1q_f64(sec[k]->m1);
        m2[k] = vld1q_f64(sec[k]->m2);
        ic1[k] = vld1q_f64(sec[k]->ic1eq);
        ic2[k] = vld1q_f64(sec[k]->ic2eq);
    }
    for (std::size_t t = 0; t < n + K - 1; ++t) {
        for (std::size_t kk = K; kk-- > 0;) {
            if (t < kk || t - kk >= n) {
                continue;
            }
            const std::size_t i = t - kk;
            const float32x2_t y = svf2StepX2(lanes(left[i], right[i]), a1[kk], a2[kk], a3[kk], m0[kk],
                                             m1[kk], m2[kk], ic1[kk], ic2[kk]);
            left[i] = vget_lane_f32(y, 0);
            right[i] = vget_lane_f32(y, 1);
        }
    }
    for (std::size_t k = 0; k < K; ++k) {
        vst1q_f64(sec[k]->ic1eq, ic1[k]);
        vst1q_f64(sec[k]->ic2eq, ic2[k]);
    }
#else
    double c[K][2][6], st[K][2][2];
    for (std::size_t k = 0; k < K; ++k) {
        for (std::size_t lane = 0; lane < 2; ++lane) {
            c[k][lane][0] = sec[k]->a1[lane];
            c[k][lane][1] = sec[k]->a2[lane];
            c[k][lane][2] = sec[k]->a3[lane];
            c[k][lane][3] = sec[k]->m0[lane];
            c[k][lane][4] = sec[k]->m1[lane];
            c[k][lane][5] = sec[k]->m2[lane];
            st[k][lane][0] = sec[k]->ic1eq[lane];
            st[k][lane][1] = sec[k]->ic2eq[lane];
        }
    }
    float* io[2] = {left, right};
    for (std::size_t t = 0; t < n + K - 1; ++t) {
        for (std::size_t kk = K; kk-- > 0;) {
            if (t < kk || t - kk >= n) {
                continue;
            }
            const std::size_t i = t - kk;
            for (std::size_t lane = 0; lane < 2; ++lane) {
                const double* q = c[kk][lane];
                io[lane][i] = static_cast<float>(svf2Step(static_cast<double>(io[lane][i]), q[0], q[1], q[2],
                                                          q[3], q[4], q[5], st[kk][lane][0], st[kk][lane][1]));
            }
        }
    }
    for (std::size_t k = 0; k < K; ++k) {
        for (std::size_t lane = 0; lane < 2; ++lane) {
            sec[k]->ic1eq[lane] = st[k][lane][0];
            sec[k]->ic2eq[lane] = st[k][lane][1];
        }
    }
#endif
}

// The same for one lane's unpaired sections (the longer channel's remainder), K at a time.
template <std::size_t K>
void runLaneGroup(SvfPair* sec, std::size_t lane, float* x, std::size_t n) {
    double c[K][6], st[K][2];
    for (std::size_t k = 0; k < K; ++k) {
        c[k][0] = sec[k].a1[lane];
        c[k][1] = sec[k].a2[lane];
        c[k][2] = sec[k].a3[lane];
        c[k][3] = sec[k].m0[lane];
        c[k][4] = sec[k].m1[lane];
        c[k][5] = sec[k].m2[lane];
        st[k][0] = sec[k].ic1eq[lane];
        st[k][1] = sec[k].ic2eq[lane];
    }
    for (std::size_t t = 0; t < n + K - 1; ++t) {
        for (std::size_t kk = K; kk-- > 0;) {
            if (t < kk || t - kk >= n) {
                continue;
            }
            const std::size_t i = t - kk;
            const double* q = c[kk];
            x[i] = static_cast<float>(svf2Step(static_cast<double>(x[i]), q[0], q[1], q[2], q[3], q[4], q[5],
                                               st[kk][0], st[kk][1]));
        }
    }
    for (std::size_t k = 0; k < K; ++k) {
        sec[k].ic1eq[lane] = st[k][0];
        sec[k].ic2eq[lane] = st[k][1];
    }
}

}  // namespace

void runSvfChainBlock(SvfPair* const* chain, std::size_t count, float* left, float* right, std::size_t frames) {
    if (frames == 0) {
        return;
    }
    std::size_t i = 0;
    for (; i + 3 <= count; i += 3) {
        runPairedGroup<3>(chain + i, left, right, frames);
    }
    if (count - i == 2) {
        runPairedGroup<2>(chain + i, left, right, frames);
    } else if (count - i == 1) {
        runPairedGroup<1>(chain + i, left, right, frames);
    }
}

void runBiquadPairChainBlock(Biquad* const* chainL, Biquad* const* chainR, std::size_t count, float* left,
                             float* right, std::size_t frames) {
    if (frames == 0 || count == 0) {
        return;
    }
    // Runs of Svf2 pairs go through the register path as SvfPair copies (each lane the same
    // svf2Step math Biquad::run / runPair use), state written back after; any other topology
    // runs pair by pair through Biquad::runPair, a section at a time over the block.
    constexpr std::size_t kMax = 8;
    SvfPair temp[kMax];
    SvfPair* ptr[kMax];
    std::size_t i = 0;
    while (i < count) {
        if (chainL[i]->topology != Biquad::Topology::Svf2 || chainR[i]->topology != Biquad::Topology::Svf2) {
            for (std::size_t f = 0; f < frames; ++f) {
                Biquad::runPair(*chainL[i], *chainR[i], left[f], right[f]);
            }
            ++i;
            continue;
        }
        std::size_t j = i;
        while (j < count && j - i < kMax && chainL[j]->topology == Biquad::Topology::Svf2 &&
               chainR[j]->topology == Biquad::Topology::Svf2) {
            SvfPair& t = temp[j - i];
            const Biquad* b[2] = {chainL[j], chainR[j]};
            for (std::size_t lane = 0; lane < 2; ++lane) {
                t.a1[lane] = b[lane]->a1;
                t.a2[lane] = b[lane]->a2;
                t.a3[lane] = b[lane]->a3;
                t.m0[lane] = b[lane]->m0;
                t.m1[lane] = b[lane]->m1;
                t.m2[lane] = b[lane]->m2;
                t.ic1eq[lane] = b[lane]->ic1eq;
                t.ic2eq[lane] = b[lane]->ic2eq;
            }
            ptr[j - i] = &t;
            ++j;
        }
        runSvfChainBlock(ptr, j - i, left, right, frames);
        for (std::size_t k = i; k < j; ++k) {
            chainL[k]->ic1eq = temp[k - i].ic1eq[0];
            chainL[k]->ic2eq = temp[k - i].ic2eq[0];
            chainR[k]->ic1eq = temp[k - i].ic1eq[1];
            chainR[k]->ic2eq = temp[k - i].ic2eq[1];
        }
        i = j;
    }
}

void PeqBank::processBlock(float* left, float* right, std::size_t frames) {
    if (frames == 0) {
        return;
    }
    // The sections both channels have, as pairs, three at a time.
    const std::size_t paired = std::min(leftCount, rightCount);
    SvfPair* ptr[kMaxPeqSectionsPerChannel];
    for (std::size_t k = 0; k < paired; ++k) {
        ptr[k] = &sections[k];
    }
    runSvfChainBlock(ptr, paired, left, right, frames);
    // The longer channel's remaining sections on its own lane.
    auto lane = [this, paired, frames](std::size_t ln, std::size_t count, float* x) {
        std::size_t j = paired;
        for (; j + 3 <= count; j += 3) {
            runLaneGroup<3>(&sections[j], ln, x, frames);
        }
        if (count - j == 2) {
            runLaneGroup<2>(&sections[j], ln, x, frames);
        } else if (count - j == 1) {
            runLaneGroup<1>(&sections[j], ln, x, frames);
        }
    };
    lane(kLeftLane, leftCount, left);
    lane(kRightLane, rightCount, right);
}

void PeqBank::process(float& left, float& right) {
    std::size_t i = 0;
#if SIPHON_NEON
    // NEON: the sections both channels have run as {left, right} pairs. The sample stays in one
    // float32x2 register across the whole cascade.
    const std::size_t paired = std::min(leftCount, rightCount);
    if (paired > 0) {
        float32x2_t x = lanes(left, right);
        for (; i < paired; ++i) {
            x = sections[i].runX2(x);
        }
        left = vget_lane_f32(x, 0);
        right = vget_lane_f32(x, 1);
    }
#endif
    // Scalar: the longer channel's remaining sections (or every section, without NEON).
    for (std::size_t j = i; j < leftCount; ++j) {
        left = sections[j].runLane(kLeftLane, left);
    }
    for (std::size_t j = i; j < rightCount; ++j) {
        right = sections[j].runLane(kRightLane, right);
    }
}
void PeqBank::clear() {
    for (auto& s : sections) {
        s.clear();
    }
}

// Trapezoidal SVF, Andy Simper/Cytomic form: g = tan(w/2) (w = 2*pi*fc/sr, the usual bilinear
// prewarp), k = 1/Q. a1/a2/a3 are shared by every 2nd-order type below; m0/m1/m2 pick the type by
// mixing the input with the two integrator outputs (v1, v2) in Biquad::run. Verified against this
// file's previous RBJ DF2T formulas by direct time-domain simulation (magnitude+phase match to
// machine precision across every filter type/fc/Q/gain this engine uses) before this migration --
// see PR description, not reproduced as a runtime check.
void makeLowPass(Biquad& q, float fc, float Q, float sr) {
    Biquad n;
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5), k = 1. / Q;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    n.topology = Biquad::Topology::Svf2;
    n.a1 = a1;
    n.a2 = a2;
    n.a3 = a3;
    n.m0 = 0;
    n.m1 = 0;
    n.m2 = 1;
    q.retune(n);
}
void makeHighPass(Biquad& q, float fc, float Q, float sr, float minFc) {
    Biquad n;
    double w = 2 * PI * clampf(fc, minFc, sr * .49f) / sr, g = std::tan(w * .5), k = 1. / Q;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    n.topology = Biquad::Topology::Svf2;
    n.a1 = a1;
    n.a2 = a2;
    n.a3 = a3;
    n.m0 = 1;
    n.m1 = -k;
    n.m2 = -1;
    q.retune(n);
}
// True 1-pole (6 dB/oct) TPT lowpass/highpass -- BW3's low-order stage (see kButterworth3Q).
// Same bilinear prewarp as every 2nd-order builder above (g=tan(w/2)); the coefficient is the
// classic single-integrator one-pole form (Zavalishin 2.2) rather than SVF's two-integrator one.
void makeLowPass1(Biquad& q, float fc, float sr) {
    Biquad n;
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5);
    n.topology = Biquad::Topology::OnePoleLowpass;
    n.a1 = g / (1. + g);
    q.retune(n);
}
void makeHighPass1(Biquad& q, float fc, float sr) {
    Biquad n;
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5);
    n.topology = Biquad::Topology::OnePoleHighpass;
    n.a1 = g / (1. + g);
    q.retune(n);
}
// Identity pass-through. BW2's crossover only needs one 2nd-order stage; the second Biquad slot
// (crossover2) is forced inert here rather than skipped per-sample in processLowCrossover/
// processMidCrossover, so those stay branch-free on the audio-thread hot path.
void makeIdentity(Biquad& q) {
    Biquad n;
    n.topology = Biquad::Topology::Svf2;
    n.a1 = n.a2 = n.a3 = 0;
    n.m0 = 1;
    n.m1 = 0;
    n.m2 = 0;
    q.retune(n);
}
// fc clamp matches makeLowPass/makeHighPass/makeAllPass2: every coefficient builder in this file
// keeps its corner inside [20 Hz, 0.49*sr] so w stays well below pi -- g=tan(w/2) then stays
// large-but-finite instead of approaching the asymptote at w/2=pi/2 (fc -> Nyquist). Callers
// already clamp (tilt freq is [200,2000]); this is belt-and-braces so a future caller can't feed
// it a near-Nyquist corner and get non-finite coefficients with no identity fallback.
void makeLowShelf(Biquad& q, float fc, float gainDb, float sr) {
    Biquad n;
    double A = std::pow(10., static_cast<double>(gainDb) / 40.),
           w = 2 * PI * clampf(fc, 20.f, sr * .49f) / sr, g = std::tan(w * .5) / std::sqrt(A),
           k = 1. / BW;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    n.topology = Biquad::Topology::Svf2;
    n.a1 = a1;
    n.a2 = a2;
    n.a3 = a3;
    n.m0 = 1;
    n.m1 = k * (A - 1);
    n.m2 = A * A - 1;
    q.retune(n);
}
void makeHighShelf(Biquad& q, float fc, float gainDb, float sr) {
    Biquad n;
    double A = std::pow(10., static_cast<double>(gainDb) / 40.),
           w = 2 * PI * clampf(fc, 20.f, sr * .49f) / sr, g = std::tan(w * .5) * std::sqrt(A),
           k = 1. / BW;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    n.topology = Biquad::Topology::Svf2;
    n.a1 = a1;
    n.a2 = a2;
    n.a3 = a3;
    n.m0 = A * A;
    n.m1 = k * (1 - A) * A;
    n.m2 = 1 - A * A;
    q.retune(n);
}
// SVF all-pass, Q = 1/sqrt(2). Unity magnitude everywhere, -360 deg phase sweep through fc.
// Matches NativeBmwRouting::AllPassSection::rebuild's second-order branch; used only by the MBC
// tree.
void makeAllPass2(Biquad& q, float fc, float sr) {
    Biquad n;
    double w = 2 * PI * clampf(fc, 20, sr * .49f) / sr, g = std::tan(w * .5), k = 1. / BW;
    const auto [a1, a2, a3] = NativeBmwRouting::svfCore(g, k);
    n.topology = Biquad::Topology::Svf2;
    n.a1 = a1;
    n.a2 = a2;
    n.a3 = a3;
    n.m0 = 1;
    n.m1 = -2 * k;
    n.m2 = 0;
    q.retune(n);
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
    Biquad n;
    n.topology = Biquad::Topology::Svf2;
    n.a1 = a1;
    n.a2 = a2;
    n.a3 = a3;
    n.m0 = m0;
    n.m1 = m1;
    n.m2 = m2;
    q.retune(n);
    return true;
}
bool peqBandSkipped(int type, double gain) {
    return type != 3 && std::fabs(gain) < 1e-9;
}

}  // namespace NativeBmwDsp
