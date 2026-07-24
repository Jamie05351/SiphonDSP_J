#include "NativeBmwDspProcessor.h"
#include <algorithm>
#include <cmath>
#include <limits>

namespace {
constexpr float PI=3.14159265358979323846f;
constexpr float BW=0.7071067812f;
constexpr float BW4Q1=0.5411961f;
constexpr float BW4Q2=1.3065630f;
inline float ftz(float x){ return (!std::isfinite(x)||std::fabs(x)<1.0e-20f)?0.0f:x; }
template<class T> T clampInt(float x){
    const float lo=static_cast<float>(std::numeric_limits<T>::min());
    const float hi=static_cast<float>(std::numeric_limits<T>::max());
    return static_cast<T>(std::lrintf(std::max(lo,std::min(hi,x))));
}
}

float NativeBmwDspProcessor::Biquad::run(float x){ const float y=b0*x+z1; z1=ftz(b1*x-a1*y+z2); z2=ftz(b2*x-a2*y); return ftz(y); }
void NativeBmwDspProcessor::Biquad::clear(){ z1=z2=0; }
float NativeBmwDspProcessor::OnePole::run(float x){ const float y=a0*x+a1*x1-b1*y1; x1=x; y1=ftz(y); return y1; }
void NativeBmwDspProcessor::OnePole::clear(){ x1=y1=0; }
float NativeBmwDspProcessor::dbToLin(float db){ return std::pow(10.0f,db/20.0f); }

NativeBmwDspProcessor::NativeBmwDspProcessor(){ rebuild(); }
void NativeBmwDspProcessor::setSampleRate(float sr){ if(sr>8000&&std::fabs(sr-sampleRate_)>0.5f){ sampleRate_=sr; rebuild(); } }

void NativeBmwDspProcessor::makeLowPass(Biquad& q,float fc,float Q,float sr){
    const float w=2*PI*std::min(fc,sr*0.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),a0=1+a;
    q.b0=((1-c)*0.5f)/a0; q.b1=(1-c)/a0; q.b2=q.b0; q.a1=(-2*c)/a0; q.a2=(1-a)/a0; q.clear();
}
void NativeBmwDspProcessor::makeHighPass(Biquad& q,float fc,float Q,float sr){
    const float w=2*PI*std::min(fc,sr*0.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),a0=1+a;
    q.b0=((1+c)*0.5f)/a0; q.b1=(-(1+c))/a0; q.b2=q.b0; q.a1=(-2*c)/a0; q.a2=(1-a)/a0; q.clear();
}
void NativeBmwDspProcessor::makeOnePoleLow(OnePole& p,float fc,float sr){ const float K=std::tan(PI*fc/sr); p.a0=K/(K+1); p.a1=p.a0; p.b1=(K-1)/(K+1); p.clear(); }
void NativeBmwDspProcessor::makeLowShelf(Biquad& q,float fc,float gain,float sr){
    const float A=std::pow(10.0f,gain/40.0f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),alpha=s/(2*BW),r=std::sqrt(A);
    const float inv=1.0f/((A+1)+(A-1)*c+2*r*alpha);
    q.b0=A*((A+1)-(A-1)*c+2*r*alpha)*inv; q.b1=2*A*((A-1)-(A+1)*c)*inv; q.b2=A*((A+1)-(A-1)*c-2*r*alpha)*inv;
    q.a1=-2*((A-1)+(A+1)*c)*inv; q.a2=((A+1)+(A-1)*c-2*r*alpha)*inv; q.clear();
}
void NativeBmwDspProcessor::makeHighShelf(Biquad& q,float fc,float gain,float sr){
    const float A=std::pow(10.0f,gain/40.0f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),alpha=s/(2*BW),r=std::sqrt(A);
    const float inv=1.0f/((A+1)-(A-1)*c+2*r*alpha);
    q.b0=A*((A+1)+(A-1)*c+2*r*alpha)*inv; q.b1=-2*A*((A-1)+(A+1)*c)*inv; q.b2=A*((A+1)+(A-1)*c-2*r*alpha)*inv;
    q.a1=2*((A-1)-(A+1)*c)*inv; q.a2=((A+1)-(A-1)*c-2*r*alpha)*inv; q.clear();
}

