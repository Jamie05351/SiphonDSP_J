#include "NativeBmwDspProcessor.h"
#include <algorithm>
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
        150.f, OutputConfig::CrossoverType::LinkwitzRiley4, true, 32.f, false, false,
        {true, -12.f, 2.f, 8.f, 40.f, 250.f, 1.5f}};
    outputConfigs_[static_cast<std::size_t>(OutputId::LowRight)] =
        outputConfigs_[static_cast<std::size_t>(OutputId::LowLeft)];
    outputConfigs_[static_cast<std::size_t>(OutputId::MidLeft)] = {
        150.f, OutputConfig::CrossoverType::LinkwitzRiley4, false, 32.f, false, false,
        {false, -10.f, 1.5f, 6.f, 10.f, 180.f, 0.f}};
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
        150.f, OutputConfig::CrossoverType::LinkwitzRiley4, false, 32.f, true, false,
        {false, -10.f, 1.5f, 6.f, 10.f, 180.f, 0.f}};
    outputConfigs_[static_cast<std::size_t>(OutputId::HighRight)] =
        outputConfigs_[static_cast<std::size_t>(OutputId::HighLeft)];
    rebuildAll();
}
NativeBmwDspProcessor::~NativeBmwDspProcessor() = default;
void NativeBmwDspProcessor::setSampleRate(float sr) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (sr >= 8000 && std::fabs(sr - sampleRate_) > .5f) {
        sampleRate_ = sr;
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
    const auto& leftCfg = outputConfig(ids.left);
    const auto& rightCfg = outputConfig(ids.right);
    // Filter stages run L and R together (NEON pairs on arm64); see NativeBmwCrossovers.h.
    if (band == Band::Low) {
        NativeBmwDsp::processLowCrossover(left, leftCfg, right, rightCfg, xl, xr);
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
    if (!left.muted) {
        NativeBmwDsp::processCompressor(xl, leftCfg.compressor, dynamics(ids.left), detector_);
    } else {
        NativeBmwDsp::publishIdleMeter(dynamics(ids.left));
    }
    if (!right.muted) {
        NativeBmwDsp::processCompressor(xr, rightCfg.compressor, dynamics(ids.right), detector_);
    } else {
        NativeBmwDsp::publishIdleMeter(dynamics(ids.right));
    }
    xl *= left.gain;
    xr *= right.gain;
    // Per-bus brick-wall limiter (stereo-linked), right after the driver gain so its threshold
    // bounds the actual level reaching the driver regardless of how much gain is dialed in.
    // No-op while disabled; independent of the per-output compressor path above.
    BusLimiter& limiter = band == Band::Low ? busLimLow_ : band == Band::Mid ? busLimMid_ : busLimHigh_;
    const bool limiterEnabled = band == Band::Low   ? p_.busLimLowEnabled
                                : band == Band::Mid ? p_.busLimMidEnabled
                                                    : p_.busLimHighEnabled;
    const float limiterThreshDb = band == Band::Low   ? p_.busLimLowThreshDb
                                  : band == Band::Mid ? p_.busLimMidThreshDb
                                                      : p_.busLimHighThreshDb;
    if (limiterEnabled) {
        limiter.process(xl, xr, limiterThreshDb, busLimAttackMix_);
    } else {
        limiter.reset();
    }
}
void NativeBmwDspProcessor::idleBandChain(NativeBmwRouting::Band band) {
    using Band = NativeBmwRouting::Band;
    const BandOutputs ids = bandOutputs(band);
    (band == Band::Low ? busLimLow_ : band == Band::Mid ? busLimMid_ : busLimHigh_).reset();
    NativeBmwDsp::publishIdleMeter(dynamics(ids.left));
    NativeBmwDsp::publishIdleMeter(dynamics(ids.right));
}

void NativeBmwDspProcessor::processFrame(float& l, float& r) {
    using Band = NativeBmwRouting::Band;
    if (!p_.enabled) {
        return;
    }
    float sL = inputDcL_.run(l, dcR_), sR = inputDcR_.run(r, dcR_);
    if (peqEnabled_) {
        sL *= peqPreamp_;
        sR *= peqPreamp_;
        inputPeq_.process(sL, sR);
    }
    sL *= headroom_;
    sR *= headroom_;
    // Pre-crossover multiband compressor: full-range stereo, before any band split. Skipped
    // entirely while disabled (how it ships).
    if (p_.mbcEnabled) {
        mbc_.process(sL, sR, p_.mbcBand, detector_);
    }
    const auto routed = routing_.process({sL, sR});
    float lowL = routed[static_cast<std::size_t>(OutputId::LowLeft)],
          lowR = routed[static_cast<std::size_t>(OutputId::LowRight)];
    float midL = routed[static_cast<std::size_t>(OutputId::MidLeft)],
          midR = routed[static_cast<std::size_t>(OutputId::MidRight)];
    float highL = routed[static_cast<std::size_t>(OutputId::HighLeft)],
          highR = routed[static_cast<std::size_t>(OutputId::HighRight)];

    if (!p_.lpfPass) {
        processBandChain(Band::Low, lowL, lowR);
    } else {
        idleBandChain(Band::Low);
    }
    if (!p_.hpfPass) {
        processBandChain(Band::Mid, midL, midR);
    } else {
        idleBandChain(Band::Mid);
    }
    if (!p_.highXoPass) {
        processBandChain(Band::High, highL, highR);
    } else {
        // Deliberately NOT the same contract as lpfPass/hpfPass (which pass the raw routed
        // signal through unfiltered -- see the comment on the routed sum below). highXoPass is
        // the 3-way master-off switch's single write for High: a tweeter with no HPF ahead of it
        // is a real speaker-damage risk from raw bass, not just an audio-quality one the way an
        // unfiltered woofer/mid is, and leaving highL/highR at their raw routed value here would
        // also add a duplicate full-range path into sumToStereo(), breaking the "master toggle
        // off is bit-identical to today's 2-way output" contract. Zeroing here makes highXoPass
        // alone sufficient to silence High, independent of the per-output mute field -- no
        // reliance on the caller keeping two flags in lockstep.
        highL = 0.f;
        highR = 0.f;
        idleBandChain(Band::High);
    }

    // Per-output polarity, then mute. Indexed in OutputId order.
    std::array<float, NativeBmwRouting::kOutputCount> logical{
        {lowL, lowR, midL, midR, highL, highR}};
    for (std::size_t i = 0; i < logical.size(); ++i) {
        if (outputs_[i].polarityInverted) {
            logical[i] = -logical[i];
        }
        if (outputs_[i].muted) {
            logical[i] = 0;
        }
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
    if (p_.channelMute == 1) {
        oL = 0;
    }
    if (p_.channelMute == 2) {
        oR = 0;
    }
    oL = NativeBmwDsp::ftz(oL);
    oR = NativeBmwDsp::ftz(oR);
    // Option A measurement-mute bus brick-wall. Operates on logical L/R, AFTER the band sum,
    // tilt, post-gain and ch_mute, but BEFORE the master limiter and BEFORE the deliberate L/R
    // hardware swap below -- the left bus always filters the LowLeft/MidLeft chain. Fully
    // bypassed (single branch, no state advanced) whenever measurement mute is off.
    if (measBus_.active()) {
        measBus_.process(oL, oR);
    }
    // Master brick-wall limiter. enabled == false is a true bypass: the stage is skipped
    // entirely and nothing constrains the summed output level.
    if (p_.limiterEnabled) {
        limiter_.process(oL, oR);
    }
    // Stage-centering L/R alignment delay -- the last processing before the hardware swap. A
    // pure fractional delay on the already-limited summed bus (delaying a brick-walled signal
    // changes nothing about its level), independent of the per-output lowDelay*/midDelay*
    // lines and the per-output all-pass sections, which all run pre-sum.
    oL = NativeBmwDsp::ftz(stageDelayL_.run(oL));
    oR = NativeBmwDsp::ftz(stageDelayR_.run(oR));
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

const float* NativeBmwDspProcessor::process(const float* s, std::size_t n) {
    if (!s) {
        return s;
    }
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!p_.enabled) {
        return s;
    }
    auto* w = const_cast<float*>(s);
    for (std::size_t i = 0; i + 1 < n; i += 2) {
        NativeBmwDsp::applyMeasurementGenerator(measGen_, p_, w[i], w[i + 1]);
        capture_.tapIn(w[i], w[i + 1]);
        processFrame(w[i], w[i + 1]);
        capture_.tapOut(w[i], w[i + 1]);
    }
    return s;
}
const int16_t* NativeBmwDspProcessor::process(const int16_t* s, std::size_t n) {
    if (!s) {
        return s;
    }
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!p_.enabled) {
        return s;
    }
    constexpr float scale = 32768.f, invScale = 1.f / scale;
    auto* w = const_cast<int16_t*>(s);
    for (std::size_t i = 0; i + 1 < n; i += 2) {
        float l = static_cast<float>(w[i]) * invScale, r = static_cast<float>(w[i + 1]) * invScale;
        NativeBmwDsp::applyMeasurementGenerator(measGen_, p_, l, r);
        capture_.tapIn(l, r);
        processFrame(l, r);
        capture_.tapOut(l, r);
        w[i] = NativeBmwDsp::clampInt<int16_t>(l * scale);
        w[i + 1] = NativeBmwDsp::clampInt<int16_t>(r * scale);
    }
    return s;
}
const int32_t* NativeBmwDspProcessor::process(const int32_t* s, std::size_t n) {
    if (!s) {
        return s;
    }
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!p_.enabled) {
        return s;
    }
    constexpr float scale = 2147483648.f, invScale = 1.f / scale;
    auto* w = const_cast<int32_t*>(s);
    for (std::size_t i = 0; i + 1 < n; i += 2) {
        float l = static_cast<float>(w[i]) * invScale, r = static_cast<float>(w[i + 1]) * invScale;
        NativeBmwDsp::applyMeasurementGenerator(measGen_, p_, l, r);
        capture_.tapIn(l, r);
        processFrame(l, r);
        capture_.tapOut(l, r);
        w[i] = NativeBmwDsp::clampInt<int32_t>(l * scale);
        w[i + 1] = NativeBmwDsp::clampInt<int32_t>(r * scale);
    }
    return s;
}
void NativeBmwDspProcessor::readCompressorMeter(float* v, std::size_t n) const {
    if (!v || n < 6) {
        return;
    }
    const auto& ll = dynamics(OutputId::LowLeft);
    const auto& lr = dynamics(OutputId::LowRight);
    const auto& ml = dynamics(OutputId::MidLeft);
    const auto& mr = dynamics(OutputId::MidRight);
    v[0] = std::max(ll.inputDb.load(), lr.inputDb.load());
    v[1] = std::max(ll.outputDb.load(), lr.outputDb.load());
    v[2] = std::max(ll.gainReductionDb.load(), lr.gainReductionDb.load());
    v[3] = std::max(ml.inputDb.load(), mr.inputDb.load());
    v[4] = std::max(ml.outputDb.load(), mr.outputDb.load());
    v[5] = std::max(ml.gainReductionDb.load(), mr.gainReductionDb.load());
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
void NativeBmwDspProcessor::readMasterLimiterMeter(float* v, std::size_t n) const {
    if (!v || n < 1) {
        return;
    }
    v[0] = masterLimiterEnabledMeterFlag_.load(std::memory_order_relaxed)
               ? limiter_.grDb() : 0.f;
}

std::vector<double> NativeBmwDspProcessor::captureTruthSnapshot() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    std::vector<double> out;
    out.reserve(3 + NativeBmwRouting::kOutputCount * kTruthOutputWidth + 4 * (3 + 16 * 6));
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

    auto appendBank = [&out](const std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth>& values,
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
            out.push_back(NativeBmwDsp::peqBandSkipped(type, v[1]) ? 0.0 : 1.0);
        }
    };
    appendBank(inputPeqValues_, inputPeqValueCount_, inputPeq_);
    appendBank(lowPeqValues_, lowPeqValueCount_, lowPeq_);
    appendBank(midPeqValues_, midPeqValueCount_, midPeq_);
    appendBank(highPeqValues_, highPeqValueCount_, highPeq_);
    return out;
}

