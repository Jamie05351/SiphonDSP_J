#ifndef SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H
#define SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H

#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <memory>
#include <vector>
#include "NativeBmwCrossovers.h"
#include "NativeBmwDelayGain.h"
#include "NativeBmwDspParams.h"
#include "NativeBmwDynamics.h"
#include "NativeBmwFilters.h"
#include "NativeBmwMeasurement.h"
#include "NativeBmwMeasurementGenerator.h"
#include "NativeBmwRouting.h"

// Thin orchestrator over the NativeBmwDsp modules: parses/validates config (configure(),
// configurePeq() -- NativeBmwDspProcessorConfig.cpp), rebuilds only what changed (applyDirty()),
// and wires the per-frame signal path (processFrame()). The DSP itself lives in the modules:
//   NativeBmwFilters      Biquad, stereo PEQ bank, coefficient builders
//   NativeBmwCrossovers   per-output crossover chains, all-pass sections, tilt
//   NativeBmwDynamics     per-output compressor, master + bus limiters, multiband compressor
//   NativeBmwDelayGain    delay lines, DC-blocking input stage
//   NativeBmwMeasurement  measurement-mute bus, signal generator hookup, capture
//   NativeBmwDspMath      shared pure math (ftz, clamps, dB, SVF step, NEON helpers)

