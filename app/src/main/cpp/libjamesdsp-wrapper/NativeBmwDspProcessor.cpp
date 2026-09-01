#include "NativeBmwDspProcessor.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>
// Declarations only -- NOT defining DR_WAV_IMPLEMENTATION here. JdspImpResToolbox.c already
// defines it once and jamesdsp-wrapper links against jdspimprestoolbox (see CMakeLists.txt),
// so the actual drwav_* function bodies are resolved from there at link time.
#include "../libjdspimptoolbox/dr_wav.h"

namespace {
constexpr float PI=3.14159265358979323846f,BW=0.7071067812f;
inline float ftz(float x){return(!std::isfinite(x)||std::fabs(x)<1e-20f)?0.f:x;}
template<class T>T clampInt(float x){const double lo=static_cast<double>(std::numeric_limits<T>::min()),hi=static_cast<double>(std::numeric_limits<T>::max());return static_cast<T>(std::llrint(std::max(lo,std::min(hi,static_cast<double>(x)))));}
inline float clampf(float x,float lo,float hi){return std::max(lo,std::min(hi,x));}
inline bool changed(float a,float b){return std::fabs(a-b)>1e-6f;}
using OutputId = NativeBmwRouting::OutputId;
}

float NativeBmwDspProcessor::Biquad::run(float x){float y=b0*x+z1;z1=ftz(b1*x-a1*y+z2);z2=ftz(b2*x-a2*y);return ftz(y);}
void NativeBmwDspProcessor::Biquad::clear(){z1=z2=0;}
void NativeBmwDspProcessor::Biquad::loadAllPass(const NativeBmwRouting::BiquadCoefficients&c){b0=c.b0;b1=c.b1;b2=c.b2;a1=c.a1;a2=c.a2;clear();}
float NativeBmwDspProcessor::PeqBank::processLeft(float sample){for(std::size_t i=0;i<leftCount;++i)sample=left[i].run(sample);return sample;}
float NativeBmwDspProcessor::PeqBank::processRight(float sample){for(std::size_t i=0;i<rightCount;++i)sample=right[i].run(sample);return sample;}
void NativeBmwDspProcessor::PeqBank::clear(){for(auto& section:left)section.clear();for(auto& section:right)section.clear();}
float NativeBmwDspProcessor::Delay::run(float x){if(delay<=0)return x;data[write]=x;float read=static_cast<float>(write)-delay;while(read<0)read+=data.size();unsigned i0=static_cast<unsigned>(read)%data.size(),i1=(i0+1)%data.size();float f=read-std::floor(read),y=data[i0]+(data[i1]-data[i0])*f;write=(write+1)%data.size();return y;}
void NativeBmwDspProcessor::Delay::clear(){data.fill(0);write=0;}
void NativeBmwDspProcessor::Limiter::clear(){delayL.clear();delayR.clear();gain=1;}
float NativeBmwDspProcessor::dbToLin(float db){return std::pow(10.f,db/20.f);}

NativeBmwDspProcessor::NativeBmwDspProcessor(){
 outputs_[static_cast<std::size_t>(OutputId::LowLeft)].id=OutputId::LowLeft;
 outputs_[static_cast<std::size_t>(OutputId::LowLeft)].isLeftSide=true;
 outputs_[static_cast<std::size_t>(OutputId::LowRight)].id=OutputId::LowRight;
 outputs_[static_cast<std::size_t>(OutputId::LowRight)].isLeftSide=false;
 outputs_[static_cast<std::size_t>(OutputId::MidLeft)].id=OutputId::MidLeft;
 outputs_[static_cast<std::size_t>(OutputId::MidLeft)].isLeftSide=true;
 outputs_[static_cast<std::size_t>(OutputId::MidRight)].id=OutputId::MidRight;
 outputs_[static_cast<std::size_t>(OutputId::MidRight)].isLeftSide=false;
 outputConfigs_[static_cast<std::size_t>(OutputId::LowLeft)]={150.f,true,true,32.f,false,false,{true,-12.f,2.f,8.f,40.f,250.f,1.5f}};
 outputConfigs_[static_cast<std::size_t>(OutputId::LowRight)]=outputConfigs_[static_cast<std::size_t>(OutputId::LowLeft)];
 outputConfigs_[static_cast<std::size_t>(OutputId::MidLeft)]={150.f,true,false,32.f,false,false,{false,-10.f,1.5f,6.f,10.f,180.f,0.f}};
 outputConfigs_[static_cast<std::size_t>(OutputId::MidRight)]=outputConfigs_[static_cast<std::size_t>(OutputId::MidLeft)];
 rebuildAll();
}
NativeBmwDspProcessor::~NativeBmwDspProcessor()=default;
void NativeBmwDspProcessor::setSampleRate(float sr){std::lock_guard<std::mutex> lock(stateMutex_);if(sr>=8000&&std::fabs(sr-sampleRate_)>.5f){sampleRate_=sr;rebuildAll();}}

