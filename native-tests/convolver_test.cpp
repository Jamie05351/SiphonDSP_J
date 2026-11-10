#include "test_support.h"

#include "NativeBmwConvolver.h"
#include "NativeBmwDspSchema.h"
#include "third_party/dr_wav.h"

#include <atomic>
#include <cstdio>
#include <random>
#include <thread>
#include <vector>

using namespace nbtest;
namespace sch = nbschema;
using NativeBmwDsp::ConvolverIr;
using NativeBmwDsp::ConvolverKernel;
using NativeBmwDsp::ConvolverStage;

// The stereo convolver, last in the chain: UPOLS with the chunk as its partition (no added
// latency), double precision, IR load/resample off the audio thread, lock-free kernel swap,
// gain trim and a crossfaded bypass.

namespace {

ConvolverIr randomIr(std::size_t frames, bool stereo, unsigned seed, double rate = 48000.0) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<double> u(-1.0, 1.0);
    ConvolverIr ir;
    ir.sampleRate = rate;
    ir.channels = stereo ? 2 : 1;
    ir.left.resize(frames);
    for (auto& v : ir.left) v = u(rng) * 0.1;
    if (stereo) {
        ir.right.resize(frames);
        for (auto& v : ir.right) v = u(rng) * 0.1;
    }
    return ir;
}

ConvolverIr deltaIr(double gain, std::size_t at = 0, double rate = 48000.0) {
    ConvolverIr ir;
    ir.sampleRate = rate;
    ir.channels = 1;
    ir.left.assign(at + 1, 0.0);
    ir.left[at] = gain;
    return ir;
}

std::vector<double> directConvolve(const std::vector<double>& h, const std::vector<double>& x) {
    std::vector<double> y(x.size(), 0.0);
    for (std::size_t n = 0; n < x.size(); ++n) {
        double acc = 0.0;
        for (std::size_t m = 0; m < h.size() && m <= n; ++m) acc += h[m] * x[n - m];
        y[n] = acc;
    }
    return y;
}

std::array<float, kConfigSize> config(bool convolverOn, float gainDb = 0.f) {
    auto c = defaultConfig();
    c[sch::kConvolverEnabled] = convolverOn ? 1.f : 0.f;
    c[sch::kConvolverGain] = gainDb;
    return c;
}

// Interleaved stereo noise, different on L and R, at amp.
std::vector<float> noise(std::size_t frames, double amp, unsigned seed) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> u(-1.f, 1.f);
    std::vector<float> buf(2 * frames);
    for (auto& v : buf) v = static_cast<float>(amp) * u(rng);
    return buf;
}

void processInBuffers(NativeBmwDspProcessor& proc, std::vector<float>& buf, std::size_t bufferFrames) {
    for (std::size_t f = 0; f < buf.size() / 2; f += bufferFrames) {
        const std::size_t n = std::min(bufferFrames, buf.size() / 2 - f);
        proc.process(buf.data() + 2 * f, 2 * n);
    }
}

}  // namespace

TEST_CASE("convolver kernel matches direct convolution, any chunking, mono and stereo IRs") {
    for (const std::size_t len : {1u, 63u, 64u, 65u, 700u, 3000u}) {
        for (const bool stereo : {false, true}) {
            const ConvolverIr ir = randomIr(len, stereo, static_cast<unsigned>(len));
            std::string error;
            auto kernel = ConvolverKernel::build(ir, error);
            REQUIRE(kernel);
            CHECK(kernel->partitions() == (len + 63) / 64);
            const std::size_t n = 6000;
            std::mt19937 rng(7);
            std::uniform_real_distribution<double> u(-1.0, 1.0);
            std::vector<double> xl(n), xr(n), yl(n), yr(n);
            for (auto& v : xl) v = u(rng);
            for (auto& v : xr) v = u(rng);
            // Full chunks, short ones and single frames, as a processor's buffers can split them.
            const std::size_t sizes[] = {64, 17, 64, 1, 30, 64, 64, 3, 47};
            std::size_t pos = 0, s = 0;
            while (pos < n) {
                const std::size_t count = std::min(sizes[s++ % 9], n - pos);
                kernel->process(&xl[pos], &xr[pos], &yl[pos], &yr[pos], count);
                pos += count;
            }
            const auto refL = directConvolve(ir.left, xl);
            const auto refR = directConvolve(stereo ? ir.right : ir.left, xr);
            double maxErr = 0.0;
            for (std::size_t i = 0; i < n; ++i) {
                maxErr = std::max(maxErr, std::max(std::fabs(refL[i] - yl[i]), std::fabs(refR[i] - yr[i])));
            }
            CAPTURE(len);
            CAPTURE(stereo);
            // Double-precision FFTs: rounding only, far below float resolution.
            CHECK(maxErr < 1e-11);
        }
    }
}

