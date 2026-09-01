#ifndef SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H
#define SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>
#include "NativeBmwRouting.h"

class NativeBmwDspProcessor {
public:
    // 139..142 were the Pultec-style bass boost/cut stage; the feature was removed (unused,
    // native processing deleted below) and the slots were left inert rather than reclaimed, same
    // as FIELD_CROSSOVER_LR4 -- shrinking the array would shift every index after it.
    //   139 -> reclaimed: measurement-mute bus brick-wall stopband offset, in octaves. 0 keeps
    //          the LR8 corner exactly on the opposite band's crossover (original behaviour);
    //          >0 walks it that many octaves into the stopband so the isolated band's own
    //          transition region is left intact for measurement. Read in configure().
    //   140 -> reclaimed: one-time "stopband offset migrated" marker, written by
    //          NativeBmwDspValues.kt so an existing saved config picks up the new default.
    //          Kotlin-only -- never read in configure().
    //   141..142 -> still inert, never read.
    // 143 (INDEX_DELAY_LINKED) is UI-only -- see NativeBmwDspValues.kt -- and is intentionally
    // never read in configure() either; it only has to be included here so the array length
    // check (NativeBmwDspJni.cpp) accepts the array Kotlin actually sends.
    //
    // 144..191 -- pre-crossover multiband compressor (MBC) + per-bus output limiter, added in
    // the 144 -> 192 growth. Indices match NativeBmwDspValues.INDEX_MBC_* / INDEX_BUS_LIMITER_*:
    //   144      MBC global enable            145      MBC dry/wet mix (percent)
    //   146..148 MBC crossover splits (Hz)    149..180 4 bands x 8 params
    //   181      Kotlin-only migration marker -- never read here
    //   182..184 Low-bus limiter  (enable, threshold dBFS, release ms)
    //   185..187 Mid-bus limiter  (enable, threshold dBFS, release ms)
    //   188..191 reserved, never read
    enum : std::size_t { kLegacyConfigSize = 86, kConfigSize = 192 };
    enum : std::size_t { kMaxPeqSectionsPerChannel = 16, kPeqBandWidth = 5 };
    enum : unsigned { kDelayLineCapacity = 256 };
    enum : std::size_t {
        kRoutingValueCount = NativeBmwRouting::kOutputCount * NativeBmwRouting::kInputCount,
        kAllPassValueWidth = 4,
        kAllPassValueCount = NativeBmwRouting::kOutputCount * NativeBmwRouting::kAllPassSectionsPerOutput * kAllPassValueWidth,
        kOutputConfigBase = 87,
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
    bool configurePeq(bool enabled, float preampDb,
                      const double* fullBands, std::size_t fullValueCount,
                      const double* lowBands, std::size_t lowValueCount,
                      const double* midBands, std::size_t midValueCount);
    const int16_t* process(const int16_t* samples, std::size_t sampleCount);
    const int32_t* process(const int32_t* samples, std::size_t sampleCount);
    const float* process(const float* samples, std::size_t sampleCount);
    void readCompressorMeter(float* values, std::size_t count) const;
    // 12 floats: 4 MBC bands x [inputDb, outputDb, gainReductionDb]. All-idle (-60/-60/0)
    // while the multiband compressor is disabled. Lock-free, same discipline as
    // readCompressorMeter -- reads atomics published by processMbc().
    void readMbcMeter(float* values, std::size_t count) const;
    // 2 floats: [lowBusGrDb, midBusGrDb] -- gain reduction of the per-bus brick-wall limiters.
    // 0 for a bus whose limiter is disabled. Lock-free; published by processBusLimiter().
    void readBusLimiterMeter(float* values, std::size_t count) const;

