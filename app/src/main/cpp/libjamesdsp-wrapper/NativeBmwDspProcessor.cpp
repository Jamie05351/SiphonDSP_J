#include "NativeBmwDspProcessor.h"
#include <algorithm>
#include <cmath>
#include <limits>

namespace {
constexpr float PI=3.14159265358979323846f,BW=0.7071067812f;
inline float ftz(float x){return(!std::isfinite(x)||std::fabs(x)<1e-20f)?0.f:x;}
// Bounds are computed in double (exact for int32_t's range, unlike float's 24-bit mantissa) so a
// full-scale input clamps to the true min/max instead of rounding past it and wrapping sign.
template<class T>T clampInt(float x){const double lo=static_cast<double>(std::numeric_limits<T>::min()),hi=static_cast<double>(std::numeric_limits<T>::max());return static_cast<T>(std::llrint(std::max(lo,std::min(hi,static_cast<double>(x)))));}
inline float clampf(float x,float lo,float hi){return std::max(lo,std::min(hi,x));}
inline bool changed(float a,float b){return std::fabs(a-b)>1e-6f;}
}

float NativeBmwDspProcessor::Biquad::run(float x){float y=b0*x+z1;z1=ftz(b1*x-a1*y+z2);z2=ftz(b2*x-a2*y);return ftz(y);}
void NativeBmwDspProcessor::Biquad::clear(){z1=z2=0;}
float NativeBmwDspProcessor::PeqBank::processLeft(float sample){for(std::size_t i=0;i<leftCount;++i)sample=left[i].run(sample);return sample;}
float NativeBmwDspProcessor::PeqBank::processRight(float sample){for(std::size_t i=0;i<rightCount;++i)sample=right[i].run(sample);return sample;}
void NativeBmwDspProcessor::PeqBank::clear(){for(auto& section:left)section.clear();for(auto& section:right)section.clear();}
float NativeBmwDspProcessor::OnePole::run(float x){float y=a0*x+a1*x1-b1*y1;x1=x;y1=ftz(y);return y1;}
void NativeBmwDspProcessor::OnePole::clear(){x1=y1=0;}
float NativeBmwDspProcessor::Delay::run(float x){if(delay<=0)return x;data[write]=x;float read=static_cast<float>(write)-delay;while(read<0)read+=data.size();unsigned i0=static_cast<unsigned>(read)%data.size(),i1=(i0+1)%data.size();float f=read-std::floor(read),y=data[i0]+(data[i1]-data[i0])*f;write=(write+1)%data.size();return y;}
void NativeBmwDspProcessor::Delay::clear(){data.fill(0);write=0;}
float NativeBmwDspProcessor::dbToLin(float db){return std::pow(10.f,db/20.f);}

NativeBmwDspProcessor::NativeBmwDspProcessor(){rebuildAll();}
NativeBmwDspProcessor::~NativeBmwDspProcessor()=default;
void NativeBmwDspProcessor::setSampleRate(float sr){if(sr>=8000&&std::fabs(sr-sampleRate_)>.5f){sampleRate_=sr;rebuildAll();}}

