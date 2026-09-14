#ifndef SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H
#define SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <random>
#include <vector>

// Native measurement signal generator: produces a log sweep or pink periodic noise test signal
// that NativeBmwDspProcessor::processFrame injects in place of the real audio input,
// pre-crossover, so a measurement run exercises the identical DC-blocker / input-PEQ / headroom /
// MBC / crossover path real playback does. Its own module, deliberately isolated from
// NativeBmwRouting.h -- signal generation has nothing to do with routing/mixing.
//
// Per-instance state only (sweep position, phase accumulator, the pink noise period buffer) --
// never shared/static, same reasoning as every other per-channel filter in this engine. All
// generation math is done in double, matching the rest of the engine's SVF-era precision, not
// generated in float and upcast; the pink noise period buffer itself is stored as float, matching
// how every other audio buffer in this engine is stored (only filter state/coefficients are kept
// in double).
//
// configureSweep()/configurePink() are the control-rate calls: each runs only on a dirty-flag
// transition (from NativeBmwDspProcessor::rebuildMeasGen(), itself only called from the control
// thread inside configure()/rebuildAll(), never per sample) and fully (re)starts the run --
// editing a parameter while a signal is playing is expected to retrigger it, same as toggling it
// back on. nextSweepSample()/nextPinkSample() are the only per-sample-frame calls and each does
// the minimum: no allocation, no per-sample trig beyond what the sweep's swept frequency itself
// requires.
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

    // Generates one period of pink noise (white noise through Paul Kellet's refined pinking
    // filter, a standard/public-domain 7-pole IIR -- same "well-known textbook formula" status as
    // the RBJ/SVF filter designs elsewhere in this engine) and stores it so nextPinkSample() can
    // loop it back sample-for-sample. Periodicity is the whole point -- it's what lets the capture
    // side average synchronously -- so the period is generated once, here, on the control thread,
    // never regenerated per sample or per loop iteration.
    void configurePink(double periodS, double levelLin, double sampleRate) {
        sampleRate_ = std::max(1000.0, sampleRate);
        const double clampedPeriodS = std::max(0.05, periodS);
        const std::size_t n =
            std::max<std::size_t>(1, static_cast<std::size_t>(clampedPeriodS * sampleRate_));
        pinkBuffer_.assign(n, 0.0f);
        pinkReadIndex_ = 0;

        // Paul Kellet's refined pinking filter: a fixed cascade of one-pole sections driven by
        // white noise. Filter state only exists for this one generation pass -- there's nothing to
        // carry between calls, unlike a live per-sample filter -- so it's a local, not a member.
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        std::mt19937 rng(std::random_device{}());
        std::uniform_real_distribution<double> dist(-1.0, 1.0);
        double sumSq = 0.0;
        std::vector<double> raw(n);
        for (std::size_t i = 0; i < n; ++i) {
            const double white = dist(rng);
            b0 = flushDenormal(0.99886 * b0 + white * 0.0555179);
            b1 = flushDenormal(0.99332 * b1 + white * 0.0750759);
            b2 = flushDenormal(0.96900 * b2 + white * 0.1538520);
            b3 = flushDenormal(0.86650 * b3 + white * 0.3104856);
            b4 = flushDenormal(0.55000 * b4 + white * 0.5329522);
            b5 = flushDenormal(-0.7616 * b5 - white * 0.0168980);
            const double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362;
            b6 = white * 0.115926;
            raw[i] = pink;
            sumSq += pink * pink;
        }
        // Normalize to the requested level as an RMS target (the usual convention for a noise
        // signal, unlike the sweep's peak-sine level) rather than relying on a fixed empirical gain
        // constant for the filter above -- the white noise realization differs every call, so a
        // fixed constant would only be approximately right.
        const double rms = std::sqrt(sumSq / static_cast<double>(n));
        const double scale = levelLin / std::max(rms, 1e-12);
        for (std::size_t i = 0; i < n; ++i) {
            pinkBuffer_[i] = static_cast<float>(raw[i] * scale);
        }
    }

    // One sample of the current pink noise period, looping back to the start every periodS
    // seconds. No allocation, no filtering -- the period was already fully generated by
    // configurePink().
    double nextPinkSample() {
        if (pinkBuffer_.empty()) {
            return 0.0;
        }
        const double s = pinkBuffer_[pinkReadIndex_];
        pinkReadIndex_ = (pinkReadIndex_ + 1) % pinkBuffer_.size();
        return s;
    }

private:
    static constexpr double kPi = 3.14159265358979323846;
    static constexpr double kFadeSeconds = 0.005;

    static double flushDenormal(double x) {
        return (!std::isfinite(x) || std::fabs(x) < 1e-30) ? 0.0 : x;
    }

    double startHz_ = 20.0, endHz_ = 20000.0, durationS_ = 10.0, levelLin_ = 0.0;
    double sampleRate_ = 48000.0, logRatio_ = 1.0;
    double totalSamples_ = 0.0, fadeSamples_ = 0.0, sweepIndex_ = 0.0, phase_ = 0.0;

    std::vector<float> pinkBuffer_;
    std::size_t pinkReadIndex_ = 0;
};

#endif  // SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H