bool NativeBmwDspProcessor::configure(const float*v,std::size_t n){
 if(!v||n!=kConfigSize)return false;
 std::lock_guard<std::mutex> lock(stateMutex_);
 Params next=p_;
 next.enabled=v[0]>=.5f;next.lpfPass=v[1]>=.5f;next.hpfPass=v[2]>=.5f;next.channelMute=static_cast<int>(clampf(v[3],0,2));next.measurementMute=static_cast<int>(clampf(v[4],0,2));
 next.headroom=clampf(v[5],-12,0);next.lowGainL=clampf(v[6],-6,0);next.lowGainR=clampf(v[7],-6,0);next.midGainL=clampf(v[8],-6,0);next.midGainR=clampf(v[9],-6,0);next.postGainL=clampf(v[10],-6,6);next.postGainR=clampf(v[11],-6,6);
 next.midDelayL=clampf(v[21],0,2.8f);next.midDelayR=clampf(v[22],0,2.8f);next.lowDelayL=clampf(v[23],0,2.8f);next.lowDelayR=clampf(v[24],0,2.8f);
 next.tilt=v[25]>=.5f;next.tiltAmount=clampf(v[26],-6,6);next.tiltFreq=clampf(v[27],200,2000);
 next.monoBass=v[42]>=.5f;next.monoBassFreq=clampf(v[43],40,120);next.monoBassBlend=clampf(v[44],0,100);next.monoBassMakeup=clampf(v[45],-6,6);
 // v[139]: measurement-mute bus brick-wall stopband offset in octaves (see rebuildMeasBus()).
 // v[140] is a Kotlin-only migration marker; v[141..142] were the Pultec bass stage, removed
 // (unused) and left unread -- see NativeBmwDspProcessor.h's kConfigSize comment.
 next.measBusStopbandOctaves=clampf(v[139],0,4);

 // Pre-crossover multiband compressor (v[144..180]) + per-bus limiter (v[182..187]). v[181]
 // is the Kotlin-only migration marker and v[188..192) are reserved -- none are read here.
 next.mbcEnabled=v[144]>=.5f;
 next.mbcMix=clampf(v[145],0,100)*.01f;
 next.mbcXo[0]=clampf(v[146],20,2000);
 next.mbcXo[1]=clampf(v[147],40,8000);
 next.mbcXo[2]=clampf(v[148],80,20000);
 for(int b=0;b<4;++b){
  const std::size_t base=149+b*8;
  auto&mb=next.mbcBand[b];
  mb.enabled=v[base]>=.5f;
  mb.threshold=clampf(v[base+1],-48,0);
  mb.ratio=clampf(v[base+2],1,20);
  mb.knee=clampf(v[base+3],0,24);
  mb.attack=clampf(v[base+4],1,200);
  mb.release=clampf(v[base+5],20,1000);
  mb.makeup=clampf(v[base+6],0,12);
  mb.stereoLink=v[base+7]>=.5f;
 }
 next.busLimLowEnabled=v[182]>=.5f;
 next.busLimLowThreshDb=clampf(v[183],-24,0);
 next.busLimLowReleaseMs=clampf(v[184],20,800);
 next.busLimMidEnabled=v[185]>=.5f;
 next.busLimMidThreshDb=clampf(v[186],-24,0);
 next.busLimMidReleaseMs=clampf(v[187],20,800);

 NativeBmwRouting::RoutingMatrix nextRouting;
 for(std::size_t out=0;out<NativeBmwRouting::kOutputCount;++out){
  const float fromLeft=v[46+out*2],fromRight=v[47+out*2];
  if(!std::isfinite(fromLeft)||!std::isfinite(fromRight)||std::fabs(fromLeft)>2.f||std::fabs(fromRight)>2.f)return false;
  nextRouting.outputs[out]={fromLeft,fromRight};
 }

 auto nextOutputs=outputs_;
 for(std::size_t out=0;out<nextOutputs.size();++out){
  for(std::size_t section=0;section<NativeBmwRouting::kAllPassSectionsPerOutput;++section){
   const std::size_t base=54+(out*NativeBmwRouting::kAllPassSectionsPerOutput+section)*kAllPassValueWidth;
   const float enabledValue=v[base],orderValue=v[base+1],freqValue=v[base+2],qValue=v[base+3];
   if(!std::isfinite(enabledValue)||!std::isfinite(orderValue)||!std::isfinite(freqValue)||!std::isfinite(qValue))return false;
   NativeBmwRouting::AllPassSection candidate;
   candidate.enabled=enabledValue>=.5f;
   candidate.secondOrder=orderValue>=1.5f;
   candidate.frequencyHz=freqValue;
   candidate.q=qValue;
   // Do not reject the whole 139-value config over one out-of-range all-pass
   // section (eg. a frequency at/above this output's current Nyquist limit).
   // rebuild() already degrades an invalid section to an identity
   // pass-through on its own and leaves every other pending change intact.
   candidate.rebuild(sampleRate_);
   const auto& current=outputs_[out].allPass[section];
   const bool sectionChanged=current.enabled!=candidate.enabled||
       current.secondOrder!=candidate.secondOrder||
       changed(current.frequencyHz,candidate.frequencyHz)||
       changed(current.q,candidate.q);
   nextOutputs[out].allPass[section]=candidate;
   // Keep the delay history when an unrelated setting is configured. Clearing an
   // active all-pass section here creates a phase discontinuity even though its
   // coefficients did not change.
   if(sectionChanged)nextOutputs[out].allPassState[section].loadAllPass(candidate.coefficients);
  }
 }

 auto nextOutputConfigs=outputConfigs_;
 auto readComp=[&](CompressorParams&c,std::size_t base){
  c.enabled=v[base]>=.5f;
  c.threshold=clampf(v[base+1],-24,0);
  c.ratio=clampf(v[base+2],1,10);
  c.knee=clampf(v[base+3],0,12);
  c.attack=clampf(v[base+4],1,100);
  c.release=clampf(v[base+5],20,800);
  c.makeup=clampf(v[base+6],0,6);
 };
 for(std::size_t out=0;out<nextOutputConfigs.size();++out){
  const std::size_t base=kOutputConfigBase+out*kOutputConfigWidth;
  auto&cfg=nextOutputConfigs[out];
  cfg.crossoverFreq=clampf(v[base],80,200);
  // The 18dB/oct option was removed -- always LR4 now. v[base+1] (FIELD_CROSSOVER_LR4) is left
  // unread rather than repurposed: shrinking the per-output config width to reclaim it would
  // shift every compressor field after it, forcing a schema/version bump for no benefit.
  cfg.crossoverLr4=true;
  cfg.subsonicEnabled=v[base+2]>=.5f;
  cfg.subsonicFreq=clampf(v[base+3],20,60);
  cfg.muted=v[base+4]>=.5f;
  cfg.polarityInverted=v[base+5]>=.5f;
  readComp(cfg.compressor,base+6);
 }

 uint32_t dirty=DirtyNone;
 if(changed(next.headroom,p_.headroom)||changed(next.lowGainL,p_.lowGainL)||changed(next.lowGainR,p_.lowGainR)||changed(next.midGainL,p_.midGainL)||changed(next.midGainR,p_.midGainR)||changed(next.postGainL,p_.postGainL)||changed(next.postGainR,p_.postGainR))dirty|=DirtyGains;
 if(changed(next.lowDelayL,p_.lowDelayL)||changed(next.lowDelayR,p_.lowDelayR)||changed(next.midDelayL,p_.midDelayL)||changed(next.midDelayR,p_.midDelayR))dirty|=DirtyDelays;
 if(changed(next.tiltAmount,p_.tiltAmount)||changed(next.tiltFreq,p_.tiltFreq))dirty|=DirtyTilt;
 // Split like the per-output compressor makeup above: a coefficient rebuild (which clears every
 // mono-bass biquad's history) only for freq changes and for the enable toggle -- so a stale
 // filter state can't carry over into the next enabled period -- while makeup is just the
 // monoBassMakeupLin_ scalar, applied live in processFrame, so dragging its slider never zips.
 if(next.monoBass!=p_.monoBass||changed(next.monoBassFreq,p_.monoBassFreq))dirty|=DirtyMonoBass;
 if(changed(next.monoBassMakeup,p_.monoBassMakeup))dirty|=DirtyMonoBassGain;

 for(std::size_t out=0;out<nextOutputConfigs.size();++out){
  const auto&old=outputConfigs_[out];const auto&now=nextOutputConfigs[out];
  const bool low=out<=static_cast<std::size_t>(OutputId::LowRight);
  if(changed(old.crossoverFreq,now.crossoverFreq)){dirty|=low?DirtyLowXo:DirtyMidXo;
   // meas-bus corner tracks the *opposite* band's crossover: rebuild it only if the
   // crossover that just moved is the one the active mute mode actually uses.
   if((next.measurementMute==1&&!low)||(next.measurementMute==2&&low))dirty|=DirtyMeasBus;}
  if(low&&(old.subsonicEnabled!=now.subsonicEnabled||changed(old.subsonicFreq,now.subsonicFreq)))dirty|=DirtySubsonic;
  if(old.muted!=now.muted||old.polarityInverted!=now.polarityInverted)dirty|=DirtyPolarity;
  if(changed(old.compressor.attack,now.compressor.attack)||changed(old.compressor.release,now.compressor.release))dirty|=DirtyCompTiming;
  if(old.compressor.enabled!=now.compressor.enabled)dirty|=DirtyCompState;
  if(changed(old.compressor.makeup,now.compressor.makeup))dirty|=DirtyGains;
 }
 if(next.measurementMute!=p_.measurementMute)dirty|=DirtyPolarity|DirtyMeasBus;
 if(changed(next.measBusStopbandOctaves,p_.measBusStopbandOctaves))dirty|=DirtyMeasBus;

 // MBC: split-freq changes -> DirtyMbc (rebuild filter coeffs, clears tree state, same cost as
 // the band crossovers). mix/makeup/attack/release -> DirtyMbcTiming (scalars only, no click).
 // threshold/ratio/knee are read live in mbcBandGain() and need no rebuild. enable or
 // stereo-link flips -> DirtyMbcState (reset cells + tree so a re-enabled band starts clean
 // and stale unlinked-[1] state can't leak in).
 if(changed(next.mbcMix,p_.mbcMix))dirty|=DirtyMbcTiming;
 for(int i=0;i<3;++i)if(changed(next.mbcXo[i],p_.mbcXo[i]))dirty|=DirtyMbc;
 if(next.mbcEnabled!=p_.mbcEnabled)dirty|=DirtyMbcState;
 for(int b=0;b<4;++b){
  const auto&cur=p_.mbcBand[b];const auto&nxt=next.mbcBand[b];
  if(changed(cur.attack,nxt.attack)||changed(cur.release,nxt.release)||changed(cur.makeup,nxt.makeup))dirty|=DirtyMbcTiming;
  if(cur.enabled!=nxt.enabled||cur.stereoLink!=nxt.stereoLink)dirty|=DirtyMbcState;
 }
 // Bus limiter: threshold is read live per sample, so only enable/release need a rebuild.
 if(next.busLimLowEnabled!=p_.busLimLowEnabled||next.busLimMidEnabled!=p_.busLimMidEnabled||
    changed(next.busLimLowReleaseMs,p_.busLimLowReleaseMs)||changed(next.busLimMidReleaseMs,p_.busLimMidReleaseMs))dirty|=DirtyBusLimiter;

 p_=next;routing_=nextRouting;outputs_=nextOutputs;outputConfigs_=nextOutputConfigs;applyDirty(dirty);return true;
}