    // Raw-input/final-output capture for the in-app measurement tool. (Re)allocates the capture
    // buffers at the current sample rate, so it's a control-thread call, never made from
    // process() itself -- same "brief, uncontended lock" discipline as configure().
    void startCapture();
    void stopCapture();
    std::size_t captureFrameCount() const;
    // Writes 2 float32 WAV files (raw input, final output) from whatever's been captured so far
    // and fills `result` with peak/null-test readings computed from that same data. Safe to call
    // whether or not capture is still running (reads only up to captureFrameCount() frames).
    bool exportCaptureWav(const char* rawInPath, const char* outPath, CaptureExportResult& result) const;

private:
    enum Dirty : uint32_t {
        DirtyNone       = 0,
        DirtyGains      = 1u << 0,
        DirtySubsonic   = 1u << 1,
        DirtyLowXo      = 1u << 2,
        DirtyMidXo      = 1u << 3,
        DirtyDelays     = 1u << 4,
        DirtyTilt       = 1u << 5,
        DirtyCompTiming = 1u << 6,
        DirtyCompState  = 1u << 7,
        DirtyMonoBass   = 1u << 8,
        DirtyPolarity   = 1u << 9,
        DirtyMeasBus    = 1u << 10,
        DirtyMbc        = 1u << 11, // crossover-tree coefficients (split freqs) -- clears filter state
        DirtyMbcTiming  = 1u << 12, // attack/release/makeup/mix scalars only -- no filter touch
        DirtyMbcState   = 1u << 13, // reset detector cells + tree state (enable / stereo-link flip)
        DirtyBusLimiter = 1u << 14,
        DirtyAll        = 0xffffffffu,
    };

    struct Biquad {
        float b0=1,b1=0,b2=0,a1=0,a2=0,z1=0,z2=0;
        float run(float x);
        void clear();
        void loadAllPass(const NativeBmwRouting::BiquadCoefficients& c);
    };
    struct Delay {
        std::array<float,kDelayLineCapacity> data{};
        unsigned write=0;
        float delay=0;
        float run(float x);
        void clear();
    };
    struct PeqBank {
        std::array<Biquad, kMaxPeqSectionsPerChannel> left{};
        std::array<Biquad, kMaxPeqSectionsPerChannel> right{};
        std::size_t leftCount=0,rightCount=0;
        float processLeft(float sample);
        float processRight(float sample);
        void clear();
    };
    struct CompressorParams {
        bool enabled=false;
        float threshold=-12,ratio=2,knee=8,attack=40,release=250,makeup=0;
    };
    struct CompressorState {
        float gain=1,rmsPower=0,peakEnv=0,attackMix=0,releaseMix=0,makeupLin=1;
        std::atomic<float> inputDb{-60.0f};
        std::atomic<float> outputDb{-60.0f};
        std::atomic<float> gainReductionDb{0.0f};
        uint32_t meterCounter=0;
    };
    struct OutputConfig {
        float crossoverFreq=150;
        // Always LR4 now -- the 18dB/oct (BW3) option was removed. This field is read (and
        // forced true) but no longer branched on; see rebuildLowCrossover/processLowCrossover.
        bool crossoverLr4=true;
        bool subsonicEnabled=false;
        float subsonicFreq=32;
        bool muted=false;
        bool polarityInverted=false;
        CompressorParams compressor{};
    };
    struct OutputRuntime {
        NativeBmwRouting::OutputId id = NativeBmwRouting::OutputId::LowLeft;
        bool isLeftSide = true;
        bool muted = false;
        bool polarityInverted = false;
        float gain = 1.0f;
        Biquad subsonic1;
        Biquad crossover1, crossover2;
        Biquad monoBassHpf1, monoBassHpf2;
        // Mid-side-only: a LP+HP allpass (same frequency/order as the Low side's own mono-bass
        // split) applied to Mid so it picks up the identical phase rotation Mono Bass's
        // mono/stereo recombination puts on Low -- without this, Low and Mid stop summing flat
        // at the Low/Mid crossover the instant Mono Bass is enabled. See processFrame and
        // rebuildMonoBass for the full explanation.
        Biquad monoBassCompLp1, monoBassCompLp2, monoBassCompHp1, monoBassCompHp2;
        Delay delay;
        std::array<NativeBmwRouting::AllPassSection, NativeBmwRouting::kAllPassSectionsPerOutput> allPass{};
        std::array<Biquad, NativeBmwRouting::kAllPassSectionsPerOutput> allPassState{};

