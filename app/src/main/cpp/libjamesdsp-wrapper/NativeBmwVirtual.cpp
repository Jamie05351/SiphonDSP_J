#include "NativeBmwVirtual.h"

#include <algorithm>
#include <cmath>
#include "NativeBmwDspMath.h"

namespace NativeBmwDsp {

namespace {
// The detector's correlation estimate integrates over a fixed window -- long enough to cover a
// few periods at the 150 Hz default HPF, short enough to follow a vocal entering. attack/release
// then shape how the centre weight itself moves.
constexpr double kPowerWindowMs = 25.0;
// Meter: centre RMS smoothing, and how often (samples) the atomics are refreshed.
constexpr double kMeterWindowMs = 300.0;
constexpr unsigned kMeterInterval = 64;
constexpr double kButterworthQ = 0.70710678118654752;

double onePoleCoef(double ms, float sampleRate) {
    const double samples = ms * 0.001 * static_cast<double>(sampleRate);
    return samples > 0 ? std::exp(-1.0 / samples) : 0.0;
}
}  // namespace

void VirtualFeed::rebuild(VirtualFeedConfig& cfg, float sampleRate) {
    gain = dbToLin(cfg.gainDb);
    inverted = cfg.polarityInverted;
    delay.delay = delaySamples(cfg.delayMs, sampleRate, kStageDelayCapacity);
    // An all-pass that can't be built at this sample rate degrades to identity (rebuild() leaves
    // identity coefficients), the same policy as the per-output all-pass sections.
    (void)cfg.allPass.rebuild(sampleRate);
    allPassOn = cfg.allPass.enabled;
    allPass.loadAllPass(cfg.allPass.coefficients);
}

void CentreExtractor::rebuild(const VirtualConfig& cfg, float sampleRate) {
    // Keep the detector corners strictly inside (0, Nyquist) at any sample rate.
    const float nyquistSafe = sampleRate * 0.45f;
    const float hpf = std::min(cfg.detectHpfHz, nyquistSafe * 0.5f);
    const float lpf = std::min(cfg.detectLpfHz, nyquistSafe);
    makeHighPass(hpL, hpf, kButterworthQ, sampleRate);
    makeHighPass(hpR, hpf, kButterworthQ, sampleRate);
    makeLowPass(lpL, lpf, kButterworthQ, sampleRate);
    makeLowPass(lpR, lpf, kButterworthQ, sampleRate);
    powerCoef = onePoleCoef(kPowerWindowMs, sampleRate);
    attackCoef = onePoleCoef(cfg.attackMs, sampleRate);
    releaseCoef = onePoleCoef(cfg.releaseMs, sampleRate);
}

float CentreExtractor::run(float l, float r) {
    const double dl = lpL.run(hpL.run(l));
    const double dr = lpR.run(hpR.run(r));
    const double a = powerCoef, b = 1.0 - powerCoef;
    powerLL = NativeBmwRouting::flushDenormal(a * powerLL + b * dl * dl);
    powerRR = NativeBmwRouting::flushDenormal(a * powerRR + b * dr * dr);
    powerLR = NativeBmwRouting::flushDenormal(a * powerLR + b * dl * dr);
    const double total = powerLL + powerRR;
    // Silence (or a detector band with nothing in it) reads as "no centre", never 0/0.
    const double raw = total > 1e-12 ? std::clamp(2.0 * powerLR / total, 0.0, 1.0) : 0.0;
    const double coef = raw > weight ? attackCoef : releaseCoef;
    weight = NativeBmwRouting::flushDenormal(raw + coef * (weight - raw));
    return static_cast<float>(weight * 0.5 * (static_cast<double>(l) + static_cast<double>(r)));
}

void CentreExtractor::clear() {
    hpL.clear();
    hpR.clear();
    lpL.clear();
    lpR.clear();
    powerLL = powerRR = powerLR = 0;
    weight = 0;
}

void VirtualSourceStage::rebuild(VirtualConfig& cfg, float sampleRate, bool clearState) {
    centre_.rebuild(cfg, sampleRate);
    for (std::size_t side = 0; side < centreFeed_.size(); ++side) {
        centreFeed_[side].rebuild(cfg.centreFeed[side], sampleRate);
    }
    centreGain_ = dbToLin(cfg.centreLevelDb);
    sideGain_ = dbToLin(cfg.sideLevelDb);
    meterCoef_ = onePoleCoef(kMeterWindowMs, sampleRate);
    if (clearState) {
        clear();
    }
}

void VirtualSourceStage::clear() {
    centre_.clear();
    for (auto& feed : centreFeed_) {
        feed.clear();
    }
    centrePower_ = 0;
    meterCountdown_ = 0;
}

void VirtualSourceStage::process(float& l, float& r) {
    // Same graceful degradation as RoutingMatrix::process(): a non-finite input sample is
    // silenced rather than allowed into the detector state, where it would stick.
    if (!std::isfinite(l) || !std::isfinite(r)) {
        l = std::isfinite(l) ? l : 0.f;
        r = std::isfinite(r) ? r : 0.f;
    }
    const float c = centre_.run(l, r);
    const float residualL = l - c, residualR = r - c;
    const float feedL = centreFeed_[0].run(c), feedR = centreFeed_[1].run(c);
    l = ftz(sideGain_ * residualL + centreGain_ * feedL);
    r = ftz(sideGain_ * residualR + centreGain_ * feedR);

    centrePower_ = NativeBmwRouting::flushDenormal(
        meterCoef_ * centrePower_ + (1.0 - meterCoef_) * static_cast<double>(c) * c);
    if (meterCountdown_ == 0) {
        meterCountdown_ = kMeterInterval;
        const double db = centrePower_ > 1e-12 ? 10.0 * std::log10(centrePower_) : -60.0;
        meterWeight_.store(static_cast<float>(centre_.weight), std::memory_order_relaxed);
        meterCentreDb_.store(static_cast<float>(std::max(db, -60.0)), std::memory_order_relaxed);
    }
    --meterCountdown_;
}

void VirtualSourceStage::readMeter(float* values, std::size_t count) const {
    if (!values) {
        return;
    }
    if (count > 0) {
        values[0] = meterWeight_.load(std::memory_order_relaxed);
    }
    if (count > 1) {
        values[1] = meterCentreDb_.load(std::memory_order_relaxed);
    }
}

void VirtualSourceStage::zeroMeter() {
    meterWeight_.store(0.f, std::memory_order_relaxed);
    meterCentreDb_.store(-60.f, std::memory_order_relaxed);
}

}  // namespace NativeBmwDsp