void NativeBmwDspProcessor::makeLowPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1-c)*.5f)/d;q.b1=(1-c)/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
void NativeBmwDspProcessor::makeHighPass(Biquad&q,float fc,float Q,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),d=1+a;q.b0=((1+c)*.5f)/d;q.b1=(-(1+c))/d;q.b2=q.b0;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
// fc clamp matches makeLowPass/makeHighPass/makeAllPass2: every coefficient builder in this file
// keeps its corner inside [20 Hz, 0.49*sr] so w stays well below pi and sin(w) stays positive --
// the shelf denominator (A+1)+(A-1)c+2*r*a then can't collapse toward zero. Callers already clamp
// (tilt freq is [200,2000]); this is belt-and-braces so a future caller can't feed it a
// near-Nyquist corner and get NaN coefficients with no identity fallback.
void NativeBmwDspProcessor::makeLowShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*clampf(fc,20.f,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)+(A-1)*c+2*r*a);q.b0=A*((A+1)-(A-1)*c+2*r*a)*iv;q.b1=2*A*((A-1)-(A+1)*c)*iv;q.b2=A*((A+1)-(A-1)*c-2*r*a)*iv;q.a1=-2*((A-1)+(A+1)*c)*iv;q.a2=((A+1)+(A-1)*c-2*r*a)*iv;q.clear();}
void NativeBmwDspProcessor::makeHighShelf(Biquad&q,float fc,float g,float sr){float A=std::pow(10.f,g/40.f),w=2*PI*clampf(fc,20.f,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),r=std::sqrt(A),iv=1/((A+1)-(A-1)*c+2*r*a);q.b0=A*((A+1)+(A-1)*c+2*r*a)*iv;q.b1=-2*A*((A-1)+(A+1)*c)*iv;q.b2=A*((A+1)+(A-1)*c-2*r*a)*iv;q.a1=2*((A-1)-(A+1)*c)*iv;q.a2=((A+1)-(A-1)*c-2*r*a)*iv;q.clear();}
// RBJ 2nd-order all-pass, Q = 1/sqrt(2). Numerator is the reversed denominator -- unity
// magnitude everywhere, -360 deg phase sweep through fc. Matches
// NativeBmwRouting::AllPassSection::rebuild's second-order branch; used only by the MBC tree.
void NativeBmwDspProcessor::makeAllPass2(Biquad&q,float fc,float sr){float w=2*PI*clampf(fc,20,sr*.49f)/sr,c=std::cos(w),s=std::sin(w),a=s/(2*BW),d=1+a;q.b0=(1-a)/d;q.b1=(-2*c)/d;q.b2=1.f;q.a1=(-2*c)/d;q.a2=(1-a)/d;q.clear();}
bool NativeBmwDspProcessor::makePeq(Biquad&q,double f,double g,double Q,int type,float sr){if(!std::isfinite(f)||!std::isfinite(g)||!std::isfinite(Q)||f<20||f>=sr*.5||Q<.1||Q>30||type<0||type>3)return false;double A=std::pow(10.,g/40.),w=2.*PI*f/sr,c=std::cos(w),s=std::sin(w),a=s/(2*Q),b0,b1,b2,a0,a1,a2;if(type==0){b0=1+a*A;b1=-2*c;b2=1-a*A;a0=1+a/A;a1=-2*c;a2=1-a/A;}else if(type==3){b0=1;b1=-2*c;b2=1;a0=1+a;a1=-2*c;a2=1-a;}else{double r=std::sqrt(A),t=2*r*a;if(type==1){b0=A*((A+1)-(A-1)*c+t);b1=2*A*((A-1)-(A+1)*c);b2=A*((A+1)-(A-1)*c-t);a0=(A+1)+(A-1)*c+t;a1=-2*((A-1)+(A+1)*c);a2=(A+1)+(A-1)*c-t;}else{b0=A*((A+1)+(A-1)*c+t);b1=-2*A*((A-1)+(A+1)*c);b2=A*((A+1)+(A-1)*c-t);a0=(A+1)-(A-1)*c+t;a1=2*((A-1)-(A+1)*c);a2=(A+1)-(A-1)*c-t;}}if(!std::isfinite(a0)||std::fabs(a0)<1e-15)return false;q.b0=b0/a0;q.b1=b1/a0;q.b2=b2/a0;q.a1=a1/a0;q.a2=a2/a0;q.clear();return true;}
bool NativeBmwDspProcessor::configurePeq(bool enabled,float preampDb,const double*full,std::size_t fullCount,const double*low,std::size_t lowCount,const double*mid,std::size_t midCount){if(!std::isfinite(preampDb)||preampDb<-30||preampDb>12)return false;std::lock_guard<std::mutex> lock(stateMutex_);auto build=[this](const double*v,std::size_t n,PeqBank&b){if(n%5||n/5>16||(n&&v==nullptr))return false;PeqBank x;for(std::size_t i=0;i<n;i+=5){int type=static_cast<int>(v[i+3]);Biquad q;if(!makePeq(q,v[i],v[i+1],v[i+2],type,sampleRate_))return false;int ch=static_cast<int>(v[i+4]);if(ch<0||ch>2)return false;if(type!=3&&std::fabs(v[i+1])<1e-9)continue;if(ch!=2)x.left[x.leftCount++]=q;if(ch!=1)x.right[x.rightCount++]=q;}b=x;return true;};PeqBank f,l,m;if(!build(full,fullCount,f)||!build(low,lowCount,l)||!build(mid,midCount,m))return false;auto save=[](auto&target,std::size_t&count,const double*source,std::size_t sourceCount){target.fill(0);if(sourceCount>0)std::copy_n(source,sourceCount,target.begin());count=sourceCount;};save(inputPeqValues_,inputPeqValueCount_,full,fullCount);save(lowPeqValues_,lowPeqValueCount_,low,lowCount);save(midPeqValues_,midPeqValueCount_,mid,midCount);inputPeq_=f;lowPeq_=l;midPeq_=m;peqEnabled_=enabled;peqPreampDb_=preampDb;peqPreamp_=dbToLin(preampDb);return true;}