TEST_CASE("convolver kernel reset() forgets the input history") {
    const ConvolverIr ir = randomIr(500, true, 3);
    std::string error;
    auto a = ConvolverKernel::build(ir, error);
    auto b = ConvolverKernel::build(ir, error);
    REQUIRE(a);
    REQUIRE(b);
    std::vector<double> x(64, 0.3), y(64), z(64);
    for (int i = 0; i < 20; ++i) a->process(x.data(), x.data(), y.data(), y.data(), 64);
    a->reset();
    for (int i = 0; i < 12; ++i) {
        a->process(x.data(), x.data(), y.data(), y.data(), 64);
        b->process(x.data(), x.data(), z.data(), z.data(), 64);
        for (std::size_t k = 0; k < 64; ++k) CHECK(y[k] == doctest::Approx(z[k]).epsilon(1e-12));
    }
}

TEST_CASE("convolver: off, or on with no IR, is bit-identical to the chain without it") {
    const auto input = noise(9000, 0.2, 11);
    NativeBmwDspProcessor reference, offWithIr, onWithoutIr;
    for (auto* p : {&reference, &offWithIr, &onWithoutIr}) p->setSampleRate(kSampleRate);
    REQUIRE(reference.configure(config(false).data(), kConfigSize));
    REQUIRE(offWithIr.configure(config(false).data(), kConfigSize));
    REQUIRE(onWithoutIr.configure(config(true).data(), kConfigSize));
    std::string error;
    REQUIRE(offWithIr.loadConvolverIr(randomIr(2000, true, 5), error));
    auto a = input, b = input, c = input;
    processInBuffers(reference, a, 480);
    processInBuffers(offWithIr, b, 480);
    processInBuffers(onWithoutIr, c, 480);
    CHECK(a == b);
    CHECK(a == c);
}

TEST_CASE("convolver adds no latency and applies the gain trim") {
    // A single tap at 0: the convolved chain is the plain chain times the tap, sample for sample.
    for (const float gainDb : {0.f, -6.f, 6.f}) {
        NativeBmwDspProcessor plain, convolved;
        plain.setSampleRate(kSampleRate);
        convolved.setSampleRate(kSampleRate);
        REQUIRE(plain.configure(config(false).data(), kConfigSize));
        REQUIRE(convolved.configure(config(true, gainDb).data(), kConfigSize));
        std::string error;
        REQUIRE(convolved.loadConvolverIr(deltaIr(0.5), error));
        auto a = noise(24000, 0.1, 21), b = a;
        processInBuffers(plain, a, 480);
        processInBuffers(convolved, b, 480);
        const double expected = 0.5 * std::pow(10.0, gainDb / 20.0);
        // After the 20 ms fade-in and the 10 ms gain ramp have settled.
        double maxErr = 0.0;
        for (std::size_t i = 2 * 12000; i < a.size(); ++i) {
            maxErr = std::max(maxErr, std::fabs(b[i] - expected * a[i]));
        }
        CAPTURE(gainDb);
        CHECK(maxErr < 1e-6);
    }
}

TEST_CASE("convolver: the IR's left channel is the car's left, through the hardware swap") {
    // processFrameBack() swaps L/R for the reversed harness: logical L leaves on output index 1.
    NativeBmwDspProcessor plain, convolved;
    plain.setSampleRate(kSampleRate);
    convolved.setSampleRate(kSampleRate);
    REQUIRE(plain.configure(config(false).data(), kConfigSize));
    REQUIRE(convolved.configure(config(true).data(), kConfigSize));
    ConvolverIr ir = deltaIr(1.0);
    ir.right = {0.0};
    ir.channels = 2;
    std::string error;
    REQUIRE(convolved.loadConvolverIr(ir, error));
    auto a = noise(12000, 0.1, 31), b = a;
    processInBuffers(plain, a, 480);
    processInBuffers(convolved, b, 480);
    double physicalLeftErr = 0.0, physicalRightPeak = 0.0;
    for (std::size_t f = 6000; f < 12000; ++f) {
        physicalLeftErr = std::max(physicalLeftErr, static_cast<double>(std::fabs(b[2 * f + 1] - a[2 * f + 1])));
        physicalRightPeak = std::max(physicalRightPeak, static_cast<double>(std::fabs(b[2 * f])));
    }
    CHECK(physicalLeftErr < 1e-6);
    CHECK(physicalRightPeak < 1e-6);
}