bool NativeBmwDspProcessor::configure(const float* v,std::size_t n){
 if(!v||n!=kConfigSize)return false;
 Params next=p_;
 next.enabled=v[0]>=.5f;next.lpfPass=v[1]>=.5f;next.hpfPass=v[2]>=.5f;next.channelMute=static_cast<int>(clampf(v[3],0,2));next.measurementMute=static_cast<int>(clampf(v[4],0,2));
 next.headroom=clampf(v[5],-12,0);next.lowGainL=clampf(v[6],-6,0);next.lowGainR=clampf(v[7],-6,0);next.midGainL=clampf(v[8],-6,0);next.midGainR=clampf(v[9],-6,0);next.postGainL=clampf(v[10],-6,6);next.postGainR=clampf(v[11],-6,6);
 next.subsonic=v[12]>=.5f;next.subFreq=clampf(v[13],20,60);next.lowMute=v[14]>=.5f;next.lpf=clampf(v[15],80,200);next.lowLr4=v[16]>=.5f;next.midMute=v[17]>=.5f;next.hpf=clampf(v[18],80,200);
 next.lowInvert=v[19]>=.5f;next.midInvert=v[20]>=.5f;next.midDelayL=clampf(v[21],0,2.8f);next.midDelayR=clampf(v[22],0,2.8f);next.lowDelayL=clampf(v[23],0,2.8f);next.lowDelayR=clampf(v[24],0,2.8f);
 next.tilt=v[25]>=.5f;next.tiltAmount=clampf(v[26],-6,6);next.tiltFreq=clampf(v[27],200,2000);next.compressor=v[28]>=.5f;next.threshold=clampf(v[29],-18,0);next.ratio=clampf(v[30],1,10);next.knee=clampf(v[31],0,12);next.attack=clampf(v[32],1,50);next.release=clampf(v[33],20,400);next.makeup=clampf(v[34],0,6);

 uint32_t dirty=DirtyNone;
 if(changed(next.headroom,p_.headroom)||changed(next.lowGainL,p_.lowGainL)||changed(next.lowGainR,p_.lowGainR)||changed(next.midGainL,p_.midGainL)||changed(next.midGainR,p_.midGainR)||changed(next.postGainL,p_.postGainL)||changed(next.postGainR,p_.postGainR)||changed(next.makeup,p_.makeup))dirty|=DirtyGains;
 if(changed(next.subFreq,p_.subFreq))dirty|=DirtySubsonic;
 if(changed(next.lpf,p_.lpf)||next.lowLr4!=p_.lowLr4)dirty|=DirtyLowXo;
 if(changed(next.hpf,p_.hpf))dirty|=DirtyMidXo;
 if(changed(next.lowDelayL,p_.lowDelayL)||changed(next.lowDelayR,p_.lowDelayR)||changed(next.midDelayL,p_.midDelayL)||changed(next.midDelayR,p_.midDelayR))dirty|=DirtyDelays;
 if(changed(next.tiltAmount,p_.tiltAmount)||changed(next.tiltFreq,p_.tiltFreq))dirty|=DirtyTilt;
 if(changed(next.attack,p_.attack)||changed(next.release,p_.release))dirty|=DirtyCompTiming;
 if(next.compressor!=p_.compressor)dirty|=DirtyCompState;
 p_=next;
 applyDirty(dirty);
 return true;
}

void NativeBmwDspProcessor::makeLowPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1-c)*.5f)/d;q.b1=(1-c)/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
void NativeBmwDspProcessor::makeHighPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1+c)*.5f)/d;q.b1=(-(1+c))/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
void NativeBmwDspProcessor::makeOnePoleLow(OnePole&p,float fc,float sr){float K=std::tan(PI*clampf(fc,20,sr*.49f)/sr);p.a0=K/(K+1);p.a1=p.a0;p.b1=(K-1)/(K+1);p.clear();}
void NativeBmwDspProcessor::makeLowShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)+(A-1)*c+2*r*a);q.b0=A*((A+1)-(A-1)*c+2*r*a)*iv;q.b1=2*A*((A-1)-(A+1)*c)*iv;q.b2=A*((A+1)-(A-1)*c-2*r*a)*iv;q.a1=-2*((A-1)+(A+1)*c)*iv;q.a2=((A+1)+(A-1)*c-2*r*a)*iv;q.clear();}
void NativeBmwDspProcessor::makeHighShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)-(A-1)*c+2*r*a);q.b0=A*((A+1)+(A-1)*c+2*r*a)*iv;q.b1=-2*A*((A-1)+(A+1)*c)*iv;q.b2=A*((A+1)+(A-1)*c-2*r*a)*iv;q.a1=2*((A-1)-(A+1)*c)*iv;q.a2=((A+1)-(A-1)*c-2*r*a)*iv;q.clear();}

bool NativeBmwDspProcessor::makePeq(Biquad&q,double frequency,double gain,double Q,int type,float sr){
 if(!std::isfinite(frequency)||!std::isfinite(gain)||!std::isfinite(Q)||frequency<20.0||frequency>=sr*.5||Q<0.1||Q>30.0||type<0||type>2)return false;
 const double A=std::pow(10.0,gain/40.0),w=2.0*PI*frequency/sr,c=std::cos(w),s=std::sin(w);
 double b0,b1,b2,a0,a1,a2;
 if(type==0){const double alpha=s/(2.0*Q);b0=1+alpha*A;b1=-2*c;b2=1-alpha*A;a0=1+alpha/A;a1=-2*c;a2=1-alpha/A;}
 else {const double alpha=s/(2.0*Q),rootA=std::sqrt(A),two=2.0*rootA*alpha;
  if(type==1){b0=A*((A+1)-(A-1)*c+two);b1=2*A*((A-1)-(A+1)*c);b2=A*((A+1)-(A-1)*c-two);a0=(A+1)+(A-1)*c+two;a1=-2*((A-1)+(A+1)*c);a2=(A+1)+(A-1)*c-two;}
  else{b0=A*((A+1)+(A-1)*c+two);b1=-2*A*((A-1)+(A+1)*c);b2=A*((A+1)+(A-1)*c-two);a0=(A+1)-(A-1)*c+two;a1=2*((A-1)-(A+1)*c);a2=(A+1)-(A-1)*c-two;}}
 if(!std::isfinite(a0)||std::fabs(a0)<1e-15)return false;
 q.b0=static_cast<float>(b0/a0);q.b1=static_cast<float>(b1/a0);q.b2=static_cast<float>(b2/a0);q.a1=static_cast<float>(a1/a0);q.a2=static_cast<float>(a2/a0);q.clear();
 return std::isfinite(q.b0)&&std::isfinite(q.b1)&&std::isfinite(q.b2)&&std::isfinite(q.a1)&&std::isfinite(q.a2);
}

