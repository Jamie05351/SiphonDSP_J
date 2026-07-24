#include "NativeBmwDspProcessor.h"
#include <algorithm>
#include <cmath>
#include <limits>

namespace {
constexpr float PI=3.14159265358979323846f,BW=0.7071067812f,BW4Q1=0.5411961f,BW4Q2=1.3065630f;
inline float ftz(float x){return(!std::isfinite(x)||std::fabs(x)<1e-20f)?0.f:x;}
template<class T>T clampInt(float x){return static_cast<T>(std::lrintf(std::clamp(x,static_cast<float>(std::numeric_limits<T>::min()),static_cast<float>(std::numeric_limits<T>::max()))));}
inline float clampf(float x,float lo,float hi){return std::max(lo,std::min(hi,x));}
}

NativeBmwDspProcessor* NativeBmwDspProcessor::latest_=nullptr;
NativeBmwDspProcessor* NativeBmwDspProcessor::latest(){return latest_;}
float NativeBmwDspProcessor::Biquad::run(float x){float y=b0*x+z1;z1=ftz(b1*x-a1*y+z2);z2=ftz(b2*x-a2*y);return ftz(y);}
void NativeBmwDspProcessor::Biquad::clear(){z1=z2=0;}
float NativeBmwDspProcessor::OnePole::run(float x){float y=a0*x+a1*x1-b1*y1;x1=x;y1=ftz(y);return y1;}
void NativeBmwDspProcessor::OnePole::clear(){x1=y1=0;}
float NativeBmwDspProcessor::Delay::run(float x){if(delay<=0)return x;data[write]=x;float read=static_cast<float>(write)-delay;while(read<0)read+=data.size();unsigned i0=static_cast<unsigned>(read)%data.size(),i1=(i0+1)%data.size();float f=read-std::floor(read),y=data[i0]+(data[i1]-data[i0])*f;write=(write+1)%data.size();return y;}
void NativeBmwDspProcessor::Delay::clear(){data.fill(0);write=0;}
float NativeBmwDspProcessor::dbToLin(float db){return std::pow(10.f,db/20.f);}

NativeBmwDspProcessor::NativeBmwDspProcessor(){latest_=this;rebuild();}
NativeBmwDspProcessor::~NativeBmwDspProcessor(){if(latest_==this)latest_=nullptr;}
void NativeBmwDspProcessor::setSampleRate(float sr){if(sr>=8000&&std::fabs(sr-sampleRate_)>.5f){sampleRate_=sr;rebuild();}}

bool NativeBmwDspProcessor::configure(const float* v,std::size_t n){
 if(!v||n!=kConfigSize)return false;
 p_.enabled=v[0]>=.5f;p_.lpfPass=v[1]>=.5f;p_.hpfPass=v[2]>=.5f;p_.channelMute=static_cast<int>(clampf(v[3],0,2));p_.measurementMute=static_cast<int>(clampf(v[4],0,2));
 p_.headroom=clampf(v[5],-12,0);p_.lowGainL=clampf(v[6],-6,0);p_.lowGainR=clampf(v[7],-6,0);p_.midGainL=clampf(v[8],-6,0);p_.midGainR=clampf(v[9],-6,0);p_.postGainL=clampf(v[10],-6,6);p_.postGainR=clampf(v[11],-6,6);
 p_.subsonic=v[12]>=.5f;p_.subFreq=clampf(v[13],20,60);p_.lowMute=v[14]>=.5f;p_.lpf=clampf(v[15],80,200);p_.lowLr4=v[16]>=.5f;p_.midMute=v[17]>=.5f;p_.hpf=clampf(v[18],80,200);
 p_.lowInvert=v[19]>=.5f;p_.midInvert=v[20]>=.5f;p_.midDelayL=clampf(v[21],0,2.8f);p_.midDelayR=clampf(v[22],0,2.8f);p_.lowDelayL=clampf(v[23],0,2.8f);p_.lowDelayR=clampf(v[24],0,2.8f);
 p_.tilt=v[25]>=.5f;p_.tiltAmount=clampf(v[26],-6,6);p_.tiltFreq=clampf(v[27],200,2000);p_.compressor=v[28]>=.5f;p_.threshold=clampf(v[29],-18,0);p_.ratio=clampf(v[30],1,10);p_.knee=clampf(v[31],0,12);p_.attack=clampf(v[32],1,50);p_.release=clampf(v[33],20,400);p_.makeup=clampf(v[34],0,6);
 rebuild();return true;
}

