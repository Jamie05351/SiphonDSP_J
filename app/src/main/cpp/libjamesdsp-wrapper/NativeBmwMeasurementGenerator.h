#ifndef SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H
#define SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <random>
#include <vector>

// Native measurement signal generator: produces a log sweep, pink periodic noise, or a REW
// Acoustic Timing Reference sequence, that NativeBmwDspProcessor::processFrame injects in place
// of the real audio input, pre-crossover, so a measurement run exercises the identical
// DC-blocker / input-PEQ / headroom / MBC / crossover path real playback does. Its own module,
// deliberately isolated from NativeBmwRouting.h -- signal generation has nothing to do with
// routing/mixing.
//
// Per-instance state only (sweep position, phase accumulator, the pink noise period buffer, the
// timing-reference segment table) -- never shared/static, same reasoning as every other
// per-channel filter in this engine. All generation math is done in double, matching the rest of
// the engine's SVF-era precision, not generated in float and upcast; the pink noise period buffer
// itself is stored as float, matching how every other audio buffer in this engine is stored (only
// filter state/coefficients are kept in double).
//
// configureSweep()/configurePink()/configureTimingRef() are the control-rate calls: each runs
// only on a dirty-flag transition (from NativeBmwDspProcessor::rebuildMeasGen(), itself only
// called from the control thread inside configure()/rebuildAll(), never per sample) and fully
// (re)starts the run -- editing a parameter while a signal is playing is expected to retrigger
// it, same as toggling it back on. nextSweepSample()/nextPinkSample()/nextTimingRefSample() are
// the only per-sample-frame calls.
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

    // Builds the full REW Acoustic Timing Reference cycle: lead silence, a timing chirp, a gap,
    // a Mid-band log sweep, a gap, two timing chirps (with a longer gap between them), a gap, a
    // Low-band log sweep, a gap, two more chirps, a gap, a second Mid-band sweep, a gap, a final
    // chirp, trailing silence -- then loops. This whole geometry (which segments get one chirp
    // vs a chirp pair, every gap duration, the chirp's own shape) was measured directly from a
    // pair of the user's actual working REW measurement files (a combined-channel one and a
    // split-channel one), not from REW's own generation algorithm, which isn't published -- this
    // is a best-effort reproduction of a real, working file, not a verified bit-exact match, and
    // still needs validating against an actual REW measurement before being trusted for tuning.
    //
    // splitChannels selects which of the two measured file styles to reproduce:
    //  - false: chirp and sweep share both channels identically (matches the "combined" file).
    //  - true:  chirp-only on l (an electrical reference-loopback tap), sweep-only on r (the
    //           DUT/speaker/mic acoustic path) -- matches the "split" file, the more rigorous of
    //           the two designs.
    void configureTimingRef(double midStartHz, double midEndHz, double lowStartHz,
                            double lowEndHz, double sweepDurationS, double sweepLevelLin,
                            double chirpLevelLin, bool splitChannels, double sampleRate) {
        sampleRate_ = std::max(1000.0, sampleRate);
        midStartHz_ = std::max(1.0, midStartHz);
        midEndHz_ = std::max(1.0, midEndHz);
        midLogRatio_ = std::log(midEndHz_ / midStartHz_);
        lowStartHz_ = std::max(1.0, lowStartHz);
        lowEndHz_ = std::max(1.0, lowEndHz);
        lowLogRatio_ = std::log(lowEndHz_ / lowStartHz_);
        timingRefDurationS_ = std::max(0.05, sweepDurationS);
        timingRefLevelLin_ = sweepLevelLin;
        chirpLevelLin_ = chirpLevelLin;
        splitChannels_ = splitChannels;

        const double leadSamples = kLeadTrailSilenceS * sampleRate_;
        const double chirpSamples = kChirpDurationS * sampleRate_;
        const double sweepSamples = timingRefDurationS_ * sampleRate_;
        const double gapBeforeSweep = kGapBeforeSweepS * sampleRate_;
        const double gapAfterSweep = kGapAfterSweepS * sampleRate_;
        const double gapBetweenPair = kGapBetweenChirpPairS * sampleRate_;

        timingRefSegments_.clear();
        double pos = 0.0;
        auto push = [&](SegKind kind, double lengthSamples) {
            timingRefSegments_.push_back({kind, pos, pos + lengthSamples});
            pos += lengthSamples;
        };
        push(SegKind::Silence, leadSamples);
        push(SegKind::Chirp, chirpSamples);
        push(SegKind::Silence, gapBeforeSweep);
        push(SegKind::SweepMid, sweepSamples);
        push(SegKind::Silence, gapAfterSweep);
        push(SegKind::Chirp, chirpSamples);
        push(SegKind::Silence, gapBetweenPair);
        push(SegKind::Chirp, chirpSamples);
        push(SegKind::Silence, gapBeforeSweep);
        push(SegKind::SweepLow, sweepSamples);
        push(SegKind::Silence, gapAfterSweep);
        push(SegKind::Chirp, chirpSamples);
        push(SegKind::Silence, gapBetweenPair);
        push(SegKind::Chirp, chirpSamples);
        push(SegKind::Silence, gapBeforeSweep);
        push(SegKind::SweepMid, sweepSamples);
        push(SegKind::Silence, gapAfterSweep);
        push(SegKind::Chirp, chirpSamples);
        // Trailing silence matches the lead-in, so the cycle loops straight back into the same
        // silence gap it started with.
        push(SegKind::Silence, leadSamples);

        timingRefCycleSamples_ = pos;
        timingRefIndex_ = 0.0;
    }

    // One sample of the timing-reference cycle built by configureTimingRef(). Linear scan over
    // the (small, ~19-entry) segment table -- cheap, no allocation, and simpler/less error-prone
    // than hand-tracking which of 9 possible phases is active.
    void nextTimingRefSample(float& l, float& r) {
        if (timingRefCycleSamples_ <= 0.0 || timingRefSegments_.empty()) {
            l = 0.f;
            r = 0.f;
            return;
        }
        const double pos = timingRefIndex_;
        float sample = 0.f;
        bool isChirp = false;
        for (const auto& seg : timingRefSegments_) {
            if (pos >= seg.startSample && pos < seg.endSample) {
                const double local = pos - seg.startSample;
                const double len = seg.endSample - seg.startSample;
                switch (seg.kind) {
                    case SegKind::Chirp:
                        sample = chirpSample(local, len);
                        isChirp = true;
                        break;
                    case SegKind::SweepMid:
                        sample = logSweepSampleAt(local, len, midStartHz_, midLogRatio_);
                        break;
                    case SegKind::SweepLow:
                        sample = logSweepSampleAt(local, len, lowStartHz_, lowLogRatio_);
                        break;
                    case SegKind::Silence:
                        break;  // sample stays 0
                }
                break;
            }
        }

        timingRefIndex_ += 1.0;
        if (timingRefIndex_ >= timingRefCycleSamples_) {
            timingRefIndex_ = 0.0;
        }

        if (!splitChannels_) {
            l = sample;
            r = sample;
        } else {
            l = isChirp ? sample : 0.f;
            r = isChirp ? 0.f : sample;
        }
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

    // Shared closed-form envelope: a short linear fade in/out at the edges of a one-shot segment
    // of the given length, so entering/leaving silence at a segment boundary doesn't click. Same
    // kFadeSeconds budget as the plain sweep's own loop-seam fade.
    double edgeFadeEnvelope(double localIndex, double lengthSamples) const {
        const double fade = std::min(lengthSamples * 0.5, kFadeSeconds * sampleRate_);
        if (fade <= 0.0) return 1.0;
        if (localIndex < fade) return localIndex / fade;
        if (localIndex > lengthSamples - fade) return (lengthSamples - localIndex) / fade;
        return 1.0;
    }

    // Linear chirp, closed-form phase: f(t) = chirpStart + (chirpEnd-chirpStart)*t/T,
    // phase(t) = 2*pi*(chirpStart*t + (chirpEnd-chirpStart)*t^2/(2T)). Frequency range and
    // duration measured from real reference files -- see configureTimingRef()'s comment.
    float chirpSample(double localIndex, double lengthSamples) const {
        if (lengthSamples <= 0.0) return 0.f;
        const double t = localIndex / sampleRate_;
        const double span = kChirpEndHz - kChirpStartHz;
        const double phase = 2.0 * kPi * (kChirpStartHz * t + span * t * t / (2.0 * kChirpDurationS));
        return static_cast<float>(chirpLevelLin_ * edgeFadeEnvelope(localIndex, lengthSamples) *
                                   std::sin(phase));
    }

    // Log sweep, closed-form phase (Farina/ESS): phase(t) = 2*pi*startHz*T/L * (e^(L*t/T) - 1).
    // Guarded for L==0 (startHz==endHz, a constant-frequency "sweep") via the formula's own
    // well-defined limit as L->0, phase(t) -> 2*pi*startHz*t -- avoids a 0/0 at that edge case.
    // Uses timingRefDurationS_/timingRefLevelLin_ (shared across the Mid/Low segments, set by
    // configureTimingRef()) -- startHz and logRatio are passed in since Mid and Low each have
    // their own band. Evaluated directly from a local sample position rather than accumulated,
    // since each segment only ever runs once per cycle (no internal loop seam to manage).
    float logSweepSampleAt(double localIndex, double lengthSamples, double startHz,
                           double logRatio) const {
        if (lengthSamples <= 0.0) return 0.f;
        const double t = localIndex / sampleRate_;
        const double phase = (std::fabs(logRatio) < 1e-9)
            ? 2.0 * kPi * startHz * t
            : 2.0 * kPi * startHz * timingRefDurationS_ / logRatio *
                  (std::exp(logRatio * t / timingRefDurationS_) - 1.0);
        return static_cast<float>(timingRefLevelLin_ * edgeFadeEnvelope(localIndex, lengthSamples) *
                                   std::sin(phase));
    }

    double startHz_ = 20.0, endHz_ = 20000.0, durationS_ = 10.0, levelLin_ = 0.0;
    double sampleRate_ = 48000.0, logRatio_ = 1.0;
    double totalSamples_ = 0.0, fadeSamples_ = 0.0, sweepIndex_ = 0.0, phase_ = 0.0;

    std::vector<float> pinkBuffer_;
    std::size_t pinkReadIndex_ = 0;

    // Acoustic Timing Reference cycle geometry -- see configureTimingRef()'s comment. Measured
    // from real REW measurement files, not from REW's own (unpublished) algorithm.
    static constexpr double kChirpStartHz = 5000.0, kChirpEndHz = 20000.0;
    static constexpr double kChirpDurationS = 0.340813;
    static constexpr double kGapBeforeSweepS = 0.32, kGapAfterSweepS = 1.38;
    static constexpr double kGapBetweenChirpPairS = 5.80;
    static constexpr double kLeadTrailSilenceS = 2.9016;

    enum class SegKind { Silence, Chirp, SweepMid, SweepLow };
    struct TimingRefSegment {
        SegKind kind;
        double startSample, endSample;
    };
    std::vector<TimingRefSegment> timingRefSegments_;
    double timingRefIndex_ = 0.0, timingRefCycleSamples_ = 0.0;
    double midStartHz_ = 100.0, midEndHz_ = 20000.0, midLogRatio_ = 1.0;
    double lowStartHz_ = 20.0, lowEndHz_ = 400.0, lowLogRatio_ = 1.0;
    double timingRefDurationS_ = 10.0, timingRefLevelLin_ = 0.0;
    double chirpLevelLin_ = 0.0;
    bool splitChannels_ = false;
};

#endif  // SIPHONDSP_NATIVE_BMW_MEASUREMENT_GENERATOR_H