        float processAllPass(float sample) {
            for (std::size_t i = 0; i < allPass.size(); ++i) {
                if (allPass[i].enabled) sample = allPassState[i].run(sample);
            }
            return sample;
        }
        void clearState() {
            subsonic1.clear(); crossover1.clear(); crossover2.clear();
            monoBassHpf1.clear(); monoBassHpf2.clear(); delay.clear();
            monoBassCompLp1.clear(); monoBassCompLp2.clear();
            monoBassCompHp1.clear(); monoBassCompHp2.clear();
            for (auto& section : allPassState) section.clear();
        }
    };
    struct Limiter {
        Delay delayL,delayR;
        float gain=1;
        float attackMix=1,releaseMix=1;
        void clear();
    };
    // Pre-crossover multiband compressor -------------------------------------------------------
    struct MbcBandParams {
        bool enabled=false;
        float threshold=-24,ratio=2,knee=6,attack=15,release=150,makeup=0;
        // true (default): one gain cell driven by max(|L|,|R|) -- keeps the stereo image put.
        // false: independent L/R detection + gain for this band.
        bool stereoLink=true;
    };
    // One channel's 4-way Linkwitz-Riley split tree. Serial: split @ f0, then the high side
    // @ f1, then that high side @ f2. Each LP/HP is LR4 = two cascaded Butterworth biquads.
    // ap* are 2nd-order all-passes that put the already-separated lower bands through the same
    // phase the later crossovers impart, so the four bands sum back to flat magnitude (an
    // all-pass overall) -- same fix pattern as Mono Bass's Mid-side compensation.
    struct MbcTree {
        Biquad lp0a,lp0b,hp0a,hp0b,lp1a,lp1b,hp1a,hp1b,lp2a,lp2b,hp2a,hp2b;
        Biquad apB0X1,apB0X2,apB1X2;
        void clear(){
            lp0a.clear();lp0b.clear();hp0a.clear();hp0b.clear();
            lp1a.clear();lp1b.clear();hp1a.clear();hp1b.clear();
            lp2a.clear();lp2b.clear();hp2a.clear();hp2b.clear();
            apB0X1.clear();apB0X2.clear();apB1X2.clear();
        }
    };
    struct MbcCell { float rms=0,peak=0,gain=1,lastDetectorDb=-60.f; };
    // Per-band published meter, aggregated across L/R. Atomics (not a lock) so readMbcMeter()
    // can be polled from the UI thread -- mirrors CompressorState's meter atomics.
    struct MbcBandMeter {
        std::atomic<float> inputDb{-60.f};
        std::atomic<float> outputDb{-60.f};
        std::atomic<float> gainReductionDb{0.f};
    };
    struct Params {
        bool enabled=true,lpfPass=false,hpfPass=false,tilt=true;
        int channelMute=0,measurementMute=0;
        float headroom=-6,lowGainL=0,lowGainR=0,midGainL=-1,midGainR=-1,postGainL=0,postGainR=0;
        float midDelayL=0,midDelayR=0,lowDelayL=0,lowDelayR=0;
        float tiltAmount=3,tiltFreq=550;
        bool monoBass=false;
        float monoBassFreq=80,monoBassBlend=100,monoBassMakeup=0;
        // Octaves to shift the measurement-mute bus brick-wall off the crossover, into the
        // stopband (v[139]). Default matches NativeBmwDspValues.DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES.
        float measBusStopbandOctaves=1;
        // Pre-crossover multiband compressor (v[144..180]). Ships disabled.
        bool mbcEnabled=false;
        float mbcMix=1.f;                 // 0..1 dry/wet (v[145] is percent)
        float mbcXo[3]={120.f,500.f,4000.f};
        MbcBandParams mbcBand[4];
        // Per-bus output limiter (v[182..187]). Additive to -- not a replacement for -- the
        // per-output processCompressor path. Ships disabled.
        bool busLimLowEnabled=false,busLimMidEnabled=false;
        float busLimLowThreshDb=-3.f,busLimLowReleaseMs=120.f;
        float busLimMidThreshDb=-3.f,busLimMidReleaseMs=120.f;
    } p_;