void NativeBmwDspProcessor::resetDynamics(){for(auto&s:outputDynamics_){s.gain=1;s.rmsPower=s.peakEnv=0;s.meterCounter=0;s.inputDb.store(-60);s.outputDb.store(-60);s.gainReductionDb.store(0);}}
void NativeBmwDspProcessor::rebuildGains(){
 headroom_=dbToLin(p_.headroom);postGainL_=dbToLin(p_.postGainL);postGainR_=dbToLin(p_.postGainR);
 output(OutputId::LowLeft).gain=dbToLin(p_.lowGainL);output(OutputId::LowRight).gain=dbToLin(p_.lowGainR);
 output(OutputId::MidLeft).gain=dbToLin(p_.midGainL);output(OutputId::MidRight).gain=dbToLin(p_.midGainR);
 for(std::size_t i=0;i<outputDynamics_.size();++i)outputDynamics_[i].makeupLin=dbToLin(outputConfigs_[i].compressor.makeup);
}
void NativeBmwDspProcessor::rebuildSubsonic(){for(OutputId id:{OutputId::LowLeft,OutputId::LowRight}){auto&out=output(id);const auto&cfg=outputConfig(id);makeHighPass(out.subsonic1,cfg.subsonicFreq,BW,sampleRate_);}}
void NativeBmwDspProcessor::rebuildLowCrossover(){for(OutputId id:{OutputId::LowLeft,OutputId::LowRight}){auto&out=output(id);const auto&cfg=outputConfig(id);makeLowPass(out.crossover1,cfg.crossoverFreq,BW,sampleRate_);makeLowPass(out.crossover2,cfg.crossoverFreq,BW,sampleRate_);}}
void NativeBmwDspProcessor::rebuildMidCrossover(){for(OutputId id:{OutputId::MidLeft,OutputId::MidRight}){auto&out=output(id);const auto&cfg=outputConfig(id);makeHighPass(out.crossover1,cfg.crossoverFreq,BW,sampleRate_);makeHighPass(out.crossover2,cfg.crossoverFreq,BW,sampleRate_);}}
void NativeBmwDspProcessor::updateDelays(){auto d=[this](float ms){return clampf(ms*sampleRate_*.001f,0,kDelayLineCapacity-1.f);};output(OutputId::LowLeft).delay.delay=d(p_.lowDelayL);output(OutputId::LowRight).delay.delay=d(p_.lowDelayR);output(OutputId::MidLeft).delay.delay=d(p_.midDelayL);output(OutputId::MidRight).delay.delay=d(p_.midDelayR);}
void NativeBmwDspProcessor::rebuildLimiter(){float lookahead=clampf(kLimiterLookaheadMs*sampleRate_*.001f,0.f,static_cast<float>(kDelayLineCapacity-1));limiter_.delayL.delay=lookahead;limiter_.delayR.delay=lookahead;float attackSeconds=(kLimiterLookaheadMs*.001f)/5.f;limiter_.attackMix=1-std::exp(-1/(attackSeconds*sampleRate_));float releaseSeconds=.080f;limiter_.releaseMix=1-std::exp(-1/(releaseSeconds*sampleRate_));}
void NativeBmwDspProcessor::rebuildMbc(){
 // LR4 tree crossovers + the all-pass compensators for the lower bands. Splits are forced
 // monotonic with a little headroom so a mis-ordered config can't collapse a band to nothing.
 const float f0=clampf(p_.mbcXo[0],20.f,sampleRate_*.45f);
 const float f1=clampf(p_.mbcXo[1],f0*1.05f,sampleRate_*.45f);
 const float f2=clampf(p_.mbcXo[2],f1*1.05f,sampleRate_*.45f);
 for(auto&t:mbc_){
  makeLowPass(t.lp0a,f0,BW,sampleRate_);makeLowPass(t.lp0b,f0,BW,sampleRate_);
  makeHighPass(t.hp0a,f0,BW,sampleRate_);makeHighPass(t.hp0b,f0,BW,sampleRate_);
  makeLowPass(t.lp1a,f1,BW,sampleRate_);makeLowPass(t.lp1b,f1,BW,sampleRate_);
  makeHighPass(t.hp1a,f1,BW,sampleRate_);makeHighPass(t.hp1b,f1,BW,sampleRate_);
  makeLowPass(t.lp2a,f2,BW,sampleRate_);makeLowPass(t.lp2b,f2,BW,sampleRate_);
  makeHighPass(t.hp2a,f2,BW,sampleRate_);makeHighPass(t.hp2b,f2,BW,sampleRate_);
  // band 0 (below f0) picks up the phase of the f1 and f2 splits; band 1 (f0..f1) that of f2.
  makeAllPass2(t.apB0X1,f1,sampleRate_);
  makeAllPass2(t.apB0X2,f2,sampleRate_);
  makeAllPass2(t.apB1X2,f2,sampleRate_);
 }
 rebuildMbcTiming();
}
void NativeBmwDspProcessor::rebuildMbcTiming(){
 // threshold/ratio/knee are read live in mbcBandGain() -- only these smoothing scalars are
 // precomputed, and none of it touches the crossover filter state.
 mbcMix_=clampf(p_.mbcMix,0.f,1.f);
 for(int b=0;b<4;++b){
  mbcMakeupLin_[b]=dbToLin(p_.mbcBand[b].makeup);
  mbcAttackMix_[b]=1-std::exp(-1/(std::max(1.f,p_.mbcBand[b].attack)*.001f*sampleRate_));
  mbcReleaseMix_[b]=1-std::exp(-1/(std::max(1.f,p_.mbcBand[b].release)*.001f*sampleRate_));
 }
}
void NativeBmwDspProcessor::resetMbcState(){for(auto&t:mbc_)t.clear();for(auto&row:mbcCell_)for(auto&c:row){c.rms=0;c.peak=0;c.gain=1;c.lastDetectorDb=-60.f;}for(auto&m:mbcMeter_){m.inputDb.store(-60.f);m.outputDb.store(-60.f);m.gainReductionDb.store(0.f);}mbcMeterCounter_=0;}
void NativeBmwDspProcessor::rebuildBusLimiter(){
 busLimAttackMix_=1-std::exp(-1/(.001f*sampleRate_));
 busLimLowGrDb_.store(0.f);busLimMidGrDb_.store(0.f);
 busLimLowReleaseMix_=1-std::exp(-1/(std::max(20.f,p_.busLimLowReleaseMs)*.001f*sampleRate_));
 busLimMidReleaseMix_=1-std::exp(-1/(std::max(20.f,p_.busLimMidReleaseMs)*.001f*sampleRate_));
}
void NativeBmwDspProcessor::rebuildTilt(){float g=p_.tiltAmount*.75f;makeLowShelf(tiltLoL1_,p_.tiltFreq,g,sampleRate_);makeLowShelf(tiltLoL2_,p_.tiltFreq,g,sampleRate_);makeHighShelf(tiltHiL1_,p_.tiltFreq,-g,sampleRate_);makeHighShelf(tiltHiL2_,p_.tiltFreq,-g,sampleRate_);makeLowShelf(tiltLoR1_,p_.tiltFreq,g,sampleRate_);makeLowShelf(tiltLoR2_,p_.tiltFreq,g,sampleRate_);makeHighShelf(tiltHiR1_,p_.tiltFreq,-g,sampleRate_);makeHighShelf(tiltHiR2_,p_.tiltFreq,-g,sampleRate_);}
void NativeBmwDspProcessor::rebuildMonoBass(){makeLowPass(monoBassLpf1_,p_.monoBassFreq,BW,sampleRate_);makeLowPass(monoBassLpf2_,p_.monoBassFreq,BW,sampleRate_);makeLowPass(monoBassMidLp1_,p_.monoBassFreq,BW,sampleRate_);makeLowPass(monoBassMidLp2_,p_.monoBassFreq,BW,sampleRate_);for(OutputId id:{OutputId::LowLeft,OutputId::LowRight}){auto&out=output(id);makeHighPass(out.monoBassHpf1,p_.monoBassFreq,BW,sampleRate_);makeHighPass(out.monoBassHpf2,p_.monoBassFreq,BW,sampleRate_);}for(OutputId id:{OutputId::MidLeft,OutputId::MidRight}){auto&out=output(id);makeHighPass(out.monoBassCompHp1,p_.monoBassFreq,BW,sampleRate_);makeHighPass(out.monoBassCompHp2,p_.monoBassFreq,BW,sampleRate_);}rebuildMonoBassGain();}
void NativeBmwDspProcessor::rebuildMonoBassGain(){monoBassMakeupLin_=dbToLin(p_.monoBassMakeup);}
void NativeBmwDspProcessor::rebuildPolarityAndMute(){for(std::size_t i=0;i<outputs_.size();++i){auto&out=outputs_[i];const auto&cfg=outputConfigs_[i];const bool isLow=i<=static_cast<std::size_t>(OutputId::LowRight);const bool measurementMuted=(p_.measurementMute==1&&isLow)||(p_.measurementMute==2&&!isLow);out.muted=cfg.muted||measurementMuted;out.polarityInverted=cfg.polarityInverted;}}
void NativeBmwDspProcessor::rebuildMeasBus(){
 // Option A: measurement-mute output-bus brick-wall. Only ever inserted into processFrame's
 // signal path while p_.measurementMute != 0; all coefficient work happens here, on a
 // measurementMute (or relevant crossover) transition via DirtyMeasBus -- never per sample.
 // mute-low (==1) -> HPF the bus below the MID crossover (removes the sub-crossover skirt the
 //                   external low/mid split would otherwise route to the woofer)
 // mute-mid (==2) -> LPF the bus above the LOW crossover
 // LR8 per side: 4x cascaded Butterworth Q=1/sqrt(2), 48 dB/oct.
 //
 // The corner is not placed on the crossover itself: sitting an LR8 exactly on the opposite
 // band's crossover also chews ~12 dB out of the band that is still playing, right where its
 // own transition lives, so an isolated measurement rolls off well short of the real acoustic
 // crossover. measBusStopbandOctaves walks the corner that many octaves *into the stopband*
 // (down for the HPF, up for the LPF) so the surviving band keeps its own transition region
 // intact while the brick-wall still kills the deep residual skirt. 0 = original on-crossover
 // behaviour.
 measBusActive_=p_.measurementMute!=0;
 measBusIsHighpass_=p_.measurementMute==1;
 for(auto&b:measBusL_)b.clear();
 for(auto&b:measBusR_)b.clear();
 if(!measBusActive_)return;
 const float shift=std::exp2(measBusIsHighpass_?-p_.measBusStopbandOctaves:p_.measBusStopbandOctaves);
 const float nyquistGuard=sampleRate_*0.45f;
 const float fcL=clampf((measBusIsHighpass_?outputConfig(OutputId::MidLeft).crossoverFreq
                                            :outputConfig(OutputId::LowLeft).crossoverFreq)*shift,10.f,nyquistGuard);
 const float fcR=clampf((measBusIsHighpass_?outputConfig(OutputId::MidRight).crossoverFreq
                                            :outputConfig(OutputId::LowRight).crossoverFreq)*shift,10.f,nyquistGuard);
 for(std::size_t i=0;i<kMeasBusSections;++i){
  if(measBusIsHighpass_){makeHighPass(measBusL_[i],fcL,BW,sampleRate_);makeHighPass(measBusR_[i],fcR,BW,sampleRate_);}
  else{makeLowPass(measBusL_[i],fcL,BW,sampleRate_);makeLowPass(measBusR_[i],fcR,BW,sampleRate_);}
 }
}
void NativeBmwDspProcessor::rebuildAllPass(){for(auto&out:outputs_)for(std::size_t i=0;i<out.allPass.size();++i){out.allPass[i].rebuild(sampleRate_);out.allPassState[i].loadAllPass(out.allPass[i].coefficients);}}
void NativeBmwDspProcessor::rebuildCompressorTiming(){rmsMix_=1-std::exp(-1/(.050f*sampleRate_));peakRelease_=std::exp(-1/(.080f*sampleRate_));for(std::size_t i=0;i<outputDynamics_.size();++i){const auto&p=outputConfigs_[i].compressor;auto&s=outputDynamics_[i];s.attackMix=1-std::exp(-1/(p.attack*.001f*sampleRate_));s.releaseMix=1-std::exp(-1/(p.release*.001f*sampleRate_));}}
void NativeBmwDspProcessor::applyDirty(uint32_t d){if(d&DirtyGains)rebuildGains();if(d&DirtySubsonic)rebuildSubsonic();if(d&DirtyLowXo)rebuildLowCrossover();if(d&DirtyMidXo)rebuildMidCrossover();if(d&DirtyDelays)updateDelays();if(d&DirtyTilt)rebuildTilt();if(d&DirtyCompTiming)rebuildCompressorTiming();if(d&DirtyCompState)resetDynamics();if(d&DirtyMonoBass)rebuildMonoBass();if(d&DirtyMonoBassGain)rebuildMonoBassGain();if(d&DirtyPolarity)rebuildPolarityAndMute();if(d&DirtyMeasBus)rebuildMeasBus();if(d&DirtyMbc)rebuildMbc();if(d&DirtyMbcTiming)rebuildMbcTiming();if(d&DirtyMbcState)resetMbcState();if(d&DirtyBusLimiter)rebuildBusLimiter();}
void NativeBmwDspProcessor::rebuildAll(){dcR_=std::exp(-2*PI*10/sampleRate_);rebuildGains();rebuildSubsonic();rebuildLowCrossover();rebuildMidCrossover();rebuildTilt();rebuildCompressorTiming();rebuildLimiter();rebuildMbc();rebuildBusLimiter();rebuildMonoBass();rebuildPolarityAndMute();rebuildMeasBus();rebuildAllPass();leftDcX_=leftDcY_=rightDcX_=rightDcY_=0;for(auto&out:outputs_)out.clearState();limiter_.clear();resetMbcState();busLimLowGain_=busLimMidGain_=1.f;busLimLowGrDb_.store(0.f);busLimMidGrDb_.store(0.f);updateDelays();resetDynamics();configurePeq(peqEnabled_,peqPreampDb_,inputPeqValues_.data(),inputPeqValueCount_,lowPeqValues_.data(),lowPeqValueCount_,midPeqValues_.data(),midPeqValueCount_);}
float NativeBmwDspProcessor::processChannelInput(float x,float&dcX,float&dcY){float y=x-dcX+dcR_*dcY;dcX=x;dcY=ftz(y);return dcY;}
void NativeBmwDspProcessor::publishIdleMeter(CompressorState&s){if((++s.meterCounter&255u)==0){s.inputDb.store(-60);s.outputDb.store(-60);s.gainReductionDb.store(0);}}
void NativeBmwDspProcessor::processCompressor(float&sample,const CompressorParams&p,CompressorState&s){float pk=std::fabs(sample);bool pub=(++s.meterCounter&255u)==0;float db=20*std::log10(std::max(pk,1e-12f));if(p.enabled){s.rmsPower=ftz(s.rmsPower+(pk*pk-s.rmsPower)*rmsMix_);s.peakEnv=pk>s.peakEnv?pk:ftz(s.peakEnv*peakRelease_);float det=std::max(std::sqrt(std::max(0.f,s.rmsPower)),s.peakEnv*.5f);db=20*std::log10(std::max(det,1e-12f));float over=db-p.threshold,slope=1-1/std::max(1.001f,p.ratio),gr=0,kh=p.knee*.5f;if(p.knee>0){if(over>=kh)gr=-over*slope;else if(over>-kh){float x=over+kh;gr=-slope*x*x/(2*p.knee);}}else if(over>0)gr=-over*slope;float target=dbToLin(gr),mix=target<s.gain?s.attackMix:s.releaseMix;s.gain=std::min(1.f,ftz(s.gain+(target-s.gain)*mix));sample*=s.gain*s.makeupLin;}else s.gain=1;if(pub){float red=-20*std::log10(std::max(s.gain,1e-12f));s.inputDb.store(clampf(db,-60,6));s.outputDb.store(clampf(db-red+(p.enabled?p.makeup:0),-60,6));s.gainReductionDb.store(clampf(red,0,60));}}
void NativeBmwDspProcessor::processLimiter(float&l,float&r){float pk=std::max(std::fabs(l),std::fabs(r));float target=pk>kLimiterCeilingLin?kLimiterCeilingLin/pk:1.f;float mix=target<limiter_.gain?limiter_.attackMix:limiter_.releaseMix;limiter_.gain=std::min(1.f,ftz(limiter_.gain+(target-limiter_.gain)*mix));float dl=limiter_.delayL.run(l),dr=limiter_.delayR.run(r);l=ftz(dl*limiter_.gain);r=ftz(dr*limiter_.gain);}