class NativeBmwDspProcessor {
public:
    // 42..45 were Mono Bass enable/freq/blend/makeup. The feature was removed (disabled by
    // default, never proven useful) and the slots were reclaimed in place rather than by
    // shrinking the array -- same rationale as 139..142 below. 43..45 are still not read in
    // configure(); a leftover value from an older save there is simply ignored. 42 is now
    // INDEX_CROSSOVER_TYPE_MIGRATED (Kotlin-only migration marker, same pattern as 140/181/
    // 188/191) -- the "flag if one turns out to be needed" case this comment used to leave open.
    //
    // 139..142 were the Pultec-style bass boost/cut stage; the feature was removed (unused,
    // native processing deleted below) and the slots were reclaimed in place rather than by
    // shrinking the array -- shrinking would shift every index after it.
    //   139 -> reclaimed: measurement-mute bus brick-wall stopband offset, in octaves. 0 keeps
    //          the LR8 corner exactly on the opposite band's crossover (original behaviour);
    //          >0 walks it that many octaves into the stopband so the isolated band's own
    //          transition region is left intact for measurement. Read in configure().
    //   140 -> reclaimed: one-time "stopband offset migrated" marker, written by
    //          NativeBmwDspValues.kt so an existing saved config picks up the new default.
    //          Kotlin-only -- never read in configure().
    //   141, 142 -> reclaimed a second time: briefly held a Mid-band independent LPF
    //          enable/corner (removed, wrong fix), now the stage-centering L/R alignment delay
    //          (ms) applied to the summed stereo bus after the master limiter -- see
    //          Params::stageDelay* and processFrame's tail. Read in configure(). (This comment
    //          previously said "unused... not read in configure()", which stopped being true
    //          once that reclaim landed -- don't trust a slot's history here without also
    //          checking configure()'s actual v[] reads.)
    // 143 (INDEX_DELAY_LINKED) is UI-only -- see NativeBmwDspValues.kt -- and is intentionally
    // never read in configure() either; it only has to be included here so the array length
    // check (NativeBmwDspJni.cpp) accepts the array Kotlin actually sends.
    //
    // 144..191 -- pre-crossover multiband compressor (MBC) + per-bus output limiter + master
    // limiter, added in the 144 -> 192 growth. Indices match NativeBmwDspValues.INDEX_MBC_* /
    // INDEX_BUS_LIMITER_* / INDEX_MASTER_LIMITER_*:
    //   144      MBC global enable            145      MBC dry/wet mix (percent)
    //   146..148 MBC crossover splits (Hz)    149..180 4 bands x 8 params
    //   181      Kotlin-only migration marker -- never read here
    //   182..184 Low-bus limiter  (enable, threshold dBFS, release ms)
    //   185..187 Mid-bus limiter  (enable, threshold dBFS, release ms)
    //   188      Kotlin-only "legacy per-output compressor force-disabled" marker -- not read here
    //   189..190 master brick-wall limiter (enable, threshold dBFS) -- both read in configure()
    //   191      Kotlin-only "master limiter migrated" marker -- never read here
    //   192      measurement generator type (0 off, 1 sweep, 2 pink periodic noise)
    //   193..196 sweep start Hz, end Hz, duration s, level dBFS
    //   197..198 pink noise period s, level dBFS
    //   199      sweep timing-reference toggle (only meaningful while type == 1)
    //   200      timing-reference channel design: 0 combined (chirp+sweep both channels),
    //            1 split (chirp-only / sweep-only) -- only meaningful while 199 is set
    //   201..202 timing-reference Mid-band sweep start Hz, end Hz
    //   203..204 timing-reference Low-band sweep start Hz, end Hz
    //
    // 205..209 -- Mid's optional upper (Mid/High) bandpass corner, added in the 205 -> 210
    // growth (see docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md). Only Mid has this field; Low/High
    // don't. Indices match NativeBmwDspValues.INDEX_MID_UPPER_XO* / midUpperXoIndex():
    //   205      Mid Left upper corner, Hz         206  Mid Left upper corner enabled
    //   207      Mid Right upper corner, Hz         208  Mid Right upper corner enabled
    //   209      Kotlin-only migration marker -- never read here
    //
    // 210..261 -- the High band, added in the 210 -> 262 growth (see
    // docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md). A genuinely new third output, not carried-but-
    // ignored like Low/Mid's mutual subsonic fields -- ships silent via both highXoPass and
    // outputConfigs_[High*].muted (both seeded true). Unlike lpfPass/hpfPass (which only bypass
    // the crossover filter, letting the raw routed signal through -- an accepted, pre-existing
    // risk for Low/Mid), highXoPass=true fully silences High: a tweeter with no HPF ahead of it
    // is a real speaker-damage risk from raw bass, and it also doubles as the 3-way master-off
    // switch's single write for High, independent of the per-output mute field:
    //   210      highXoPass (true = High fully silent; NOT the same contract as lpfPass/hpfPass)
    //   211..212 highGainL, highGainR                 213..214 highDelayL, highDelayR
    //   215..218 routing: High Left [fromFrontL, fromFrontR], High Right [fromFrontL, fromFrontR]
    //   219..234 all-pass: 2 outputs x 2 sections x [enabled, order, freq, q]
    //   235..260 output-config: 2 outputs x 13-wide, same layout as the Low/Mid block
    //            (crossoverFreq [High's single HPF corner], crossoverType, subsonicEnabled/Freq
    //            [carried but ignored, same as Mid], mute, invert, compressor 7-tuple)
    //   261      Kotlin-only migration marker -- never read here
    //
    // 262..265 -- the High-bus brick-wall limiter, added in the 262 -> 266 growth. Same
    // contract as the Low/Mid bus limiters at 182..187; ships disabled:
    //   262      enabled   263  threshold dBFS   264  release ms
    //   265      Kotlin-only migration marker -- never read here
    enum : std::size_t { kLegacyConfigSize = 86, kConfigSize = 266 };
    enum : std::size_t {
        kMaxPeqSectionsPerChannel = NativeBmwDsp::kMaxPeqSectionsPerChannel,
        kPeqBandWidth = NativeBmwDsp::kPeqBandWidth,
    };
    enum : unsigned { kDelayLineCapacity = NativeBmwDsp::kDelayLineCapacity };
    // Stage-centering L/R alignment delay on the summed stereo bus (post master limiter). Sized
    // for a wider range than the sub-millisecond crossover/driver alignment: 10 ms cap -> 480
    // samples @ 48 kHz, 960 @ 96 kHz. 1024 keeps the full 10 ms available up to ~102 kHz. Kept
    // separate from kDelayLineCapacity so the per-output / limiter Delay instances stay 256.
    enum : unsigned { kStageDelayCapacity = NativeBmwDsp::kStageDelayCapacity };
    static constexpr float kStageDelayMaxMs = 10.f;
    // kRoutingBase is the one literal anchor here (the first slot after the earlier scalar
    // fields, index 46) -- kAllPassBase/kOutputSchemaMarkerIndex/kOutputConfigBase are all
    // derived from it and the routing/all-pass block sizes, rather than being independent
    // literals that would silently misalign with configure()'s actual v[] reads (and with
    // NativeBmwDspSchema.h's own copies of these same offsets) if kLegacyOutputCount/kInputCount/
    // kAllPassSectionsPerOutput ever changed without every bare-literal copy being updated too.
    //
    // Deliberately derived from NativeBmwRouting::kLegacyOutputCount (4), NOT kOutputCount (6):
    // these four offsets are the persisted-schema layout every existing saved config already
    // relies on. If they were derived from kOutputCount instead, adding the High band would
    // silently shift every index from 87 onward (the whole 139->205 tail: measurement-mute,
    // MBC, bus limiters, master limiter, measurement generator) and corrupt every existing
    // user's save on their next launch. High's own persisted block lives in the schema's tail
    // (appended after index 204) instead of growing this block in place. See
    // docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    enum : std::size_t {
        kRoutingBase = 46,
        kRoutingValueCount = NativeBmwRouting::kLegacyOutputCount * NativeBmwRouting::kInputCount,
        kAllPassBase = kRoutingBase + kRoutingValueCount,
        kAllPassValueWidth = 4,
        kAllPassValueCount = NativeBmwRouting::kLegacyOutputCount *
                             NativeBmwRouting::kAllPassSectionsPerOutput * kAllPassValueWidth,
        kOutputSchemaMarkerIndex = kAllPassBase + kAllPassValueCount,
        kOutputConfigBase = kOutputSchemaMarkerIndex + 1,
        kOutputConfigWidth = 13,
    };
    // Capture buffers hold this many seconds at whatever sampleRate_ is current when
    // startCapture() (re)allocates them -- fixed duration, not a wraparound ring: capture
    // auto-stops once full rather than overwriting the start of a long take.
    static constexpr float kCaptureMaxSeconds = 30.f;

    struct CaptureExportResult {
        float peakInDb = -100.f;
        float peakOutDb = -100.f;
        float nullTestRmsDb = -100.f;
    };

    // Owns a stopped take independently of the processor. Export needs no audio
    // lock and remains safe after a new capture starts or the engine is closed.
    struct CaptureSnapshot {
        std::vector<float> rawInL, rawInR, outL, outR;
        std::size_t frames = 0;
        float sampleRate = 48000.f;
        bool exportWav(const char* rawInPath, const char* outPath,
                       CaptureExportResult& result) const;
    };

    // setSampleRate()/configure()/configurePeq() (UI/config thread) and process() (audio thread)
    // both touch p_/routing_/outputs_/outputConfigs_/PEQ state, so each takes stateMutex_ --
    // configure family for its whole write, process() once per call (not per sample) around the
    // whole buffer -- to avoid a torn read/write of that state. The audio-thread side is a brief,
    // uncontended lock/unlock in the common case, since config changes are rare relative to how
    // often process() runs.
    NativeBmwDspProcessor();
    ~NativeBmwDspProcessor();
    void setSampleRate(float sampleRate);
    bool configure(const float* values, std::size_t count);
    bool configurePeq(bool enabled, float preampDb, const double* fullBands,
                      std::size_t fullValueCount, const double* lowBands, std::size_t lowValueCount,
                      const double* midBands, std::size_t midValueCount, const double* highBands,
                      std::size_t highValueCount);
    const int16_t* process(const int16_t* samples, std::size_t sampleCount);
    const int32_t* process(const int32_t* samples, std::size_t sampleCount);
    const float* process(const float* samples, std::size_t sampleCount);
    void readCompressorMeter(float* values, std::size_t count) const;
    // 12 floats: 4 MBC bands x [inputDb, outputDb, gainReductionDb]. All-idle (-60/-60/0)
    // while the multiband compressor is disabled. Lock-free, same discipline as
    // readCompressorMeter -- reads atomics published by MultibandCompressor::process().
    void readMbcMeter(float* values, std::size_t count) const;
    // 3 floats: [lowBusGrDb, midBusGrDb, highBusGrDb] -- gain reduction of the per-bus
    // brick-wall limiters.
    // 0 for a bus whose limiter is disabled. Lock-free; published by BusLimiter::process().
    void readBusLimiterMeter(float* values, std::size_t count) const;
    // 1 float: gain reduction (dB, >= 0) of the master brick-wall limiter on the summed output.
    // 0 while the limiter is bypassed. Lock-free; published by MasterLimiter::process().
    void readMasterLimiterMeter(float* values, std::size_t count) const;

    // Read-only whole-state snapshot for the native-truth debug screen (proves what this
    // processor is actually running, as opposed to what Kotlin/UI last requested). Takes
    // stateMutex_ once -- the same brief, uncontended-in-practice lock configure()/configurePeq()
    // already use -- so the crossover and PEQ data returned together represent one consistent
    // instant: a config change landing mid-read can only be entirely before or entirely after
    // this call, never torn across it. Not const (stateMutex_ isn't mutable, matching every other
    // lock-taking method here).
    //
    // Every value is a double. Layout:
    //   [0] sampleRate
    //   [1] peqEnabled (0/1)                  [2] peqPreampDb
    //   then 6 fixed-width (kTruthOutputWidth doubles) output blocks, in OutputId order
    //   (LowLeft, LowRight, MidLeft, MidRight, HighLeft, HighRight):
    //     crossoverFreqHz, crossoverType (OutputConfig::CrossoverType ordinal), subsonicEnabled
    //     (0/1; Mid/High outputs are always 0 -- subsonic only ever applies to Low, see
    //     processLowCrossover/processMidCrossover/processHighCrossover), subsonicFreqHz, muted,
    //     polarityInverted, gainDb (from the runtime gain actually multiplied in, i.e. what's
    //     really applied, not replayed from a config field), delayMs,
    //     stage1: topology (Biquad::Topology ordinal), a1, a2, a3, m0, m1, m2, opA
    //     stage2: same 8 fields
    //     stage3: same 8 fields (Mid's optional upper-corner LPF; identity/inert for Low/High,
    //             which never touch crossover3/4 -- reported truthfully either way, same
    //             "actual installed state, not requested" contract as everything else here)
    //     stage4: same 8 fields
    //   then 4 variable-length PEQ bank blocks, in order Full, Low, Mid, High:
    //     rawBandCount, leftActiveCount, rightActiveCount (the last two are PeqBank::leftCount/
    //     rightCount -- the actual number of Biquads process() runs for that bank/channel),
    //     then rawBandCount * [freqHz, gainDb, q, type, channel, active (0/1)]. "active" mirrors
    //     configurePeqLocked's build() skip (peqBandSkipped()) -- a band this processor discarded
    //     as a no-op is reported as inactive even though its raw values are still shown.
    enum : std::size_t { kTruthOutputWidth = 40 };
    std::vector<double> captureTruthSnapshot();

    // Raw-input/final-output capture for the in-app measurement tool. (Re)allocates the capture
    // buffers at the current sample rate, so it's a control-thread call, never made from
    // process() itself -- same "brief, uncontended lock" discipline as configure().
    void startCapture();
    void stopCapture();
    // Acquire-paired with CaptureRecorder::tapOut()'s release store: any caller that reads this (UI-thread
    // progress polling) is guaranteed to see every capture-buffer
    // write up to the returned count, not just an up-to-date index with possibly-stale/torn
    // sample data behind it on a weakly-ordered CPU (this app's target, ARM64).
    std::size_t captureFrameCount() const;
    // Stops capture and transfers its buffers in O(1) under the audio lock.
    // The take is consumed; startCapture() allocates fresh buffers for the next take.
    std::unique_ptr<CaptureSnapshot> takeCaptureSnapshot();

