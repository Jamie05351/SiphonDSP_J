#ifndef SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H
#define SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include "NativeBmwRouting.h"

class NativeBmwDspProcessor {
public:
    enum : std::size_t { kLegacyConfigSize = 86, kConfigSize = 139 };
    enum : std::size_t { kMaxPeqSectionsPerChannel = 16, kPeqBandWidth = 5 };
    enum : unsigned { kDelayLineCapacity = 256 };
    enum : std::size_t {
        kRoutingValueCount = NativeBmwRouting::kOutputCount * NativeBmwRouting::kInputCount,
        kAllPassValueWidth = 4,
        kAllPassValueCount = NativeBmwRouting::kOutputCount * NativeBmwRouting::kAllPassSectionsPerOutput * kAllPassValueWidth,
        kOutputConfigBase = 87,
        kOutputConfigWidth = 13,
    };

    // KNOWN ISSUE (not yet fixed -- flagged during an OpenCodeReview pass, left for a dedicated
    // follow-up rather than patched blindly here): setSampleRate()/configure()/configurePeq(),
    // called from the UI/config thread, write p_/routing_/outputs_/outputConfigs_/PEQ state with
    // no synchronization against process()'s reads of that same state on the audio thread. Every
    // field involved is a plain float/bool/enum (no pointers), so this can't corrupt memory or
    // crash -- worst case is a torn read producing one inconsistent audio frame (e.g. new filter
    // coefficients paired with old delay-buffer contents) right as a setting changes, which could
    // manifest as a brief click/glitch. A real fix (a mutex locked once per process() call, or
    // lock-free double-buffering) needs on-device audio-glitch verification before landing.
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
        DirtyPolarity   = 1u << 9,
        DirtyAll        = 0xffffffffu,
    };

    struct Biquad {
        float b0=1,b1=0,b2=0,a1=0,a2=0,z1=0,z2=0;
        float run(float x);
        void clear();
        void loadAllPass(const NativeBmwRouting::BiquadCoefficients& c);
    };
    struct OnePole {
        float a0=1,a1=0,b1=0,x1=0,y1=0;
        float run(float x);
        void clear();
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
        OnePole crossoverPole;
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
            subsonic1.clear(); crossover1.clear(); crossover2.clear(); crossoverPole.clear();
            delay.clear();
            for (auto& section : allPassState) section.clear();
        }
    };
    struct Limiter {
        Delay delayL,delayR;
        float gain=1;
        float attackMix=1,releaseMix=1;
        void clear();
    };
    struct Params {
        bool enabled=true,lpfPass=false,hpfPass=false,tilt=true;
        int channelMute=0,measurementMute=0;
        float headroom=-6,lowGainL=0,lowGainR=0,midGainL=-1,midGainR=-1,postGainL=0,postGainR=0;
        float midDelayL=0,midDelayR=0,lowDelayL=0,lowDelayR=0;
        float tiltAmount=3,tiltFreq=550;
    } p_;

    static float dbToLin(float db);
    static void makeLowPass(Biquad& q,float fc,float Q,float sr);
    static void makeHighPass(Biquad& q,float fc,float Q,float sr);
    static void makeLowShelf(Biquad& q,float fc,float gain,float sr);
    static void makeHighShelf(Biquad& q,float fc,float gain,float sr);
    static bool makePeq(Biquad& q, double frequency, double gain, double Q, int type, float sampleRate);
    static void makeOnePoleLow(OnePole& p,float fc,float sr);
    float processChannelInput(float x, float& dcX, float& dcY);
    float processLowCrossover(OutputRuntime& out, const OutputConfig& config, float sample);
    float processMidCrossover(OutputRuntime& out, float sample);
    void processFrame(float& l,float& r);
    void processCompressor(float& sample,const CompressorParams& params,CompressorState& state);
    void processLimiter(float& left,float& right);
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
    void rebuildPolarityAndMute();
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
    Biquad tiltLoL1_,tiltLoL2_,tiltHiL1_,tiltHiL2_;
    Biquad tiltLoR1_,tiltLoR2_,tiltHiR1_,tiltHiR2_;
    static constexpr float kLimiterLookaheadMs = 5.f;
    static constexpr float kLimiterCeilingLin = 0.891251f;
};

#endif
