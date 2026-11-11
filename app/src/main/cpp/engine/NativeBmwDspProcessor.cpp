#include "NativeBmwDspProcessor.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <vector>
#include "NativeBmwDspMath.h"

namespace {
using NativeBmwDsp::clampf;
using NativeBmwDsp::changed;
using NativeBmwDsp::dbToLin;
using NativeBmwDsp::measurementMuteIsolates;
using OutputId = NativeBmwRouting::OutputId;
}  // namespace

NativeBmwDspProcessor::NativeBmwDspProcessor() {
    outputs_[static_cast<std::size_t>(OutputId::LowLeft)].id = OutputId::LowLeft;
    outputs_[static_cast<std::size_t>(OutputId::LowLeft)].isLeftSide = true;
    outputs_[static_cast<std::size_t>(OutputId::LowRight)].id = OutputId::LowRight;
    outputs_[static_cast<std::size_t>(OutputId::LowRight)].isLeftSide = false;
    outputs_[static_cast<std::size_t>(OutputId::MidLeft)].id = OutputId::MidLeft;
    outputs_[static_cast<std::size_t>(OutputId::MidLeft)].isLeftSide = true;
    outputs_[static_cast<std::size_t>(OutputId::MidRight)].id = OutputId::MidRight;
    outputs_[static_cast<std::size_t>(OutputId::MidRight)].isLeftSide = false;
    outputConfigs_[static_cast<std::size_t>(OutputId::LowLeft)] = {
        150.f, OutputConfig::CrossoverType::LinkwitzRiley4, true, 32.f, false, false};
    outputConfigs_[static_cast<std::size_t>(OutputId::LowRight)] =
        outputConfigs_[static_cast<std::size_t>(OutputId::LowLeft)];
    outputConfigs_[static_cast<std::size_t>(OutputId::MidLeft)] = {
        150.f, OutputConfig::CrossoverType::LinkwitzRiley4, false, 32.f, false, false};
    outputConfigs_[static_cast<std::size_t>(OutputId::MidRight)] =
        outputConfigs_[static_cast<std::size_t>(OutputId::MidLeft)];
    // High's real config comes from configure() (v[210..260]) once the app calls it; these are
    // just the pre-first-configure() defaults, muted to match DEFAULTS/migrateHighBandIfNeeded's
    // shipped-off state until then.
    outputs_[static_cast<std::size_t>(OutputId::HighLeft)].id = OutputId::HighLeft;
    outputs_[static_cast<std::size_t>(OutputId::HighLeft)].isLeftSide = true;
    outputs_[static_cast<std::size_t>(OutputId::HighRight)].id = OutputId::HighRight;
    outputs_[static_cast<std::size_t>(OutputId::HighRight)].isLeftSide = false;
    outputConfigs_[static_cast<std::size_t>(OutputId::HighLeft)] = {
        150.f, OutputConfig::CrossoverType::LinkwitzRiley4, false, 32.f, true, false};
    outputConfigs_[static_cast<std::size_t>(OutputId::HighRight)] =
        outputConfigs_[static_cast<std::size_t>(OutputId::HighLeft)];
    // The first configure() parses from here, as it would have from p_ / outputConfigs_.
    configShadow_.params = p_;
    configShadow_.outputConfigs = outputConfigs_;
    rebuildAll();
}
NativeBmwDspProcessor::~NativeBmwDspProcessor() = default;
void NativeBmwDspProcessor::setSampleRate(float sr) {
    {
        std::lock_guard<std::mutex> lock(controlMutex_);
        request_.kind = RequestKind::SetSampleRate;
        request_.sampleRate = sr;
        runControlRequest();
    }
    // The kernel is built for one rate: rebuild it from the kept source at the new one, on this
    // thread but outside convolverMutex_ (a resample + transform of up to 65536 taps, tens of ms
    // on a phone), so convolverInfo() and loads never wait on it. The old kernel keeps running
    // until the new one lands.
    rebuildConvolverForRate();
}

void NativeBmwDspProcessor::applySampleRate(float sr) {
    installPending();
    if (sr >= 8000 && std::fabs(sr - sampleRate_) > .5f) {
        sampleRate_ = sr;
        configSampleRate_.store(sr, std::memory_order_relaxed);
        masterRampCoef_ = 1.f - std::exp(-1.f / (0.01f * sr));
        headroomRampCoef_ = 1.f - std::exp(-1.f / (kHeadroomRampSeconds * sr));
        outputRampCoef_ = 1.f - std::exp(-1.f / (kOutputRampSeconds * sr));
        loudnessRampCoef_ = 1.f - std::exp(-1.f / (kLoudnessRampSeconds * sr));
        monoBassFadeStep_ = 1.f / (kMonoBassFadeSeconds * sr);
        convolver_.setSampleRate(sr);
        rebuildAll();
    }
}

// ---- Per-frame signal path ---------------------------------------------------------------------
namespace {
struct BandOutputs {
    OutputId left, right;
};
BandOutputs bandOutputs(NativeBmwRouting::Band band) {
    switch (band) {
        case NativeBmwRouting::Band::Low:
            return {OutputId::LowLeft, OutputId::LowRight};
        case NativeBmwRouting::Band::Mid:
            return {OutputId::MidLeft, OutputId::MidRight};
        case NativeBmwRouting::Band::High:
        default:
            return {OutputId::HighLeft, OutputId::HighRight};
    }
}
}  // namespace

