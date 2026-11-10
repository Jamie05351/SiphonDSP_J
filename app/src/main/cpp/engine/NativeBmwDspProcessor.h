#ifndef SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H
#define SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H

#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <memory>
#include <string>
#include <vector>
#include "NativeBmwConvolver.h"
#include "NativeBmwCrossovers.h"
#include "NativeBmwDelayGain.h"
#include "NativeBmwDspParams.h"
#include "NativeBmwDynamics.h"
#include "NativeBmwFilters.h"
#include "NativeBmwHandoff.h"
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
//   NativeBmwConvolver    the stereo FIR convolver, last in the chain
//   NativeBmwDspMath      shared pure math (ftz, clamps, dB, SVF step, NEON helpers)

class NativeBmwDspProcessor {
public:
    // The flat config's layout -- every slot, its default, and whether configure() reads it -- is
    // generated from app/src/main/schema/native_bmw_dsp.schema into NativeBmwDspSchema.h (with the
    // Kotlin side and docs/NATIVE_BMW_DSP_SCHEMA.md). kConfigSize is pinned to it by a static_assert
    // there. Slots are append-only: saves are stored by index.
    enum : std::size_t { kLegacyConfigSize = 86, kConfigSize = 309 };
    enum : std::size_t {
        kMaxPeqSectionsPerChannel = NativeBmwDsp::kMaxPeqSectionsPerChannel,
        kPeqBandWidth = NativeBmwDsp::kPeqBandWidth,
    };
    enum : unsigned { kDelayLineCapacity = NativeBmwDsp::kDelayLineCapacity };
    // Multi-millisecond delay ring size and range, used by the virtual-centre feeds.
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

    // Threading. The running DSP state belongs to whoever holds ownership_: the audio thread for
    // the length of each process() buffer, which it claims and releases with one atomic each and
    // never waits for while audio is flowing. No call here takes a lock on the audio thread.
    //
    // configure() and configurePeq() (config thread) validate and build their update on the
    // calling thread and hand it over through a lock-free triple buffer (configHandoff_ /
    // peqHandoff_); the owner installs it at the start of its next buffer. Nothing installed runs
    // allocating work on the audio thread: the measurement generator, the one stage whose rebuild
    // allocates, is built with the update and swapped in (see ParsedConfig::generator).
    //
    // setSampleRate(), the truth snapshot and the capture calls are control requests: one at a
    // time (controlMutex_), each posted to the audio thread, which serves it at the start of its
    // next buffer -- an O(1) swap, a flag or a fill of a buffer the caller reserved -- while the
    // caller waits. While no audio is flowing (no buffer for kAudioIdleNs, or the call is on the
    // thread that ran the last buffer) the caller claims ownership itself and serves the request
    // on its own thread. Only then can a buffer that starts at that moment wait for it: a stream
    // resuming, never one in progress. A sample-rate change is the exception: it rebuilds
    // everything (and allocates), so the caller always serves it itself, between buffers. The
    // pipeline changes rate before audio flows; were a live stream to change rate, its next
    // buffer would wait out the rebuild, as it would have for the old lock.
    NativeBmwDspProcessor();
    ~NativeBmwDspProcessor();
    void setSampleRate(float sampleRate);
    bool configure(const float* values, std::size_t count);
    bool configurePeq(bool enabled, float preampDb, const double* fullBands,
                      std::size_t fullValueCount, const double* lowBands, std::size_t lowValueCount,
                      const double* midBands, std::size_t midValueCount, const double* highBands,
                      std::size_t highValueCount);
    // Processes one interleaved stereo buffer of `sampleCount` samples in place. Allocation-free.
    const int16_t* process(const int16_t* samples, std::size_t sampleCount);
    const int32_t* process(const int32_t* samples, std::size_t sampleCount);
    const float* process(const float* samples, std::size_t sampleCount);
    // 12 floats: 4 MBC bands x [inputDb, outputDb, gainReductionDb]. All-idle (-60/-60/0)
    // while the multiband compressor is disabled. Lock-free: reads atomics published by
    // MultibandCompressor::process().
    void readMbcMeter(float* values, std::size_t count) const;
    // 3 floats: [lowBusGrDb, midBusGrDb, highBusGrDb] -- gain reduction of the per-bus
    // brick-wall limiters.
    // 0 for a bus whose limiter is disabled. Lock-free; published by BusLimiter::process().
    void readBusLimiterMeter(float* values, std::size_t count) const;
    // 1 float: gain reduction (dB, >= 0) of the master brick-wall limiter on the summed output.
    // 0 while the limiter is bypassed. Lock-free; published by MasterLimiter::process().
    void readMasterLimiterMeter(float* values, std::size_t count) const;
    // 2 floats: [centreWeight 0..1, centreRmsDb] of the virtual centre. Idle [0, -60] while the
    // virtual stage is disabled. Lock-free; published by VirtualSourceStage::process().
    void readVirtualMeter(float* values, std::size_t count) const;