TEST_CASE("convolver stage: bypass and kernel swaps crossfade, never step") {
    ConvolverStage stage;
    stage.setSampleRate(kSampleRate);
    std::string error;
    stage.publish(ConvolverKernel::build(deltaIr(0.5), error));
    std::array<float, 64> l{}, r{};
    auto run = [&](bool enabled, int chunks, std::vector<float>& trace) {
        for (int c = 0; c < chunks; ++c) {
            l.fill(0.5f);
            r.fill(0.5f);
            stage.process(l.data(), r.data(), 64, enabled, 0.f);
            trace.insert(trace.end(), l.begin(), l.end());
        }
    };
    std::vector<float> trace;
    run(true, 40, trace);
    CHECK(trace.back() == doctest::Approx(0.25f));  // faded in: 0.5 x the tap
    trace.clear();
    run(false, 40, trace);  // bypass: back to the dry 0.5
    CHECK(trace.back() == doctest::Approx(0.5f));
    const float fadeFrames = ConvolverStage::kFadeSeconds * kSampleRate;
    float maxStep = 0.f;
    for (std::size_t i = 1; i < trace.size(); ++i) maxStep = std::max(maxStep, std::fabs(trace[i] - trace[i - 1]));
    CHECK(maxStep <= 0.25f / fadeFrames * 1.05f);
    CHECK(trace.front() < 0.26f);  // started from the convolved level, not a jump to dry

    trace.clear();
    run(true, 40, trace);
    stage.publish(ConvolverKernel::build(deltaIr(1.0), error));  // swap while running
    trace.clear();
    run(true, 40, trace);
    CHECK(trace.front() < 0.26f);
    CHECK(trace.back() == doctest::Approx(0.5f));
    maxStep = 0.f;
    for (std::size_t i = 1; i < trace.size(); ++i) maxStep = std::max(maxStep, std::fabs(trace[i] - trace[i - 1]));
    CHECK(maxStep <= 0.25f / fadeFrames * 1.05f);

    stage.publish(nullptr);  // unload while running: fades to dry
    trace.clear();
    run(true, 40, trace);
    CHECK(trace.front() > 0.49f);
    CHECK(trace.back() == doctest::Approx(0.5f));
    CHECK_FALSE(stage.kernelActive());
}

TEST_CASE("convolver: an IR at another rate is resampled with its response kept") {
    // A band-limited 44.1 kHz IR: a 3 kHz windowed sine burst, so the response is well inside both
    // Nyquists. Its response at 3 kHz must survive the resample to 48 kHz.
    ConvolverIr ir;
    ir.sampleRate = 44100.0;
    ir.channels = 1;
    ir.left.resize(2000);
    for (std::size_t n = 0; n < ir.left.size(); ++n) {
        const double hann = 0.5 - 0.5 * std::cos(2.0 * kPi * n / (ir.left.size() - 1));
        ir.left[n] = 0.01 * hann * std::sin(2.0 * kPi * 3000.0 * n / 44100.0);
    }
    auto responseAt = [](const ConvolverIr& h, double f) {
        double re = 0.0, im = 0.0;
        for (std::size_t n = 0; n < h.left.size(); ++n) {
            re += h.left[n] * std::cos(2.0 * kPi * f * n / h.sampleRate);
            im -= h.left[n] * std::sin(2.0 * kPi * f * n / h.sampleRate);
        }
        return std::hypot(re, im);
    };
    ConvolverIr resampled;
    std::string error;
    REQUIRE(NativeBmwDsp::resampleConvolverIr(ir, 48000.0, resampled, error));
    CHECK(resampled.frames() == static_cast<std::size_t>(std::ceil(2000.0 * 48000.0 / 44100.0)));
    for (const double f : {1500.0, 3000.0, 4500.0}) {
        CAPTURE(f);
        CHECK(linToDb(responseAt(resampled, f)) == doctest::Approx(linToDb(responseAt(ir, f))).epsilon(0.002));
    }

    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.loadConvolverIr(ir, error));
    auto info = proc.convolverInfo();
    CHECK(info.loaded);
    CHECK(info.sourceRate == 44100.0);
    CHECK(info.streamRate == 48000.0);
    CHECK(info.streamFrames == resampled.frames());
    // A stream-rate change rebuilds the kernel from the kept source.
    proc.setSampleRate(44100.f);
    info = proc.convolverInfo();
    CHECK(info.streamRate == 44100.0);
    CHECK(info.streamFrames == 2000);
    CHECK(info.error.empty());
}

