#ifndef SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H
#define SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H

#include <algorithm>
#include <cmath>

// Native measurement signal generator: produces a log sweep (pink periodic noise lands in a
// follow-up) that NativeBmwDspProcessor::processFrame injects in place of the real audio input,
// pre-crossover, so a measurement run exercises the identical DC-blocker / input-PEQ / headroom /
// MBC / crossover path real playback does. Its own module, deliberately isolated from
// NativeBmwRouting.h -- signal generation has nothing to do with routing/mixing.
//
// Per-instance state only (sweep position, phase accumulator) -- never shared/static, same
// reasoning as every other per-channel filter in this engine. All math in double, matching the
// rest of the engine's SVF-era precision, not generated in float and upcast.
//
// configureSweep() is the control-rate call: it runs only on a dirty-flag transition (from
// NativeBmwDspProcessor::rebuildMeasGen(), itself only called from the control thread inside
// configure()/rebuildAll(), never per sample) and fully restarts the run from t=0 -- editing a
// parameter while a sweep is playing is expected to retrigger it, same as toggling it back on.
// nextSweepSample() is the only per-sample-frame call and does the minimum: derive the
// instantaneous sweep frequency, accumulate phase, apply the start/end fade, return one sample.
class NativeBmwMeasurementGenerator {
public:
    void configureSweep(double startHz, double endHz, double durationS, double levelLin,
                        double sampleRate) {
        startHz_ = std::max(1.0, startHz);
        endHz_ = std::max(1.0, endHz);
        durationS_ = std::max(0.05, durationS);
        levelLin_ = levelLin;
        sampleRate_ = std::max(1000.0, sampleRate);
        totalSamples_ = durationS_ * sampleRate_;
        // Fade a few ms at each end so the loop's own start/stop -- and the seam where one pass
        // wraps into the next -- don't click. Capped at half the run so a very short sweep can't
        // make the two fades overlap.
        fadeSamples_ = std::min(totalSamples_ * 0.5, kFadeSeconds * sampleRate_);
        // Exponential (log) sweep: instantaneous frequency f(t) = startHz * (endHz/startHz)^(t/T).
        // Standard log-sweep synthesis, not a naive linear chirp -- this is what gives a log sweep
        // its flat-per-octave energy distribution.
        logRatio_ = std::log(endHz_ / startHz_);
        sweepIndex_ = 0.0;
        phase_ = 0.0;
    }

    // One sample of the current sweep, looping continuously (retriggering at t=0 with the same
    // fade) until the caller stops calling this -- a fixed-duration sweep with no auto-repeat
    // would go silent while the user is still walking back to the microphone.
    double nextSweepSample() {
        if (totalSamples_ <= 0.0) {
            return 0.0;
        }
        const double t = sweepIndex_ / sampleRate_;
        const double instFreqHz = startHz_ * std::exp(logRatio_ * (t / durationS_));
        phase_ += 2.0 * kPi * instFreqHz / sampleRate_;
        if (phase_ >= 2.0 * kPi) {
            phase_ -= 2.0 * kPi;
        }

        double envelope = 1.0;
        if (sweepIndex_ < fadeSamples_) {
            envelope = sweepIndex_ / fadeSamples_;
        } else if (sweepIndex_ > totalSamples_ - fadeSamples_) {
            envelope = (totalSamples_ - sweepIndex_) / fadeSamples_;
        }

        sweepIndex_ += 1.0;
        if (sweepIndex_ >= totalSamples_) {
            sweepIndex_ = 0.0;
            phase_ = 0.0;  // avoid a discontinuity stacking with the fade at the loop seam
        }
        return levelLin_ * envelope * std::sin(phase_);
    }

private:
    static constexpr double kPi = 3.14159265358979323846;
    static constexpr double kFadeSeconds = 0.005;

    double startHz_ = 20.0, endHz_ = 20000.0, durationS_ = 10.0, levelLin_ = 0.0;
    double sampleRate_ = 48000.0, logRatio_ = 1.0;
    double totalSamples_ = 0.0, fadeSamples_ = 0.0, sweepIndex_ = 0.0, phase_ = 0.0;
};

#endif  // SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H
