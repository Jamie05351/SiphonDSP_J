#ifndef SIPHONDSP_NATIVE_BMW_VIRTUAL_H
#define SIPHONDSP_NATIVE_BMW_VIRTUAL_H

// Virtual channels: sources derived from the stereo input that don't exist as physical outputs,
// processed on their own and fed back into the internal L/R chains ahead of routing. The head
// unit only has two physical outputs, so this is how a "centre" (or later any other virtual
// source) can be tuned apart from the rest of the stage. See docs/NATIVE_BMW_VIRTUAL_CHANNELS.md.
//
// Realtime: process() allocates nothing and takes no lock; every coefficient is (re)built from
// rebuild(), which only configure()/setSampleRate() call.

#include <array>
#include <atomic>
#include <cstddef>
#include "NativeBmwDelayGain.h"
#include "NativeBmwFilters.h"
#include "NativeBmwRouting.h"

namespace NativeBmwDsp {

/** The virtual sources. Only the centre exists so far; a new source is a new value here. */
enum class VirtualSourceId : std::size_t {
    Centre = 0,
};
constexpr std::size_t kVirtualSourceCount = 1;

/** One source's feed into one internal side (Left = 0, Right = 1). */
struct VirtualFeedConfig {
    float gainDb = 0;
    float delayMs = 0;
    bool polarityInverted = false;
    NativeBmwRouting::AllPassSection allPass{};
};

/** The parsed virtual-stage config (v[266..286]); lives in Params. */
struct VirtualConfig {
    bool enabled = false;
    float detectHpfHz = 150, detectLpfHz = 8000;
    float attackMs = 10, releaseMs = 200;
    float centreLevelDb = 0, sideLevelDb = 0;
    std::array<VirtualFeedConfig, 2> centreFeed{};
};

/** gain -> polarity -> fractional delay -> optional all-pass. Identity at its defaults. */
struct VirtualFeed {
    float gain = 1;
    bool inverted = false;
    bool allPassOn = false;
    AlignmentDelay<kStageDelayCapacity> delay;  // setDelayNoLatency(): summed with the undelayed side
    Biquad allPass;

    /** [keepState]: carry the all-pass history over (see rebuildKeepingState). */
    void rebuild(VirtualFeedConfig& cfg, float sampleRate, bool keepState);
    float run(float x) {
        x *= inverted ? -gain : gain;
        x = delay.run(x);
        return allPassOn ? allPass.run(x) : x;
    }
    void clear() {
        delay.clear();
        allPass.clear();
    }
};

/**
 * Zero-latency adaptive centre extractor. A band-limited detector sidechain measures how
 * correlated L and R are; the centre is w * (L + R) / 2 with w = 2 E[LR] / (E[L^2] + E[R^2])
 * clamped to [0, 1] and attack/release smoothed. The audio itself is never filtered, so
 * (L - C) + C reconstructs the input exactly.
 */
struct CentreExtractor {
    Biquad hpL, hpR, lpL, lpR;
    double powerLL = 0, powerRR = 0, powerLR = 0;
    double weight = 0;
    double powerCoef = 0, attackCoef = 0, releaseCoef = 0;

    void rebuild(const VirtualConfig& cfg, float sampleRate, bool keepState);
    /** Returns the centre sample for (l, r); both must be finite. */
    float run(float l, float r);
    void clear();
};

class VirtualSourceStage {
public:
    /** Rebuilds coefficients from [cfg] (all-pass sections are degraded to identity in place if
     *  they can't be built). Keeps filter/delay state unless [clearState]. */
    void rebuild(VirtualConfig& cfg, float sampleRate, bool clearState);
    void clear();
    /** Extracts, feeds and re-sums in place. Only called while the stage is enabled. */
    void process(float& l, float& r);

    /** Lock-free meter: [centreWeight 0..1, centreRmsDb]. */
    void readMeter(float* values, std::size_t count) const;
    /** Publishes the idle meter values (the stage just got disabled). */
    void zeroMeter();

private:
    CentreExtractor centre_;
    std::array<VirtualFeed, 2> centreFeed_{};
    float centreGain_ = 1, sideGain_ = 1;
    double centrePower_ = 0, meterCoef_ = 0;
    unsigned meterCountdown_ = 0;
    std::atomic<float> meterWeight_{0.f};
    std::atomic<float> meterCentreDb_{-60.f};
};

}  // namespace NativeBmwDsp

#endif  // SIPHONDSP_NATIVE_BMW_VIRTUAL_H