bool NativeBmwDspProcessor::configurePeq(bool enabled,float preampDb,
 const double* full,std::size_t fullCount,const double* low,std::size_t lowCount,const double* mid,std::size_t midCount){
 if(!std::isfinite(preampDb)||preampDb<-30.f||preampDb>12.f)return false;
 auto build=[this](const double* values,std::size_t count,PeqBank& bank)->bool{
  if(count%kPeqBandWidth!=0||count/kPeqBandWidth>kMaxPeqSectionsPerChannel||(count>0&&values==nullptr))return false;
  PeqBank next;
  for(std::size_t i=0;i<count;i+=kPeqBandWidth){
   const double f=values[i],g=values[i+1],q=values[i+2],typeValue=values[i+3],channelValue=values[i+4];
   if(!std::isfinite(typeValue)||!std::isfinite(channelValue)||typeValue!=std::floor(typeValue)||channelValue!=std::floor(channelValue))return false;
   const int type=static_cast<int>(typeValue),channel=static_cast<int>(channelValue);
   if(channel<0||channel>2)return false;
   Biquad section;if(!makePeq(section,f,g,q,type,sampleRate_))return false;
   if(std::fabs(g)<1e-9)continue;
   if(channel!=2){if(next.leftCount>=kMaxPeqSectionsPerChannel)return false;next.left[next.leftCount++]=section;}
   if(channel!=1){if(next.rightCount>=kMaxPeqSectionsPerChannel)return false;next.right[next.rightCount++]=section;}
  }
  bank=next;return true;
 };
 PeqBank nextFull,nextLow,nextMid;
 if(!build(full,fullCount,nextFull)||!build(low,lowCount,nextLow)||!build(mid,midCount,nextMid))return false;
 auto save=[](auto& target,std::size_t& targetCount,const double* source,std::size_t sourceCount){target.fill(0);if(sourceCount>0)std::copy_n(source,sourceCount,target.begin());targetCount=sourceCount;};
 save(fullPeqValues_,fullPeqValueCount_,full,fullCount);save(lowPeqValues_,lowPeqValueCount_,low,lowCount);save(midPeqValues_,midPeqValueCount_,mid,midCount);
 fullPeq_=nextFull;lowPeq_=nextLow;midPeq_=nextMid;peqEnabled_=enabled;peqPreampDb_=preampDb;peqPreamp_=dbToLin(preampDb);return true;
}

