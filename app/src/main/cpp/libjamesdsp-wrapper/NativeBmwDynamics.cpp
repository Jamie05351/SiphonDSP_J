#include "NativeBmwDynamics.h"
#include <algorithm>
#include <cmath>
#include "NativeBmwDspMath.h"

namespace NativeBmwDsp {

// ---- Per-output compressor ---------------------------------------------------------------------
void DetectorTiming::rebuild(float sampleRate) {
    rmsMix = 1 - std::exp(-1 / (.050f * sampleRate));
    peakRelease = std::exp(-1 / (.080f * sampleRate));
}
void rebuildCompressorTiming(const CompressorParams& p, CompressorState& s, float sampleRate) {
    s.attackMix = 1 - std::exp(-1 / (p.attack * .001f * sampleRate));
    s.releaseMix = 1 - std::exp(-1 / (p.release * .001f * sampleRate));
}
void resetCompressorState(CompressorState& s) {
    s.gain = 1;
    s.rmsPower = s.peakEnv = 0;
    s.meterCounter = 0;
    s.inputDb.store(-60);
    s.outputDb.store(-60);
    s.gainReductionDb.store(0);
}
void publishIdleMeter(CompressorState& s) {
    if ((++s.meterCounter & 255u) == 0) {
        s.inputDb.store(-60);
        s.outputDb.store(-60);
        s.gainReductionDb.store(0);
    }
}
void processCompressor(float& sample, const CompressorParams& p, CompressorState& s,
                       const DetectorTiming& detector) {
    float pk = std::fabs(sample);
    bool pub = (++s.meterCounter & 255u) == 0;
    float db = 20 * std::log10(std::max(pk, 1e-12f));
    if (p.enabled) {
        s.rmsPower = ftz(s.rmsPower + (pk * pk - s.rmsPower) * detector.rmsMix);
        s.peakEnv = pk > s.peakEnv ? pk : ftz(s.peakEnv * detector.peakRelease);
        float det = std::max(std::sqrt(std::max(0.f, s.rmsPower)), s.peakEnv * .5f);
        db = 20 * std::log10(std::max(det, 1e-12f));
        float gr = softKneeGainReductionDb(db - p.threshold, p.ratio, p.knee);
        float target = dbToLin(gr), mix = target < s.gain ? s.attackMix : s.releaseMix;
        s.gain = std::min(1.f, ftz(s.gain + (target - s.gain) * mix));
        sample *= s.gain * s.makeupLin;
    } else {
        s.gain = 1;
    }
    if (pub) {
        float red = -20 * std::log10(std::max(s.gain, 1e-12f));
        s.inputDb.store(clampf(db, -60, 6));
        s.outputDb.store(clampf(db - red + (p.enabled ? p.makeup : 0), -60, 6));
        s.gainReductionDb.store(clampf(red, 0, 60));
    }
}

// ---- Master brick-wall limiter -----------------------------------------------------------------
void MasterLimiter::rebuild(float sampleRate, float thresholdDb) {
    float lookahead = clampf(kLookaheadMs * sampleRate * .001f, 0.f,
                             static_cast<float>(kDelayLineCapacity - 1));
    delayL_.delay = lookahead;
    delayR_.delay = lookahead;
    float attackSeconds = (kLookaheadMs * .001f) / 5.f;
    attackMix_ = 1 - std::exp(-1 / (attackSeconds * sampleRate));
    float releaseSeconds = .080f;
    releaseMix_ = 1 - std::exp(-1 / (releaseSeconds * sampleRate));
    ceilingLin_ = dbToLin(clampf(thresholdDb, -12.f, 0.f));
    grDb_.store(0.f);
}
void MasterLimiter::clear() {
    delayL_.clear();
    delayR_.clear();
    gain_ = 1;
    grDb_.store(0.f);
    meterCounter_ = 0;
}
void MasterLimiter::process(float& l, float& r) {
    float pk = std::max(std::fabs(l), std::fabs(r));
    float target = pk > ceilingLin_ ? ceilingLin_ / pk : 1.f;
    float mix = target < gain_ ? attackMix_ : releaseMix_;
    gain_ = std::min(1.f, ftz(gain_ + (target - gain_) * mix));
    float dl = delayL_.run(l), dr = delayR_.run(r);
    l = ftz(dl * gain_);
    r = ftz(dr * gain_);
    if ((++meterCounter_ & 255u) == 0) {
        grDb_.store(-20.f * std::log10(std::max(gain_, 1e-12f)), std::memory_order_relaxed);
    }
}

// ---- Per-bus brick-wall limiter ----------------------------------------------------------------
void BusLimiter::process(float& l, float& r, float thresholdDb, float attackMix) {
    const float ceilingLin = dbToLin(thresholdDb);
    const float pk = std::max(std::fabs(l), std::fabs(r));
    const float target = pk > ceilingLin ? ceilingLin / pk : 1.f;
    const float mix = target < gain ? attackMix : releaseMix;
    gain = std::min(1.f, ftz(gain + (target - gain) * mix));
    grDb.store(-20.f * std::log10(std::max(gain, 1e-12f)), std::memory_order_relaxed);
    l = ftz(l * gain);
    r = ftz(r * gain);
}
void BusLimiter::reset() {
    if (gain != 1.f) {
        gain = 1.f;
        grDb.store(0.f, std::memory_order_relaxed);
    }
}

// ---- Pre-crossover multiband compressor --------------------------------------------------------
void MultibandCompressor::Tree::clear() {
    lp0a.clear();
    lp0b.clear();
    hp0a.clear();
    hp0b.clear();
    lp1a.clear();
    lp1b.clear();
    hp1a.clear();
    hp1b.clear();
    lp2a.clear();
    lp2b.clear();
    hp2a.clear();
    hp2b.clear();
    apB0X1.clear();
    apB0X2.clear();
    apB1X2.clear();
}
void MultibandCompressor::rebuild(const float (&splitsHz)[3], float mix, const MbcBands& bands,
                                  float sampleRate) {
    // LR4 tree crossovers + the all-pass compensators for the lower bands. Splits are forced
    // monotonic with a little headroom so a mis-ordered config can't collapse a band to nothing
    // (configure() also now sorts the splits ascending before they ever reach here). Each stage's
    // lower bound is itself clamped against the ceiling: without that, a low sample rate plus an
    // f0/f1 already close to the ceiling can make the naive lower bound (f0*1.05f) exceed the
    // ceiling, and clampf(x, lo, hi) with lo>hi returns lo -- silently landing past the
    // documented sampleRate*.45f cap instead of at it. f0/f1's own upper bound is likewise pulled
    // in by the 5% steps still owed to the stages above them, so a low sample rate can't pin two
    // adjacent splits to the same ceiling value and collapse the band between them to zero width.
    const float ceiling = sampleRate * .45f;
    const float f1Ceiling = ceiling / 1.05f;
    const float f0Ceiling = f1Ceiling / 1.05f;
    const float f0 = clampf(splitsHz[0], 20.f, f0Ceiling);
    const float f1 = clampf(splitsHz[1], std::min(f0 * 1.05f, f1Ceiling), f1Ceiling);
    const float f2 = clampf(splitsHz[2], std::min(f1 * 1.05f, ceiling), ceiling);
    // Both channels get the same filters: build each one as a Biquad, then load it into both
    // lanes of its SvfPair (loadBoth also clears that section's state).
    auto lowPass = [sampleRate](SvfPair& s, float fc) {
        Biquad q;
        makeLowPass(q, fc, BW, sampleRate);
        s.loadBoth(q);
    };
    auto highPass = [sampleRate](SvfPair& s, float fc) {
        Biquad q;
        makeHighPass(q, fc, BW, sampleRate);
        s.loadBoth(q);
    };
    auto allPass = [sampleRate](SvfPair& s, float fc) {
        Biquad q;
        makeAllPass2(q, fc, sampleRate);
        s.loadBoth(q);
    };
    auto& t = tree_;
    t.clear();  // explicit -- loadBoth below also clears each section it touches, so this is
                // belt-and-braces, but it makes the state-reset intent visible here rather than
                // relying on a side effect three lines down.
    lowPass(t.lp0a, f0);
    lowPass(t.lp0b, f0);
    highPass(t.hp0a, f0);
    highPass(t.hp0b, f0);
    lowPass(t.lp1a, f1);
    lowPass(t.lp1b, f1);
    highPass(t.hp1a, f1);
    highPass(t.hp1b, f1);
    lowPass(t.lp2a, f2);
    lowPass(t.lp2b, f2);
    highPass(t.hp2a, f2);
    highPass(t.hp2b, f2);
    // band 0 (below f0) picks up the phase of the f1 and f2 splits; band 1 (f0..f1) that of f2.
    allPass(t.apB0X1, f1);
    allPass(t.apB0X2, f2);
    allPass(t.apB1X2, f2);
    rebuildTiming(mix, bands, sampleRate);
}
void MultibandCompressor::rebuildTiming(float mix, const MbcBands& bands, float sampleRate) {
    // threshold/ratio/knee are read live in bandGain() -- only these smoothing scalars are
    // precomputed, and none of it touches the crossover filter state.
    mix_ = clampf(mix, 0.f, 1.f);
    for (int b = 0; b < kMbcBandCount; ++b) {
        makeupLin_[b] = dbToLin(bands[b].makeup);
        attackMix_[b] = 1 - std::exp(-1 / (std::max(1.f, bands[b].attack) * .001f * sampleRate));
        releaseMix_[b] =
            1 - std::exp(-1 / (std::max(1.f, bands[b].release) * .001f * sampleRate));
    }
}
void MultibandCompressor::resetState() {
    tree_.clear();
    for (auto& row : cell_) {
        for (auto& c : row) {
            c.rms = 0;
            c.peak = 0;
            c.gain = 1;
            c.lastDetectorDb = -60.f;
        }
    }
    for (auto& m : meter_) {
        m.inputDb.store(-60.f);
        m.outputDb.store(-60.f);
        m.gainReductionDb.store(0.f);
    }
    meterCounter_ = 0;
}
float MultibandCompressor::bandGain(float peakAbs, const MbcBandParams& p, Cell& s, int band,
                                    const DetectorTiming& detector) {
    s.rms = ftz(s.rms + (peakAbs * peakAbs - s.rms) * detector.rmsMix);
    s.peak = peakAbs > s.peak ? peakAbs : ftz(s.peak * detector.peakRelease);
    float det = std::max(std::sqrt(std::max(0.f, s.rms)), s.peak * .5f);
    float db = 20 * std::log10(std::max(det, 1e-12f));
    s.lastDetectorDb = db;
    float gr = softKneeGainReductionDb(db - p.threshold, p.ratio, p.knee);
    float target = dbToLin(gr), mix = target < s.gain ? attackMix_[band] : releaseMix_[band];
    s.gain = std::min(1.f, ftz(s.gain + (target - s.gain) * mix));
    return s.gain;
}
void MultibandCompressor::process(float& l, float& r, const MbcBands& bands,
                                  const DetectorTiming& detector) {
    const float dryL = l, dryR = r;
    float band[2][4];
    {
        // Split tree, L and R together: each SvfPair::run is one NEON pass on arm64. Each
        // section sees the same input sequence per channel as the old per-channel trees did.
        auto& t = tree_;
        float low0L = l, low0R = r;
        t.lp0a.run(low0L, low0R);
        t.lp0b.run(low0L, low0R);
        float rest0L = l, rest0R = r;
        t.hp0a.run(rest0L, rest0R);
        t.hp0b.run(rest0L, rest0R);
        float low1L = rest0L, low1R = rest0R;
        t.lp1a.run(low1L, low1R);
        t.lp1b.run(low1L, low1R);
        float rest1L = rest0L, rest1R = rest0R;
        t.hp1a.run(rest1L, rest1R);
        t.hp1b.run(rest1L, rest1R);
        float low2L = rest1L, low2R = rest1R;
        t.lp2a.run(low2L, low2R);
        t.lp2b.run(low2L, low2R);
        float high2L = rest1L, high2R = rest1R;
        t.hp2a.run(high2L, high2R);
        t.hp2b.run(high2L, high2R);
        t.apB0X1.run(low0L, low0R);
        t.apB0X2.run(low0L, low0R);
        t.apB1X2.run(low1L, low1R);
        band[0][0] = low0L;
        band[1][0] = low0R;
        band[0][1] = low1L;
        band[1][1] = low1R;
        band[0][2] = low2L;
        band[1][2] = low2R;
        band[0][3] = high2L;
        band[1][3] = high2R;
    }
    const bool publishMeter = (++meterCounter_ & 255u) == 0;
    float wetL = 0, wetR = 0;
    for (int b = 0; b < kMbcBandCount; ++b) {
        const auto& bp = bands[b];
        float sL = band[0][b], sR = band[1][b];
        float meterDb, reductionDb;
        if (bp.enabled) {
            float gWorst;
            if (bp.stereoLink) {
                const float g =
                    bandGain(std::max(std::fabs(sL), std::fabs(sR)), bp, cell_[0][b], b, detector);
                sL *= g;
                sR *= g;
                meterDb = cell_[0][b].lastDetectorDb;
                gWorst = g;
            } else {
                const float gL = bandGain(std::fabs(sL), bp, cell_[0][b], b, detector);
                const float gR = bandGain(std::fabs(sR), bp, cell_[1][b], b, detector);
                sL *= gL;
                sR *= gR;
                meterDb = std::max(cell_[0][b].lastDetectorDb, cell_[1][b].lastDetectorDb);
                gWorst = std::min(gL, gR);
            }
            sL *= makeupLin_[b];
            sR *= makeupLin_[b];
            reductionDb = -20.f * std::log10(std::max(gWorst, 1e-12f));
        } else {
            // Disabled band: meter still shows the band's input level (no reduction), same spirit as
            // processCompressor's idle publish.
            meterDb = 20.f * std::log10(std::max(std::max(std::fabs(sL), std::fabs(sR)), 1e-12f));
            reductionDb = 0.f;
        }
        if (publishMeter) {
            auto& m = meter_[b];
            m.inputDb.store(clampf(meterDb, -60.f, 6.f));
            m.gainReductionDb.store(clampf(reductionDb, 0.f, 60.f));
            m.outputDb.store(
                clampf(meterDb - reductionDb + (bp.enabled ? bp.makeup : 0.f), -60.f, 6.f));
        }
        wetL += sL;
        wetR += sR;
    }
    l = ftz(dryL * (1.f - mix_) + wetL * mix_);
    r = ftz(dryR * (1.f - mix_) + wetR * mix_);
}
void MultibandCompressor::readMeter(float* v, bool active) const {
    for (int b = 0; b < kMbcBandCount; ++b) {
        const auto& m = meter_[b];
        v[b * 3 + 0] = active ? m.inputDb.load() : -60.f;
        v[b * 3 + 1] = active ? m.outputDb.load() : -60.f;
        v[b * 3 + 2] = active ? m.gainReductionDb.load() : 0.f;
    }
}

}  // namespace NativeBmwDsp
