#include "NativeBmwMeasurement.h"
#include <cmath>
#include "NativeBmwDspMath.h"
#include "NativeBmwDspProcessor.h"
// Declarations only -- NOT defining DR_WAV_IMPLEMENTATION here. JdspImpResToolbox.c already
// defines it once and jamesdsp-wrapper links against jdspimprestoolbox (see CMakeLists.txt),
// so the actual drwav_* function bodies are resolved from there at link time.
#include "../libjdspimptoolbox/dr_wav.h"

namespace NativeBmwDsp {

namespace {
// See configureMeasurementGenerator()'s timing-reference branch.
constexpr float kTimingRefChirpLevelOffsetDb = 4.f;
using OutputId = NativeBmwRouting::OutputId;
}  // namespace

void MeasurementBus::rebuild(int mode, float stopbandOctaves,
                             const std::array<OutputConfig, NativeBmwRouting::kOutputCount>& configs,
                             float sampleRate) {
    // Option A: measurement-mute output-bus brick-wall. Only ever inserted into processFrame's
    // signal path while measurement mute is on; all coefficient work happens here, on a
    // measurementMute (or relevant crossover) transition via DirtyMeasBus -- never per sample.
    // Each mode keeps one band and cuts the bus where the external split would otherwise route
    // that band's residual skirt to a neighbouring driver:
    // isolate Mid  (==1) -> HPF the bus below the MID lower crossover, plus (only while Mid's
    //                       upper Mid/High corner is on, i.e. 3-way) an LPF above that corner
    // isolate Low  (==2) -> LPF the bus above the LOW crossover
    // isolate High (==3) -> HPF the bus below the HIGH crossover (the Mid/High corner)
    // LR8 per side: 4x cascaded Butterworth Q=1/sqrt(2), 48 dB/oct.
    //
    // The corner is not placed on the crossover itself: sitting an LR8 exactly on the opposite
    // band's crossover also chews ~12 dB out of the band that is still playing, right where its
    // own transition lives, so an isolated measurement rolls off well short of the real acoustic
    // crossover. stopbandOctaves walks the corner that many octaves *into the stopband* (down
    // for an HPF, up for an LPF) so the surviving band keeps its own transition region intact
    // while the brick-wall still kills the deep residual skirt. 0 = original on-crossover
    // behaviour.
    auto config = [&configs](OutputId id) -> const OutputConfig& {
        return configs[static_cast<std::size_t>(id)];
    };
    active_ = mode != 0;
    upperActive_ = mode == 1 && config(OutputId::MidLeft).upperCrossoverEnabled;
    for (auto* bank : {&busL_, &busR_, &upperL_, &upperR_}) {
        for (auto& b : *bank) {
            b.clear();
        }
    }
    if (!active_) {
        return;
    }
    const bool highpass = mode != 2;
    const float down = std::exp2(-stopbandOctaves);
    const float up = std::exp2(stopbandOctaves);
    const float nyquistGuard = sampleRate * 0.45f;
    auto corner = [&](float crossoverHz, float shift) {
        return clampf(crossoverHz * shift, 10.f, nyquistGuard);
    };
    const OutputId sourceL = mode == 1 ? OutputId::MidLeft : mode == 2 ? OutputId::LowLeft : OutputId::HighLeft;
    const OutputId sourceR = mode == 1 ? OutputId::MidRight : mode == 2 ? OutputId::LowRight : OutputId::HighRight;
    const float fcL = corner(config(sourceL).crossoverFreq, highpass ? down : up);
    const float fcR = corner(config(sourceR).crossoverFreq, highpass ? down : up);
    for (std::size_t i = 0; i < kSections; ++i) {
        if (highpass) {
            makeHighPass(busL_[i], fcL, BW, sampleRate);
            makeHighPass(busR_[i], fcR, BW, sampleRate);
        } else {
            makeLowPass(busL_[i], fcL, BW, sampleRate);
            makeLowPass(busR_[i], fcR, BW, sampleRate);
        }
    }
    if (upperActive_) {
        const float fcUpL = corner(config(OutputId::MidLeft).upperCrossoverFreq, up);
        const float fcUpR = corner(config(OutputId::MidRight).upperCrossoverFreq, up);
        for (std::size_t i = 0; i < kSections; ++i) {
            makeLowPass(upperL_[i], fcUpL, BW, sampleRate);
            makeLowPass(upperR_[i], fcUpR, BW, sampleRate);
        }
    }
}
void MeasurementBus::process(float& oL, float& oR) {
    for (std::size_t i = 0; i < kSections; ++i) {
        Biquad::runPair(busL_[i], busR_[i], oL, oR);
    }
    if (upperActive_) {
        for (std::size_t i = 0; i < kSections; ++i) {
            Biquad::runPair(upperL_[i], upperR_[i], oL, oR);
        }
    }
    oL = ftz(oL);
    oR = ftz(oR);
}

void configureMeasurementGenerator(NativeBmwMeasurementGenerator& generator, const Params& p,
                                   float sampleRate) {
    // Control-thread only, on a DirtyMeasGen transition -- never per sample. (Re)builds
    // unconditionally for the active type; see the DirtyMeasGen comment in configure() for why
    // that's correct even for a param edit while already running.
    if (p.measGenType == 1) {
        if (p.measGenTimingRefEnabled) {
            // Timing chirp runs 4 dB hotter than the sweep (matches the measured ratio in an
            // actual REW-exported Acoustic Timing Reference file: chirp -8 dBFS vs sweep
            // -12 dBFS), clamped so a sweep level close to 0 dBFS can't push the chirp into
            // clipping.
            const float chirpLevelDb =
                std::min(0.f, p.measGenSweepLevelDb + kTimingRefChirpLevelOffsetDb);
            generator.configureTimingRef(p.measGenTimingRefMidStartHz, p.measGenTimingRefMidEndHz,
                                         p.measGenTimingRefLowStartHz, p.measGenTimingRefLowEndHz,
                                         p.measGenSweepDurationS, dbToLin(p.measGenSweepLevelDb),
                                         dbToLin(chirpLevelDb), p.measGenTimingRefSplitChannels,
                                         sampleRate);
        } else {
            generator.configureSweep(p.measGenSweepStartHz, p.measGenSweepEndHz,
                                     p.measGenSweepDurationS, dbToLin(p.measGenSweepLevelDb),
                                     sampleRate);
        }
    } else if (p.measGenType == 2) {
        generator.configurePink(p.measGenPinkPeriodS, dbToLin(p.measGenPinkLevelDb), sampleRate);
    }
}
void applyMeasurementGenerator(NativeBmwMeasurementGenerator& generator, const Params& p, float& l,
                               float& r) {
    // Replaces the real input entirely, before the capture tap sees it, so a measurement run's
    // captured "raw input" is the actual stimulus and the raw-input/output WAV pair is a valid
    // stimulus/response pair for a null test. processFrame() then runs the substituted signal
    // through the identical path real playback does, not a separate tap.
    if (p.measGenType == 1) {
        if (p.measGenTimingRefEnabled) {
            // Different content per channel (silence / timing chirp on l only / sweep on both) --
            // see nextTimingRefSample()'s own comment for why this exists.
            generator.nextTimingRefSample(l, r);
        } else {
            const float g = static_cast<float>(generator.nextSweepSample());
            l = g;
            r = g;
        }
    } else if (p.measGenType == 2) {
        const float g = static_cast<float>(generator.nextPinkSample());
        l = g;
        r = g;
    }
}

void CaptureRecorder::start(bool swapIn, std::vector<float>& rawInL, std::vector<float>& rawInR,
                            std::vector<float>& outL, std::vector<float>& outR) {
    if (swapIn) {
        rawInL_.swap(rawInL);
        rawInR_.swap(rawInR);
        outL_.swap(outL);
        outR_.swap(outR);
    }
    capacity_ = rawInL_.size();
    writeIndex_.store(0, std::memory_order_relaxed);
    enabled_ = capacity_ > 0;
}
std::size_t CaptureRecorder::take(std::vector<float>& rawInL, std::vector<float>& rawInR,
                                  std::vector<float>& outL, std::vector<float>& outR) {
    enabled_ = false;
    const std::size_t frames = std::min(frameCount(), capacity_);
    rawInL.swap(rawInL_);
    rawInR.swap(rawInR_);
    outL.swap(outL_);
    outR.swap(outR_);
    capacity_ = 0;
    return frames;
}

}  // namespace NativeBmwDsp