// RMS+peak detector and soft-knee gain computer -- deliberately identical math to
// processCompressor's per-output path (rmsMix_/peakRelease_ are shared), just factored out so
// a stereo-linked band can feed it one combined level. Returns the smoothed linear gain (<=1).
float NativeBmwDspProcessor::mbcBandGain(float peakAbs,const MbcBandParams&p,MbcCell&s,int band){
 s.rms=ftz(s.rms+(peakAbs*peakAbs-s.rms)*rmsMix_);
 s.peak=peakAbs>s.peak?peakAbs:ftz(s.peak*peakRelease_);
 float det=std::max(std::sqrt(std::max(0.f,s.rms)),s.peak*.5f);
 float db=20*std::log10(std::max(det,1e-12f));
 s.lastDetectorDb=db;
 float over=db-p.threshold,slope=1-1/std::max(1.001f,p.ratio),gr=0,kh=p.knee*.5f;
 if(p.knee>0){if(over>=kh)gr=-over*slope;else if(over>-kh){float x=over+kh;gr=-slope*x*x/(2*p.knee);}}
 else if(over>0)gr=-over*slope;
 float target=dbToLin(gr),mix=target<s.gain?mbcAttackMix_[band]:mbcReleaseMix_[band];
 s.gain=std::min(1.f,ftz(s.gain+(target-s.gain)*mix));
 return s.gain;
}
void NativeBmwDspProcessor::processMbc(float&l,float&r){
 if(!p_.mbcEnabled)return;
 const float dryL=l,dryR=r;
 float band[2][4];
 for(int ch=0;ch<2;++ch){
  auto&t=mbc_[ch];
  const float x=ch==0?l:r;
  const float low0=t.lp0b.run(t.lp0a.run(x));
  const float rest0=t.hp0b.run(t.hp0a.run(x));
  const float low1=t.lp1b.run(t.lp1a.run(rest0));
  const float rest1=t.hp1b.run(t.hp1a.run(rest0));
  const float low2=t.lp2b.run(t.lp2a.run(rest1));
  const float high2=t.hp2b.run(t.hp2a.run(rest1));
  band[ch][0]=t.apB0X2.run(t.apB0X1.run(low0));
  band[ch][1]=t.apB1X2.run(low1);
  band[ch][2]=low2;
  band[ch][3]=high2;
 }
 const bool publishMeter=(++mbcMeterCounter_&255u)==0;
 float wetL=0,wetR=0;
 for(int b=0;b<4;++b){
  const auto&bp=p_.mbcBand[b];
  float sL=band[0][b],sR=band[1][b];
  float meterDb,reductionDb;
  if(bp.enabled){
   float gWorst;
   if(bp.stereoLink){
    const float g=mbcBandGain(std::max(std::fabs(sL),std::fabs(sR)),bp,mbcCell_[0][b],b);
    sL*=g;sR*=g;
    meterDb=mbcCell_[0][b].lastDetectorDb;gWorst=g;
   }else{
    const float gL=mbcBandGain(std::fabs(sL),bp,mbcCell_[0][b],b);
    const float gR=mbcBandGain(std::fabs(sR),bp,mbcCell_[1][b],b);
    sL*=gL;sR*=gR;
    meterDb=std::max(mbcCell_[0][b].lastDetectorDb,mbcCell_[1][b].lastDetectorDb);gWorst=std::min(gL,gR);
   }
   sL*=mbcMakeupLin_[b];sR*=mbcMakeupLin_[b];
   reductionDb=-20.f*std::log10(std::max(gWorst,1e-12f));
  }else{
   // Disabled band: meter still shows the band's input level (no reduction), same spirit as
   // processCompressor's idle publish.
   meterDb=20.f*std::log10(std::max(std::max(std::fabs(sL),std::fabs(sR)),1e-12f));
   reductionDb=0.f;
  }
  if(publishMeter){
   auto&m=mbcMeter_[b];
   m.inputDb.store(clampf(meterDb,-60.f,6.f));
   m.gainReductionDb.store(clampf(reductionDb,0.f,60.f));
   m.outputDb.store(clampf(meterDb-reductionDb+(bp.enabled?bp.makeup:0.f),-60.f,6.f));
  }
  wetL+=sL;wetR+=sR;
 }
 l=ftz(dryL*(1.f-mbcMix_)+wetL*mbcMix_);
 r=ftz(dryR*(1.f-mbcMix_)+wetR*mbcMix_);
}
void NativeBmwDspProcessor::processBusLimiter(float&l,float&r,float thresholdDb,float&gain,float releaseMix,std::atomic<float>&grMeterDb){
 const float ceilingLin=dbToLin(thresholdDb);
 const float pk=std::max(std::fabs(l),std::fabs(r));
 const float target=pk>ceilingLin?ceilingLin/pk:1.f;
 const float mix=target<gain?busLimAttackMix_:releaseMix;
 gain=std::min(1.f,ftz(gain+(target-gain)*mix));
 grMeterDb.store(-20.f*std::log10(std::max(gain,1e-12f)),std::memory_order_relaxed);
 l=ftz(l*gain);r=ftz(r*gain);
}
float NativeBmwDspProcessor::processLowCrossover(OutputRuntime&out,const OutputConfig&cfg,float sample){if(cfg.subsonicEnabled)sample=out.subsonic1.run(sample);sample=out.crossover1.run(sample);sample=out.crossover2.run(sample);return sample;}
float NativeBmwDspProcessor::processMidCrossover(OutputRuntime&out,float sample){sample=out.crossover1.run(sample);sample=out.crossover2.run(sample);return sample;}