    // Read-only whole-state snapshot for the native-truth debug screen (proves what this
    // processor is actually running, as opposed to what Kotlin/UI last requested). A control
    // request: the owner installs any pending update first and fills a buffer this call reserved
    // beforehand, so it reports every accepted configure()/configurePeq(), the crossover and PEQ
    // data returned together represent one consistent instant, and the audio thread allocates
    // nothing to produce it.
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
    //     parsePeq()'s build() skip (peqBandSkipped()) -- a band this processor discarded
    //     as a no-op is reported as inactive even though its raw values are still shown.
    enum : std::size_t { kTruthOutputWidth = 40 };
    std::vector<double> captureTruthSnapshot();

    // Raw-input/final-output capture for the in-app measurement tool. Control requests:
    // startCapture() (re)allocates the capture buffers at the current sample rate on the calling
    // thread when their size changes, and the owner swaps them in; the retired buffers are freed
    // back on the calling thread too.
    void startCapture();
    void stopCapture();
    // Acquire-paired with CaptureRecorder::tapOut()'s release store: any caller that reads this (UI-thread
    // progress polling) is guaranteed to see every capture-buffer
    // write up to the returned count, not just an up-to-date index with possibly-stale/torn
    // sample data behind it on a weakly-ordered CPU (this app's target, ARM64).
    std::size_t captureFrameCount() const;
    // Stops capture and transfers its buffers out in O(1) (a control request).
    // The take is consumed; startCapture() allocates fresh buffers for the next take.
    std::unique_ptr<CaptureSnapshot> takeCaptureSnapshot();

