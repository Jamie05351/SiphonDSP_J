#include "NativeBmwCrossovers.h"
#include "NativeBmwDspMath.h"

namespace NativeBmwDsp {

void buildSubsonic(OutputRuntime& out, const OutputConfig& cfg, float sampleRate) {
    makeHighPass(out.subsonic1, cfg.subsonicFreq, BW, sampleRate);
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
    // Same crossoverType table as the HPF pair above, mirrored as a lowpass and cascaded
    // after it in processMidCrossover(). Forced to identity when disabled (rather than
    // skipped per-sample) so processMidCrossover stays branch-free on the audio-thread hot
    // path -- same pattern as crossover2 being forced inert for BW1/BW2 above.
    if (!cfg.upperCrossoverEnabled) {
        makeIdentity(out.crossover3);
        makeIdentity(out.crossover4);
        return;
    }
    switch (cfg.crossoverType) {
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
    } else if (leftConfig.subsonicEnabled) {
        xl = left.subsonic1.run(xl);
    } else if (rightConfig.subsonicEnabled) {
        xr = right.subsonic1.run(xr);
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
void Tilt::process(float& l, float& r) {
    // Same Lo1 -> Lo2 -> Hi1 -> Hi2 order per channel, L/R as NEON pairs.
    Biquad::runPair(loL1_, loR1_, l, r);
    Biquad::runPair(loL2_, loR2_, l, r);
    Biquad::runPair(hiL1_, hiR1_, l, r);
    Biquad::runPair(hiL2_, hiR2_, l, r);
}

}  // namespace NativeBmwDsp
