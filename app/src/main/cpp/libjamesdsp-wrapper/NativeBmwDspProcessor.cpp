#include "NativeBmwDspProcessor.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace {
constexpr float PI=3.14159265358979323846f;
constexpr float BW=0.7071067812f;
constexpr float BW4Q1=0.5411961f;
constexpr float BW4Q2=1.3065630f;
constexpr double MIN_Q=0.1;
constexpr double MAX_Q=30.0;
constexpr double UNITY_EPSILON_DB=0.0001;

inline float ftz(float x){return(!std::isfinite(x)||std::fabs(x)<1e-20f)?0.f:x;}
template<class T>T clampInt(float x){const float lo=static_cast<float>(std::numeric_limits<T>::min()),hi=static_cast<float>(std::numeric_limits<T>::max());return static_cast<T>(std::lrintf(std::max(lo,std::min(hi,x))));}
inline float clampf(float x,float lo,float hi){return std::max(lo,std::min(hi,x));}
inline bool changed(float a,float b){return std::fabs(a-b)>1e-6f;}
}

float NativeBmwDspProcessor::Biquad::run(float x){float y=b0*x+z1;z1=ftz(b1*x-a1*y+z2);z2=ftz(b2*x-a2*y);return ftz(y);}
void NativeBmwDspProcessor::Biquad::clear(){z1=z2=0;}
float NativeBmwDspProcessor::PeqBank::run(float x){for(std::size_t i=0;i<count;++i)x=sections[i].run(x);return std::isfinite(x)?x:0.f;}
void NativeBmwDspProcessor::PeqBank::clear(){for(std::size_t i=0;i<count;++i)sections[i].clear();count=0;}
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

bool NativeBmwDspProcessor::configureBandPeq(
    const double* lowValues,
    std::size_t lowValueCount,
    const double* midValues,
    std::size_t midValueCount)
{
    const std::size_t maxValues=lowPeqConfig_.size();
    if((lowValueCount%kPeqValuesPerBand)!=0||(midValueCount%kPeqValuesPerBand)!=0||
       lowValueCount>maxValues||midValueCount>maxValues||
       (lowValueCount>0&&lowValues==nullptr)||(midValueCount>0&&midValues==nullptr))return false;

    PeqBank lowLeft{},lowRight{},midLeft{},midRight{};
    if(!buildPeqBanks(lowValues,lowValueCount,lowLeft,lowRight)||
       !buildPeqBanks(midValues,midValueCount,midLeft,midRight))return false;

    if(lowValueCount>0)std::copy_n(lowValues,lowValueCount,lowPeqConfig_.begin());
    if(midValueCount>0)std::copy_n(midValues,midValueCount,midPeqConfig_.begin());
    lowPeqValueCount_=lowValueCount;
    midPeqValueCount_=midValueCount;
    left_.lowPeq=lowLeft;
    right_.lowPeq=lowRight;
    left_.midPeq=midLeft;
    right_.midPeq=midRight;
    return true;
}

void NativeBmwDspProcessor::makeLowPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1-c)*.5f)/d;q.b1=(1-c)/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
void NativeBmwDspProcessor::makeHighPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1+c)*.5f)/d;q.b1=(-(1+c))/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
void NativeBmwDspProcessor::makeOnePoleLow(OnePole&p,float fc,float sr){float K=std::tan(PI*clampf(fc,20,sr*.49f)/sr);p.a0=K/(K+1);p.a1=p.a0;p.b1=(K-1)/(K+1);p.clear();}
void NativeBmwDspProcessor::makeLowShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)+(A-1)*c+2*r*a);q.b0=A*((A+1)-(A-1)*c+2*r*a)*iv;q.b1=2*A*((A-1)-(A+1)*c)*iv;q.b2=A*((A+1)-(A-1)*c-2*r*a)*iv;q.a1=-2*((A-1)+(A+1)*c)*iv;q.a2=((A+1)+(A-1)*c-2*r*a)*iv;q.clear();}
void NativeBmwDspProcessor::makeHighShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*fc/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)-(A-1)*c+2*r*a);q.b0=A*((A+1)+(A-1)*c+2*r*a)*iv;q.b1=-2*A*((A-1)+(A+1)*c)*iv;q.b2=A*((A+1)+(A-1)*c-2*r*a)*iv;q.a1=2*((A-1)-(A+1)*c)*iv;q.a2=((A+1)-(A-1)*c-2*r*a)*iv;q.clear();}