void NativeBmwDspProcessor::startCapture() {
    // The up-to-~46MB assign()/zero below used to run while holding stateMutex_, the same lock
    // process() takes once per audio buffer -- if a capture (re)allocation landed while the audio
    // thread was waiting on that lock, the allocation/zeroing time added directly to audio-thread
    // latency (an audible glitch right when the user starts a capture). Build the new buffers
    // (when actually needed) into locals first, outside any lock, then swap them in under a
    // second, brief lock -- vector::swap is O(1), no memory touched while locked.
    std::size_t capacity;
    bool needsRealloc;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        capacity = static_cast<std::size_t>(sampleRate_ * kCaptureMaxSeconds);
        needsRealloc = !capture_.sizedFor(capacity);
    }
    std::vector<float> rawInL, rawInR, outL, outR;
    if (needsRealloc) {
        rawInL.assign(capacity, 0.f);
        rawInR.assign(capacity, 0.f);
        outL.assign(capacity, 0.f);
        outR.assign(capacity, 0.f);
    }
    std::lock_guard<std::mutex> lock(stateMutex_);
    // sampleRate_ could in principle have changed between the two critical sections above (a
    // concurrent setSampleRate() call) -- rare and harmless: worst case this capture's buffers
    // are sized for the sample rate current when this call started, not a rate that changed
    // mid-call. The recorder's capacity always reflects the size actually in use, so nothing
    // downstream can index past what's real.
    capture_.start(needsRealloc, rawInL, rawInR, outL, outR);
}

void NativeBmwDspProcessor::stopCapture() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    capture_.stop();
}

std::size_t NativeBmwDspProcessor::captureFrameCount() const {
    return capture_.frameCount();
}

std::unique_ptr<NativeBmwDspProcessor::CaptureSnapshot>
NativeBmwDspProcessor::takeCaptureSnapshot() {
    auto snapshot = std::make_unique<CaptureSnapshot>();
    std::lock_guard<std::mutex> lock(stateMutex_);
    snapshot->sampleRate = sampleRate_;
    snapshot->frames =
        capture_.take(snapshot->rawInL, snapshot->rawInR, snapshot->outL, snapshot->outR);
    return snapshot;
}