void NativeBmwDspProcessor::processFrame(float&l,float&r){
 if(!p_.enabled)return;
 float sL=processChannelInput(l,leftDcX_,leftDcY_),sR=processChannelInput(r,rightDcX_,rightDcY_);
 if(peqEnabled_){sL=inputPeq_.processLeft(sL*peqPreamp_);sR=inputPeq_.processRight(sR*peqPreamp_);}
 sL*=headroom_;sR*=headroom_;
 // Pre-crossover multiband compressor: full-range stereo, before any band split. No-op branch
 // while disabled (how it ships).
 processMbc(sL,sR);
 const auto routed=routing_.process({sL,sR});
 float lowL=routed[static_cast<std::size_t>(OutputId::LowLeft)],lowR=routed[static_cast<std::size_t>(OutputId::LowRight)];
 float midL=routed[static_cast<std::size_t>(OutputId::MidLeft)],midR=routed[static_cast<std::size_t>(OutputId::MidRight)];
 auto&lowLeft=output(OutputId::LowLeft);auto&lowRight=output(OutputId::LowRight);auto&midLeft=output(OutputId::MidLeft);auto&midRight=output(OutputId::MidRight);
 const auto&lowLeftCfg=outputConfig(OutputId::LowLeft);const auto&lowRightCfg=outputConfig(OutputId::LowRight);const auto&midLeftCfg=outputConfig(OutputId::MidLeft);const auto&midRightCfg=outputConfig(OutputId::MidRight);

 if(!p_.lpfPass){
  lowL=processLowCrossover(lowLeft,lowLeftCfg,lowL);lowR=processLowCrossover(lowRight,lowRightCfg,lowR);
  if(p_.monoBass){float monoSum=(lowL+lowR)*.5f;float monoFiltered=monoBassLpf2_.run(monoBassLpf1_.run(monoSum))*monoBassMakeupLin_;float stL=lowLeft.monoBassHpf2.run(lowLeft.monoBassHpf1.run(lowL)),stR=lowRight.monoBassHpf2.run(lowRight.monoBassHpf1.run(lowR));float blend=p_.monoBassBlend*.01f;lowL=lowL*(1-blend)+(monoFiltered+stL)*blend;lowR=lowR*(1-blend)+(monoFiltered+stR)*blend;}
  if(peqEnabled_){lowL=lowPeq_.processLeft(lowL);lowR=lowPeq_.processRight(lowR);}
  lowL=lowLeft.processAllPass(lowL);lowR=lowRight.processAllPass(lowR);
  lowL=lowLeft.delay.run(lowL);lowR=lowRight.delay.run(lowR);
  if(!lowLeft.muted)processCompressor(lowL,lowLeftCfg.compressor,dynamics(OutputId::LowLeft));else publishIdleMeter(dynamics(OutputId::LowLeft));
  if(!lowRight.muted)processCompressor(lowR,lowRightCfg.compressor,dynamics(OutputId::LowRight));else publishIdleMeter(dynamics(OutputId::LowRight));
  // Per-bus brick-wall limiter (stereo-linked), right before the driver gain. No-op while
  // disabled; independent of the per-output processCompressor path above.
  if(p_.busLimLowEnabled)processBusLimiter(lowL,lowR,p_.busLimLowThreshDb,busLimLowGain_,busLimLowReleaseMix_,busLimLowGrDb_);
  lowL*=lowLeft.gain;lowR*=lowRight.gain;
 }else{publishIdleMeter(dynamics(OutputId::LowLeft));publishIdleMeter(dynamics(OutputId::LowRight));}

 if(!p_.hpfPass){
  midL=processMidCrossover(midLeft,midL);midR=processMidCrossover(midRight,midR);
  // Mono Bass's Low-side mono/stereo split-and-recombine (below) rotates phase and -- with
  // makeup != 0 dB -- reshapes magnitude, and it does so differently for the mono and the
  // stereo (side) parts of the signal because the low half runs on (lowL+lowR)/2, not the
  // per-channel sample. Nothing else does that to Mid, so once Mono Bass is on Low reaches the
  // Low/Mid sum shaped in a way Mid isn't and the two stop summing flat right at the crossover
  // -- a real, audible dip, worst on wide/stereo bass.
  //
  // Fix: Mid re-runs the EXACT same recombination on its own band -- the low/makeup half fed
  // the Mid *mono sum* (midL+midR)/2, the high half per channel, same blend. Because every
  // stage is linear, applying the identical operation to both bands post-split is the same as
  // applying Mono Bass once pre-split, so Low and Mid stay summing flat for stereo content too,
  // not only for L==R. (The old version ran the low half on the per-channel Mid sample, which
  // only matched Low when L==R.)
  if(p_.monoBass){
   float mbBlend=p_.monoBassBlend*.01f;
   float midMono=(midL+midR)*.5f;
   float midMonoFiltered=monoBassMidLp2_.run(monoBassMidLp1_.run(midMono))*monoBassMakeupLin_;
   float compL=midMonoFiltered+midLeft.monoBassCompHp2.run(midLeft.monoBassCompHp1.run(midL));
   float compR=midMonoFiltered+midRight.monoBassCompHp2.run(midRight.monoBassCompHp1.run(midR));
   midL=midL*(1-mbBlend)+compL*mbBlend;midR=midR*(1-mbBlend)+compR*mbBlend;
  }
  if(peqEnabled_){midL=midPeq_.processLeft(midL);midR=midPeq_.processRight(midR);}
  midL=midLeft.processAllPass(midL);midR=midRight.processAllPass(midR);
  midL=midLeft.delay.run(midL);midR=midRight.delay.run(midR);
  if(!midLeft.muted)processCompressor(midL,midLeftCfg.compressor,dynamics(OutputId::MidLeft));else publishIdleMeter(dynamics(OutputId::MidLeft));
  if(!midRight.muted)processCompressor(midR,midRightCfg.compressor,dynamics(OutputId::MidRight));else publishIdleMeter(dynamics(OutputId::MidRight));
  if(p_.busLimMidEnabled)processBusLimiter(midL,midR,p_.busLimMidThreshDb,busLimMidGain_,busLimMidReleaseMix_,busLimMidGrDb_);
  midL*=midLeft.gain;midR*=midRight.gain;
 }else{publishIdleMeter(dynamics(OutputId::MidLeft));publishIdleMeter(dynamics(OutputId::MidRight));}

 if(lowLeft.polarityInverted)lowL=-lowL;if(lowRight.polarityInverted)lowR=-lowR;if(midLeft.polarityInverted)midL=-midL;if(midRight.polarityInverted)midR=-midR;
 if(lowLeft.muted)lowL=0;if(lowRight.muted)lowR=0;if(midLeft.muted)midL=0;if(midRight.muted)midR=0;
 const std::array<float,NativeBmwRouting::kOutputCount> logical{{lowL,lowR,midL,midR}};
 const auto stereo=NativeBmwRouting::sumToStereo(logical);
 float oL=(p_.lpfPass&&p_.hpfPass)?sL:stereo.left,oR=(p_.lpfPass&&p_.hpfPass)?sR:stereo.right;
 if(p_.tilt){oL=tiltHiL2_.run(tiltHiL1_.run(tiltLoL2_.run(tiltLoL1_.run(oL))));oR=tiltHiR2_.run(tiltHiR1_.run(tiltLoR2_.run(tiltLoR1_.run(oR))));}
 oL*=postGainL_;oR*=postGainR_;
 if(p_.channelMute==1)oL=0;if(p_.channelMute==2)oR=0;
 oL=ftz(oL);oR=ftz(oR);
 // Option A measurement-mute bus brick-wall. Operates on logical L/R, AFTER the band sum,
 // tilt, post-gain and ch_mute, but BEFORE processLimiter and BEFORE the deliberate L/R
 // hardware swap below -- measBusL_ always filters the LowLeft/MidLeft chain. Fully bypassed
 // (single branch, no state advanced) whenever measurement mute is off; see rebuildMeasBus().
 if(measBusActive_){
  for(std::size_t i=0;i<kMeasBusSections;++i){oL=measBusL_[i].run(oL);oR=measBusR_[i].run(oR);}
  oL=ftz(oL);oR=ftz(oR);
 }
 processLimiter(oL,oR);
 // Deliberate final-output swap -- DO NOT REMOVE OR "FIX" THIS.
 // The target vehicle's factory speaker wiring harness is physically reversed (L/R swapped
 // at the amp/speaker connectors, not something this DSP can see or control). This swap
 // cancels that physical fault: oL (built from the LowLeft/MidLeft chain) is written to
 // output param r, and oR (LowRight/MidRight) to output param l. Because of this, every
 // "Left"/"Right"-labeled control elsewhere in the app (gains, routing, delay, PEQ, and the
 // BmwSignalChain preview-graph mapping) is correct as a direct, unswapped index -- they all
 // rely on this line already correcting the physical fault. If a future review flags this as
 // "controls wired backwards", the bug is almost certainly elsewhere; do not add a
 // compensating swap here or anywhere downstream in response to that.
 l=oR;r=oL;
}