    // ---- Convolver IR (see NativeBmwConvolver.h) ----
    // Control calls, one at a time (convolverMutex_), never on the audio thread. A load reads
    // the WAV, resamples it to the stream rate when the rates differ, builds the kernel and hands
    // it to the audio thread lock-free; the stage crossfades to it. The decoded source is kept so
    // a later sample-rate change rebuilds the kernel without reading the file again. The
    // enable/bypass and gain are ordinary config slots (v[307..308]).
    struct ConvolverInfo {
        bool loaded = false;
        int channels = 0;
        double sourceRate = 0.0;
        std::size_t sourceFrames = 0;   // after trailing-silence trim, at sourceRate
        double streamRate = 0.0;
        std::size_t streamFrames = 0;   // what the kernel runs, at streamRate
        std::size_t partitions = 0;
        double peakGainDb = -200.0;     // the IR's loudest frequency, dB
        std::string error;              // why the last load / rebuild failed ("" when it didn't)
    };
    // False with a user-facing message in error; the previous IR (if any) keeps running.
    bool loadConvolverIr(const char* wavPath, std::string& error);
    bool loadConvolverIr(NativeBmwDsp::ConvolverIr ir, std::string& error);
    void clearConvolverIr();
    ConvolverInfo convolverInfo();
    // Lock-free: a kernel is installed on the audio side (it lands at the next buffer).
    bool convolverKernelActive() const { return convolver_.kernelActive(); }

private:
    enum Dirty : uint32_t {
        DirtyNone = 0,
        DirtyGains = 1u << 0,
        DirtySubsonic = 1u << 1,
        DirtyLowXo = 1u << 2,
        DirtyMidXo = 1u << 3,
        DirtyDelays = 1u << 4,
        DirtyTilt = 1u << 5,
        DirtyTiltResume = 1u << 8,  // tilt switched on: clear the state it froze with when off
        DirtyPolarity = 1u << 9,
        DirtyMeasBus = 1u << 10,
        DirtyMbc = 1u << 11,  // crossover-tree coefficients (split freqs) -- keeps filter state
        DirtyMbcTiming = 1u << 12,  // attack/release/makeup/mix scalars only -- no filter touch
        DirtyMbcState = 1u << 13,   // reset detector cells + tree state (enable / stereo-link flip)
        DirtyBusLimiter = 1u << 14,
        DirtyLimiter = 1u << 16,  // master limiter ceiling scalar (threshold dB); enable read live
        DirtyMeasGen = 1u << 17,  // measurement generator type/params -- restarts the run
        DirtyHighXo = 1u << 18,
        DirtyHighResume = 1u << 19,  // High's chain runs again (highXoPass/highPass off): clear its state
        DirtyVirtual = 1u << 20,       // virtual-stage coefficients (keeps filter/delay state)
        DirtyVirtualState = 1u << 21,  // virtual stage switched on/off: clear its state + meter
        DirtyLowResume = 1u << 22,  // Low's chain runs again (lpfPass off): clear its state
        DirtyMidResume = 1u << 23,  // Mid's chain runs again (hpfPass off): clear its state
        DirtyLoudness = 1u << 24,   // volume or loudness settings changed: retune (keeps state)
        DirtyLoudnessResume = 1u << 25,  // loudness switched on: clear the state it froze with
        DirtyMbcEnable = 1u << 26,  // the MBC as a whole switched on/off: start at the set Mix
        DirtyMonoBass = 1u << 27,        // Mono Bass split frequency: retune (keeps state)
        DirtyMonoBassResume = 1u << 28,  // Mono Bass switched on from silent: clear its state
        DirtyVirtualBass = 1u << 29,        // Virtual Bass cut, range or response: retune (keeps state)
        DirtyVirtualBassResume = 1u << 30,  // Virtual Bass switched on from silent: clear its state
        DirtyAll = 0xffffffffu,
    };

    using Biquad = NativeBmwDsp::Biquad;
    // Host tests only (native-tests/concurrency_test.cpp): holds ownership like process().
    friend struct NativeBmwDspProcessorTestAccess;

    using PeqBank = NativeBmwDsp::PeqBank;
    using OutputConfig = NativeBmwDsp::OutputConfig;
    using OutputRuntime = NativeBmwDsp::OutputRuntime;
    using BusLimiter = NativeBmwDsp::BusLimiter;
    using Params = NativeBmwDsp::Params;

    Params p_;