void NativeBmwDspProcessor::resetDynamics(){
 compGain_=1;rmsPower_=peakEnv_=0;compressorMeterCounter_=0;
 compressorInputDb_.store(-60.f,std::memory_order_relaxed);
 compressorOutputDb_.store(-60.f,std::memory_order_relaxed);
 compressorGainReductionDb_.store(0.f,std::memory_order_relaxed);
}
void NativeBmwDspProcessor::rebuildGains(){headroom_=dbToLin(p_.headroom);lowGainL_=dbToLin(p_.lowGainL);lowGainR_=dbToLin(p_.lowGainR);midGainL_=dbToLin(p_.midGainL);midGainR_=dbToLin(p_.midGainR);postGainL_=dbToLin(p_.postGainL);postGainR_=dbToLin(p_.postGainR);makeup_=dbToLin(p_.makeup);}
void NativeBmwDspProcessor::rebuildSubsonic(){for(Channel*c:{&left_,&right_})makeHighPass(c->sub1,p_.subFreq,BW,sampleRate_);}
void NativeBmwDspProcessor::rebuildLowCrossover(){for(Channel*c:{&left_,&right_}){makeLowPass(c->lowA,p_.lpf,p_.lowLr4?BW:1.f,sampleRate_);makeLowPass(c->lowB,p_.lpf,BW,sampleRate_);makeOnePoleLow(c->lowPole,p_.lpf,sampleRate_);}}
void NativeBmwDspProcessor::rebuildMidCrossover(){for(Channel*c:{&left_,&right_}){makeHighPass(c->mid1,p_.hpf,BW,sampleRate_);makeHighPass(c->mid2,p_.hpf,BW,sampleRate_);}}
void NativeBmwDspProcessor::updateDelays(){left_.lowDelay.delay=p_.lowDelayL*sampleRate_*.001f;right_.lowDelay.delay=p_.lowDelayR*sampleRate_*.001f;left_.midDelay.delay=p_.midDelayL*sampleRate_*.001f;right_.midDelay.delay=p_.midDelayR*sampleRate_*.001f;}
void NativeBmwDspProcessor::rebuildTilt(){float tg=p_.tiltAmount*.75f;for(Channel*c:{&left_,&right_}){makeLowShelf(c->tiltLo1,p_.tiltFreq,tg,sampleRate_);makeLowShelf(c->tiltLo2,p_.tiltFreq,tg,sampleRate_);makeHighShelf(c->tiltHi1,p_.tiltFreq,-tg,sampleRate_);makeHighShelf(c->tiltHi2,p_.tiltFreq,-tg,sampleRate_);}}
void NativeBmwDspProcessor::rebuildCompressorTiming(){rmsMix_=1-std::exp(-1/(.050f*sampleRate_));peakRelease_=std::exp(-1/(.080f*sampleRate_));attackMix_=1-std::exp(-1/(p_.attack*.001f*sampleRate_));releaseMix_=1-std::exp(-1/(p_.release*.001f*sampleRate_));}
void NativeBmwDspProcessor::applyDirty(uint32_t d){if(d&DirtyGains)rebuildGains();if(d&DirtySubsonic)rebuildSubsonic();if(d&DirtyLowXo)rebuildLowCrossover();if(d&DirtyMidXo)rebuildMidCrossover();if(d&DirtyDelays)updateDelays();if(d&DirtyTilt)rebuildTilt();if(d&DirtyCompTiming)rebuildCompressorTiming();if(d&DirtyCompState)resetDynamics();}
void NativeBmwDspProcessor::rebuildAll(){
 dcR_=std::exp(-2*PI*10/sampleRate_);rebuildGains();rebuildSubsonic();rebuildLowCrossover();rebuildMidCrossover();rebuildTilt();rebuildCompressorTiming();
 for(Channel*c:{&left_,&right_}){c->dcX=c->dcY=0;c->lowDelay.clear();c->midDelay.clear();}
 updateDelays();resetDynamics();
 configurePeq(peqEnabled_,peqPreampDb_,fullPeqValues_.data(),fullPeqValueCount_,lowPeqValues_.data(),lowPeqValueCount_,midPeqValues_.data(),midPeqValueCount_);
}

