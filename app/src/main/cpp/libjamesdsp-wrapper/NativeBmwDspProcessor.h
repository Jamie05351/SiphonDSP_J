#ifndef SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H
#define SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H

#include <array>
#include <cstddef>
#include <cstdint>

class NativeBmwDspProcessor {
public:
    static constexpr std::size_t kConfigSize = 31;
    NativeBmwDspProcessor();
    ~NativeBmwDspProcessor();
    void setSampleRate(float sampleRate);
    bool configure(const float* values, std::size_t count);
    static NativeBmwDspProcessor* latest();
    const int16_t* process(const int16_t* samples, std::size_t sampleCount);
    const int32_t* process(const int32_t* samples, std::size_t sampleCount);
    const float* process(const float* samples, std::size_t sampleCount);

private:
    struct Biquad { float b0=1,b1=0,b2=0,a1=0,a2=0,z1=0,z2=0; float run(float x); void clear(); };
    struct OnePole { float a0=1,a1=0,b1=0,x1=0,y1=0; float run(float x); void clear(); };
    struct Delay {
        std::array<float,256> data{};
        unsigned write=0;
        float delay=0;
        float run(float x);
        void clear();
    };
    struct Channel {
        Biquad sub1,sub2,lowA,lowB,mid1,mid2,tiltLo1,tiltLo2,tiltHi1,tiltHi2;
        OnePole lowPole;
        Delay lowDelay,midDelay;
        float dcX=0,dcY=0;
    };
    struct Params {
        bool enabled=true,lpfPass=false,hpfPass=false,subsonic=true,lowMute=false,midMute=false;
        bool lowLr4=false,lowInvert=false,midInvert=false,tilt=true,compressor=true;
        int channelMute=0,measurementMute=0;
        float headroom=-6,lowGainL=0,lowGainR=0,midGainL=-1,midGainR=-1,postGainL=0,postGainR=0;
        float subFreq=32,lpf=150,hpf=125,lowDelayL=0,lowDelayR=0,midDelayL=0,midDelayR=0;
        float tiltAmount=3,tiltFreq=550,threshold=-12,ratio=2,knee=8,attack=40,release=250,makeup=1.5f;
    } p_;

    static NativeBmwDspProcessor* latest_;
    static float dbToLin(float db);
    static void makeLowPass(Biquad& q,float fc,float Q,float sr);
    static void makeHighPass(Biquad& q,float fc,float Q,float sr);
    static void makeLowShelf(Biquad& q,float fc,float gain,float sr);
    static void makeHighShelf(Biquad& q,float fc,float gain,float sr);
    static void makeOnePoleLow(OnePole& p,float fc,float sr);
    float processChannelInput(float x, Channel& c);
    void processFrame(float& l,float& r);
    void rebuild();
    void resetDynamics();

    Channel left_,right_;
    float sampleRate_=48000.0f,dcR_=0.0f;
    float headroom_=1,lowGainL_=1,lowGainR_=1,midGainL_=1,midGainR_=1,postGainL_=1,postGainR_=1,makeup_=1;
    float compGain_=1,rmsPower_=0,peakEnv_=0,rmsMix_=0,peakRelease_=0,attackMix_=0,releaseMix_=0;
};

#endif