    static float dbToLin(float db);
    static void makeLowPass(Biquad& q,float fc,float Q,float sr);
    static void makeHighPass(Biquad& q,float fc,float Q,float sr);
    static void makeLowShelf(Biquad& q,float fc,float gain,float sr);
    static void makeHighShelf(Biquad& q,float fc,float gain,float sr);
    static void makeAllPass2(Biquad& q,float fc,float sr);
    static bool makePeq(Biquad& q, double frequency, double gain, double Q, int type, float sampleRate);
    float processChannelInput(float x, float& dcX, float& dcY);
    float processLowCrossover(OutputRuntime& out, const OutputConfig& config, float sample);
    float processMidCrossover(OutputRuntime& out, float sample);
    void processFrame(float& l,float& r);
    void processCompressor(float& sample,const CompressorParams& params,CompressorState& state);
    void processLimiter(float& left,float& right);
    // Pre-crossover multiband compressor: splits the post-headroom stereo bus into 4 bands,
    // compresses each, sums flat, blends dry/wet. No-op (single branch) while p_.mbcEnabled
    // is false, which is how it ships. Runs before routing_.process in processFrame.
    void processMbc(float& left,float& right);
    float mbcBandGain(float peakAbs,const MbcBandParams& p,MbcCell& cell,int band);
    // Brick-wall (infinite ratio, fixed-fast attack) limiter for one output bus. threshold in
    // dBFS, one stereo-linked gain follower. No lookahead -- the master limiter downstream
    // already carries that. No-op branch while the bus's enable is false.
    void processBusLimiter(float& left,float& right,float thresholdDb,float& gain,float releaseMix,std::atomic<float>& grMeterDb);
    void publishIdleMeter(CompressorState& state);
    void rebuildAll();
    void applyDirty(uint32_t dirty);
    void rebuildGains();
    void rebuildSubsonic();
    void rebuildLowCrossover();
    void rebuildMidCrossover();
    void updateDelays();
    void rebuildTilt();
    void rebuildCompressorTiming();
    void rebuildLimiter();
    void rebuildMbc();        // split-frequency filter coefficients (+ calls rebuildMbcTiming)
    void rebuildMbcTiming();  // dry/wet mix, per-band makeup + attack/release smoothing coeffs
    void resetMbcState();
    void rebuildBusLimiter();
    void rebuildMonoBass();
    void rebuildPolarityAndMute();
    // Option A measurement-mute bus brick-wall; rebuilt only on a DirtyMeasBus transition.
    void rebuildMeasBus();
    void rebuildAllPass();
    void resetDynamics();
    OutputRuntime& output(NativeBmwRouting::OutputId id) { return outputs_[static_cast<std::size_t>(id)]; }
    const OutputRuntime& output(NativeBmwRouting::OutputId id) const { return outputs_[static_cast<std::size_t>(id)]; }
    OutputConfig& outputConfig(NativeBmwRouting::OutputId id) { return outputConfigs_[static_cast<std::size_t>(id)]; }
    const OutputConfig& outputConfig(NativeBmwRouting::OutputId id) const { return outputConfigs_[static_cast<std::size_t>(id)]; }
    CompressorState& dynamics(NativeBmwRouting::OutputId id) { return outputDynamics_[static_cast<std::size_t>(id)]; }
    const CompressorState& dynamics(NativeBmwRouting::OutputId id) const { return outputDynamics_[static_cast<std::size_t>(id)]; }

