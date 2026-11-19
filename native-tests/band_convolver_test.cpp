#include "test_support.h"
#include "NativeBmwConvolver.h"
#include <random>
using namespace nbtest;
namespace {
NativeBmwDsp::ConvolverIr bandDelta(double left=1, double right=1, std::size_t delay=0) {
    NativeBmwDsp::ConvolverIr ir;
    ir.sampleRate=48000; ir.channels=2;
    ir.left.assign(delay+1,0); ir.right.assign(delay+1,0);
    ir.left[delay]=left; ir.right[delay]=right;
    return ir;
}
void warm(NativeBmwDspProcessor& p) {
    std::vector<float> zeros(2*24000,0); p.process(zeros.data(),zeros.size());
}
}
TEST_CASE("band mode requires all slots and preserves stereo source") {
    NativeBmwDspProcessor p;
    std::string error;
    CHECK_FALSE(p.setConvolverMode(1,error));
    CHECK_FALSE(p.setConvolverMode(2,error));
    REQUIRE(p.loadConvolverIr(bandDelta(.25,.25),error));
    for(int b=0;b<3;++b) REQUIRE(p.loadBandConvolverIr(b,bandDelta(),error));
    REQUIRE(p.setConvolverMode(1,error));
    CHECK(p.convolverMode()==1);
    CHECK(p.convolverInfo().loaded);
    CHECK(p.convolverInfo().peakGainDb==doctest::Approx(-12.0412).epsilon(.0001));
    CHECK_FALSE(p.loadBandConvolverIr(1,NativeBmwDsp::ConvolverIr{},error));
    CHECK(p.bandConvolverInfo(1).loaded);
    p.clearBandConvolverIr(1);
    CHECK(p.convolverMode()==0);
    CHECK(p.bandConvolverInfo(0).loaded);
    CHECK_FALSE(p.bandConvolverInfo(1).loaded);
    CHECK(p.convolverInfo().loaded);
}
TEST_CASE("unity band IRs preserve crossover sum with one common partition delay") {
    NativeBmwDspProcessor base, corrected;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1;
    base.configure(c.data(),c.size()); corrected.configure(c.data(),c.size());
    std::string error;
    for(int b=0;b<3;++b) REQUIRE(corrected.loadBandConvolverIr(b,bandDelta(),error));
    REQUIRE(corrected.setConvolverMode(1,error));
    warm(base); warm(corrected);
    std::vector<float> dry(2*12000),wet;
    std::mt19937 rng(8712); std::uniform_real_distribution<float> u(-.005,.005);
    for(auto& x:dry)x=u(rng); wet=dry;
    base.process(dry.data(),dry.size()); corrected.process(wet.data(),wet.size());
    constexpr std::size_t latency=NativeBmwDsp::kConvolverLatencyFrames;
    double errorMax=0;
    for(std::size_t i=latency;i<12000;++i)for(int ch=0;ch<2;++ch)
        errorMax=std::max(errorMax,std::fabs(double(wet[2*i+ch]-dry[2*(i-latency)+ch])));
    CHECK(errorMax<1e-6);
}
TEST_CASE("band mode switches preserve files and resample every IR") {
    NativeBmwDspProcessor p; std::string error;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1;
    p.configure(c.data(),c.size());
    for(int b=0;b<3;++b) REQUIRE(p.loadBandConvolverIr(b,bandDelta(.5,.25,48),error));
    REQUIRE(p.loadConvolverIr(bandDelta(),error));
    for(int i=0;i<8;++i) {
        REQUIRE(p.setConvolverMode(i%2,error));
        auto signal=stereoSine(1200,.01,5000); p.process(signal.data(),signal.size());
        for(float x:signal) CHECK(std::isfinite(x));
    }
    p.setSampleRate(44100);
    for(int b=0;b<3;++b) {
        CHECK(p.bandConvolverInfo(b).streamRate==44100);
        CHECK(p.bandConvolverSnapshot(b).ir->sampleRate==44100);
    }
    CHECK(p.convolverInfo().loaded);
    REQUIRE(p.setConvolverMode(1,error));
    CHECK_FALSE(p.bandConvolverSnapshot(-1).ir);
}

TEST_CASE("band mode common trim cannot change relative IR gain through positive peak caps") {
    NativeBmwDspProcessor a,b; std::string error;
    auto c=defaultConfig();c[nbschema::kConvolverEnabled]=1;
    a.configure(c.data(),c.size());c[nbschema::kConvolverGain]=6;
    b.configure(c.data(),c.size());
    for(int band=0;band<3;++band) {
        const double gain=band==0?.25:band==1?.5:1.;
        REQUIRE(a.loadBandConvolverIr(band,bandDelta(gain,gain),error));
        REQUIRE(b.loadBandConvolverIr(band,bandDelta(gain,gain),error));
    }
    REQUIRE(a.setConvolverMode(1,error));REQUIRE(b.setConvolverMode(1,error));
    warm(a);warm(b);
    auto x=stereoSine(1000,.01,6000),y=x;
    a.process(x.data(),x.size());b.process(y.data(),y.size());
    double maxError=0;for(std::size_t i=0;i<x.size();++i)maxError=std::max(maxError,std::fabs(double(x[i]-y[i])));
    CHECK(maxError<1e-7);
}