bool NativeBmwDspProcessor::makePeq(Biquad& section,double frequency,double gain,double quality,int type,float sampleRate){
 if(!std::isfinite(frequency)||!std::isfinite(gain)||!std::isfinite(quality)||
    frequency<20.0||frequency>=sampleRate*.5||gain < -30.0||gain > 30.0||
    quality<MIN_Q||quality>MAX_Q||type<0||type>2)return false;
 const double A=std::pow(10.0,gain/40.0),w=2.0*PI*frequency/sampleRate,s=std::sin(w),c=std::cos(w),alpha=s/(2.0*quality);
 double b0,b1,b2,a0,a1,a2;
 if(type==0){
  b0=1.0+alpha*A;b1=-2.0*c;b2=1.0-alpha*A;
  a0=1.0+alpha/A;a1=-2.0*c;a2=1.0-alpha/A;
 }else{
  const double rootA=std::sqrt(A),twoRootAAlpha=2.0*rootA*alpha;
  if(type==1){
   b0=A*((A+1.0)-(A-1.0)*c+twoRootAAlpha);
   b1=2.0*A*((A-1.0)-(A+1.0)*c);
   b2=A*((A+1.0)-(A-1.0)*c-twoRootAAlpha);
   a0=(A+1.0)+(A-1.0)*c+twoRootAAlpha;
   a1=-2.0*((A-1.0)+(A+1.0)*c);
   a2=(A+1.0)+(A-1.0)*c-twoRootAAlpha;
  }else{
   b0=A*((A+1.0)+(A-1.0)*c+twoRootAAlpha);
   b1=-2.0*A*((A-1.0)+(A+1.0)*c);
   b2=A*((A+1.0)+(A-1.0)*c-twoRootAAlpha);
   a0=(A+1.0)-(A-1.0)*c+twoRootAAlpha;
   a1=2.0*((A-1.0)-(A+1.0)*c);
   a2=(A+1.0)-(A-1.0)*c-twoRootAAlpha;
  }
 }
 if(!std::isfinite(a0)||std::fabs(a0)<1e-20)return false;
 const double inv=1.0/a0;
 section.b0=static_cast<float>(b0*inv);section.b1=static_cast<float>(b1*inv);section.b2=static_cast<float>(b2*inv);
 section.a1=static_cast<float>(a1*inv);section.a2=static_cast<float>(a2*inv);section.clear();
 return std::isfinite(section.b0)&&std::isfinite(section.b1)&&std::isfinite(section.b2)&&std::isfinite(section.a1)&&std::isfinite(section.a2);
}

bool NativeBmwDspProcessor::buildPeqBanks(const double* values,std::size_t valueCount,PeqBank& left,PeqBank& right) const{
 if(valueCount==0)return true;
 if(values==nullptr||valueCount%kPeqValuesPerBand!=0)return false;
 for(std::size_t i=0;i<valueCount;i+=kPeqValuesPerBand){
  const double frequency=values[i],gain=values[i+1],quality=values[i+2];
  const int type=static_cast<int>(values[i+3]),channel=static_cast<int>(values[i+4]);
  if(channel<0||channel>2)return false;
  if(std::fabs(gain)<=UNITY_EPSILON_DB)continue;
  Biquad section;
  if(!makePeq(section,frequency,gain,quality,type,sampleRate_))return false;
  if(channel!=2){if(left.count>=kMaxPeqSectionsPerChannel)return false;left.sections[left.count++]=section;}
  if(channel!=1){if(right.count>=kMaxPeqSectionsPerChannel)return false;right.sections[right.count++]=section;}
 }
 return true;
}

void NativeBmwDspProcessor::resetDynamics(){compGain_=1;rmsPower_=peakEnv_=0;}
void NativeBmwDspProcessor::rebuildGains(){headroom_=dbToLin(p_.headroom);lowGainL_=dbToLin(p_.lowGainL);lowGainR_=dbToLin(p_.lowGainR);midGainL_=dbToLin(p_.midGainL);midGainR_=dbToLin(p_.midGainR);postGainL_=dbToLin(p_.postGainL);postGainR_=dbToLin(p_.postGainR);makeup_=dbToLin(p_.makeup);}
void NativeBmwDspProcessor::rebuildSubsonic(){for(Channel*c:{&left_,&right_}){makeHighPass(c->sub1,p_.subFreq,BW4Q1,sampleRate_);makeHighPass(c->sub2,p_.subFreq,BW4Q2,sampleRate_);}}
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
 PeqBank lowLeft{},lowRight{},midLeft{},midRight{};
 if(buildPeqBanks(lowPeqConfig_.data(),lowPeqValueCount_,lowLeft,lowRight)&&buildPeqBanks(midPeqConfig_.data(),midPeqValueCount_,midLeft,midRight)){
  left_.lowPeq=lowLeft;right_.lowPeq=lowRight;left_.midPeq=midLeft;right_.midPeq=midRight;
 }else{
  left_.lowPeq.clear();right_.lowPeq.clear();left_.midPeq.clear();right_.midPeq.clear();
 }
}