TEST_CASE("convolver: a rejected IR leaves the current one running") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    std::string error;
    REQUIRE(proc.loadConvolverIr(randomIr(1000, false, 1), error));
    ConvolverIr tooLong = randomIr(NativeBmwDsp::kConvolverMaxIrFrames + 1, false, 2);
    CHECK_FALSE(proc.loadConvolverIr(tooLong, error));
    CHECK(error.find("too long") != std::string::npos);
    const auto info = proc.convolverInfo();
    CHECK(info.loaded);
    CHECK(info.streamFrames == 1000);
    CHECK_FALSE(info.error.empty());
    proc.clearConvolverIr();
    CHECK_FALSE(proc.convolverInfo().loaded);
}

TEST_CASE("convolver: WAV IRs load, trailing silence is trimmed, odd files are rejected") {
    const std::string dir = std::string(NBTEST_TMP_DIR);
    auto writeWav = [](const std::string& path, unsigned channels, unsigned rate,
                       const std::vector<float>& interleaved) {
        drwav_data_format fmt{};
        fmt.container = drwav_container_riff;
        fmt.format = DR_WAVE_FORMAT_IEEE_FLOAT;
        fmt.channels = channels;
        fmt.sampleRate = rate;
        fmt.bitsPerSample = 32;
        drwav wav;
        REQUIRE(drwav_init_file_write(&wav, path.c_str(), &fmt, nullptr));
        drwav_write_pcm_frames(&wav, interleaved.size() / channels, interleaved.data());
        drwav_uninit(&wav);
    };
    std::vector<float> stereo(2 * 1000, 0.f);
    stereo[0] = 0.5f;          // L tap 0
    stereo[2 * 99 + 1] = 0.25f;  // R tap 99; everything after is silence
    writeWav(dir + "/ir_stereo.wav", 2, 48000, stereo);
    ConvolverIr ir;
    std::string error;
    REQUIRE(NativeBmwDsp::loadConvolverIrWav((dir + "/ir_stereo.wav").c_str(), ir, error));
    CHECK(ir.channels == 2);
    CHECK(ir.sampleRate == 48000.0);
    CHECK(ir.frames() == 100);
    CHECK(ir.left[0] == doctest::Approx(0.5));
    CHECK(ir.right[99] == doctest::Approx(0.25));

    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    CHECK(proc.loadConvolverIr((dir + "/ir_stereo.wav").c_str(), error));
    CHECK(proc.convolverInfo().peakGainDb == doctest::Approx(linToDb(0.5)).epsilon(1e-6));

    writeWav(dir + "/ir_quad.wav", 4, 48000, std::vector<float>(4 * 10, 0.1f));
    CHECK_FALSE(NativeBmwDsp::loadConvolverIrWav((dir + "/ir_quad.wav").c_str(), ir, error));
    CHECK(error.find("mono or stereo") != std::string::npos);
    writeWav(dir + "/ir_silent.wav", 1, 48000, std::vector<float>(100, 0.f));
    CHECK_FALSE(NativeBmwDsp::loadConvolverIrWav((dir + "/ir_silent.wav").c_str(), ir, error));
    CHECK_FALSE(NativeBmwDsp::loadConvolverIrWav((dir + "/does_not_exist.wav").c_str(), ir, error));
    std::remove((dir + "/ir_stereo.wav").c_str());
    std::remove((dir + "/ir_quad.wav").c_str());
    std::remove((dir + "/ir_silent.wav").c_str());
}

TEST_CASE("convolver: IR loads on a control thread while audio runs") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(config(true).data(), kConfigSize));
    std::atomic<bool> stop{false};
    std::thread loader([&] {
        std::string error;
        for (unsigned i = 0; i < 40 && !stop.load(); ++i) {
            if (i % 5 == 4) {
                proc.clearConvolverIr();
            } else {
                proc.loadConvolverIr(randomIr(200 + 97 * i, (i & 1) != 0, i), error);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    });
    auto buf = noise(480, 0.1, 9);
    bool finite = true;
    for (int i = 0; i < 2000; ++i) {
        auto b = buf;
        proc.process(b.data(), b.size());
        for (float v : b) finite = finite && std::isfinite(v) && std::fabs(v) <= 1.f;
    }
    stop = true;
    loader.join();
    CHECK(finite);
}
