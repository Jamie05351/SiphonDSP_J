#ifndef SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H
#define SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

class NativeBmwDspProcessor {
public:
    enum : std::size_t { kConfigSize = 35 };
    enum : std::size_t { kMaxPeqSectionsPerChannel = 16, kPeqBandWidth = 5 };
    // Sized for the 2.8ms max delay clamped in configure() at the highest sample rate the
    // service actually opens AudioRecord/AudioTrack at (48kHz -> 134.4 samples), with headroom.
    // updateDelays() must clamp requested delay samples to below this capacity so a caller that
    // ever raises sampleRate_ beyond ~91kHz can't silently alias the delay line instead of
    // producing the requested delay.
    enum : unsigned { kDelayLineCapacity = 256 };
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
        DirtyAll        = 0xffffffffu,
    };

    struct Biquad { float b0=1,b1=0,b2=0,a1=0,a2=0,z1=0,z2=0; float run(float x); void clear(); };
    struct OnePole { float a0=1,a1=0,b1=0,x1=0,y1=0; float run(float x); void clear(); };
    struct Delay {
        std::array<float,kDelayLineCapacity> data{};
        unsigned write=0;
        float delay=0;
        float run(float x);
        void clear();
    };
    struct Channel {
        Biquad sub1,lowA,lowB,mid1,mid2,tiltLo1,tiltLo2,tiltHi1,tiltHi2;
        OnePole lowPole;
        Delay lowDelay,midDelay;
        float dcX=0,dcY=0;
    };
    struct PeqBank {
        std::array<Biquad, kMaxPeqSectionsPerChannel> left{};
        std::array<Biquad, kMaxPeqSectionsPerChannel> right{};
        std::size_t leftCount=0,rightCount=0;
        float processLeft(float sample);
        float processRight(float sample);
        void clear();
    };
    struct Params {
        bool enabled=true,lpfPass=false,hpfPass=false,subsonic=true,lowMute=false,midMute=false;
        bool lowLr4=false,lowInvert=false,midInvert=false,tilt=true,compressor=true;
        int channelMute=0,measurementMute=0;
        float headroom=-6,lowGainL=0,lowGainR=0,midGainL=-1,midGainR=-1,postGainL=0,postGainR=0;
        float subFreq=32,lpf=150,hpf=125,lowDelayL=0,lowDelayR=0,midDelayL=0,midDelayR=0;
        float tiltAmount=3,tiltFreq=550,threshold=-12,ratio=2,knee=8,attack=40,release=250,makeup=1.5f;
    } p_;

    static float dbToLin(float db);
    static void makeLowPass(Biquad& q,float fc,float Q,float sr);
    static void makeHighPass(Biquad& q,float fc,float Q,float sr);
    static void makeLowShelf(Biquad& q,float fc,float gain,float sr);
    static void makeHighShelf(Biquad& q,float fc,float gain,float sr);
    static bool makePeq(Biquad& q, double frequency, double gain, double Q,
                        int type, float sampleRate);
    static void makeOnePoleLow(OnePole& p,float fc,float sr);
    float processChannelInput(float x, Channel& c);
    void processFrame(float& l,float& r);
    void rebuildAll();
    void applyDirty(uint32_t dirty);
    void rebuildGains();
    void rebuildSubsonic();
    void rebuildLowCrossover();
    void rebuildMidCrossover();
    void updateDelays();
    void rebuildTilt();
    void rebuildCompressorTiming();
    void resetDynamics();

    Channel left_,right_;
    PeqBank fullPeq_,lowPeq_,midPeq_;
    bool peqEnabled_=false;
    float peqPreampDb_=0,peqPreamp_=1;
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> fullPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> lowPeqValues_{};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth> midPeqValues_{};
    std::size_t fullPeqValueCount_=0,lowPeqValueCount_=0,midPeqValueCount_=0;
    float sampleRate_=48000.0f,dcR_=0.0f;
    float headroom_=1,lowGainL_=1,lowGainR_=1,midGainL_=1,midGainR_=1,postGainL_=1,postGainR_=1,makeup_=1;
    float compGain_=1,rmsPower_=0,peakEnv_=0,rmsMix_=0,peakRelease_=0,attackMix_=0,releaseMix_=0;
    std::atomic<float> compressorInputDb_{-60.0f};
    std::atomic<float> compressorOutputDb_{-60.0f};
    std::atomic<float> compressorGainReductionDb_{0.0f};
    uint32_t compressorMeterCounter_=0;
};

#endif