void NativeBmwDspProcessor::makeLowPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1-c)*.5f)/d;q.b1=(1-c)/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
void NativeBmwDspProcessor::makeHighPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1+c)*.5f)/d;q.b1=(-(1+c))/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
void NativeBmwDspProcessor::makeOnePoleLow(OnePole&p,float fc,float sr){float K=std::tan(PI*clampf(fc,20,sr*.49f)/sr);p.a0=K/(K+1);p.a1=p.a0;p.b1=(K-1)/(K+1);p.clear();}
void NativeBmwDspProcessor::makeLowShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)+(A-1)*c+2*r*a);q.b0=A*((A+1)-(A-1)*c+2*r*a)*iv;q.b1=2*A*((A-1)-(A+1)*c)*iv;q.b2=A*((A+1)-(A-1)*c-2*r*a)*iv;q.a1=-2*((A-1)+(A+1)*c)*iv;q.a2=((A+1)+(A-1)*c-2*r*a)*iv;q.clear();}
void NativeBmwDspProcessor::makeHighShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)-(A-1)*c+2*r*a);q.b0=A*((A+1)+(A-1)*c+2*r*a)*iv;q.b1=-2*A*((A-1)+(A+1)*c)*iv;q.b2=A*((A+1)+(A-1)*c-2*r*a)*iv;q.a1=2*((A-1)-(A+1)*c)*iv;q.a2=((A+1)-(A-1)*c-2*r*a)*iv;q.clear();}