void NativeBmwDspProcessor::rebuild(){
    dcR_=std::exp(-2*PI*10.0f/sampleRate_);
    for(Channel* c:{&left_,&right_}){
        makeHighPass(c->sub1,32,BW4Q1,sampleRate_); makeHighPass(c->sub2,32,BW4Q2,sampleRate_);
        makeLowPass(c->low2,150,1.0f,sampleRate_); makeOnePoleLow(c->low1,150,sampleRate_);
        makeHighPass(c->mid1,125,BW,sampleRate_); makeHighPass(c->mid2,125,BW,sampleRate_);
        makeLowShelf(c->tiltLo1,550,2.25f,sampleRate_); makeLowShelf(c->tiltLo2,550,2.25f,sampleRate_);
        makeHighShelf(c->tiltHi1,550,-2.25f,sampleRate_); makeHighShelf(c->tiltHi2,550,-2.25f,sampleRate_);
        c->dcX=c->dcY=0;
    }
    rmsMix_=1-std::exp(-1.0f/(0.050f*sampleRate_)); peakRelease_=std::exp(-1.0f/(0.080f*sampleRate_));
    attackMix_=1-std::exp(-1.0f/(0.040f*sampleRate_)); releaseMix_=1-std::exp(-1.0f/(0.250f*sampleRate_));
    compGain_=1; rmsPower_=peakEnv_=0;
}

float NativeBmwDspProcessor::processChannelInput(float x,Channel& c){ const float y=x-c.dcX+dcR_*c.dcY; c.dcX=x; c.dcY=ftz(y); return c.dcY*headroom_; }
void NativeBmwDspProcessor::processFrame(float& l,float& r){
    const float srcL=processChannelInput(l,left_),srcR=processChannelInput(r,right_);
    float lowL=left_.low1.run(left_.low2.run(left_.sub2.run(left_.sub1.run(srcL))));
    float lowR=right_.low1.run(right_.low2.run(right_.sub2.run(right_.sub1.run(srcR))));
    float midL=left_.mid2.run(left_.mid1.run(srcL))*midGain_;
    float midR=right_.mid2.run(right_.mid1.run(srcR))*midGain_;

    const float peak=std::max(std::fabs(lowL),std::fabs(lowR));
    rmsPower_=ftz(rmsPower_+(peak*peak-rmsPower_)*rmsMix_);
    peakEnv_=peak>peakEnv_?peak:ftz(peakEnv_*peakRelease_);
    const float detector=std::max(std::sqrt(std::max(0.0f,rmsPower_)),peakEnv_*0.5f);
    const float db=20.0f*std::log10(std::max(detector,1.0e-12f));
    const float over=db+12.0f,knee=8.0f,slope=0.5f;
    float gr=0;
    if(over>=4) gr=-over*slope; else if(over>-4){ const float x=over+4; gr=-slope*x*x/(2*knee); }
    const float target=dbToLin(gr),mix=target<compGain_?attackMix_:releaseMix_;
    compGain_=std::min(1.0f,ftz(compGain_+(target-compGain_)*mix));
    lowL*=compGain_*makeup_; lowR*=compGain_*makeup_;

    float outL=lowL+midL,outR=lowR+midR;
    outL=left_.tiltHi2.run(left_.tiltHi1.run(left_.tiltLo2.run(left_.tiltLo1.run(outL))));
    outR=right_.tiltHi2.run(right_.tiltHi1.run(right_.tiltLo2.run(right_.tiltLo1.run(outR))));
    l=outR; r=outL;
}

const float* NativeBmwDspProcessor::process(const float* s,std::size_t n){ if(!s)return s; auto*w=const_cast<float*>(s); for(std::size_t i=0;i+1<n;i+=2) processFrame(w[i],w[i+1]); return s; }
const int16_t* NativeBmwDspProcessor::process(const int16_t* s,std::size_t n){ if(!s)return s; auto*w=const_cast<int16_t*>(s); for(std::size_t i=0;i+1<n;i+=2){ float l=w[i],r=w[i+1]; processFrame(l,r); w[i]=clampInt<int16_t>(l); w[i+1]=clampInt<int16_t>(r);} return s; }
const int32_t* NativeBmwDspProcessor::process(const int32_t* s,std::size_t n){ if(!s)return s; auto*w=const_cast<int32_t*>(s); for(std::size_t i=0;i+1<n;i+=2){ float l=w[i],r=w[i+1]; processFrame(l,r); w[i]=clampInt<int32_t>(l); w[i+1]=clampInt<int32_t>(r);} return s; }