    // The chain runs in chunks of up to kChunkFrames frames (see processChunk()): the input
    // stages frame by frame into one lane per output, each band's chain over the whole chunk, then
    // the output stages frame by frame. Same arithmetic in the same order per band as a
    // frame-by-frame loop, so bit-identical to it.
    enum : std::size_t { kChunkFrames = 64 };
    void processChunk(float* interleaved, std::size_t frames);
    // DC blocker through the routing matrix: one input frame to the six routed outputs.
    std::array<float, NativeBmwRouting::kOutputCount> processInputStages(float l, float r);
    // One band's lanes over the chunk: its chain, its bypass latency, or silence.
    void processBandStage(NativeBmwRouting::Band band, std::size_t frames);
    // Per-output polarity/mute, the sum, tilt, post gain, channel mute and measurement bus: the
    // six band outputs of one frame to logical L / R.
    void processOutputStages(std::array<float, NativeBmwRouting::kOutputCount> logical, float& l,
                             float& r);
    // The master limiter, master volume, loudness and the hardware swap.
    void processFrameBack(float& l, float& r);
    // The float processing for one buffer.
    void processBlock(float* interleaved, std::size_t sampleCount);
    // The integer formats: each chunk converted to float, processed and converted back.
    template <class Sample>
    void processInt(Sample* samples, std::size_t sampleCount, float scale);
    // processChunk()'s per-output lanes and the integer paths' float chunk. Fixed size: nothing
    // is allocated per buffer.
    std::array<std::array<float, kChunkFrames>, NativeBmwRouting::kOutputCount> chunkLanes_{};
    std::array<float, 2 * kChunkFrames> chunkInt_{};
    // Each chunk frame's raw input, for the capture's input/output pairs.
    std::array<float, 2 * kChunkFrames> chunkRawIn_{};
    // The chunk's logical L/R after processFrameBack(), the convolver's input and output.
    static_assert(static_cast<std::size_t>(kChunkFrames) ==
                      static_cast<std::size_t>(NativeBmwDsp::kConvolverBlockFrames),
                  "the convolver's partition is the chunk, so it adds no latency");
    std::array<float, kChunkFrames> chunkConvL_{}, chunkConvR_{};
    // One band's post-routing chain on its L/R outputs: crossover, PEQ, all-pass, delay, driver
    // gain, then the band's stereo-linked bus limiter.
    void processBandChain(NativeBmwRouting::Band band, float& xl, float& xr);
    // Bookkeeping for a band whose chain is skipped (lpfPass/hpfPass/highXoPass): resets its bus
    // limiter.
    void idleBandChain(NativeBmwRouting::Band band);
    // A validated configure() update, built on the config thread: everything configure() reads
    // from v[], with the all-pass coefficients built at sampleRate.
    struct ParsedConfig {
        Params params;
        NativeBmwRouting::RoutingMatrix routing{};
        std::array<OutputConfig, NativeBmwRouting::kOutputCount> outputConfigs{};
        std::array<std::array<NativeBmwRouting::AllPassSection, NativeBmwRouting::kAllPassSectionsPerOutput>,
                   NativeBmwRouting::kOutputCount>
            allPass{};
        float sampleRate = 0.f;
        // A fresh measurement generator for exactly these settings, built on the config thread:
        // (re)building one allocates and, for pink noise, synthesises up to 10 s of samples --
        // never audio-thread work. Every published update carries one. generatorParams /
        // generatorSampleRate record what it was built for, so a parse into this slot reuses it
        // only when those match. installConfig() swaps it in when the settings changed
        // (generatorInstalled: the slot then holds the retired one, rebuilt on the next parse).
        std::unique_ptr<NativeBmwMeasurementGenerator> generator;
        Params generatorParams;
        float generatorSampleRate = 0.f;
        bool generatorInstalled = false;
    };
    // A validated configurePeq() update: the four banks built at sampleRate (clean state) and
    // the raw band values they came from. Bank order: Full, Low, Mid, High.
    struct ParsedPeq {
        bool enabled = false;
        float preampDb = 0.f;
        std::array<PeqBank, 4> banks{};
        std::array<std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth>, 4> values{};
        std::array<std::size_t, 4> counts{};
        float sampleRate = 0.f;
    };
    // Config thread (configMutex_): parses and validates v[] into out, starting from the last
    // accepted update (configShadow_). Touches no audio state. False rejects the whole update.
    bool parseConfig(const float* v, ParsedConfig& out) const;
    // Pure: validates and builds a PEQ update at sampleRate. False rejects it.
    // dropUnbuildable is for values already accepted at another rate (a sample-rate change):
    // a band that can't be built at the new one -- its frequency at or above the new Nyquist --
    // is left out instead of rejecting the whole update, as an invalid all-pass section degrades
    // to identity rather than failing configure().
    static bool parsePeq(bool enabled, float preampDb, const double* fullBands,
                         std::size_t fullValueCount, const double* lowBands,
                         std::size_t lowValueCount, const double* midBands,
                         std::size_t midValueCount, const double* highBands,
                         std::size_t highValueCount, float sampleRate, ParsedPeq& out,
                         bool dropUnbuildable = false);
    // Owner only: install an update, diffing it against the running state.
    void installConfig(ParsedConfig& config);
    void installPeq(ParsedPeq& peq);
    // Owner only: install whatever configure()/configurePeq() handed over since last time.
    void installPending();
    // Owner only: rebuild the PEQ banks from their saved raw values at sampleRate_ (a
    // sample-rate change, or an update built at a rate that has since changed).
    void rebuildPeqLocked();
    void rebuildAll();
    void applyDirty(uint32_t dirty);
    void rebuildGains();
    void rebuildSubsonic();
    void rebuildLowCrossover();
    void rebuildMidCrossover();
    void rebuildHighCrossover();
    // BW2/BW4 pair auto-correction per side, [left, right] (see NativeBmwDsp::pairCrossovers):
    // recomputed from p_ / outputConfigs_; true when it changed, so the crossovers and polarity
    // it feeds need rebuilding.
    std::array<NativeBmwDsp::CrossoverPairing, 2> pairing_{};
    bool updatePairing();
    void updateDelays();
    void rebuildBusLimiter();
    // snap: jump outputGain straight to its target (start-up / full rebuild) instead of ramping.
    void rebuildPolarityAndMute(bool snap = false);
    void rebuildAllPass();
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