void NativeBmwDspProcessor::processBandChain(NativeBmwRouting::Band band, float& xl, float& xr) {
    using Band = NativeBmwRouting::Band;
    const BandOutputs ids = bandOutputs(band);
    auto& left = output(ids.left);
    auto& right = output(ids.right);
    // Filter stages run L and R together (NEON pairs on arm64); see NativeBmwCrossovers.h.
    if (band == Band::Low) {
        NativeBmwDsp::processLowCrossover(left, outputConfig(ids.left), right,
                                          outputConfig(ids.right), xl, xr);
    } else if (band == Band::Mid) {
        NativeBmwDsp::processMidCrossover(left, right, xl, xr);
    } else {
        NativeBmwDsp::processHighCrossover(left, right, xl, xr);
    }
    if (peqEnabled_) {
        (band == Band::Low ? lowPeq_ : band == Band::Mid ? midPeq_ : highPeq_).process(xl, xr);
    }
    OutputRuntime::processAllPassPair(left, right, xl, xr);
    xl = left.delay.run(xl);
    xr = right.delay.run(xr);
    xl *= left.gain;
    xr *= right.gain;
    // Per-bus brick-wall limiter (stereo-linked), right after the driver gain so its threshold
    // bounds the actual level reaching the driver regardless of how much gain is dialed in.
    // Disabled, it still delays the band by its lookahead so every band keeps the same latency.
    BusLimiter& limiter = band == Band::Low ? busLimLow_ : band == Band::Mid ? busLimMid_ : busLimHigh_;
    const bool limiterEnabled = band == Band::Low   ? p_.busLimLowEnabled
                                : band == Band::Mid ? p_.busLimMidEnabled
                                                    : p_.busLimHighEnabled;
    const float limiterThreshDb = band == Band::Low   ? p_.busLimLowThreshDb
                                  : band == Band::Mid ? p_.busLimMidThreshDb
                                                      : p_.busLimHighThreshDb;
    if (limiterEnabled) {
        limiter.process(xl, xr, limiterThreshDb);
    } else {
        limiter.passThrough(xl, xr, limiterThreshDb);
    }
}
void NativeBmwDspProcessor::idleBandChain(NativeBmwRouting::Band band) {
    using Band = NativeBmwRouting::Band;
    (band == Band::Low ? busLimLow_ : band == Band::Mid ? busLimMid_ : busLimHigh_).reset();
}

std::array<float, NativeBmwRouting::kOutputCount> NativeBmwDspProcessor::processInputStages(float l, float r) {
    float sL = inputDcL_.run(l, dcR_), sR = inputDcR_.run(r, dcR_);
    if (p_.monoBassEnabled) {
        monoBassMix_ = std::min(1.f, monoBassMix_ + monoBassFadeStep_);
    } else if (monoBassMix_ > 0.f) {
        monoBassMix_ = std::max(0.f, monoBassMix_ - monoBassFadeStep_);
    }
    if (monoBassMix_ > 0.f) {
        monoBass_.process(sL, sR, monoBassMix_);
    }
    if (p_.virtualBassEnabled) {
        virtualBassMix_ = std::min(1.f, virtualBassMix_ + monoBassFadeStep_);
    } else if (virtualBassMix_ > 0.f) {
        virtualBassMix_ = std::max(0.f, virtualBassMix_ - monoBassFadeStep_);
    }
    if (virtualBassMix_ > 0.f) {
        virtualBassRemoveMix_ = p_.virtualBassRemoveSub
                                    ? std::min(1.f, virtualBassRemoveMix_ + monoBassFadeStep_)
                                    : std::max(0.f, virtualBassRemoveMix_ - monoBassFadeStep_);
        virtualBassGain_ = NativeBmwDsp::rampToward(virtualBassGain_, p_.virtualBassAmount, loudnessRampCoef_);
        virtualBassCharacter_ =
            NativeBmwDsp::rampToward(virtualBassCharacter_, p_.virtualBassCharacter, loudnessRampCoef_);
        virtualBass_.process(sL, sR, virtualBassMix_, virtualBassRemoveMix_, virtualBassGain_, virtualBassCharacter_);
    }
    if (peqEnabled_) {
        sL *= peqPreamp_;
        sR *= peqPreamp_;
        inputPeq_.process(sL, sR);
    }
    headroom_ = NativeBmwDsp::rampToward(headroom_, headroomTarget_, headroomRampCoef_);
    sL *= headroom_;
    sR *= headroom_;
    // Pre-crossover multiband compressor: full-range stereo, before any band split. Skipped
    // entirely while disabled (how it ships).
    if (p_.mbcEnabled) {
        mbc_.process(sL, sR, p_.mbcBand, detector_);
    }
    // Virtual-source stage: extracts the virtual centre and feeds it back into the internal L/R
    // with its own per-side level/delay/polarity/all-pass, ahead of routing so it then runs
    // through every band chain. Skipped entirely while disabled (how it ships).
    if (p_.virtualStage.enabled) {
        virtual_.process(sL, sR);
    }
    return routing_.process({sL, sR});
}