TEST_CASE("resuming crossovers discards audio buffered in band FIRs") {
    NativeBmwDspProcessor p; std::string error;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1;
    p.configure(c.data(),c.size());
    for(int b=0;b<3;++b) REQUIRE(p.loadBandConvolverIr(b,bandDelta(1,1,12000),error));
    REQUIRE(p.setConvolverMode(1,error)); warm(p);
    auto tone=stereoSine(1000,.02,3000); p.process(tone.data(),tone.size());
    c[nbschema::kCrossoverBypass]=1; p.configure(c.data(),c.size()); warm(p);
    c[nbschema::kCrossoverBypass]=0; p.configure(c.data(),c.size());
    std::vector<float> silence(2*20000,0); p.process(silence.data(),silence.size());
    float peak=0; for(float x:silence)peak=std::max(peak,std::fabs(x));
    CHECK(peak<1e-7f);
}

TEST_CASE("crossover bypass in band mode runs the stereo IR and resumes per-band with the split") {
    NativeBmwDspProcessor bands, stereo; std::string error;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1; c[nbschema::kCrossoverBypass]=1;
    bands.configure(c.data(),c.size()); stereo.configure(c.data(),c.size());
    for(int b=0;b<3;++b) REQUIRE(bands.loadBandConvolverIr(b,bandDelta(),error));
    REQUIRE(bands.loadConvolverIr(bandDelta(.5,.5),error));
    REQUIRE(stereo.loadConvolverIr(bandDelta(.5,.5),error));
    // Per-band stays selected while bypassed; the audio runs the stereo slot.
    REQUIRE(bands.setConvolverMode(1,error));
    warm(bands); warm(stereo);
    CHECK(bands.convolverMode()==1);
    CHECK(bands.activeConvolverMode()==0);
    auto x=stereoSine(1000,.01,6000),y=x;
    bands.process(x.data(),x.size()); stereo.process(y.data(),y.size());
    double maxError=0; float peak=0;
    for(std::size_t i=0;i<x.size();++i){maxError=std::max(maxError,std::fabs(double(x[i]-y[i])));peak=std::max(peak,std::fabs(x[i]));}
    CHECK(maxError<1e-7);
    CHECK(peak>1e-3f);  // corrected, not silent
    c[nbschema::kCrossoverBypass]=0; bands.configure(c.data(),c.size()); warm(bands);
    CHECK(bands.activeConvolverMode()==1);
}

TEST_CASE("band IRs share one unity cap so their relative levels survive") {
    NativeBmwDspProcessor corrected; std::string error;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1;
    corrected.configure(c.data(),c.size());
    // Low boosts +6 dB; Mid and High are flat. A per-band cap would flatten Low back to unity;
    // the shared cap brings all three down 6 dB together.
    REQUIRE(corrected.loadBandConvolverIr(0,bandDelta(2,2),error));
    REQUIRE(corrected.loadBandConvolverIr(1,bandDelta(1,1),error));
    REQUIRE(corrected.loadBandConvolverIr(2,bandDelta(1,1),error));
    REQUIRE(corrected.setConvolverMode(1,error));
    warm(corrected);
    // Reference: the same IR shapes already at 0 dB peak (Low at unity, Mid/High at half), which
    // no cap touches -- exactly what the shared cap should make of the set above.
    NativeBmwDspProcessor half; half.configure(c.data(),c.size());
    REQUIRE(half.loadBandConvolverIr(0,bandDelta(1,1),error));
    REQUIRE(half.loadBandConvolverIr(1,bandDelta(.5,.5),error));
    REQUIRE(half.loadBandConvolverIr(2,bandDelta(.5,.5),error));
    REQUIRE(half.setConvolverMode(1,error));
    warm(half);
    auto x=stereoSine(1000,.01,6000),y=x;
    corrected.process(x.data(),x.size()); half.process(y.data(),y.size());
    double maxError=0;
    for(std::size_t i=0;i<x.size();++i) maxError=std::max(maxError,std::fabs(double(x[i]-y[i])));
    CHECK(maxError<1e-7);
}

