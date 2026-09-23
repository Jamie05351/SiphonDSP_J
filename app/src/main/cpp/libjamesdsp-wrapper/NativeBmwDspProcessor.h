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
#include "NativeBmwMeasurementGenerator.h"
#include "NativeBmwRouting.h"

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
    enum : std::size_t { kMaxPeqSectionsPerChannel = 16, kPeqBandWidth = 5 };
    enum : unsigned { kDelayLineCapacity = 256 };
    // Stage-centering L/R alignment delay on the summed stereo bus (post master limiter). Sized
    // for a wider range than the sub-millisecond crossover/driver alignment: 10 ms cap -> 480
    // samples @ 48 kHz, 960 @ 96 kHz. 1024 keeps the full 10 ms available up to ~102 kHz. Kept
    // separate from kDelayLineCapacity so the per-output / limiter Delay instances stay 256.
    enum : unsigned { kStageDelayCapacity = 1024 };
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
    // readCompressorMeter -- reads atomics published by processMbc().
    void readMbcMeter(float* values, std::size_t count) const;
    // 3 floats: [lowBusGrDb, midBusGrDb, highBusGrDb] -- gain reduction of the per-bus
    // brick-wall limiters.
    // 0 for a bus whose limiter is disabled. Lock-free; published by processBusLimiter().
    void readBusLimiterMeter(float* values, std::size_t count) const;
    // 1 float: gain reduction (dB, >= 0) of the master brick-wall limiter on the summed output.
    // 0 while the limiter is bypassed. Lock-free; published by processLimiter().
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
    // Acquire-paired with captureTapOut()'s release store: any caller that reads this (UI-thread
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

    // Trapezoidal-integrated state variable filter (Andy Simper / "Cytomic" topology), not RBJ
    // Direct Form II Transposed. DF2T's a1/a2 coefficients cluster near the unit circle at low
    // corner frequencies relative to the sample rate -- the subsonic HPF (~32 Hz), the low-band
    // crossover, the 40-65 Hz phase-alignment all-pass sections -- which is exactly the case SVF
    // is designed for: its two trapezoidal integrator states (ic1eq/ic2eq) stay well-conditioned
    // there by construction, rather than needing double precision (still used throughout, see
    // below) to paper over an ill-conditioned realization. Every coefficient-design function
    // below (makeLowPass/makeHighPass/makeLowShelf/makeHighShelf/makeAllPass2/makePeq) derives
    // the identical designed transfer function the old RBJ formulas did -- verified by direct
    // time-domain simulation against those formulas before this migration, machine-precision
    // match on magnitude and phase across every filter type/fc/Q/gain this engine uses. Don't
    // simplify this back to DF2T or to float.
    struct Biquad {
        // topology picks run()'s recursion. Svf2 is the default 2nd-order Cytomic/Simper form
        // every LP/HP/shelf/bell/notch/allpass2 builder below produces. OnePoleAllpass is the
        // exception carried over from before the SVF migration: a true 1-pole design (used only
        // by NativeBmwRouting::AllPassSection's optional 1st-order phase-align case) doesn't map
        // onto SVF's 2-integrator form, so it keeps its original 1-pole recursion untouched.
        // OnePoleLowpass/OnePoleHighpass are a second, genuine 1-pole TPT design (Zavalishin,
        // "The Art of VA Filter Design" 2.2) -- the 6 dB/oct stage a 3rd-order (BW3) crossover
        // cascades with a 2nd-order Q=1 SVF section (see rebuildLowCrossover/rebuildMidCrossover).
        // Not the same math as OnePoleAllpass (unity magnitude by construction); these two
        // actually roll off. Still per-instance state, no sharing -- just a branch in run().
        enum class Topology : std::uint8_t {
            Svf2 = 0,
            OnePoleAllpass = 1,
            OnePoleLowpass = 2,
            OnePoleHighpass = 3,
        };
        Topology topology = Topology::Svf2;
        double op_z1 = 0, op_a = 0;
        // SVF state (trapezoidal integrators).
        double ic1eq = 0, ic2eq = 0;
        // SVF coefficients: a1/a2/a3 from g=tan(pi*fc/fs), k=1/Q; m0/m1/m2 select the filter type
        // (LP/HP/shelf/bell/all-pass) as a mix of the two integrator outputs plus the input.
        // Defaults (a1=a2=a3=0, m0=1/m1=0/m2=0) are an identity pass-through -- matching the old
        // DF2T struct's b0=1-rest-0 default -- so a Biquad that somehow runs before its owning
        // rebuild*() ever fires (e.g. process() called before the first configure()) is inert
        // instead of silent.
        double a1 = 0, a2 = 0, a3 = 0, m0 = 1, m1 = 0, m2 = 0;
        float run(float x);
        void clear();
        void loadAllPass(const NativeBmwRouting::BiquadCoefficients& c);
    };
    struct Delay {
        std::array<float, kDelayLineCapacity> data{};
        unsigned write = 0;
        float delay = 0;
        float run(float x);
        void clear();
    };
    // Same fractional-delay line as Delay (see Delay::run in the .cpp), with a larger ring
    // buffer for the multi-millisecond stage-centering range. Its own type -- rather than
    // templating Delay on capacity -- so none of the existing Delay users change.
    struct StageDelay {
        std::array<float, kStageDelayCapacity> data{};
        unsigned write = 0;
        float delay = 0;
        float run(float x) {
            // See Delay::run in the .cpp: write and advance unconditionally, even at delay<=0,
            // so the ring is never stale/silent the moment this delay is first enabled.
            const unsigned w = write;
            data[w] = x;
            write = (w + 1) % data.size();
            if (delay <= 0) {
                return x;
            }
            float read = static_cast<float>(w) - delay;
            while (read < 0) {
                read += data.size();
            }
            unsigned i0 = static_cast<unsigned>(read) % data.size(), i1 = (i0 + 1) % data.size();
            float f = read - std::floor(read), y = data[i0] + (data[i1] - data[i0]) * f;
            return y;
        }
        void clear() {
            data.fill(0);
            write = 0;
        }
    };
    struct PeqBank {
        std::array<Biquad, kMaxPeqSectionsPerChannel> left{};
        std::array<Biquad, kMaxPeqSectionsPerChannel> right{};
        std::size_t leftCount = 0, rightCount = 0;
        float processLeft(float sample);
        float processRight(float sample);
        void clear();
    };
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
    struct OutputConfig {
        float crossoverFreq = 150;
        // BW2 = one 2nd-order Butterworth stage (12 dB/oct). BW3 = a 1st-order stage cascaded
        // with a 2nd-order Q=1 stage (18 dB/oct total -- Q=1 is the exact factor of the 3rd-order
        // Butterworth polynomial's quadratic term, s^2+s+1). LinkwitzRiley4 = two cascaded
        // 2nd-order Butterworth (Q=1/sqrt(2)) stages (24 dB/oct), unchanged from before this was
        // selectable. Butterworth4 = two cascaded 2nd-order stages at the 4th-order Butterworth
        // polynomial's Qs (0.5412 / 1.3066): 24 dB/oct like LR4 but -3 dB (not -6 dB) at the
        // corner. See rebuildLowCrossover/rebuildMidCrossover.
        enum class CrossoverType : std::uint8_t {
            Butterworth2 = 0,
            Butterworth3 = 1,
            LinkwitzRiley4 = 2,
            Butterworth1 = 3, // single first-order stage, 6 dB/oct
            Butterworth4 = 4, // two 2nd-order stages, 24 dB/oct, -3 dB at the corner
        };
        CrossoverType crossoverType = CrossoverType::LinkwitzRiley4;
        bool subsonicEnabled = false;
        float subsonicFreq = 32;
        bool muted = false;
        bool polarityInverted = false;
        CompressorParams compressor{};
        // Mid's optional upper (Mid/High) bandpass corner -- only meaningful when the owning
        // output is Mid; carried-but-ignored for Low/High, same as subsonicEnabled/subsonicFreq
        // above being carried-but-ignored outside Low. Appended at the end of this struct (not
        // grouped with crossoverFreq/crossoverType above) so the existing positional aggregate
        // initializers in the constructor keep working unchanged -- default-initializes to
        // disabled for all three of them. See rebuildMidCrossover() and
        // docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
        bool upperCrossoverEnabled = false;
        float upperCrossoverFreq = 3000;
    };
    struct OutputRuntime {
        NativeBmwRouting::OutputId id = NativeBmwRouting::OutputId::LowLeft;
        bool isLeftSide = true;
        bool muted = false;
        bool polarityInverted = false;
        float gain = 1.0f;
        Biquad subsonic1;
        Biquad crossover1, crossover2;
        // Second filter-pair slot, only consumed by Mid's future bandpass (an LPF cascade at a
        // second corner, cascaded after crossover1/2's HPF) -- unused by Low/High, which only
        // ever need one filter direction. Inert (identity pass-through, Biquad's default) until
        // wired up. See docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
        Biquad crossover3, crossover4;
        Delay delay;
        std::array<NativeBmwRouting::AllPassSection, NativeBmwRouting::kAllPassSectionsPerOutput>
            allPass{};
        std::array<Biquad, NativeBmwRouting::kAllPassSectionsPerOutput> allPassState{};

        float processAllPass(float sample) {
            for (std::size_t i = 0; i < allPass.size(); ++i) {
                if (allPass[i].enabled) {
                    sample = allPassState[i].run(sample);
                }
            }
            return sample;
        }
        void clearState() {
            subsonic1.clear();
            crossover1.clear();
            crossover2.clear();
            crossover3.clear();
            crossover4.clear();
            delay.clear();
            for (auto& section : allPassState) {
                section.clear();
            }
        }
    };
    struct Limiter {
        Delay delayL, delayR;
        float gain = 1;
        float attackMix = 1, releaseMix = 1;
        void clear();
    };
    // Pre-crossover multiband compressor -------------------------------------------------------
    struct MbcBandParams {
        bool enabled = false;
        float threshold = -24, ratio = 2, knee = 6, attack = 15, release = 150, makeup = 0;
        // true (default): one gain cell driven by max(|L|,|R|) -- keeps the stereo image put.
        // false: independent L/R detection + gain for this band.
        bool stereoLink = true;
    };
    // One channel's 4-way Linkwitz-Riley split tree. Serial: split @ f0, then the high side
    // @ f1, then that high side @ f2. Each LP/HP is LR4 = two cascaded Butterworth biquads.
    // ap* are 2nd-order all-passes that put the already-separated lower bands through the same
    // phase the later crossovers impart, so the four bands sum back to flat magnitude (an
    // all-pass overall) -- same fix pattern as Mono Bass's Mid-side compensation.
    struct MbcTree {
        Biquad lp0a, lp0b, hp0a, hp0b, lp1a, lp1b, hp1a, hp1b, lp2a, lp2b, hp2a, hp2b;
        Biquad apB0X1, apB0X2, apB1X2;
        void clear() {
            lp0a.clear();
            lp0b.clear();
            hp0a.clear();
            hp0b.clear();
            lp1a.clear();
            lp1b.clear();
            hp1a.clear();
            hp1b.clear();
            lp2a.clear();
            lp2b.clear();
            hp2a.clear();
            hp2b.clear();
            apB0X1.clear();
            apB0X2.clear();
            apB1X2.clear();
        }
    };
    struct MbcCell {
        float rms = 0, peak = 0, gain = 1, lastDetectorDb = -60.f;
    };
    // Per-band published meter, aggregated across L/R. Atomics (not a lock) so readMbcMeter()
    // can be polled from the UI thread -- mirrors CompressorState's meter atomics.
    struct MbcBandMeter {
        std::atomic<float> inputDb{-60.f};
        std::atomic<float> outputDb{-60.f};
        std::atomic<float> gainReductionDb{0.f};
    };
    struct Params {
        bool enabled = true, lpfPass = false, hpfPass = false, tilt = true;
        int channelMute = 0, measurementMute = 0;
        float headroom = -6, lowGainL = 0, lowGainR = 0, midGainL = -1, midGainR = -1,
              postGainL = 0, postGainR = 0;
        float midDelayL = 0, midDelayR = 0, lowDelayL = 0, lowDelayR = 0;
        // High band (v[210..214], added in the 210 -> 262 growth). Unlike lpfPass/hpfPass
        // (which only bypass the crossover *filter*, letting the raw routed signal through --
        // see processFrame()'s own comment on this for Low/Mid), highXoPass=true fully silences
        // High: a tweeter with no HPF ahead of it is a real speaker-damage risk from raw bass,
        // not just an audio-quality one, so this flag alone is sufficient to silence it, with no
        // reliance on outputConfigs_[High*].muted also being set correctly. See
        // docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
        bool highXoPass = false;
        float highGainL = 0, highGainR = 0, highDelayL = 0, highDelayR = 0;
        // Stage-centering L/R alignment delay (ms), applied to the summed stereo bus after the
        // master limiter -- the last thing before the deliberate hardware L/R swap. A separate
        // correction layer from the per-output crossover/driver-alignment delays above (v[141]
        // / v[142]). Clamped to kStageDelayMaxMs.
        float stageDelayL = 0, stageDelayR = 0;
        float tiltAmount = 3, tiltFreq = 550;
        // Octaves to shift the measurement-mute bus brick-wall off the crossover, into the
        // stopband (v[139]). Default matches NativeBmwDspValues.DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES.
        float measBusStopbandOctaves = 1;
        // Measurement signal generator (v[192..199]). 0 = off; nonzero replaces the real input
        // entirely, upstream of captureTapIn() -- see applyMeasurementGenerator(). Ships off.
        int measGenType = 0;
        float measGenSweepStartHz = 20, measGenSweepEndHz = 20000, measGenSweepDurationS = 10,
              measGenSweepLevelDb = -12;
        float measGenPinkPeriodS = 2, measGenPinkLevelDb = -12;
        // v[199..204]: only meaningful while measGenType == 1. Wraps the sweep in REW's Acoustic
        // Timing Reference cycle (Mid sweep, Low sweep, Mid sweep, bracketed by timing chirps)
        // instead of the bare continuously-looping single sweep -- see
        // NativeBmwMeasurementGenerator::configureTimingRef()/nextTimingRefSample().
        bool measGenTimingRefEnabled = false;
        bool measGenTimingRefSplitChannels = false;
        float measGenTimingRefMidStartHz = 100, measGenTimingRefMidEndHz = 20000;
        float measGenTimingRefLowStartHz = 20, measGenTimingRefLowEndHz = 400;
        // Pre-crossover multiband compressor (v[144..180]). Ships disabled.
        bool mbcEnabled = false;
        float mbcMix = 1.f;  // 0..1 dry/wet (v[145] is percent)
        float mbcXo[3] = {80.f, 500.f, 4000.f};
        MbcBandParams mbcBand[4];
        // Per-bus output limiter (Low/Mid v[182..187], High v[262..264]). Additive to -- not
        // a replacement for -- the per-output processCompressor path. Ships disabled.
        bool busLimLowEnabled = false, busLimMidEnabled = false, busLimHighEnabled = false;
        float busLimLowThreshDb = -3.f, busLimLowReleaseMs = 120.f;
        float busLimMidThreshDb = -3.f, busLimMidReleaseMs = 120.f;
        float busLimHighThreshDb = -3.f, busLimHighReleaseMs = 120.f;
        // Master brick-wall limiter on the summed output. enabled == false is a true bypass
        // (processLimiter is not called); the threshold defaults to -1 dBFS, the fixed ceiling
        // this stage always used before it was made adjustable.
        bool limiterEnabled = true;
        float limiterThreshDb = -1.f;
    } p_;

    static float dbToLin(float db);
    static void makeLowPass(Biquad& q, float fc, float Q, float sr);
    static void makeHighPass(Biquad& q, float fc, float Q, float sr);
    static void makeLowPass1(Biquad& q, float fc, float sr);
    static void makeHighPass1(Biquad& q, float fc, float sr);
    static void makeIdentity(Biquad& q);
    static void makeLowShelf(Biquad& q, float fc, float gain, float sr);
    static void makeHighShelf(Biquad& q, float fc, float gain, float sr);
    static void makeAllPass2(Biquad& q, float fc, float sr);
    static bool makePeq(Biquad& q, double frequency, double gain, double Q, int type,
                        float sampleRate);
    // True for a PEQ band configurePeqLocked's build() drops as a no-op: a non-notch band with
    // ~0 dB gain has no audible effect, so it's never installed as a running Biquad. Shared with
    // captureTruthSnapshot() so the "active" flag it reports can never drift from what build()
    // actually does.
    static bool peqBandSkipped(int type, double gain);
    float processChannelInput(float x, float& dcX, float& dcY);
    float processLowCrossover(OutputRuntime& out, const OutputConfig& config, float sample);
    float processMidCrossover(OutputRuntime& out, const OutputConfig& config, float sample);
    float processHighCrossover(OutputRuntime& out, const OutputConfig& config, float sample);
    void processFrame(float& l, float& r);
    // Substitutes the measurement generator's stimulus for l/r when p_.measGenType != 0. Called
    // from each process() overload before captureTapIn(), so a measurement run's captured "raw
    // input" is the actual generated stimulus, not the bypassed real input -- otherwise the
    // exported raw-input/output WAV pair wouldn't represent stimulus/response and any null test
    // on them would be invalid.
    void applyMeasurementGenerator(float& l, float& r);
    void processCompressor(float& sample, const CompressorParams& params, CompressorState& state);
    void processLimiter(float& left, float& right);
    // Pre-crossover multiband compressor: splits the post-headroom stereo bus into 4 bands,
    // compresses each, sums flat, blends dry/wet. No-op (single branch) while p_.mbcEnabled
    // is false, which is how it ships. Runs before routing_.process in processFrame.
    void processMbc(float& left, float& right);
    float mbcBandGain(float peakAbs, const MbcBandParams& p, MbcCell& cell, int band);
    // Brick-wall (infinite ratio, fixed-fast attack) limiter for one output bus. threshold in
    // dBFS, one stereo-linked gain follower. No lookahead -- the master limiter downstream
    // already carries that. No-op branch while the bus's enable is false.
    void resetBusLimiter(float& gain, std::atomic<float>& grMeterDb);
    void processBusLimiter(float& left, float& right, float thresholdDb, float& gain,
                           float releaseMix, std::atomic<float>& grMeterDb);
    void publishIdleMeter(CompressorState& state);
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
    void rebuildTilt();
    void rebuildCompressorTiming();
    void rebuildLimiter();
    void rebuildMbc();        // split-frequency filter coefficients (+ calls rebuildMbcTiming)
    void rebuildMbcTiming();  // dry/wet mix, per-band makeup + attack/release smoothing coeffs
    void resetMbcState();
    void rebuildBusLimiter();
    void rebuildPolarityAndMute();
    // Option A measurement-mute bus brick-wall; rebuilt only on a DirtyMeasBus transition.
    void rebuildMeasBus();
    // Measurement signal generator; restarts the run on a DirtyMeasGen transition (type flipped,
    // or a sweep parameter changed while already running).
    void rebuildMeasGen();
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

    std::array<OutputRuntime, NativeBmwRouting::kOutputCount> outputs_{};
    std::array<OutputConfig, NativeBmwRouting::kOutputCount> outputConfigs_{};
    std::array<CompressorState, NativeBmwRouting::kOutputCount> outputDynamics_{};
    NativeBmwRouting::RoutingMatrix routing_{};
    float leftDcX_ = 0, leftDcY_ = 0, rightDcX_ = 0, rightDcY_ = 0;
    PeqBank inputPeq_, lowPeq_, midPeq_, highPeq_;
    bool peqEnabled_ = false;
    float peqPreampDb_ = 0, peqPreamp_ = 1;
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> inputPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> lowPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> midPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> highPeqValues_{};
    std::size_t inputPeqValueCount_ = 0, lowPeqValueCount_ = 0, midPeqValueCount_ = 0,
                highPeqValueCount_ = 0;
    float sampleRate_ = 48000.0f, dcR_ = 0.0f;
    float headroom_ = 1, postGainL_ = 1, postGainR_ = 1;
    float rmsMix_ = 0, peakRelease_ = 0;
    Limiter limiter_;
    // Stage-centering L/R alignment delay lines on the summed stereo bus (see Params::stageDelay*
    // and processFrame's tail). delay (in samples) is set by updateDelays().
    StageDelay stageDelayL_, stageDelayR_;
    Biquad tiltLoL1_, tiltLoL2_, tiltHiL1_, tiltHiL2_;
    Biquad tiltLoR1_, tiltLoR2_, tiltHiR1_, tiltHiR2_;
    // Option A measurement-mute bus brick-wall. Inert unless p_.measurementMute != 0: the
    // sections are only run by processFrame() while measBusActive_, and only (re)built by
    // rebuildMeasBus() on a DirtyMeasBus transition -- never touched for the normal tuned
    // output, so it adds no latency, no phase shift and no per-sample/coefficient cost when
    // measurement mute is off. LR8 = 4 cascaded Butterworth Q=1/sqrt(2) sections (48 dB/oct),
    // steeper than the LR4 crossovers; excluded-band phase is irrelevant (discarded).
    static constexpr std::size_t kMeasBusSections = 4;
    std::array<Biquad, kMeasBusSections> measBusL_{}, measBusR_{};
    // Isolate-Mid's second (LPF) brick-wall above Mid's upper corner, only while 3-way is on.
    std::array<Biquad, kMeasBusSections> measBusUpperL_{}, measBusUpperR_{};
    bool measBusActive_ = false;
    bool measBusUpperActive_ = false;
    // Measurement mute values: 0 off, 1 isolate Mid, 2 isolate Low, 3 isolate High.
    static constexpr bool measurementMuteIsolates(int mode, NativeBmwRouting::Band band) {
        return (mode == 1 && band == NativeBmwRouting::Band::Mid) ||
               (mode == 2 && band == NativeBmwRouting::Band::Low) ||
               (mode == 3 && band == NativeBmwRouting::Band::High);
    }
    // Measurement signal generator (own module, see NativeBmwMeasurementGenerator.h). Per-instance
    // state, only ever touched from applyMeasurementGenerator() (audio thread, while
    // p_.measGenType != 0) and rebuildMeasGen() (control thread, under stateMutex_ like everything
    // else here).
    NativeBmwMeasurementGenerator measGen_;
    static constexpr float kLimiterLookaheadMs = 5.f;
    static constexpr float kLimiterCeilingLin = 0.891251f;  // -1 dBFS -- the default threshold
    // Live ceiling = dbToLin(p_.limiterThreshDb), refreshed by rebuildLimiter().
    float limiterCeilingLin_ = kLimiterCeilingLin;
    // Published gain reduction (dB, >= 0) of the master limiter, for readMasterLimiterMeter().
    // Stored every 256 frames from processLimiter(), same discipline as the MBC meter.
    std::atomic<float> masterLimiterGrDb_{0.f};
    uint32_t limiterMeterCounter_ = 0;
    // Mirrors of p_.mbcEnabled/busLim{Low,Mid,High}Enabled/limiterEnabled, published by
    // configure() right after p_ = next (same spot the rest of this file republishes derived
    // state). The read*Meter() functions are const and take no lock, so they can't safely read
    // the plain bools inside p_ directly -- that's a data race against configure()'s whole-struct
    // p_ = next assignment from the control thread. Same "atomic mirror, no lock" discipline this
    // file already uses for the GR meters themselves (masterLimiterGrDb_ etc above).
    std::atomic<bool> mbcEnabledMeterFlag_{false};
    std::atomic<bool> busLimLowEnabledMeterFlag_{false}, busLimMidEnabledMeterFlag_{false};
    std::atomic<bool> busLimHighEnabledMeterFlag_{false};
    std::atomic<bool> masterLimiterEnabledMeterFlag_{true};

    // Pre-crossover multiband compressor state. mbc_[0] = left chain, mbc_[1] = right chain.
    // mbcCell_[ch][band]: detector + gain follower. When a band is stereo-linked only [0][band]
    // is used (fed by max(|L|,|R|)); unlinked uses [0]=left, [1]=right. mbcMix_ is 0..1.
    std::array<MbcTree, 2> mbc_{};
    MbcCell mbcCell_[2][4]{};
    std::array<MbcBandMeter, 4> mbcMeter_{};
    uint32_t mbcMeterCounter_ = 0;
    float mbcMix_ = 1.f;
    float mbcMakeupLin_[4] = {1.f, 1.f, 1.f, 1.f};
    float mbcAttackMix_[4] = {0.f, 0.f, 0.f, 0.f};
    float mbcReleaseMix_[4] = {0.f, 0.f, 0.f, 0.f};
    // Per-bus output limiters (Low, Mid, High). Fixed ~1 ms attack shared; release per bus.
    float busLimAttackMix_ = 1.f;
    float busLimLowReleaseMix_ = 0.f, busLimMidReleaseMix_ = 0.f, busLimHighReleaseMix_ = 0.f;
    float busLimLowGain_ = 1.f, busLimMidGain_ = 1.f, busLimHighGain_ = 1.f;
    // Published gain reduction (dB, >= 0) of each per-bus limiter, for readBusLimiterMeter().
    std::atomic<float> busLimLowGrDb_{0.f}, busLimMidGrDb_{0.f}, busLimHighGrDb_{0.f};

    // Capture state -- see startCapture()/stopCapture()/takeCaptureSnapshot(). The buffers and
    // captureEnabled_ are protected by stateMutex_ like everything else here (including from
    // inside process() itself, which already holds stateMutex_ for the whole buffer, so mutating
    // captureEnabled_ there needs no extra locking). captureWriteIndex_ is the one exception --
    // it's an atomic (same spirit as CompressorState's atomic<float> meters) specifically so
    // captureFrameCount() can be polled from the UI thread for progress without taking the
    // audio-thread's lock at all.
    std::vector<float> captureRawInL_, captureRawInR_, captureOutL_, captureOutR_;
    bool captureEnabled_ = false;
    std::atomic<std::size_t> captureWriteIndex_{0};
    std::size_t captureCapacity_ = 0;

    // Called from inside the stateMutex_-locked section of each process() overload, once before
    // processFrame() (rawIn) and once after (out). No-ops (single branch) when capture is off or
    // the buffer's already full, so this is cheap on every frame regardless.
    void captureTapIn(float l, float r) {
        if (!captureEnabled_) {
            return;
        }
        const std::size_t i = captureWriteIndex_.load(std::memory_order_relaxed);
        if (i < captureCapacity_) {
            captureRawInL_[i] = l;
            captureRawInR_[i] = r;
        }
    }
    void captureTapOut(float l, float r) {
        if (!captureEnabled_) {
            return;
        }
        const std::size_t i = captureWriteIndex_.load(std::memory_order_relaxed);
        if (i >= captureCapacity_) {
            return;
        }
        captureOutL_[i] = l;
        captureOutR_[i] = r;
        const std::size_t next = i + 1;
        // Release: publishes the plain-float writes above (and captureTapIn()'s, earlier this
        // same process() call) so a thread that reads captureFrameCount() with a matching acquire
        // load is guaranteed to see them -- see captureFrameCount()'s comment. The two loads
        // inside this class (here and in captureTapIn()) stay relaxed; they're same-thread
        // bookkeeping reads with no cross-thread consumer of their own.
        captureWriteIndex_.store(next, std::memory_order_release);
        if (next >= captureCapacity_) {
            captureEnabled_ = false;
        }
    }

    std::mutex stateMutex_;
};

#endif