void NativeBmwDspProcessor::processBandStage(NativeBmwRouting::Band band, std::size_t frames) {
    using Band = NativeBmwRouting::Band;
    const BandOutputs ids = bandOutputs(band);
    const auto li = static_cast<std::size_t>(ids.left), ri = static_cast<std::size_t>(ids.right);
    float* xl = chunkLanes_[li].data();
    float* xr = chunkLanes_[ri].data();
    // What the band does with its routed signal: its chain, the raw routed signal through (the
    // lpfPass/hpfPass/highPass contract), or silence (highXoPass: a tweeter with no HPF ahead of
    // it is a speaker-damage risk, and the 3-way master-off switch's single write for High).
    enum class Mode { Chain, Bypass, Silent };
    Mode mode;
    if (band == Band::Low) {
        mode = p_.lpfPass ? Mode::Bypass : Mode::Chain;
    } else if (band == Band::Mid) {
        mode = p_.hpfPass ? Mode::Bypass : Mode::Chain;
    } else {
        mode = p_.highXoPass ? Mode::Silent : p_.highPass ? Mode::Bypass : Mode::Chain;
    }
    auto& bypassL = bypassLatency_[li];
    auto& bypassR = bypassLatency_[ri];
    for (std::size_t i = 0; i < frames; ++i) {
        // A bypassed band skips the band chain -- its AlignmentDelay and its bus limiter's
        // lookahead -- so it takes the same fixed latency here to stay aligned with the processed
        // bands. Run every frame, bypassed or not, so a line is never stale the moment its bypass
        // flag flips on.
        const float lBypassed = bypassL.run(xl[i]), rBypassed = bypassR.run(xr[i]);
        switch (mode) {
            case Mode::Chain:
                processBandChain(band, xl[i], xr[i]);
                break;
            case Mode::Bypass:
                xl[i] = lBypassed;
                xr[i] = rBypassed;
                idleBandChain(band);
                break;
            case Mode::Silent:
                xl[i] = 0.f;
                xr[i] = 0.f;
                idleBandChain(band);
                break;
        }
    }
}

void NativeBmwDspProcessor::processOutputStages(
    std::array<float, NativeBmwRouting::kOutputCount> logical, float& l, float& r) {
    // Per-output polarity and mute, as one ramped multiplier (see OutputRuntime::outputGain).
    // Indexed in OutputId order.
    for (std::size_t i = 0; i < logical.size(); ++i) {
        auto& out = outputs_[i];
        out.outputGain = NativeBmwDsp::rampToward(out.outputGain, out.outputGainTarget, outputRampCoef_);
        logical[i] *= out.outputGain;
    }
    const auto stereo = NativeBmwRouting::sumToStereo(logical);
    // Always use the routed sum here, even with both lpfPass and hpfPass set (crossover filtering
    // skipped on both bands). routing_.process() and the polarity/mute block above already ran
    // unconditionally, so lowL/lowR/midL/midR -- and therefore stereo.left/right -- correctly
    // reflect the user's routing matrix and per-output polarity/mute either way. The old
    // "fall back to sL/sR" shortcut for the both-bypassed case instead discarded all of that: any
    // non-identity routing matrix (e.g. mixing both front channels into one output) silently had
    // no effect whenever both bypass flags were on, with no error or indication why.
    float oL = stereo.left, oR = stereo.right;
    if (p_.tilt) {
        tilt_.process(oL, oR);
    }
    oL *= postGainL_;
    oR *= postGainR_;
    // Channel mute, ramped like the per-output mute above.
    channelGainL_ = NativeBmwDsp::rampToward(channelGainL_, p_.channelMute == 1 ? 0.f : 1.f, outputRampCoef_);
    channelGainR_ = NativeBmwDsp::rampToward(channelGainR_, p_.channelMute == 2 ? 0.f : 1.f, outputRampCoef_);
    oL *= channelGainL_;
    oR *= channelGainR_;
    oL = NativeBmwDsp::ftz(oL);
    oR = NativeBmwDsp::ftz(oR);
    // Option A measurement-mute bus brick-wall. Operates on logical L/R, AFTER the band sum,
    // tilt, post-gain and ch_mute, but BEFORE the master limiter and BEFORE the deliberate L/R
    // hardware swap below -- the left bus always filters the LowLeft/MidLeft chain. Fully
    // bypassed (single branch, no state advanced) whenever measurement mute is off.
    if (measBus_.active()) {
        measBus_.process(oL, oR);
    }
    l = oL;
    r = oR;
}