private:
    enum Dirty : uint32_t {
        DirtyNone = 0,
        DirtyGains = 1u << 0,
        DirtySubsonic = 1u << 1,
        DirtyLowXo = 1u << 2,
        DirtyMidXo = 1u << 3,
        DirtyDelays = 1u << 4,
        DirtyTilt = 1u << 5,
        DirtyCompTiming = 1u << 6,
        DirtyCompState = 1u << 7,
        DirtyPolarity = 1u << 9,
        DirtyMeasBus = 1u << 10,
        DirtyMbc = 1u << 11,  // crossover-tree coefficients (split freqs) -- clears filter state
        DirtyMbcTiming = 1u << 12,  // attack/release/makeup/mix scalars only -- no filter touch
        DirtyMbcState = 1u << 13,   // reset detector cells + tree state (enable / stereo-link flip)
        DirtyBusLimiter = 1u << 14,
        DirtyLimiter = 1u << 16,  // master limiter ceiling scalar (threshold dB); enable read live
        DirtyMeasGen = 1u << 17,  // measurement generator type/params -- restarts the run
        DirtyHighXo = 1u << 18,
        DirtyHighResume = 1u << 19,  // High un-silenced (highXoPass on -> off): clear its state
        DirtyAll = 0xffffffffu,
    };

    using Biquad = NativeBmwDsp::Biquad;
    using PeqBank = NativeBmwDsp::PeqBank;
    using OutputConfig = NativeBmwDsp::OutputConfig;
    using OutputRuntime = NativeBmwDsp::OutputRuntime;
    using CompressorParams = NativeBmwDsp::CompressorParams;
    using CompressorState = NativeBmwDsp::CompressorState;
    using BusLimiter = NativeBmwDsp::BusLimiter;
    using Params = NativeBmwDsp::Params;

    Params p_;

    void processFrame(float& l, float& r);
    // One band's post-routing chain on its L/R outputs: crossover, PEQ, all-pass, delay,
    // per-output compressor (idle meter while muted), driver gain, then the band's
    // stereo-linked bus limiter.
    void processBandChain(NativeBmwRouting::Band band, float& xl, float& xr);
    // Bookkeeping for a band whose chain is skipped (lpfPass/hpfPass/highXoPass): resets its bus
    // limiter and keeps its compressor meters publishing idle.
    void idleBandChain(NativeBmwRouting::Band band);
    // configurePeq()'s body, assuming stateMutex_ is already held by the caller. Exists so
    // rebuildAll() -- itself called from setSampleRate() while already holding the lock -- can
    // reapply the PEQ banks without taking stateMutex_ a second time; std::mutex is
    // non-recursive, so configurePeq() (which takes the lock itself) would deadlock there.
    bool configurePeqLocked(bool enabled, float preampDb, const double* fullBands,
                            std::size_t fullValueCount, const double* lowBands,
                            std::size_t lowValueCount, const double* midBands,
                            std::size_t midValueCount, const double* highBands,
                            std::size_t highValueCount);
    void rebuildAll();
    void applyDirty(uint32_t dirty);
    void rebuildGains();
    void rebuildSubsonic();
    void rebuildLowCrossover();
    void rebuildMidCrossover();
    void rebuildHighCrossover();
    void updateDelays();
    void rebuildCompressorTiming();
    void rebuildBusLimiter();
    void rebuildPolarityAndMute();
    void rebuildAllPass();
    void resetDynamics();
    OutputRuntime& output(NativeBmwRouting::OutputId id) {
        return outputs_[static_cast<std::size_t>(id)];
    }
    const OutputRuntime& output(NativeBmwRouting::OutputId id) const {
        return outputs_[static_cast<std::size_t>(id)];
    }
    OutputConfig& outputConfig(NativeBmwRouting::OutputId id) {
        return outputConfigs_[static_cast<std::size_t>(id)];
    }
    const OutputConfig& outputConfig(NativeBmwRouting::OutputId id) const {
        return outputConfigs_[static_cast<std::size_t>(id)];
    }
    CompressorState& dynamics(NativeBmwRouting::OutputId id) {
        return outputDynamics_[static_cast<std::size_t>(id)];
    }
    const CompressorState& dynamics(NativeBmwRouting::OutputId id) const {
        return outputDynamics_[static_cast<std::size_t>(id)];
    }

    // Per-output state, indexed by OutputId.
    std::array<OutputRuntime, NativeBmwRouting::kOutputCount> outputs_{};
    std::array<OutputConfig, NativeBmwRouting::kOutputCount> outputConfigs_{};
    std::array<CompressorState, NativeBmwRouting::kOutputCount> outputDynamics_{};
    NativeBmwRouting::RoutingMatrix routing_{};
    float sampleRate_ = 48000.0f;

    // Input stage.
    NativeBmwDsp::DcBlocker inputDcL_, inputDcR_;
    float dcR_ = 0.0f;

    // PEQ: four stereo banks plus the raw band values they were built from (kept so rebuildAll()
    // can rebuild them at a new sample rate, and for captureTruthSnapshot()).
    PeqBank inputPeq_, lowPeq_, midPeq_, highPeq_;
    bool peqEnabled_ = false;
    float peqPreampDb_ = 0, peqPreamp_ = 1;
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> inputPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> lowPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> midPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> highPeqValues_{};
    std::size_t inputPeqValueCount_ = 0, lowPeqValueCount_ = 0, midPeqValueCount_ = 0,
                highPeqValueCount_ = 0;

    // Gains, tilt and the summed-bus stages.
    float headroom_ = 1, postGainL_ = 1, postGainR_ = 1;
    NativeBmwDsp::Tilt tilt_;
    // Stage-centering L/R alignment delay lines on the summed stereo bus (see Params::stageDelay*
    // and processFrame's tail). delay (in samples) is set by updateDelays().
    NativeBmwDsp::StageDelay stageDelayL_, stageDelayR_;

    // Dynamics.
    NativeBmwDsp::DetectorTiming detector_;
    NativeBmwDsp::MasterLimiter limiter_;
    NativeBmwDsp::MultibandCompressor mbc_;
    // Per-bus output limiters (Low, Mid, High). ~1 ms attack shared; release per bus.
    float busLimAttackMix_ = 1.f;
    BusLimiter busLimLow_, busLimMid_, busLimHigh_;
    // Mirrors of p_.mbcEnabled/busLim{Low,Mid,High}Enabled/limiterEnabled, published by
    // configure() right after p_ = next. The read*Meter() functions are const and take no lock,
    // so they can't safely read the plain bools inside p_ directly -- that's a data race against
    // configure()'s whole-struct p_ = next assignment from the control thread. Same "atomic
    // mirror, no lock" discipline the modules already use for the GR meters themselves.
    std::atomic<bool> mbcEnabledMeterFlag_{false};
    std::atomic<bool> busLimLowEnabledMeterFlag_{false}, busLimMidEnabledMeterFlag_{false};
    std::atomic<bool> busLimHighEnabledMeterFlag_{false};
    std::atomic<bool> masterLimiterEnabledMeterFlag_{true};

    // Measurement.
    NativeBmwDsp::MeasurementBus measBus_;
    // Measurement signal generator (own module, see NativeBmwMeasurementGenerator.h). Per-instance
    // state, only ever touched from the process() loops (audio thread, while p_.measGenType != 0)
    // and applyDirty() (control thread, under stateMutex_ like everything else here).
    NativeBmwMeasurementGenerator measGen_;
    // Capture state -- see startCapture()/stopCapture()/takeCaptureSnapshot(). Protected by
    // stateMutex_ like everything else here (including from inside process() itself, which
    // already holds stateMutex_ for the whole buffer); captureFrameCount() is the lock-free
    // exception, see CaptureRecorder.
    NativeBmwDsp::CaptureRecorder capture_;

    std::mutex stateMutex_;
};

#endif