    // Per-output state, indexed by OutputId.
    std::array<OutputRuntime, NativeBmwRouting::kOutputCount> outputs_{};
    std::array<OutputConfig, NativeBmwRouting::kOutputCount> outputConfigs_{};
    NativeBmwRouting::RoutingMatrix routing_{};
    float sampleRate_ = 48000.0f;

    // Master volume (v[292..293]). masterGain_ ramps toward masterGainTarget_ every sample with
    // a ~10 ms one-pole (masterRampCoef_, rebuilt with the sample rate) so a step never clicks.
    // The first configure() snaps straight to the target, so start-up never ramps down from
    // full level at a low saved volume.
    float masterGainTarget_ = 1.f;
    float masterGain_ = 1.f;
    float masterRampCoef_ = 0.0020811617f;  // 1 - exp(-1 / (10 ms * 48 kHz))
    bool masterGainPrimed_ = false;

    // Per-output polarity/mute and channel-mute ramps: a ~5 ms one-pole (outputRampCoef_, rebuilt
    // with the sample rate), so toggles fade instead of clicking. Snapped to their targets on a
    // full rebuild and on the first configure(), so start-up never fades in or out.
    static constexpr float kOutputRampSeconds = 0.005f;
    float outputRampCoef_ = 0.0041580101f;  // 1 - exp(-1 / (5 ms * 48 kHz))
    float channelGainL_ = 1.f, channelGainR_ = 1.f;
    bool outputGainsPrimed_ = false;
    // Delay-change crossfade length, seconds (AlignmentDelay::fadeLength).
    static constexpr float kDelayFadeSeconds = 0.01f;

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
    // headroom_ ramps toward headroomTarget_ with a ~10 ms one-pole (headroomRampCoef_, rebuilt
    // with the sample rate), like the master volume, so a change -- the automatic headroom
    // following a PEQ edit included -- never steps. Snapped on a full rebuild and on the first
    // configure(), so start-up doesn't fade in.
    float headroomTarget_ = 1;
    static constexpr float kHeadroomRampSeconds = 0.01f;
    float headroomRampCoef_ = 0.0020811617f;  // 1 - exp(-1 / (10 ms * 48 kHz))
    NativeBmwDsp::Tilt tilt_;
    // Mono Bass, right after the input DC blocker. monoBassMix_ is the crossfade from the input
    // (0) to the split (1), a linear ramp over kMonoBassFadeSeconds toward p_.monoBassEnabled;
    // at 0 the stage is skipped, so with Mono Bass off the signal is untouched. Snapped on a full
    // rebuild and on the first configure().
    NativeBmwDsp::MonoBass monoBass_;
    float monoBassMix_ = 0.f;
    static constexpr float kMonoBassFadeSeconds = 0.02f;
    float monoBassFadeStep_ = 1.f / (0.02f * 48000.f);
    // Virtual Bass, right after Mono Bass. virtualBassMix_ crossfades from the input (0) to the
    // processed signal (1) and virtualBassRemoveMix_ the base from the input to the input
    // high-passed at the cut, both linear ramps over kMonoBassFadeSeconds; virtualBassGain_ follows
    // the harmonics level on a one-pole (loudnessRampCoef_). At virtualBassMix_ 0 the stage is
    // skipped, so with Virtual Bass off the signal is untouched. Snapped on a full rebuild and on
    // the first configure().
    NativeBmwDsp::VirtualBass virtualBass_;
    float virtualBassMix_ = 0.f;
    float virtualBassRemoveMix_ = 0.f;
    float virtualBassGain_ = 0.f;
    float virtualBassCharacter_ = .5f;  // follows p_.virtualBassCharacter like virtualBassGain_
    // Loudness compensation, after the master volume (see Loudness).
    NativeBmwDsp::Loudness loudness_;
    // Retunes loudness_ for the volume actually reaching the output: the smaller of the cut at the
    // ramping masterGain_ and at its target, so the lift never exceeds the attenuation in effect
    // while the volume ramps either way.
    void retuneLoudness();
    // Lands Virtual Bass's on/off, sub-removal and level ramps on their settings at once.
    void snapVirtualBass();
    unsigned loudnessRetuneCounter_ = 0;
    // Effective strength (0..1), ramped toward p_ (0 while switched off) over ~50 ms -- slow
    // enough that the stepwise retunes are far smaller than the signal's own movement.
    float loudnessStrength_ = 0.f;
    static constexpr float kLoudnessRampSeconds = 0.05f;
    float loudnessRampCoef_ = 0.00041658f;  // 1 - exp(-1 / (50 ms * 48 kHz))
    // Fixed latency for a bypassed band, [LowL, LowR, MidL, MidR, HighL, HighR]: the
    // AlignmentDelay kernel's plus the bus limiter's lookahead (set in rebuildBusLimiter()).
    std::array<NativeBmwDsp::Delay, 6> bypassLatency_{};
    // Virtual-source stage (the virtual centre), between the MBC and routing_. Skipped entirely
    // while p_.virtualStage.enabled is false; see docs/NATIVE_BMW_VIRTUAL_CHANNELS.md.
    NativeBmwDsp::VirtualSourceStage virtual_;