void NativeBmwDspProcessor::processFrameBack(float& l, float& r) {
    float oL = l, oR = r;
    // Master brick-wall limiter. enabled == false is a true bypass: the stage is skipped
    // entirely and nothing constrains the summed output level.
    if (p_.limiterEnabled) {
        limiter_.process(oL, oR);
    }
    // Last clean-up before the output gains: with the limiter off, nothing else between the
    // front of the chain and the output would catch a denormal.
    oL = NativeBmwDsp::ftz(oL);
    oR = NativeBmwDsp::ftz(oR);
    // Master volume -- the very last gain, after the limiter (which so always sees full level),
    // ramped toward its target so a steering-wheel step never clicks.
    masterGain_ = NativeBmwDsp::rampToward(masterGain_, masterGainTarget_, masterRampCoef_);
    oL *= masterGain_;
    oR *= masterGain_;
    // Loudness compensation follows the volume, after it: its lift is always less than the cut.
    // The switch and strength ramp the effective strength (so on/off fades, no thump), and while
    // that or the volume is moving the lift is retuned every 8 samples (see retuneLoudness()), so
    // it never runs ahead of the attenuation. Fully off, the stage is skipped.
    const float strengthTarget = p_.loudnessEnabled ? p_.loudnessStrength : 0.f;
    const bool loudnessMoving = loudnessStrength_ != strengthTarget || masterGain_ != masterGainTarget_;
    loudnessStrength_ = NativeBmwDsp::rampToward(loudnessStrength_, strengthTarget, loudnessRampCoef_);
    if (loudnessMoving && (++loudnessRetuneCounter_ & 7u) == 0) {
        retuneLoudness();
    }
    if (loudnessStrength_ > 0.f) {
        loudness_.process(oL, oR);
    }
    // Deliberate final-output swap -- DO NOT REMOVE OR "FIX" THIS.
    // The target vehicle's factory speaker wiring harness is physically reversed (L/R swapped
    // at the amp/speaker connectors, not something this DSP can see or control). This swap
    // cancels that physical fault: oL (built from the LowLeft/MidLeft chain) is written to
    // output param r, and oR (LowRight/MidRight) to output param l. Because of this, every
    // "Left"/"Right"-labeled control elsewhere in the app (gains, routing, delay, PEQ, and the
    // BmwSignalChain preview-graph mapping) is correct as a direct, unswapped index -- they all
    // rely on this line already correcting the physical fault. If a future review flags this as
    // "controls wired backwards", the bug is almost certainly elsewhere; do not add a
    // compensating swap here or anywhere downstream in response to that.
    l = oR;
    r = oL;
}

void NativeBmwDspProcessor::processChunk(float* w, std::size_t frames) {
    using Band = NativeBmwRouting::Band;
    // Front of the chain, frame by frame, up to the routing matrix: into one lane per output.
    for (std::size_t i = 0; i < frames; ++i) {
        float& l = w[2 * i];
        float& r = w[2 * i + 1];
        NativeBmwDsp::applyMeasurementGenerator(measGen_, p_, l, r);
        // The capture records input and output in pairs (it advances on tapOut), so the raw
        // input waits here until the frame's output is known.
        chunkRawIn_[2 * i] = l;
        chunkRawIn_[2 * i + 1] = r;
        const auto routed = processInputStages(l, r);
        for (std::size_t o = 0; o < routed.size(); ++o) {
            chunkLanes_[o][i] = routed[o];
        }
    }
    // Each band's chain over the whole chunk. The bands share no state, so this is the same
    // arithmetic, in the same order per band, as running them frame by frame -- bit-identical --
    // with each band's filter and limiter state kept hot while it runs.
    processBandStage(Band::Low, frames);
    processBandStage(Band::Mid, frames);
    processBandStage(Band::High, frames);
    // (a) The band sum and the output stages, frame by frame, into logical L/R.
    for (std::size_t i = 0; i < frames; ++i) {
        std::array<float, NativeBmwRouting::kOutputCount> logical;
        for (std::size_t o = 0; o < logical.size(); ++o) {
            logical[o] = chunkLanes_[o][i];
        }
        processOutputStages(logical, chunkConvL_[i], chunkConvR_[i]);
    }
    // (b) The convolver, over the chunk, right after the band sum and ahead of the master
    // limiter: an IR that corrects phase raises the crest factor without raising the magnitude,
    // so its peaks must still pass through the limiter. Logical L/R in (the hardware swap comes
    // later, in processFrameBack()), so the IR's left channel is the car's left. A true bypass
    // (untouched samples) while off or with no IR loaded.
    convolver_.process(chunkConvL_.data(), chunkConvR_.data(), frames, p_.convolverEnabled,
                       p_.convolverGainDb);
    // (c) The master limiter, volume, loudness and the hardware swap, then the output guard and
    // the capture tap, frame by frame.
    for (std::size_t i = 0; i < frames; ++i) {
        float l = chunkConvL_[i], r = chunkConvR_[i];
        processFrameBack(l, r);
        l = NativeBmwDsp::outputGuard(l);
        r = NativeBmwDsp::outputGuard(r);
        capture_.tapIn(chunkRawIn_[2 * i], chunkRawIn_[2 * i + 1]);
        capture_.tapOut(l, r);
        w[2 * i] = l;
        w[2 * i + 1] = r;
    }
}

void NativeBmwDspProcessor::processBlock(float* w, std::size_t n) {
    const std::size_t frames = n / 2;
    for (std::size_t done = 0; done < frames;) {
        const std::size_t count = std::min<std::size_t>(frames - done, kChunkFrames);
        processChunk(w + 2 * done, count);
        done += count;
    }
}

const float* NativeBmwDspProcessor::process(const float* s, std::size_t n) {
    if (!s) {
        return s;
    }
    const AudioOwnership owner(*this);
    if (!p_.enabled) {
        return s;
    }
    processBlock(const_cast<float*>(s), n);
    return s;
}

template <class Sample>
void NativeBmwDspProcessor::processInt(Sample* w, std::size_t n, float scale) {
    const float invScale = 1.f / scale;
    const std::size_t frames = n / 2;
    for (std::size_t done = 0; done < frames;) {
        const std::size_t count = std::min<std::size_t>(frames - done, kChunkFrames);
        Sample* in = w + 2 * done;
        for (std::size_t i = 0; i < 2 * count; ++i) {
            chunkInt_[i] = static_cast<float>(in[i]) * invScale;
        }
        processChunk(chunkInt_.data(), count);
        for (std::size_t i = 0; i < 2 * count; ++i) {
            in[i] = NativeBmwDsp::clampInt<Sample>(chunkInt_[i] * scale);
        }
        done += count;
    }
}