float NativeBmwDspProcessor::processChannelInput(float x,Channel&c){float y=x-c.dcX+dcR_*c.dcY;c.dcX=x;c.dcY=ftz(y);return c.dcY;}
void NativeBmwDspProcessor::processFrame(float&l,float&r){if(!p_.enabled)return;float sL=processChannelInput(l,left_),sR=processChannelInput(r,right_);if(peqEnabled_){sL=fullPeq_.processLeft(sL*peqPreamp_);sR=fullPeq_.processRight(sR*peqPreamp_);}sL*=headroom_;sR*=headroom_;float lowL=sL,lowR=sR,midL=sL,midR=sR;
 if(p_.subsonic){lowL=left_.sub1.run(lowL);lowR=right_.sub1.run(lowR);}
 if(!p_.lpfPass){lowL=left_.lowA.run(lowL);lowR=right_.lowA.run(lowR);if(p_.lowLr4){lowL=left_.lowB.run(lowL);lowR=right_.lowB.run(lowR);}else{lowL=left_.lowPole.run(lowL);lowR=right_.lowPole.run(lowR);}if(peqEnabled_){lowL=lowPeq_.processLeft(lowL);lowR=lowPeq_.processRight(lowR);}lowL=left_.lowDelay.run(lowL);lowR=right_.lowDelay.run(lowR);
  if(!p_.lowMute&&p_.measurementMute!=1){
   const float pk=std::max(std::fabs(lowL),std::fabs(lowR));
   const bool publishMeter=(++compressorMeterCounter_&255u)==0u;
   float detectorDb=-60.f;
   if(p_.compressor){
    rmsPower_=ftz(rmsPower_+(pk*pk-rmsPower_)*rmsMix_);
    peakEnv_=pk>peakEnv_?pk:ftz(peakEnv_*peakRelease_);
    const float det=std::max(std::sqrt(std::max(0.f,rmsPower_)),peakEnv_*.5f);
    detectorDb=20.f*std::log10(std::max(det,1e-12f));
    const float over=detectorDb-p_.threshold,slope=1-1/std::max(1.001f,p_.ratio),kh=p_.knee*.5f;
    float gr=0;
    if(p_.knee>0){if(over>=kh)gr=-over*slope;else if(over>-kh){const float x=over+kh;gr=-slope*x*x/(2*p_.knee);}}
    else if(over>0)gr=-over*slope;
    const float target=dbToLin(gr),mix=target<compGain_?attackMix_:releaseMix_;
    compGain_=std::min(1.f,ftz(compGain_+(target-compGain_)*mix));
    lowL*=compGain_*makeup_;lowR*=compGain_*makeup_;
   }
   else if(publishMeter)detectorDb=20.f*std::log10(std::max(pk,1e-12f));
   if(publishMeter){
    const float gainReduction=-20.f*std::log10(std::max(compGain_,1e-12f));
    const float appliedMakeup=p_.compressor?p_.makeup:0.f;
    compressorInputDb_.store(clampf(detectorDb,-60.f,6.f),std::memory_order_relaxed);
    compressorOutputDb_.store(clampf(detectorDb-gainReduction+appliedMakeup,-60.f,6.f),std::memory_order_relaxed);
    compressorGainReductionDb_.store(clampf(gainReduction,0.f,60.f),std::memory_order_relaxed);
   }
  }
  else if((++compressorMeterCounter_&255u)==0u){
   compressorInputDb_.store(-60.f,std::memory_order_relaxed);
   compressorOutputDb_.store(-60.f,std::memory_order_relaxed);
   compressorGainReductionDb_.store(0.f,std::memory_order_relaxed);
  }
  lowL*=lowGainL_;lowR*=lowGainR_;}
 else if((++compressorMeterCounter_&255u)==0u){
  compressorInputDb_.store(-60.f,std::memory_order_relaxed);
  compressorOutputDb_.store(-60.f,std::memory_order_relaxed);
  compressorGainReductionDb_.store(0.f,std::memory_order_relaxed);
 }
 if(!p_.hpfPass){midL=left_.mid2.run(left_.mid1.run(midL));midR=right_.mid2.run(right_.mid1.run(midR));if(peqEnabled_){midL=midPeq_.processLeft(midL);midR=midPeq_.processRight(midR);}midL=left_.midDelay.run(midL)*midGainL_;midR=right_.midDelay.run(midR)*midGainR_;}
 if(p_.lowInvert){lowL=-lowL;lowR=-lowR;}if(p_.midInvert){midL=-midL;midR=-midR;}if(p_.lowMute||p_.measurementMute==1)lowL=lowR=0;if(p_.midMute||p_.measurementMute==2)midL=midR=0;
 float oL=(p_.lpfPass&&p_.hpfPass)?sL:lowL+midL,oR=(p_.lpfPass&&p_.hpfPass)?sR:lowR+midR;if(p_.tilt){oL=left_.tiltHi2.run(left_.tiltHi1.run(left_.tiltLo2.run(left_.tiltLo1.run(oL))));oR=right_.tiltHi2.run(right_.tiltHi1.run(right_.tiltLo2.run(right_.tiltLo1.run(oR))));}oL*=postGainL_;oR*=postGainR_;if(p_.channelMute==1)oL=0;if(p_.channelMute==2)oR=0;l=oR;r=oL;}
const float* NativeBmwDspProcessor::process(const float*s,std::size_t n){if(!s)return s;auto*w=const_cast<float*>(s);for(std::size_t i=0;i+1<n;i+=2)processFrame(w[i],w[i+1]);return s;}
const int16_t* NativeBmwDspProcessor::process(const int16_t*s,std::size_t n){if(!s)return s;auto*w=const_cast<int16_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=w[i],r=w[i+1];processFrame(l,r);w[i]=clampInt<int16_t>(l);w[i+1]=clampInt<int16_t>(r);}return s;}
const int32_t* NativeBmwDspProcessor::process(const int32_t*s,std::size_t n){if(!s)return s;auto*w=const_cast<int32_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=w[i],r=w[i+1];processFrame(l,r);w[i]=clampInt<int32_t>(l);w[i+1]=clampInt<int32_t>(r);}return s;}
void NativeBmwDspProcessor::readCompressorMeter(float* values,std::size_t count)const{
 if(values==nullptr||count<3)return;
 values[0]=compressorInputDb_.load(std::memory_order_relaxed);
 values[1]=compressorOutputDb_.load(std::memory_order_relaxed);
 values[2]=compressorGainReductionDb_.load(std::memory_order_relaxed);
}