    // Dynamics.
    NativeBmwDsp::DetectorTiming detector_;
    NativeBmwDsp::MasterLimiter limiter_;
    NativeBmwDsp::MultibandCompressor mbc_;
    // Per-bus output limiters (Low, Mid, High). Shared lookahead; release per bus.
    BusLimiter busLimLow_, busLimMid_, busLimHigh_;
    // Mirrors of p_.mbcEnabled/busLim{Low,Mid,High}Enabled/limiterEnabled, published by
    // configure() right after p_ = next. The read*Meter() functions are const and take no lock,
    // so they can't safely read the plain bools inside p_ directly -- that's a data race against
    // configure()'s whole-struct p_ = next assignment from the control thread. Same "atomic
    // mirror, no lock" discipline the modules already use for the GR meters themselves.
    std::atomic<bool> mbcEnabledMeterFlag_{false};
    std::atomic<bool> virtualEnabledMeterFlag_{false};
    std::atomic<bool> busLimLowEnabledMeterFlag_{false}, busLimMidEnabledMeterFlag_{false};
    std::atomic<bool> busLimHighEnabledMeterFlag_{false};
    std::atomic<bool> masterLimiterEnabledMeterFlag_{true};

    // Measurement.
    NativeBmwDsp::MeasurementBus measBus_;
    // Measurement signal generator (own module, see NativeBmwMeasurementGenerator.h). Per-instance
    // state, only ever touched from the process() loops (audio thread, while p_.measGenType != 0)
    // and applyDirty() (by the owner, like everything else here).
    NativeBmwMeasurementGenerator measGen_;
    // Capture state -- see startCapture()/stopCapture()/takeCaptureSnapshot(). Touched only by
    // the owner; captureFrameCount() is the lock-free exception, see CaptureRecorder.
    NativeBmwDsp::CaptureRecorder capture_;