const int16_t* NativeBmwDspProcessor::process(const int16_t* s, std::size_t n) {
    if (!s) {
        return s;
    }
    const AudioOwnership owner(*this);
    if (!p_.enabled) {
        return s;
    }
    processInt(const_cast<int16_t*>(s), n, 32768.f);
    return s;
}
const int32_t* NativeBmwDspProcessor::process(const int32_t* s, std::size_t n) {
    if (!s) {
        return s;
    }
    const AudioOwnership owner(*this);
    if (!p_.enabled) {
        return s;
    }
    processInt(const_cast<int32_t*>(s), n, 2147483648.f);
    return s;
}
void NativeBmwDspProcessor::readMbcMeter(float* v, std::size_t n) const {
    if (!v || n < 12) {
        return;
    }
    mbc_.readMeter(v, mbcEnabledMeterFlag_.load(std::memory_order_relaxed));
}
void NativeBmwDspProcessor::readBusLimiterMeter(float* v, std::size_t n) const {
    if (!v || n < 2) {
        return;
    }
    v[0] = busLimLowEnabledMeterFlag_.load(std::memory_order_relaxed)
               ? busLimLow_.grDb.load(std::memory_order_relaxed) : 0.f;
    v[1] = busLimMidEnabledMeterFlag_.load(std::memory_order_relaxed)
               ? busLimMid_.grDb.load(std::memory_order_relaxed) : 0.f;
    if (n >= 3) {
        v[2] = busLimHighEnabledMeterFlag_.load(std::memory_order_relaxed)
                   ? busLimHigh_.grDb.load(std::memory_order_relaxed) : 0.f;
    }
}
void NativeBmwDspProcessor::readVirtualMeter(float* v, std::size_t n) const {
    if (!v) {
        return;
    }
    if (!virtualEnabledMeterFlag_.load(std::memory_order_relaxed)) {
        if (n > 0) {
            v[0] = 0.f;
        }
        if (n > 1) {
            v[1] = -60.f;
        }
        return;
    }
    virtual_.readMeter(v, n);
}
void NativeBmwDspProcessor::readMasterLimiterMeter(float* v, std::size_t n) const {
    if (!v || n < 1) {
        return;
    }
    v[0] = masterLimiterEnabledMeterFlag_.load(std::memory_order_relaxed)
               ? limiter_.grDb() : 0.f;
}

std::vector<double> NativeBmwDspProcessor::captureTruthSnapshot() {
    std::vector<double> out;
    // Every value the snapshot can hold, so filling it never allocates (it may be the audio
    // thread that fills it).
    out.reserve(3 + NativeBmwRouting::kOutputCount * kTruthOutputWidth +
                4 * (3 + kMaxPeqSectionsPerChannel * 6));
    std::lock_guard<std::mutex> lock(controlMutex_);
    request_.kind = RequestKind::TruthSnapshot;
    request_.truth = &out;
    runControlRequest();
    request_.truth = nullptr;
    return out;
}

void NativeBmwDspProcessor::fillTruthSnapshot(std::vector<double>& out) {
    installPending();
    out.clear();
    out.push_back(static_cast<double>(sampleRate_));
    out.push_back(peqEnabled_ ? 1.0 : 0.0);
    out.push_back(static_cast<double>(peqPreampDb_));

    // Appends one Biquad's topology + every coefficient field (opA is only meaningful for the two
    // one-pole topologies; SVF's a1/a2/a3/m0/m1/m2 are only meaningful for Svf2 -- the unused half
    // is still emitted, as whatever that field's default-constructed/last-built value happens to
    // be, so the block width stays fixed and callers can tell a genuine SVF identity stage
    // (a1=a2=a3=0, m0=1,m1=0,m2=0) apart from a one-pole stage without a side channel).
    auto appendStage = [&out](const Biquad& stage) {
        out.push_back(static_cast<double>(static_cast<std::uint8_t>(stage.topology)));
        out.push_back(stage.a1);
        out.push_back(stage.a2);
        out.push_back(stage.a3);
        out.push_back(stage.m0);
        out.push_back(stage.m1);
        out.push_back(stage.m2);
        out.push_back(stage.op_a);
    };

    for (OutputId id : {OutputId::LowLeft, OutputId::LowRight, OutputId::MidLeft,
                        OutputId::MidRight, OutputId::HighLeft, OutputId::HighRight}) {
        const auto& cfg = outputConfig(id);
        const auto& rt = output(id);
        out.push_back(static_cast<double>(cfg.crossoverFreq));
        out.push_back(static_cast<double>(static_cast<std::uint8_t>(cfg.crossoverType)));
        // Mid/High outputs never read subsonicEnabled (see processMidCrossover/
        // processHighCrossover) -- report that truthfully rather than echoing back a config
        // field that has no runtime effect there.
        const bool subsonicApplies = NativeBmwRouting::isLowBandOutput(id) && cfg.subsonicEnabled;
        out.push_back(subsonicApplies ? 1.0 : 0.0);
        out.push_back(static_cast<double>(cfg.subsonicFreq));
        out.push_back(rt.muted ? 1.0 : 0.0);
        out.push_back(rt.polarityInverted ? 1.0 : 0.0);
        out.push_back(20.0 * std::log10(std::max(static_cast<double>(rt.gain), 1e-9)));
        out.push_back(sampleRate_ > 0.f
                           ? static_cast<double>(rt.delay.delay) * 1000.0 / sampleRate_
                           : 0.0);
        appendStage(rt.crossover1);
        appendStage(rt.crossover2);
        appendStage(rt.crossover3);
        appendStage(rt.crossover4);
    }

    auto appendBank = [&out, this](const std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth>& values,
                             std::size_t valueCount, const PeqBank& bank) {
        const std::size_t bandCount = valueCount / kPeqBandWidth;
        out.push_back(static_cast<double>(bandCount));
        out.push_back(static_cast<double>(bank.leftCount));
        out.push_back(static_cast<double>(bank.rightCount));
        for (std::size_t i = 0; i < bandCount; ++i) {
            const double* v = &values[i * kPeqBandWidth];
            const int type = static_cast<int>(v[3]);
            out.push_back(v[0]);  // frequency Hz
            out.push_back(v[1]);  // gain dB
            out.push_back(v[2]);  // Q
            out.push_back(v[3]);  // type
            out.push_back(v[4]);  // channel (0 both, 1 left, 2 right)
            // Inactive too: a band left out because it can't be built at the running rate (see
            // parsePeq's dropUnbuildable).
            NativeBmwDsp::Biquad probe;
            const bool built = NativeBmwDsp::makePeq(probe, v[0], v[1], v[2], type, sampleRate_);
            out.push_back(!built || NativeBmwDsp::peqBandSkipped(type, v[1]) ? 0.0 : 1.0);
        }
    };
    appendBank(inputPeqValues_, inputPeqValueCount_, inputPeq_);
    appendBank(lowPeqValues_, lowPeqValueCount_, lowPeq_);
    appendBank(midPeqValues_, midPeqValueCount_, midPeq_);
    appendBank(highPeqValues_, highPeqValueCount_, highPeq_);
}