TEST_CASE("switching mode with the convolver off neither mutes nor resets the chain") {
    NativeBmwDspProcessor switched, steady; std::string error;
    auto c=defaultConfig();
    switched.configure(c.data(),c.size()); steady.configure(c.data(),c.size());
    for(int b=0;b<3;++b) REQUIRE(switched.loadBandConvolverIr(b,bandDelta(),error));
    warm(switched); warm(steady);
    auto x=stereoSine(1000,.01,6000),y=x;
    switched.process(x.data(),2000);  steady.process(y.data(),2000);
    REQUIRE(switched.setConvolverMode(1,error));
    switched.process(x.data()+2000,x.size()-2000); steady.process(y.data()+2000,y.size()-2000);
    CHECK(switched.activeConvolverMode()==1);
    double maxError=0;
    for(std::size_t i=0;i<x.size();++i) maxError=std::max(maxError,std::fabs(double(x[i]-y[i])));
    CHECK(maxError<1e-7);
}

TEST_CASE("a louder band IR lowers the shared cap smoothly, without a step in the other bands") {
    NativeBmwDspProcessor p; std::string error;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1;
    p.configure(c.data(),c.size());
    for(int b=0;b<3;++b) REQUIRE(p.loadBandConvolverIr(b,bandDelta(),error));
    REQUIRE(p.setConvolverMode(1,error)); warm(p);
    // One continuous 1 kHz tone: settled first, then Low swaps to a +6 dB IR mid-stream, so
    // every band comes down 6 dB -- as a ramp, not a step.
    constexpr std::size_t kSettle=24000, kTotal=48000;
    auto x=stereoSine(1000,.05,kTotal);
    p.process(x.data(),2*kSettle);
    REQUIRE(p.loadBandConvolverIr(0,bandDelta(2,2),error));
    p.process(x.data()+2*kSettle,2*(kTotal-kSettle));
    // Peak of each cycle (48 frames): a hard 6 dB step halves it from one cycle to the next;
    // the ~10 ms ramp moves it a few percent per cycle.
    std::vector<double> peaks;
    for(std::size_t start=kSettle-4800;start+48<=kTotal;start+=48){
        double peak=0; for(std::size_t i=start;i<start+48;++i) peak=std::max(peak,std::fabs(double(x[2*i])));
        peaks.push_back(peak);
    }
    REQUIRE(peaks.front()>0);
    REQUIRE(peaks.back()<peaks.front()*.6);  // it did come down ~6 dB overall
    double worst=1;
    for(std::size_t k=0;k+1<peaks.size();++k) worst=std::min(worst,peaks[k+1]/peaks[k]);
    CHECK(worst>.8);
}

TEST_CASE("disabling the convolver and switching mode together still dips before the crossover fade") {
    NativeBmwDspProcessor p; std::string error;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1;
    p.configure(c.data(),c.size());
    for(int b=0;b<3;++b) REQUIRE(p.loadBandConvolverIr(b,bandDelta(),error));
    REQUIRE(p.setConvolverMode(1,error)); warm(p);
    auto tone=stereoSine(1000,.02,2400); p.process(tone.data(),tone.size());
    // One update: convolver off and crossovers bypassed (target mode 0). The band FIRs are still
    // convolving, so the switch must wait for the dip rather than happen at once.
    c[nbschema::kConvolverEnabled]=0; c[nbschema::kCrossoverBypass]=1; p.configure(c.data(),c.size());
    std::vector<float> one(2*16,0); p.process(one.data(),one.size());
    CHECK(p.activeConvolverMode()==1);
    warm(p);
    CHECK(p.activeConvolverMode()==0);
}

TEST_CASE("a louder band IR never plays above the shared cap while it lowers") {
    NativeBmwDspProcessor p; std::string error;
    auto c=defaultConfig(); c[nbschema::kConvolverEnabled]=1;
    p.configure(c.data(),c.size());
    for(int b=0;b<3;++b) REQUIRE(p.loadBandConvolverIr(b,bandDelta(),error));
    REQUIRE(p.setConvolverMode(1,error)); warm(p);
    // A bass tone, carried by Low. Low swaps from flat to a 2x (+6 dB) IR: capped at 0.5 it
    // lands back at unity, so the tone must never come out louder than before the swap.
    constexpr std::size_t kSettle=24000, kTotal=48000, kPeriod=800;  // 60 Hz
    auto x=stereoSine(60,.05,kTotal);
    p.process(x.data(),2*kSettle);
    REQUIRE(p.loadBandConvolverIr(0,bandDelta(2,2),error));
    p.process(x.data()+2*kSettle,2*(kTotal-kSettle));
    auto peakOf=[&](std::size_t start){double v=0;for(std::size_t i=start;i<start+kPeriod;++i)v=std::max(v,std::fabs(double(x[2*i])));return v;};
    const double before=peakOf(kSettle-2*kPeriod);
    REQUIRE(before>0);
    double worst=0;
    for(std::size_t start=kSettle;start+kPeriod<=kTotal;start+=kPeriod/8) worst=std::max(worst,peakOf(start));
    CHECK(worst<before*1.01);
}