    std::array<OutputRuntime, NativeBmwRouting::kOutputCount> outputs_{};
    std::array<OutputConfig, NativeBmwRouting::kOutputCount> outputConfigs_{};
    std::array<CompressorState, NativeBmwRouting::kOutputCount> outputDynamics_{};
    NativeBmwRouting::RoutingMatrix routing_{};
    float leftDcX_=0,leftDcY_=0,rightDcX_=0,rightDcY_=0;
    PeqBank inputPeq_,lowPeq_,midPeq_;
    bool peqEnabled_=false;
    float peqPreampDb_=0,peqPreamp_=1;
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> inputPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> lowPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> midPeqValues_{};
    std::size_t inputPeqValueCount_=0,lowPeqValueCount_=0,midPeqValueCount_=0;
    float sampleRate_=48000.0f,dcR_=0.0f;
    float headroom_=1,postGainL_=1,postGainR_=1;
    float rmsMix_=0,peakRelease_=0;
    Limiter limiter_;
    Biquad monoBassLpf1_,monoBassLpf2_;
    float monoBassMakeupLin_=1;
    Biquad tiltLoL1_,tiltLoL2_,tiltHiL1_,tiltHiL2_;
    Biquad tiltLoR1_,tiltLoR2_,tiltHiR1_,tiltHiR2_;
    // Option A measurement-mute bus brick-wall. Inert unless p_.measurementMute != 0: the
    // sections are only run by processFrame() while measBusActive_, and only (re)built by
    // rebuildMeasBus() on a DirtyMeasBus transition -- never touched for the normal tuned
    // output, so it adds no latency, no phase shift and no per-sample/coefficient cost when
    // measurement mute is off. LR8 = 4 cascaded Butterworth Q=1/sqrt(2) sections (48 dB/oct),
    // steeper than the LR4 crossovers; excluded-band phase is irrelevant (discarded).
    static constexpr std::size_t kMeasBusSections = 4;
    std::array<Biquad, kMeasBusSections> measBusL_{}, measBusR_{};
    bool measBusActive_ = false;
    bool measBusIsHighpass_ = true;
    static constexpr float kLimiterLookaheadMs = 5.f;
    static constexpr float kLimiterCeilingLin = 0.891251f;

    // Pre-crossover multiband compressor state. mbc_[0] = left chain, mbc_[1] = right chain.
    // mbcCell_[ch][band]: detector + gain follower. When a band is stereo-linked only [0][band]
    // is used (fed by max(|L|,|R|)); unlinked uses [0]=left, [1]=right. mbcMix_ is 0..1.
    std::array<MbcTree, 2> mbc_{};
    MbcCell mbcCell_[2][4]{};
    std::array<MbcBandMeter, 4> mbcMeter_{};
    uint32_t mbcMeterCounter_ = 0;
    float mbcMix_ = 1.f;
    float mbcMakeupLin_[4] = {1.f,1.f,1.f,1.f};
    float mbcAttackMix_[4] = {0.f,0.f,0.f,0.f};
    float mbcReleaseMix_[4] = {0.f,0.f,0.f,0.f};
    // Per-bus output limiters (Low, Mid). Fixed ~1 ms attack shared; release per bus.
    float busLimAttackMix_ = 1.f;
    float busLimLowReleaseMix_ = 0.f, busLimMidReleaseMix_ = 0.f;
    float busLimLowGain_ = 1.f, busLimMidGain_ = 1.f;
    // Published gain reduction (dB, >= 0) of each per-bus limiter, for readBusLimiterMeter().
    std::atomic<float> busLimLowGrDb_{0.f}, busLimMidGrDb_{0.f};

    // Capture state -- see startCapture()/stopCapture()/exportCaptureWav(). The buffers and
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
        if (!captureEnabled_) return;
        const std::size_t i = captureWriteIndex_.load(std::memory_order_relaxed);
        if (i < captureCapacity_) {
            captureRawInL_[i] = l;
            captureRawInR_[i] = r;
        }
    }
    void captureTapOut(float l, float r) {
        if (!captureEnabled_) return;
        const std::size_t i = captureWriteIndex_.load(std::memory_order_relaxed);
        if (i >= captureCapacity_) return;
        captureOutL_[i] = l;
        captureOutR_[i] = r;
        const std::size_t next = i + 1;
        captureWriteIndex_.store(next, std::memory_order_relaxed);
        if (next >= captureCapacity_) captureEnabled_ = false;
    }

    std::mutex stateMutex_;
};

#endif