void NativeBmwDspProcessor::startCapture() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    const auto capacity =
        static_cast<std::size_t>(configSampleRate_.load(std::memory_order_relaxed) * kCaptureMaxSeconds);
    // First without buffers: the current ones are reused when they already have this size.
    request_.kind = RequestKind::StartCapture;
    request_.captureCapacity = capacity;
    runControlRequest();
    if (!request_.captureStarted) {
        // The up-to-~46 MB allocation and zeroing happen here, on the calling thread; the owner
        // only swaps the buffers in (O(1)).
        request_.rawInL.assign(capacity, 0.f);
        request_.rawInR.assign(capacity, 0.f);
        request_.outL.assign(capacity, 0.f);
        request_.outR.assign(capacity, 0.f);
        runControlRequest();
    }
    // The retired buffers (swapped out by the owner) are freed here, not on the audio thread.
    request_.rawInL = {};
    request_.rawInR = {};
    request_.outL = {};
    request_.outR = {};
}

void NativeBmwDspProcessor::stopCapture() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    request_.kind = RequestKind::StopCapture;
    runControlRequest();
}

std::size_t NativeBmwDspProcessor::captureFrameCount() const {
    return capture_.frameCount();
}

std::unique_ptr<NativeBmwDspProcessor::CaptureSnapshot>
NativeBmwDspProcessor::takeCaptureSnapshot() {
    auto snapshot = std::make_unique<CaptureSnapshot>();
    std::lock_guard<std::mutex> lock(controlMutex_);
    request_.kind = RequestKind::TakeCapture;
    runControlRequest();
    snapshot->sampleRate = request_.capturedSampleRate;
    snapshot->frames = request_.frames;
    snapshot->rawInL.swap(request_.rawInL);
    snapshot->rawInR.swap(request_.rawInR);
    snapshot->outL.swap(request_.outL);
    snapshot->outR.swap(request_.outR);
    return snapshot;
}

// ---- Convolver IR ----------------------------------------------------------------------------
namespace {
// Pure: the source IR at rate, built into a kernel; streamIr gets the taps at rate (for the
// graphs' response). Allocates; never on the audio thread.
std::unique_ptr<NativeBmwDsp::ConvolverKernel> buildConvolverKernel(
    const NativeBmwDsp::ConvolverIr& source, float rate, std::size_t& streamFrames,
    std::shared_ptr<const NativeBmwDsp::ConvolverIr>& streamIr, std::string& error) {
    streamIr.reset();
    auto atRate = std::make_shared<NativeBmwDsp::ConvolverIr>();
    if (!NativeBmwDsp::resampleConvolverIr(source, rate, *atRate, error)) {
        return nullptr;
    }
    streamFrames = atRate->frames();
    auto kernel = NativeBmwDsp::ConvolverKernel::build(*atRate, error);
    if (kernel) {
        streamIr = std::move(atRate);
    }
    return kernel;
}
}  // namespace

void NativeBmwDspProcessor::commitConvolverLocked(
    std::unique_ptr<NativeBmwDsp::ConvolverKernel> kernel,
    std::shared_ptr<const NativeBmwDsp::ConvolverIr> streamIr, float rate, std::size_t streamFrames,
    const std::string& error) {
    ++convolverGeneration_;
    convolverInfo_.streamRate = rate;
    convolverStreamIr_ = kernel ? std::move(streamIr) : nullptr;
    if (!kernel) {
        // Can't run at this rate (e.g. too long once resampled up): unload rather than convolve
        // at the wrong rate. The source is kept; the next rate change tries again.
        convolverInfo_.streamFrames = 0;
        convolverInfo_.partitions = 0;
        convolverInfo_.latencyFrames = 0;
        convolverInfo_.latencyMs = 0.0;
        convolverInfo_.error = error;
        convolver_.publish(nullptr);
        return;
    }
    convolverInfo_.streamFrames = streamFrames;
    convolverInfo_.partitions = kernel->partitions();
    convolverInfo_.peakGainDb = kernel->peakGainDb();
    convolverInfo_.latencyFrames = kernel->latencyFrames();
    convolverInfo_.latencyMs = rate > 0.f ? 1000.0 * static_cast<double>(kernel->latencyFrames()) / rate : 0.0;
    convolverInfo_.error.clear();
    convolver_.publish(std::move(kernel));
}