const float* NativeBmwDspProcessor::process(const float*s,std::size_t n){if(!s)return s;std::lock_guard<std::mutex> lock(stateMutex_);auto*w=const_cast<float*>(s);for(std::size_t i=0;i+1<n;i+=2){captureTapIn(w[i],w[i+1]);processFrame(w[i],w[i+1]);captureTapOut(w[i],w[i+1]);}return s;}
const int16_t* NativeBmwDspProcessor::process(const int16_t*s,std::size_t n){if(!s)return s;std::lock_guard<std::mutex> lock(stateMutex_);if(!p_.enabled)return s;constexpr float scale=32768.f,invScale=1.f/scale;auto*w=const_cast<int16_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=static_cast<float>(w[i])*invScale,r=static_cast<float>(w[i+1])*invScale;captureTapIn(l,r);processFrame(l,r);captureTapOut(l,r);w[i]=clampInt<int16_t>(l*scale);w[i+1]=clampInt<int16_t>(r*scale);}return s;}
const int32_t* NativeBmwDspProcessor::process(const int32_t*s,std::size_t n){if(!s)return s;std::lock_guard<std::mutex> lock(stateMutex_);if(!p_.enabled)return s;constexpr float scale=2147483648.f,invScale=1.f/scale;auto*w=const_cast<int32_t*>(s);for(std::size_t i=0;i+1<n;i+=2){float l=static_cast<float>(w[i])*invScale,r=static_cast<float>(w[i+1])*invScale;captureTapIn(l,r);processFrame(l,r);captureTapOut(l,r);w[i]=clampInt<int32_t>(l*scale);w[i+1]=clampInt<int32_t>(r*scale);}return s;}
void NativeBmwDspProcessor::readCompressorMeter(float*v,std::size_t n)const{if(!v||n<6)return;const auto&ll=dynamics(OutputId::LowLeft);const auto&lr=dynamics(OutputId::LowRight);const auto&ml=dynamics(OutputId::MidLeft);const auto&mr=dynamics(OutputId::MidRight);v[0]=std::max(ll.inputDb.load(),lr.inputDb.load());v[1]=std::max(ll.outputDb.load(),lr.outputDb.load());v[2]=std::max(ll.gainReductionDb.load(),lr.gainReductionDb.load());v[3]=std::max(ml.inputDb.load(),mr.inputDb.load());v[4]=std::max(ml.outputDb.load(),mr.outputDb.load());v[5]=std::max(ml.gainReductionDb.load(),mr.gainReductionDb.load());}
void NativeBmwDspProcessor::readMbcMeter(float*v,std::size_t n)const{
 if(!v||n<12)return;
 const bool active=p_.mbcEnabled;
 for(int b=0;b<4;++b){
  const auto&m=mbcMeter_[b];
  v[b*3+0]=active?m.inputDb.load():-60.f;
  v[b*3+1]=active?m.outputDb.load():-60.f;
  v[b*3+2]=active?m.gainReductionDb.load():0.f;
 }
}
void NativeBmwDspProcessor::readBusLimiterMeter(float*v,std::size_t n)const{
 if(!v||n<2)return;
 v[0]=p_.busLimLowEnabled?busLimLowGrDb_.load(std::memory_order_relaxed):0.f;
 v[1]=p_.busLimMidEnabled?busLimMidGrDb_.load(std::memory_order_relaxed):0.f;
}