void NativeBmwDspProcessor::resetDynamics(){compGain_=1;rmsPower_=peakEnv_=0;}
void NativeBmwDspProcessor::rebuild(){
 dcR_=std::exp(-2*PI*10/sampleRate_);headroom_=dbToLin(p_.headroom);lowGainL_=dbToLin(p_.lowGainL);lowGainR_=dbToLin(p_.lowGainR);midGainL_=dbToLin(p_.midGainL);midGainR_=dbToLin(p_.midGainR);postGainL_=dbToLin(p_.postGainL);postGainR_=dbToLin(p_.postGainR);makeup_=dbToLin(p_.makeup);
 for(Channel*c:{&left_,&right_}){makeHighPass(c->sub1,p_.subFreq,BW4Q1,sampleRate_);makeHighPass(c->sub2,p_.subFreq,BW4Q2,sampleRate_);makeLowPass(c->lowA,p_.lpf,p_.lowLr4?BW:1.f,sampleRate_);makeLowPass(c->lowB,p_.lpf,BW,sampleRate_);makeOnePoleLow(c->lowPole,p_.lpf,sampleRate_);makeHighPass(c->mid1,p_.hpf,BW,sampleRate_);makeHighPass(c->mid2,p_.hpf,BW,sampleRate_);float tg=p_.tiltAmount*.75f;makeLowShelf(c->tiltLo1,p_.tiltFreq,tg,sampleRate_);makeLowShelf(c->tiltLo2,p_.tiltFreq,tg,sampleRate_);makeHighShelf(c->tiltHi1,p_.tiltFreq,-tg,sampleRate_);makeHighShelf(c->tiltHi2,p_.tiltFreq,-tg,sampleRate_);c->dcX=c->dcY=0;c->lowDelay.clear();c->midDelay.clear();}
 left_.lowDelay.delay=p_.lowDelayL*sampleRate_*.001f;right_.lowDelay.delay=p_.lowDelayR*sampleRate_*.001f;left_.midDelay.delay=p_.midDelayL*sampleRate_*.001f;right_.midDelay.delay=p_.midDelayR*sampleRate_*.001f;
 rmsMix_=1-std::exp(-1/(.050f*sampleRate_));peakRelease_=std::exp(-1/(.080f*sampleRate_));attackMix_=1-std::exp(-1/(p_.attack*.001f*sampleRate_));releaseMix_=1-std::exp(-1/(p_.release*.001f*sampleRate_));resetDynamics();
}
float NativeBmwDspProcessor::processChannelInput(float x,Channel&c){float y=x-c.dcX+dcR_*c.dcY;c.dcX=x;c.dcY=ftz(y);return c.dcY*headroom_;}
void NativeBmwDspProcessor::processFrame(float&l,float&r){if(!p_.enabled)return;float sL=processChannelInput(l,left_),sR=processChannelInput(r,right_),lowL=sL,lowR=sR,midL=sL,midR=sR;
 if(p_.subsonic){lowL=left_.sub2.run(left_.sub1.run(lowL));lowR=right_.sub2.run(right_.sub1.run(lowR));}
 if(!p_.lpfPass){lowL=left_.lowA.run(lowL);lowR=right_.lowA.run(lowR);if(p_.lowLr4){lowL=left_.lowB.run(lowL);lowR=right_.lowB.run(lowR);}else{lowL=left_.lowPole.run(lowL);lowR=right_.lowPole.run(lowR);}lowL=left_.lowDelay.run(lowL);lowR=right_.lowDelay.run(lowR);
  if(p_.compressor&&!p_.lowMute&&p_.measurementMute!=1){float pk=std::max(std::fabs(lowL),std::fabs(lowR));rmsPower_=ftz(rmsPower_+(pk*pk-rmsPower_)*rmsMix_);peakEnv_=pk>peakEnv_?pk:ftz(peakEnv_*peakRelease_);float det=std::max(std::sqrt(std::max(0.f,rmsPower_)),peakEnv_*.5f),db=20*std::log10(std::max(det,1e-12f)),over=db-p_.threshold,slope=1-1/std::max(1.001f,p_.ratio),gr=0,kh=p_.knee*.5f;if(p_.knee>0){if(over>=kh)gr=-over*slope;else if(over>-kh){float x=over+kh;gr=-slope*x*x/(2*p_.knee);}}else if(over>0)gr=-over*slope;float t=dbToLin(gr),mix=t<compGain_?attackMix_:releaseMix_;compGain_=std::min(1.f,ftz(compGain_+(t-compGain_)*mix));lowL*=compGain_*makeup_;lowR*=compGain_*makeup_;}lowL*=lowGainL_;lowR*=lowGainR_;}
 if(!p_.hpfPass){midL=left_.midDelay.run(left_.mid2.run(left_.mid1.run(midL)))*midGainL_;midR=right_.midDelay.run(right_.mid2.run(right_.mid1.run(midR)))*midGainR_;}
 if(p_.lowInvert){lowL=-lowL;lowR=-lowR;}if(p_.midInvert){midL=-midL;midR=-midR;}if(p_.lowMute||p_.measurementMute==1)lowL=lowR=0;if(p_.midMute||p_.measurementMute==2)midL=midR=0;
 float oL=(p_.lpfPass&&p_.hpfPass)?sL:lowL+midL,oR=(p_.lpfPass&&p_.hpfPass)?sR:lowR+midR;if(p_.tilt){oL=left_.tiltHi2.run(left_.tiltHi1.run(left_.tiltLo2.run(left_.tiltLo1.run(oL))));oR=right_.tiltHi2.run(right_.tiltHi1.run(right_.tiltLo2.run(right_.tiltLo1.run(oR))));}oL*=postGainL_;oR*=postGainR_;if(p_.channelMute==1)oL=0;if(p_.channelMute==2)oR=0;l=oR;r=oL;}
const float* NativeBmwDspProcessor::process(const float*s,std::size_t n){if(!s)return s;auto*w=const_cast<float*>(s);for(std::size_t i=0;i+1<n;i+=2)processFrame(w[i],w[i+1]);return s;}
const int16_t* NativeBmwDspProcessor::process(const int16_t*s,std::size_t n){if(!s)return s;auto*w=const_cast<int16_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=w[i],r=w[i+1];processFrame(l,r);w[i]=clampInt<int16_t>(l);w[i+1]=clampInt<int16_t>(r);}return s;}
const int32_t* NativeBmwDspProcessor::process(const int32_t*s,std::size_t n){if(!s)return s;auto*w=const_cast<int32_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=w[i],r=w[i+1];processFrame(l,r);w[i]=clampInt<int32_t>(l);w[i+1]=clampInt<int32_t>(r);}return s;}