void NativeBmwDspProcessor::rebuildConvolverForRate() {
    // Retries until a build lands for the current source at the current rate: a load, clear or
    // second rate change that commits meanwhile makes this build stale.
    for (;;) {
        NativeBmwDsp::ConvolverIr source;
        std::uint64_t generation;
        float rate;
        {
            std::lock_guard<std::mutex> lock(convolverMutex_);
            rate = configSampleRate_.load(std::memory_order_relaxed);
            if (!convolverInfo_.loaded || std::fabs(convolverInfo_.streamRate - rate) <= .5) {
                return;
            }
            source = convolverSource_;
            generation = convolverGeneration_;
        }
        std::string error;
        std::size_t frames = 0;
        std::shared_ptr<const NativeBmwDsp::ConvolverIr> streamIr;
        auto kernel = buildConvolverKernel(source, rate, frames, streamIr, error);
        std::lock_guard<std::mutex> lock(convolverMutex_);
        if (generation == convolverGeneration_ &&
            std::fabs(configSampleRate_.load(std::memory_order_relaxed) - rate) <= .5f) {
            commitConvolverLocked(std::move(kernel), std::move(streamIr), rate, frames, error);
            return;
        }
    }
}

bool NativeBmwDspProcessor::loadConvolverIr(const char* wavPath, std::string& error) {
    NativeBmwDsp::ConvolverIr ir;
    if (!NativeBmwDsp::loadConvolverIrWav(wavPath, ir, error)) {
        std::lock_guard<std::mutex> lock(convolverMutex_);
        convolverInfo_.error = error;
        return false;
    }
    return loadConvolverIr(std::move(ir), error);
}

bool NativeBmwDspProcessor::loadConvolverIr(NativeBmwDsp::ConvolverIr ir, std::string& error) {
    if (ir.frames() == 0 || ir.sampleRate < 8000.0 || (ir.stereo() && ir.right.size() != ir.frames())) {
        error = "No usable IR";
        return false;
    }
    if (ir.channels == 0) {
        ir.channels = ir.stereo() ? 2 : 1;
    }
    // Validate and build before touching the running source, so a rejected file leaves the
    // current IR playing; outside the lock, like a rate change.
    float rate = configSampleRate_.load(std::memory_order_relaxed);
    std::size_t frames = 0;
    std::shared_ptr<const NativeBmwDsp::ConvolverIr> streamIr;
    auto kernel = buildConvolverKernel(ir, rate, frames, streamIr, error);
    if (!kernel) {
        std::lock_guard<std::mutex> lock(convolverMutex_);
        convolverInfo_.error = error;
        return false;
    }
    std::lock_guard<std::mutex> lock(convolverMutex_);
    // A rate change that ran while this built saw the previous source (or none) and won't
    // rebuild this one: build it again at the rate that is current now. Rare, so under the lock.
    const float now = configSampleRate_.load(std::memory_order_relaxed);
    if (std::fabs(now - rate) > .5f) {
        rate = now;
        kernel = buildConvolverKernel(ir, rate, frames, streamIr, error);
        if (!kernel) {
            convolverInfo_.error = error;
            return false;
        }
    }
    convolverInfo_ = ConvolverInfo{};
    convolverInfo_.loaded = true;
    convolverInfo_.channels = ir.channels;
    convolverInfo_.sourceRate = ir.sampleRate;
    convolverInfo_.sourceFrames = ir.frames();
    convolverSource_ = std::move(ir);
    commitConvolverLocked(std::move(kernel), std::move(streamIr), rate, frames, error);
    return true;
}

void NativeBmwDspProcessor::clearConvolverIr() {
    std::lock_guard<std::mutex> lock(convolverMutex_);
    ++convolverGeneration_;
    convolverSource_ = NativeBmwDsp::ConvolverIr{};
    convolverInfo_ = ConvolverInfo{};
    convolverStreamIr_.reset();
    convolver_.publish(nullptr);
}

NativeBmwDspProcessor::ConvolverInfo NativeBmwDspProcessor::convolverInfo() {
    std::lock_guard<std::mutex> lock(convolverMutex_);
    return convolverInfo_;
}

std::uint64_t NativeBmwDspProcessor::convolverGeneration() {
    std::lock_guard<std::mutex> lock(convolverMutex_);
    return convolverGeneration_;
}