void NativeBmwDspProcessor::startCapture() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    // Reallocating only when the size actually changes (not on every start) avoids repeatedly
    // touching ~23MB of memory for repeat captures at the same sample rate.
    const std::size_t capacity = static_cast<std::size_t>(sampleRate_ * kCaptureMaxSeconds);
    if (captureRawInL_.size() != capacity) {
        captureRawInL_.assign(capacity, 0.f);
        captureRawInR_.assign(capacity, 0.f);
        captureOutL_.assign(capacity, 0.f);
        captureOutR_.assign(capacity, 0.f);
    }
    captureCapacity_ = capacity;
    captureWriteIndex_.store(0, std::memory_order_relaxed);
    captureEnabled_ = capacity > 0;
}

void NativeBmwDspProcessor::stopCapture() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    captureEnabled_ = false;
}

std::size_t NativeBmwDspProcessor::captureFrameCount() const {
    return captureWriteIndex_.load(std::memory_order_relaxed);
}

bool NativeBmwDspProcessor::exportCaptureWav(const char* rawInPath, const char* outPath, CaptureExportResult& result) const {
    if (!rawInPath || !outPath) return false;
    const std::size_t n = captureFrameCount();
    if (n == 0) return false;

    auto writeStereo = [&](const char* path, const float* left, const float* right) -> bool {
        std::vector<float> interleaved(n * 2);
        for (std::size_t i = 0; i < n; ++i) {
            interleaved[i * 2] = left[i];
            interleaved[i * 2 + 1] = right[i];
        }
        drwav wav;
        drwav_data_format format;
        format.container = drwav_container_riff;
        format.format = DR_WAVE_FORMAT_IEEE_FLOAT;
        format.channels = 2;
        format.sampleRate = static_cast<drwav_uint32>(sampleRate_);
        format.bitsPerSample = 32;
        if (!drwav_init_file_write(&wav, path, &format, nullptr)) return false;
        const drwav_uint64 written = drwav_write_pcm_frames(&wav, n, interleaved.data());
        drwav_uninit(&wav);
        return written == n;
    };

    if (!writeStereo(rawInPath, captureRawInL_.data(), captureRawInR_.data())) return false;
    if (!writeStereo(outPath, captureOutL_.data(), captureOutR_.data())) return false;

    // Null test: RMS of (final output - raw input) across both channels. Near-silent when the
    // DSP genuinely isn't changing the signal beyond headroom/tilt/limiter defaults; a nonzero
    // reading pinpoints that *something* in the chain is doing more than expected.
    float peakIn = 0.f, peakOut = 0.f, diffSumSq = 0.f;
    for (std::size_t i = 0; i < n; ++i) {
        peakIn = std::max({peakIn, std::fabs(captureRawInL_[i]), std::fabs(captureRawInR_[i])});
        peakOut = std::max({peakOut, std::fabs(captureOutL_[i]), std::fabs(captureOutR_[i])});
        const float dl = captureOutL_[i] - captureRawInL_[i];
        const float dr = captureOutR_[i] - captureRawInR_[i];
        diffSumSq += dl * dl + dr * dr;
    }
    const float rms = std::sqrt(diffSumSq / static_cast<float>(n * 2));
    result.peakInDb = peakIn > 0.f ? 20.f * std::log10(peakIn) : -100.f;
    result.peakOutDb = peakOut > 0.f ? 20.f * std::log10(peakOut) : -100.f;
    result.nullTestRmsDb = rms > 0.f ? 20.f * std::log10(rms) : -100.f;
    return true;
}