bool NativeBmwDspProcessor::CaptureSnapshot::exportWav(const char* rawInPath, const char* outPath,
                                                       CaptureExportResult& result) const {
    if (!rawInPath || !outPath) {
        return false;
    }
    const std::size_t n = frames;
    if (n == 0) {
        return false;
    }

    auto writeStereo = [&](const char* path, const float* left, const float* right) -> bool {
        drwav wav;
        drwav_data_format format;
        format.container = drwav_container_riff;
        format.format = DR_WAVE_FORMAT_IEEE_FLOAT;
        format.channels = 2;
        format.sampleRate = static_cast<drwav_uint32>(sampleRate);
        format.bitsPerSample = 32;
        if (!drwav_init_file_write(&wav, path, &format, nullptr)) {
            return false;
        }
        // Stream in fixed-size chunks rather than materializing the whole interleaved buffer up
        // front -- at the max 30s capture window that's ~11.5MB per call, on top of the ~46MB
        // already held across the four capture buffers.
        constexpr std::size_t kChunkFrames = 4096;
        std::array<float, kChunkFrames * 2> chunk{};
        drwav_uint64 totalWritten = 0;
        for (std::size_t offset = 0; offset < n; offset += kChunkFrames) {
            const std::size_t count = std::min(kChunkFrames, n - offset);
            for (std::size_t i = 0; i < count; ++i) {
                chunk[i * 2] = left[offset + i];
                chunk[i * 2 + 1] = right[offset + i];
            }
            totalWritten += drwav_write_pcm_frames(&wav, count, chunk.data());
        }
        drwav_uninit(&wav);
        return totalWritten == n;
    };

    if (!writeStereo(rawInPath, rawInL.data(), rawInR.data())) {
        return false;
    }
    if (!writeStereo(outPath, outL.data(), outR.data())) {
        return false;
    }

    // Null test: RMS of (final output - raw input) across both channels. Near-silent when the
    // DSP genuinely isn't changing the signal beyond headroom/tilt/limiter defaults; a nonzero
    // reading pinpoints that *something* in the chain is doing more than expected.
    float peakIn = 0.f, peakOut = 0.f, diffSumSq = 0.f;
    for (std::size_t i = 0; i < n; ++i) {
        peakIn = std::max({peakIn, std::fabs(rawInL[i]), std::fabs(rawInR[i])});
        peakOut = std::max({peakOut, std::fabs(outL[i]), std::fabs(outR[i])});
        const float dl = outL[i] - rawInL[i];
        const float dr = outR[i] - rawInR[i];
        diffSumSq += dl * dl + dr * dr;
    }
    const float rms = std::sqrt(diffSumSq / static_cast<float>(n * 2));
    result.peakInDb = peakIn > 0.f ? 20.f * std::log10(peakIn) : -100.f;
    result.peakOutDb = peakOut > 0.f ? 20.f * std::log10(peakOut) : -100.f;
    result.nullTestRmsDb = rms > 0.f ? 20.f * std::log10(rms) : -100.f;
    return true;
}
