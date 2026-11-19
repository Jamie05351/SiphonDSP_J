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