bool NativeBmwDspProcessor::convolverResponse(const double* freqs, std::size_t count,
                                              ConvolverResponse& out) {
    std::shared_ptr<const NativeBmwDsp::ConvolverIr> ir;
    {
        std::lock_guard<std::mutex> lock(convolverMutex_);
        out = ConvolverResponse{};
        out.generation = convolverGeneration_;
        ir = convolverStreamIr_;
        out.latencyFrames = convolverInfo_.latencyFrames;
        out.peakGainDb = convolverInfo_.peakGainDb;
    }
    if (!ir || ir->frames() == 0 || ir->sampleRate <= 0.0 || (count > 0 && freqs == nullptr)) {
        return false;
    }
    // The taps' own rate: the stream rate they were built at.
    out.sampleRate = ir->sampleRate;
    out.left.resize(2 * count);
    NativeBmwDsp::convolverIrResponse(ir->left, ir->sampleRate, out.latencyFrames, freqs, count,
                                      out.left.data());
    if (ir->stereo()) {
        out.right.resize(2 * count);
        NativeBmwDsp::convolverIrResponse(ir->right, ir->sampleRate, out.latencyFrames, freqs, count,
                                          out.right.data());
    } else {
        out.right = out.left;
    }
    return true;
}

// ---- Ownership and control requests --------------------------------------------------------
namespace {
std::int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
}  // namespace

void NativeBmwDspProcessor::claimAudioOwnership() {
    // Free unless a control call is serving a request on its own thread, which it only does while
    // no audio was flowing: this buffer is the stream resuming, and waits out that one request.
    std::uint32_t expected = kOwnerFree;
    for (unsigned spins = 0; !ownership_.compare_exchange_weak(
             expected, kOwnerAudio, std::memory_order_acquire, std::memory_order_relaxed);
         ++spins) {
        expected = kOwnerFree;
        if (spins > 64) {
            std::this_thread::yield();
        }
    }
    audioThread_.store(std::this_thread::get_id(), std::memory_order_relaxed);
    lastBufferNs_.store(nowNs(), std::memory_order_relaxed);
}

NativeBmwDspProcessor::AudioOwnership::AudioOwnership(NativeBmwDspProcessor& processor)
    : processor_(processor) {
    processor_.claimAudioOwnership();
    if (processor_.requestSeq_.load(std::memory_order_acquire) !=
        processor_.servedSeq_.load(std::memory_order_relaxed)) {
        processor_.serveControlRequest();
        processor_.audioServedRequests_.fetch_add(1, std::memory_order_relaxed);
    }
    processor_.installPending();
}

NativeBmwDspProcessor::AudioOwnership::~AudioOwnership() {
    processor_.ownership_.store(kOwnerFree, std::memory_order_release);
}

void NativeBmwDspProcessor::serveControlRequest() {
    serveRequest(request_);
    servedSeq_.store(requestSeq_.load(std::memory_order_relaxed), std::memory_order_release);
}

void NativeBmwDspProcessor::serveRequest(ControlRequest& request) {
    switch (request.kind) {
        case RequestKind::SetSampleRate:
            applySampleRate(request.sampleRate);
            break;
        case RequestKind::TruthSnapshot:
            if (request.truth != nullptr) {
                fillTruthSnapshot(*request.truth);
            }
            break;
        case RequestKind::StartCapture: {
            const bool sized = capture_.sizedFor(request.captureCapacity);
            const bool supplied = request.rawInL.size() == request.captureCapacity &&
                                  request.captureCapacity > 0;
            request.captureStarted = sized || supplied;
            if (request.captureStarted) {
                capture_.start(!sized, request.rawInL, request.rawInR, request.outL, request.outR);
            }
            break;
        }
        case RequestKind::StopCapture:
            capture_.stop();
            break;
        case RequestKind::TakeCapture:
            request.capturedSampleRate = sampleRate_;
            request.frames = capture_.take(request.rawInL, request.rawInR, request.outL, request.outR);
            break;
    }
}

void NativeBmwDspProcessor::runControlRequest() {
    const std::uint32_t seq = requestSeq_.load(std::memory_order_relaxed) + 1;
    const std::int64_t start = nowNs();
    // A sample-rate change rebuilds everything (and allocates): never audio-thread work, so the
    // caller always serves it, between buffers.
    const bool audioFlowing =
        request_.kind != RequestKind::SetSampleRate &&
        start - lastBufferNs_.load(std::memory_order_relaxed) < kAudioIdleNs &&
        audioThread_.load(std::memory_order_relaxed) != std::this_thread::get_id();
    if (audioFlowing) {
        // The audio thread serves it at the start of its next buffer.
        requestSeq_.store(seq, std::memory_order_release);
        while (servedSeq_.load(std::memory_order_acquire) != seq) {
            if (nowNs() - start > kRequestTimeoutNs) {
                break;  // the stream stopped meanwhile: serve it below
            }
            std::this_thread::sleep_for(std::chrono::microseconds(500));
        }
        if (servedSeq_.load(std::memory_order_acquire) == seq) {
            return;
        }
    }
    // Serve it here, as the owner. Waits out a buffer in progress (a stream that stopped
    // mid-request, or a live stream changing rate).
    std::uint32_t expected = kOwnerFree;
    while (!ownership_.compare_exchange_weak(expected, kOwnerControl, std::memory_order_acquire,
                                             std::memory_order_relaxed)) {
        expected = kOwnerFree;
        std::this_thread::yield();
    }
    // The audio thread may have served a request posted above just before stopping.
    if (!audioFlowing || servedSeq_.load(std::memory_order_acquire) != seq) {
        serveRequest(request_);
        requestSeq_.store(seq, std::memory_order_relaxed);
        servedSeq_.store(seq, std::memory_order_relaxed);
    }
    ownership_.store(kOwnerFree, std::memory_order_release);
}