    // Convolver: the stage is the owner's; the source IR and info are the control side's.
    NativeBmwDsp::ConvolverStage convolver_;
    std::mutex convolverMutex_;
    NativeBmwDsp::ConvolverIr convolverSource_;
    ConvolverInfo convolverInfo_;
    // convolverMutex_ held: (re)builds the kernel from convolverSource_ at rate and publishes it.
    bool rebuildConvolverLocked(float rate, std::string& error);

    // ---- Ownership and control requests (see the threading comment above the constructor) ----
    enum : std::uint32_t { kOwnerFree = 0, kOwnerAudio = 1, kOwnerControl = 2 };
    // No buffer for this long and the stream counts as stopped: a control call serves itself.
    static constexpr std::int64_t kAudioIdleNs = 200'000'000;
    // How long a control call waits for the audio thread before serving itself.
    static constexpr std::int64_t kRequestTimeoutNs = 1'000'000'000;
    enum class RequestKind : std::uint8_t { SetSampleRate, TruthSnapshot, StartCapture, StopCapture, TakeCapture };
    struct ControlRequest {
        RequestKind kind = RequestKind::StopCapture;
        float sampleRate = 0.f;                 // SetSampleRate
        std::vector<double>* truth = nullptr;   // TruthSnapshot: reserved by the caller
        std::size_t captureCapacity = 0;        // StartCapture: frames
        bool captureStarted = false;            // StartCapture result: false = needs buffers
        std::vector<float> rawInL, rawInR, outL, outR;  // StartCapture in / TakeCapture out
        std::size_t frames = 0;                 // TakeCapture result
        float capturedSampleRate = 0.f;         // TakeCapture result
    };
    // process()'s claim on the running state for one buffer: claims ownership, serves a pending
    // control request and installs pending updates; gives ownership back when it goes.
    class AudioOwnership {
    public:
        explicit AudioOwnership(NativeBmwDspProcessor& processor);
        ~AudioOwnership();
        AudioOwnership(const AudioOwnership&) = delete;
        AudioOwnership& operator=(const AudioOwnership&) = delete;

    private:
        NativeBmwDspProcessor& processor_;
    };
    // Owner only: setSampleRate()'s work.
    void applySampleRate(float sampleRate);
    // Posts the request to the audio thread and waits for it to be served, or serves it on this
    // thread while no audio is flowing. Caller holds controlMutex_.
    void runControlRequest();
    // Owner only: serves request_ if one is pending.
    void serveControlRequest();
    void serveRequest(ControlRequest& request);
    // Owner only: the truth snapshot itself (see captureTruthSnapshot()).
    void fillTruthSnapshot(std::vector<double>& out);
    // Claims ownership for the audio thread (process()); returns when it has it.
    void claimAudioOwnership();
    std::mutex controlMutex_;
    ControlRequest request_;
    std::atomic<std::uint32_t> requestSeq_{0};
    std::atomic<std::uint32_t> servedSeq_{0};
    std::atomic<std::uint32_t> ownership_{kOwnerFree};
    std::atomic<std::int64_t> lastBufferNs_{0};
    std::atomic<std::thread::id> audioThread_{};
    // Requests the audio thread served (as opposed to a control call serving itself); tests.
    std::atomic<std::uint32_t> audioServedRequests_{0};

    // Config-thread side of the handoff (see the threading comment above the constructor).
    // configMutex_ serialises configure()/configurePeq() callers among themselves; the audio
    // thread never takes it. configShadow_ is the last accepted update, so the next parse starts
    // from it exactly as configure() used to start from p_. configSampleRate_ is the rate new
    // updates are built at; an update built at a rate that changed before it was installed is
    // rebuilt at install.
    std::mutex configMutex_;
    ParsedConfig configShadow_;
    std::atomic<float> configSampleRate_{48000.0f};
    NativeBmwDsp::TripleBuffer<ParsedConfig> configHandoff_;
    NativeBmwDsp::TripleBuffer<ParsedPeq> peqHandoff_;
    // rebuildPeqLocked()'s workspace (too big for the stack); the owner's.
    ParsedPeq peqScratch_;
};

#endif