float NativeBmwDspProcessor::processChannelInput(float x,Channel&c){float y=x-c.dcX+dcR_*c.dcY;c.dcX=x;c.dcY=ftz(y);return c.dcY*headroom_;}
void NativeBmwDspProcessor::processFrame(float&l,float&r){
 if(!p_.enabled)return;
 float sL=processChannelInput(l,left_),sR=processChannelInput(r,right_),lowL=sL,lowR=sR,midL=sL,midR=sR;
 if(p_.subsonic){lowL=left_.sub2.run(left_.sub1.run(lowL));lowR=right_.sub2.run(right_.sub1.run(lowR));}
 if(!p_.lpfPass){
  lowL=left_.lowA.run(lowL);lowR=right_.lowA.run(lowR);
  if(p_.lowLr4){lowL=left_.lowB.run(lowL);lowR=right_.lowB.run(lowR);}else{lowL=left_.lowPole.run(lowL);lowR=right_.lowPole.run(lowR);}
  lowL=left_.lowPeq.run(lowL);lowR=right_.lowPeq.run(lowR);
  lowL=left_.lowDelay.run(lowL);lowR=right_.lowDelay.run(lowR);
  if(p_.compressor&&!p_.lowMute&&p_.measurementMute!=1){
   float pk=std::max(std::fabs(lowL),std::fabs(lowR));rmsPower_=ftz(rmsPower_+(pk*pk-rmsPower_)*rmsMix_);peakEnv_=pk>peakEnv_?pk:ftz(peakEnv_*peakRelease_);
   float det=std::max(std::sqrt(std::max(0.f,rmsPower_)),peakEnv_*.5f),db=20*std::log10(std::max(det,1e-12f)),over=db-p_.threshold,slope=1-1/std::max(1.001f,p_.ratio),gr=0,kh=p_.knee*.5f;
   if(p_.knee>0){if(over>=kh)gr=-over*slope;else if(over>-kh){float x=over+kh;gr=-slope*x*x/(2*p_.knee);}}else if(over>0)gr=-over*slope;
   float t=dbToLin(gr),mix=t<compGain_?attackMix_:releaseMix_;compGain_=std::min(1.f,ftz(compGain_+(t-compGain_)*mix));lowL*=compGain_*makeup_;lowR*=compGain_*makeup_;
  }
  lowL*=lowGainL_;lowR*=lowGainR_;
 }
 if(!p_.hpfPass){
  midL=left_.mid2.run(left_.mid1.run(midL));midR=right_.mid2.run(right_.mid1.run(midR));
  midL=left_.midPeq.run(midL);midR=right_.midPeq.run(midR);
  midL=left_.midDelay.run(midL)*midGainL_;midR=right_.midDelay.run(midR)*midGainR_;
 }
 if(p_.lowInvert){lowL=-lowL;lowR=-lowR;}if(p_.midInvert){midL=-midL;midR=-midR;}if(p_.lowMute||p_.measurementMute==1)lowL=lowR=0;if(p_.midMute||p_.measurementMute==2)midL=midR=0;
 float oL=(p_.lpfPass&&p_.hpfPass)?sL:lowL+midL,oR=(p_.lpfPass&&p_.hpfPass)?sR:lowR+midR;
 if(p_.tilt){oL=left_.tiltHi2.run(left_.tiltHi1.run(left_.tiltLo2.run(left_.tiltLo1.run(oL))));oR=right_.tiltHi2.run(right_.tiltHi1.run(right_.tiltLo2.run(right_.tiltLo1.run(oR))));}
 oL*=postGainL_;oR*=postGainR_;if(p_.channelMute==1)oL=0;if(p_.channelMute==2)oR=0;
 // Intentional correction remains the final operation before output.
 l=oR;r=oL;
}
const float* NativeBmwDspProcessor::process(const float*s,std::size_t n){if(!s)return s;auto*w=const_cast<float*>(s);for(std::size_t i=0;i+1<n;i+=2)processFrame(w[i],w[i+1]);return s;}
const int16_t* NativeBmwDspProcessor::process(const int16_t*s,std::size_t n){if(!s)return s;auto*w=const_cast<int16_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=w[i],r=w[i+1];processFrame(l,r);w[i]=clampInt<int16_t>(l);w[i+1]=clampInt<int16_t>(r);}return s;}
const int32_t* NativeBmwDspProcessor::process(const int32_t*s,std::size_t n){if(!s)return s;auto*w=const_cast<int32_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=w[i],r=w[i+1];processFrame(l,r);w[i]=clampInt<int32_t>(l);w[i+1]=clampInt<int32_t>(r);}return s;}
