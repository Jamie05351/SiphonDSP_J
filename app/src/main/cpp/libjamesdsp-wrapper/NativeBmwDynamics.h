#ifndef SIPHONDSP_NATIVE_BMW_DYNAMICS_H
#define SIPHONDSP_NATIVE_BMW_DYNAMICS_H

// Dynamics: the per-output compressor, the master brick-wall limiter, the per-bus brick-wall
// limiters and the pre-crossover multiband compressor (MBC).

#include <array>
#include <atomic>
#include <cstdint>
#include "NativeBmwDelayGain.h"
#include "NativeBmwFilters.h"

namespace NativeBmwDsp {

// ---- Per-output compressor ---------------------------------------------------------------------
struct CompressorParams {
    bool enabled = false;
    float threshold = -12, ratio = 2, knee = 8, attack = 40, release = 250, makeup = 0;
};
struct CompressorState {
    float gain = 1, rmsPower = 0, peakEnv = 0, attackMix = 0, releaseMix = 0, makeupLin = 1;
    std::atomic<float> inputDb{-60.0f};
    std::atomic<float> outputDb{-60.0f};
    std::atomic<float> gainReductionDb{0.0f};
    uint32_t meterCounter = 0;
};
// RMS/peak detector smoothing shared by every per-output compressor and every MBC band.
struct DetectorTiming {
    float rmsMix = 0, peakRelease = 0;
    void rebuild(float sampleRate);
};
void rebuildCompressorTiming(const CompressorParams& params, CompressorState& state,
                             float sampleRate);
void resetCompressorState(CompressorState& state);
void publishIdleMeter(CompressorState& state);
void processCompressor(float& sample, const CompressorParams& params, CompressorState& state,
                       const DetectorTiming& detector);

// ---- Master brick-wall limiter (summed stereo output) ------------------------------------------
class MasterLimiter {
public:
    static constexpr float kLookaheadMs = 5.f;
    static constexpr float kDefaultCeilingLin = 0.891251f;  // -1 dBFS -- the default threshold
    // Lookahead/attack/release for this sample rate + ceiling from thresholdDb (clamped to
    // [-12, 0] dBFS). Resets the published meter; leaves the delay lines and gain alone.
    void rebuild(float sampleRate, float thresholdDb);
    // Delay lines, gain follower, meter and meter counter back to rest.
    void clear();
    void process(float& left, float& right);
    // Gain reduction (dB, >= 0), published every 256 frames by process().
    float grDb() const {
        return grDb_.load(std::memory_order_relaxed);
    }

private:
    Delay delayL_, delayR_;
    float gain_ = 1;
    float attackMix_ = 1, releaseMix_ = 1;
    // Live ceiling = dbToLin(threshold), refreshed by rebuild().
    float ceilingLin_ = kDefaultCeilingLin;
    std::atomic<float> grDb_{0.f};
    uint32_t meterCounter_ = 0;
};

// ---- Per-bus brick-wall limiter (Low / Mid / High) ---------------------------------------------
// Infinite ratio, fixed-fast attack, one stereo-linked gain follower. No lookahead -- the master
// limiter downstream already carries that. Threshold is read live per sample.
struct BusLimiter {
    float gain = 1.f;
    float releaseMix = 0.f;
    // Published gain reduction (dB, >= 0), for readBusLimiterMeter().
    std::atomic<float> grDb{0.f};
    void process(float& left, float& right, float thresholdDb, float attackMix);
    // Called whenever the limiter is skipped (disabled, or its band bypassed/silenced) so it
    // neither leaves the meter stuck on its last reading nor carries stale gain reduction into
    // the next time it runs -- which would fade that band back in over the release time.
    // Guarded so the steady skipped state costs no atomic store per sample.
    void reset();
};
// ~1 ms attack shared by all three bus limiters.
inline float busLimiterAttackMix(float sampleRate) {
    return 1 - std::exp(-1 / (.001f * sampleRate));
}
inline float busLimiterReleaseMix(float releaseMs, float sampleRate) {
    return 1 - std::exp(-1 / (std::max(20.f, releaseMs) * .001f * sampleRate));
}

// ---- Pre-crossover multiband compressor --------------------------------------------------------
struct MbcBandParams {
    bool enabled = false;
    float threshold = -24, ratio = 2, knee = 6, attack = 15, release = 150, makeup = 0;
    // true (default): one gain cell driven by max(|L|,|R|) -- keeps the stereo image put.
    // false: independent L/R detection + gain for this band.
    bool stereoLink = true;
};
enum : int { kMbcBandCount = 4 };
using MbcBands = MbcBandParams[kMbcBandCount];

class MultibandCompressor {
public:
    // Split-frequency filter coefficients (clears tree state), then rebuildTiming().
    void rebuild(const float (&splitsHz)[3], float mix, const MbcBands& bands, float sampleRate);
    // Dry/wet mix, per-band makeup + attack/release smoothing -- scalars only, no filter touch.
    void rebuildTiming(float mix, const MbcBands& bands, float sampleRate);
    // Detector cells + tree state + meters back to rest.
    void resetState();
    // Splits the stereo bus into 4 bands, compresses each, sums flat, blends dry/wet.
    void process(float& left, float& right, const MbcBands& bands, const DetectorTiming& detector);
    // 12 floats: 4 bands x [inputDb, outputDb, gainReductionDb]; idle values when !active.
    void readMeter(float* values, bool active) const;

private:
    // The 4-way Linkwitz-Riley split tree, both channels at once: every section is an SvfPair
    // (lane 0 = left chain, lane 1 = right chain), so each stage runs L and R in one NEON pass.
    // Serial: split @ f0, then the high side @ f1, then that high side @ f2. Each LP/HP is LR4 =
    // two cascaded Butterworth sections. ap* are 2nd-order all-passes that put the
    // already-separated lower bands through the same phase the later crossovers impart, so the
    // four bands sum back to flat magnitude (an all-pass overall) -- same fix pattern as Mono
    // Bass's Mid-side compensation.
    struct Tree {
        SvfPair lp0a, lp0b, hp0a, hp0b, lp1a, lp1b, hp1a, hp1b, lp2a, lp2b, hp2a, hp2b;
        SvfPair apB0X1, apB0X2, apB1X2;
        void clear();
    };
    struct Cell {
        float rms = 0, peak = 0, gain = 1, lastDetectorDb = -60.f;
    };
    // Per-band published meter, aggregated across L/R. Atomics (not a lock) so readMeter() can
    // be polled from the UI thread -- mirrors CompressorState's meter atomics.
    struct BandMeter {
        std::atomic<float> inputDb{-60.f};
        std::atomic<float> outputDb{-60.f};
        std::atomic<float> gainReductionDb{0.f};
    };
    // RMS+peak detector and soft-knee gain computer -- shares softKneeGainReductionDb with
    // processCompressor's per-output path (the DetectorTiming is shared too), just factored out
    // so a stereo-linked band can feed it one combined level. Returns the smoothed gain (<=1).
    float bandGain(float peakAbs, const MbcBandParams& p, Cell& cell, int band,
                   const DetectorTiming& detector);

    // cell_[ch][band]: detector + gain follower. When a band is stereo-linked only [0][band] is
    // used (fed by max(|L|,|R|)); unlinked uses [0]=left, [1]=right.
    Tree tree_{};
    Cell cell_[2][kMbcBandCount]{};
    std::array<BandMeter, kMbcBandCount> meter_{};
    uint32_t meterCounter_ = 0;
    float mix_ = 1.f;  // 0..1
    float makeupLin_[kMbcBandCount] = {1.f, 1.f, 1.f, 1.f};
    float attackMix_[kMbcBandCount] = {0.f, 0.f, 0.f, 0.f};
    float releaseMix_[kMbcBandCount] = {0.f, 0.f, 0.f, 0.f};
};

}  // namespace NativeBmwDsp

#endif
