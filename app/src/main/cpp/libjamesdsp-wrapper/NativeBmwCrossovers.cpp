#include "NativeBmwCrossovers.h"
#include <cmath>
#include <algorithm>
#include "NativeBmwDspMath.h"

namespace NativeBmwDsp {

void buildSubsonic(OutputRuntime& out, const OutputConfig& cfg, float sampleRate) {
    makeHighPass(out.subsonic1, cfg.subsonicFreq, BW, sampleRate);
    makeHighPass(out.subsonic2, cfg.subsonicFreq, BW, sampleRate);
}
float spreadCorner(float fc, float k, float sampleRate) {
    if (k == 1.f || !(sampleRate > 0.f)) {
        return fc;
    }
    constexpr double kPi = 3.14159265358979323846;
    const double warped = std::tan(kPi * fc / sampleRate) * k;
    return static_cast<float>(std::atan(warped) * sampleRate / kPi);
}
CrossoverPairing pairCrossovers(const OutputConfig& low, const OutputConfig& mid,
                                const OutputConfig& high, bool lowMidRuns, bool midHighRuns) {
    using CrossoverType = OutputConfig::CrossoverType;
    CrossoverPairing p;
    auto spread = [](CrossoverType a, CrossoverType b, float fa, float fb) {
        const float ratio = fa > fb ? fa / fb : fb / fa;
        if (a != b || !(ratio <= kPairCornerTolerance)) {
            return 1.f;
        }
        return a == CrossoverType::Butterworth2 ? kBw2PairSpread
               : a == CrossoverType::Butterworth4 ? kBw4PairSpread
                                                  : 1.f;
    };
    if (lowMidRuns) {
        const float k = spread(low.crossoverType, mid.crossoverType, low.crossoverFreq, mid.crossoverFreq);
        p.lowLowpass = 1.f / k;
        p.midHighpass = k;
        p.midInverted = k == kBw2PairSpread;
    }
    bool midHighInverts = false;
    if (midHighRuns && mid.upperCrossoverEnabled) {
        const float k = spread(mid.upperCrossoverType, high.crossoverType, mid.upperCrossoverFreq,
                               high.crossoverFreq);
        p.midLowpass = 1.f / k;
        p.highHighpass = k;
        midHighInverts = k == kBw2PairSpread;
    }
    // High's polarity relative to Mid: inverted across a BW2 pair, the same otherwise.
    p.highInverted = p.midInverted != midHighInverts;
    return p;
}
void buildLowCrossover(OutputRuntime& out, const OutputConfig& cfg, float sampleRate) {
    using CrossoverType = OutputConfig::CrossoverType;
    switch (cfg.crossoverType) {
        case CrossoverType::Butterworth1:
            makeLowPass1(out.crossover1, cfg.crossoverFreq, sampleRate);
            makeIdentity(out.crossover2);
            break;
        case CrossoverType::Butterworth2:
            makeLowPass(out.crossover1, cfg.crossoverFreq, BW, sampleRate);
            makeIdentity(out.crossover2);
            break;
        case CrossoverType::Butterworth3:
            makeLowPass1(out.crossover1, cfg.crossoverFreq, sampleRate);
            makeLowPass(out.crossover2, cfg.crossoverFreq, kButterworth3Q, sampleRate);
            break;
        case CrossoverType::Butterworth4:
            makeLowPass(out.crossover1, cfg.crossoverFreq, kButterworth4QLow, sampleRate);
            makeLowPass(out.crossover2, cfg.crossoverFreq, kButterworth4QHigh, sampleRate);
            break;
        case CrossoverType::LinkwitzRiley4:
        default:
            makeLowPass(out.crossover1, cfg.crossoverFreq, BW, sampleRate);
            makeLowPass(out.crossover2, cfg.crossoverFreq, BW, sampleRate);
            break;
    }
}
void buildMidCrossover(OutputRuntime& out, const OutputConfig& cfg, float sampleRate) {
    using CrossoverType = OutputConfig::CrossoverType;
    switch (cfg.crossoverType) {
        case CrossoverType::Butterworth1:
            makeHighPass1(out.crossover1, cfg.crossoverFreq, sampleRate);
            makeIdentity(out.crossover2);
            break;
        case CrossoverType::Butterworth2:
            makeHighPass(out.crossover1, cfg.crossoverFreq, BW, sampleRate);
            makeIdentity(out.crossover2);
            break;
        case CrossoverType::Butterworth3:
            makeHighPass1(out.crossover1, cfg.crossoverFreq, sampleRate);
            makeHighPass(out.crossover2, cfg.crossoverFreq, kButterworth3Q, sampleRate);
            break;
        case CrossoverType::Butterworth4:
            makeHighPass(out.crossover1, cfg.crossoverFreq, kButterworth4QLow, sampleRate);
            makeHighPass(out.crossover2, cfg.crossoverFreq, kButterworth4QHigh, sampleRate);
            break;
        case CrossoverType::LinkwitzRiley4:
        default:
            makeHighPass(out.crossover1, cfg.crossoverFreq, BW, sampleRate);
            makeHighPass(out.crossover2, cfg.crossoverFreq, BW, sampleRate);
            break;
    }
    // Optional upper (Mid/High) corner -- turns Mid from HPF-only into a true bandpass.
    // Same slope table as the HPF pair above, mirrored as a lowpass, but driven by its own
    // upperCrossoverType, and cascaded after it in processMidCrossover(). Forced to identity when disabled (rather than
    // skipped per-sample) so processMidCrossover stays branch-free on the audio-thread hot
    // path -- same pattern as crossover2 being forced inert for BW1/BW2 above.
    if (!cfg.upperCrossoverEnabled) {
        makeIdentity(out.crossover3);
        makeIdentity(out.crossover4);
        return;
    }
    switch (cfg.upperCrossoverType) {
        case CrossoverType::Butterworth1:
            makeLowPass1(out.crossover3, cfg.upperCrossoverFreq, sampleRate);
            makeIdentity(out.crossover4);
            break;
        case CrossoverType::Butterworth2:
            makeLowPass(out.crossover3, cfg.upperCrossoverFreq, BW, sampleRate);
            makeIdentity(out.crossover4);
            break;
        case CrossoverType::Butterworth3:
            makeLowPass1(out.crossover3, cfg.upperCrossoverFreq, sampleRate);
            makeLowPass(out.crossover4, cfg.upperCrossoverFreq, kButterworth3Q, sampleRate);
            break;
        case CrossoverType::Butterworth4:
            makeLowPass(out.crossover3, cfg.upperCrossoverFreq, kButterworth4QLow, sampleRate);
            makeLowPass(out.crossover4, cfg.upperCrossoverFreq, kButterworth4QHigh, sampleRate);
            break;
        case CrossoverType::LinkwitzRiley4:
        default:
            makeLowPass(out.crossover3, cfg.upperCrossoverFreq, BW, sampleRate);
            makeLowPass(out.crossover4, cfg.upperCrossoverFreq, BW, sampleRate);
            break;
    }
}
void buildHighCrossover(OutputRuntime& out, const OutputConfig& cfg, float sampleRate) {
    // HPF-only, same shape as buildMidCrossover() before Phase 2 added its optional lowpass
    // pair -- High is the top band, nothing above it to band-limit against.
    using CrossoverType = OutputConfig::CrossoverType;
    switch (cfg.crossoverType) {
        case CrossoverType::Butterworth1:
            makeHighPass1(out.crossover1, cfg.crossoverFreq, sampleRate);
            makeIdentity(out.crossover2);
            break;
        case CrossoverType::Butterworth2:
            makeHighPass(out.crossover1, cfg.crossoverFreq, BW, sampleRate);
            makeIdentity(out.crossover2);
            break;
        case CrossoverType::Butterworth3:
            makeHighPass1(out.crossover1, cfg.crossoverFreq, sampleRate);
            makeHighPass(out.crossover2, cfg.crossoverFreq, kButterworth3Q, sampleRate);
            break;
        case CrossoverType::Butterworth4:
            makeHighPass(out.crossover1, cfg.crossoverFreq, kButterworth4QLow, sampleRate);
            makeHighPass(out.crossover2, cfg.crossoverFreq, kButterworth4QHigh, sampleRate);
            break;
        case CrossoverType::LinkwitzRiley4:
        default:
            makeHighPass(out.crossover1, cfg.crossoverFreq, BW, sampleRate);
            makeHighPass(out.crossover2, cfg.crossoverFreq, BW, sampleRate);
            break;
    }
}
void rebuildAllPass(OutputRuntime& out, float sampleRate) {
    for (std::size_t i = 0; i < out.allPass.size(); ++i) {
        (void)out.allPass[i].rebuild(sampleRate);
        out.allPassState[i].loadAllPass(out.allPass[i].coefficients);
    }
}

// The three crossover chains below run a band's left and right outputs stage by stage through
// Biquad::runPair() -- one NEON pass per stage when both sides are Svf2 (BW1/BW3's one-pole
// stages fall back to scalar inside runPair). Each side still sees its own stages in its own
// order; L and R share no state, so this is identical to running each side's chain alone.
void processLowCrossover(OutputRuntime& left, const OutputConfig& leftConfig, OutputRuntime& right,
                         const OutputConfig& rightConfig, float& xl, float& xr) {
    if (leftConfig.subsonicEnabled && rightConfig.subsonicEnabled) {
        Biquad::runPair(left.subsonic1, right.subsonic1, xl, xr);
        Biquad::runPair(left.subsonic2, right.subsonic2, xl, xr);
    } else if (leftConfig.subsonicEnabled) {
        xl = left.subsonic2.run(left.subsonic1.run(xl));
    } else if (rightConfig.subsonicEnabled) {
        xr = right.subsonic2.run(right.subsonic1.run(xr));
    }
    Biquad::runPair(left.crossover1, right.crossover1, xl, xr);
    Biquad::runPair(left.crossover2, right.crossover2, xl, xr);
}
void processMidCrossover(OutputRuntime& left, OutputRuntime& right, float& xl, float& xr) {
    // HPF pair (Low/Mid corner), then the optional LPF pair (Mid/High corner) -- crossover3/4
    // are forced to an identity pass-through by buildMidCrossover() when the upper corner is
    // disabled, so running them unconditionally here stays correct (and branch-free) either way.
    Biquad::runPair(left.crossover1, right.crossover1, xl, xr);
    Biquad::runPair(left.crossover2, right.crossover2, xl, xr);
    Biquad::runPair(left.crossover3, right.crossover3, xl, xr);
    Biquad::runPair(left.crossover4, right.crossover4, xl, xr);
}
void processHighCrossover(OutputRuntime& left, OutputRuntime& right, float& xl, float& xr) {
    // HPF-only, mirrors processMidCrossover() before Phase 2 added its optional lowpass pair.
    Biquad::runPair(left.crossover1, right.crossover1, xl, xr);
    Biquad::runPair(left.crossover2, right.crossover2, xl, xr);
}

void Tilt::rebuild(float amountDb, float freqHz, float sampleRate) {
    float g = amountDb * .75f;
    makeLowShelf(loL1_, freqHz, g, sampleRate);
    makeLowShelf(loL2_, freqHz, g, sampleRate);
    makeHighShelf(hiL1_, freqHz, -g, sampleRate);
    makeHighShelf(hiL2_, freqHz, -g, sampleRate);
    makeLowShelf(loR1_, freqHz, g, sampleRate);
    makeLowShelf(loR2_, freqHz, g, sampleRate);
    makeHighShelf(hiR1_, freqHz, -g, sampleRate);
    makeHighShelf(hiR2_, freqHz, -g, sampleRate);
}
void Loudness::liftDb(float volumeDb, float strength, float& bassDb, float& trebleDb) {
    const float cut = std::max(0.f, -volumeDb);
    const float s = clampf(strength, 0.f, 1.f);
    bassDb = std::min(kBassMaxDb, kBassPerDb * cut) * s;
    trebleDb = std::min(kTrebleMaxDb, kTreblePerDb * cut) * s;
}
void Loudness::rebuild(float volumeDb, float strength, float sampleRate) {
    float bassDb, trebleDb;
    liftDb(volumeDb, strength, bassDb, trebleDb);
    makeLowShelf(bassL_, kBassHz, bassDb, sampleRate);
    makeLowShelf(bassR_, kBassHz, bassDb, sampleRate);
    makeHighShelf(trebleL_, kTrebleHz, trebleDb, sampleRate);
    makeHighShelf(trebleR_, kTrebleHz, trebleDb, sampleRate);
}
void Loudness::clear() {
    for (Biquad* b : {&bassL_, &bassR_, &trebleL_, &trebleR_}) {
        b->clear();
    }
}
void Loudness::process(float& l, float& r) {
    Biquad::runPair(bassL_, bassR_, l, r);
    Biquad::runPair(trebleL_, trebleR_, l, r);
}
void Tilt::clear() {
    for (Biquad* b : {&loL1_, &loL2_, &hiL1_, &hiL2_, &loR1_, &loR2_, &hiR1_, &hiR2_}) {
        b->clear();
    }
}
void Tilt::process(float& l, float& r) {
    // Same Lo1 -> Lo2 -> Hi1 -> Hi2 order per channel, L/R as NEON pairs.
    Biquad::runPair(loL1_, loR1_, l, r);
    Biquad::runPair(loL2_, loR2_, l, r);
    Biquad::runPair(hiL1_, hiR1_, l, r);
    Biquad::runPair(hiL2_, hiR2_, l, r);
}

void MonoBass::rebuild(float freqHz, float sampleRate) {
    const float fc = clampf(freqHz, kMinHz, kMaxHz);
    constexpr float kButterworthQ = 0.70710678f;
    makeLowPass(lp1_, fc, kButterworthQ, sampleRate);
    makeLowPass(lp2_, fc, kButterworthQ, sampleRate);
    makeHighPass(hpL1_, fc, kButterworthQ, sampleRate);
    makeHighPass(hpL2_, fc, kButterworthQ, sampleRate);
    makeHighPass(hpR1_, fc, kButterworthQ, sampleRate);
    makeHighPass(hpR2_, fc, kButterworthQ, sampleRate);
}
void MonoBass::clear() {
    for (Biquad* b : {&lp1_, &lp2_, &hpL1_, &hpL2_, &hpR1_, &hpR2_}) {
        b->clear();
    }
}
void MonoBass::process(float& l, float& r, float mix) {
    const float low = lp2_.run(lp1_.run((l + r) * .5f));
    float hl = l, hr = r;
    Biquad::runPair(hpL1_, hpR1_, hl, hr);
    Biquad::runPair(hpL2_, hpR2_, hl, hr);
    const float splitL = low + hl, splitR = low + hr;
    if (mix >= 1.f) {
        l = splitL;
        r = splitR;
    } else {
        l += (splitL - l) * mix;
        r += (splitR - r) * mix;
    }
}

void VirtualBass::rebuild(float cutHz, float sampleRate) {
    const float fc = clampf(cutHz, kMinHz, kMaxHz);
    constexpr float kButterworthQ = 0.70710678f;
    // 4th-order Butterworth as two sections; squared, an LR8.
    constexpr float kBw4QA = 0.54119610f, kBw4QB = 1.30656296f;
    // Its own floor: at a 25 Hz reach the octave below starts at 12.5 Hz, not 20.
    makeHighPass(inHp_, fc * kInputHpRatio, kButterworthQ, sampleRate, kMinHz * kInputHpRatio);
    const float inFc = fc * kInputRatio;
    makeLowPass(inLpA1_, inFc, kBw4QA, sampleRate);
    makeLowPass(inLpB1_, inFc, kBw4QB, sampleRate);
    makeLowPass(inLpA2_, inFc, kBw4QA, sampleRate);
    makeLowPass(inLpB2_, inFc, kBw4QB, sampleRate);
    makeHighPass(outHp1_, fc, kButterworthQ, sampleRate);
    makeHighPass(outHp2_, fc, kButterworthQ, sampleRate);
    makeLowPass(outLp1_, fc * kTopRatio, kButterworthQ, sampleRate);
    makeLowPass(outLp2_, fc * kTopRatio, kButterworthQ, sampleRate);
    makeHighPass(cutL_, fc * kRemovalRatio, kButterworthQ, sampleRate);
    makeHighPass(cutR_, fc * kRemovalRatio, kButterworthQ, sampleRate);
    release_ = std::exp(-1.f / (kReleaseSeconds * sampleRate));
    capRecovery_ = 1.f - std::exp(-1.f / (kCapRecoverySeconds * sampleRate));
}
void VirtualBass::clear() {
    for (Biquad* b : {&inHp_, &inLpA1_, &inLpB1_, &inLpA2_, &inLpB2_, &outHp1_, &outHp2_, &outLp1_, &outLp2_,
                      &cutL_, &cutR_}) {
        b->clear();
    }
    env_ = 0.f;
    peak_ = 0.f;
    capGain_ = 1.f;
}
void VirtualBass::process(float& l, float& r, float onMix, float removeMix, float gain, float character) {
    // As VirtualSourceStage / RoutingMatrix: a non-finite sample is silenced rather than let into
    // the envelope and filter state, where it would stick until the stage was cleared.
    if (!std::isfinite(l) || !std::isfinite(r)) {
        l = std::isfinite(l) ? l : 0.f;
        r = std::isfinite(r) ? r : 0.f;
    }
    // The sub band, and its envelope (instant attack, so |u| <= 1 without clipping a rising edge).
    const float sub = inLpB2_.run(inLpA2_.run(inLpB1_.run(inLpA1_.run(inHp_.run((l + r) * .5f)))));
    const float mag = std::fabs(sub);
    env_ = mag > env_ * release_ ? mag : env_ * release_;
    float harmonics = 0.f;
    // Below ~-120 dBFS there's nothing to extend (and dividing by it would only amplify noise).
    if (env_ > 1e-6f) {
        const float u = clampf(sub / env_, -1.f, 1.f);
        const float u2 = u * u;
        const float t2 = 2.f * u2 - 1.f;
        const float t3 = u * (4.f * u2 - 3.f);
        const float t4 = 8.f * u2 * (u2 - 1.f) + 1.f;
        const float c = clampf(character, 0.f, 1.f);
        const float w2 = kWarmW2 + (kPunchyW2 - kWarmW2) * c;
        const float w3 = kWarmW3 + (kPunchyW3 - kWarmW3) * c;
        const float w4 = kWarmW4 + (kPunchyW4 - kWarmW4) * c;
        harmonics = env_ * (w2 * t2 + w3 * t3 + w4 * t4);
    }
    // Keep only what the woofers can play: the T2 / T4 offsets and anything left below the cut go,
    // as does the top of the series.
    harmonics = outLp2_.run(outLp1_.run(outHp2_.run(outHp1_.run(harmonics))));

    float baseL = l, baseR = r;
    float hl = l, hr = r;
    Biquad::runPair(cutL_, cutR_, hl, hr);
    if (removeMix > 0.f) {
        baseL += (hl - baseL) * removeMix;
        baseR += (hr - baseR) * removeMix;
    }
    float add = gain * harmonics;
    // The peak cap. The output may rise at most (1 + rise x gain) over the recent stereo peak --
    // the allowance auto headroom makes, which bounds the level, not each channel against itself
    // (so a note panned hard to one side still gets its harmonics in both, as it always has) --
    // and where this sample's harmonics would carry either channel further, they're scaled down to
    // just reach it (a channel already past it on its own takes none). The gain drops at once and
    // recovers smoothly; it's the harmonics alone that duck, not the music, and only on the rare
    // peak where the added harmonics land in step with the note's own.
    peak_ = std::max(std::max(std::max(std::fabs(l), std::fabs(r)), std::max(std::fabs(baseL), std::fabs(baseR))),
                     peak_ * release_);
    const float rise = (removeMix > 0.f ? kPeakRiseRemoved : kPeakRiseKept) * std::max(gain, 0.f);
    const float limit = (1.f + rise) * peak_;
    auto allowed = [&](float base) {
        if (std::fabs(base + add) <= limit) return 1.f;
        if (std::fabs(base) >= limit) return 0.f;
        // base + g x add reaches +-limit on the side add pushes it toward.
        const float edge = add > 0.f ? limit : -limit;
        return clampf((edge - base) / add, 0.f, 1.f);
    };
    const float capTarget = std::min(allowed(baseL), allowed(baseR));
    capGain_ = std::min(capTarget, capGain_ + (1.f - capGain_) * capRecovery_);
    add *= capGain_;
    const float outL = baseL + add, outR = baseR + add;
    if (onMix >= 1.f) {
        l = outL;
        r = outR;
    } else {
        l += (outL - l) * onMix;
        r += (outR - r) * onMix;
    }
}

}  // namespace NativeBmwDsp
