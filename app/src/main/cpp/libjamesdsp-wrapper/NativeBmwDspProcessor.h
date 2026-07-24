#ifndef SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H
#define SIPHONDSP_NATIVE_BMW_DSP_PROCESSOR_H

#include <cstddef>
#include <cstdint>

class NativeBmwDspProcessor {
public:
    NativeBmwDspProcessor();
    void setSampleRate(float sampleRate);
    const int16_t* process(const int16_t* samples, std::size_t sampleCount);
    const int32_t* process(const int32_t* samples, std::size_t sampleCount);
    const float* process(const float* samples, std::size_t sampleCount);

private:
    struct Biquad { float b0=1,b1=0,b2=0,a1=0,a2=0,z1=0,z2=0; float run(float x); void clear(); };
    struct OnePole { float a0=1,a1=0,b1=0,x1=0,y1=0; float run(float x); void clear(); };
    struct Channel {
        Biquad sub1,sub2,low2,mid1,mid2,tiltLo1,tiltLo2,tiltHi1,tiltHi2;
        OnePole low1;
        float dcX=0,dcY=0;
    };

    static float dbToLin(float db);
    static void makeLowPass(Biquad& q,float fc,float Q,float sr);
    static void makeHighPass(Biquad& q,float fc,float Q,float sr);
    static void makeLowShelf(Biquad& q,float fc,float gain,float sr);
    static void makeHighShelf(Biquad& q,float fc,float gain,float sr);
    static void makeOnePoleLow(OnePole& p,float fc,float sr);
    float processChannelInput(float x, Channel& c);
    void processFrame(float& l,float& r);
    void rebuild();

    Channel left_,right_;
    float sampleRate_=48000.0f,dcR_=0.0f;
    float headroom_=0.501187234f,midGain_=0.891250938f,makeup_=1.188502227f;
    float compGain_=1.0f,rmsPower_=0.0f,peakEnv_=0.0f;
    float rmsMix_=0,peakRelease_=0,attackMix_=0,releaseMix_=0;
};

#endif
