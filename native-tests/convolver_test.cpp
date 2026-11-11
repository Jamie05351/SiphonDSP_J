#include "test_support.h"

#include "NativeBmwConvolver.h"
#include "NativeBmwDspSchema.h"
#include "third_party/dr_wav.h"

#include <atomic>
#include <pthread.h>
#include <cstdio>
#include <random>
#include <thread>
#include <vector>

using namespace nbtest;
namespace sch = nbschema;
using NativeBmwDsp::ConvolverIr;
using NativeBmwDsp::ConvolverKernel;
using NativeBmwDsp::ConvolverStage;

// The stereo convolver, after the band sum and ahead of the master limiter: UPOLS with the chunk as its partition (no added
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

TEST_CASE("convolver adds no latency and applies the gain trim, capped at unity peak") {
    // A single tap at 0: the convolved chain is the plain chain times the tap, sample for sample.
    // The tap is 0.5 (-6 dB), so the trim can lift it back to unity (+6 dB) but no further.
    for (const float gainDb : {0.f, -6.f, 6.f, 12.f}) {
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
        const double expected = std::min(1.0, 0.5 * std::pow(10.0, gainDb / 20.0));
        // After the 20 ms fade-in and the 10 ms gain ramp have settled.
        double maxErr = 0.0;
        for (std::size_t i = 2 * 12000; i < a.size(); ++i) {
            maxErr = std::max(maxErr, std::fabs(b[i] - expected * a[i]));
        }
        CAPTURE(gainDb);
        CHECK(maxErr < 1e-6);
    }
}

TEST_CASE("convolver: an IR that boosts is brought down to unity at its loudest frequency") {
    // A +6 dB IR at 0 dB trim is capped back to unity rather than driving the limiter.
    NativeBmwDspProcessor plain, convolved;
    plain.setSampleRate(kSampleRate);
    convolved.setSampleRate(kSampleRate);
    REQUIRE(plain.configure(config(false).data(), kConfigSize));
    REQUIRE(convolved.configure(config(true, 0.f).data(), kConfigSize));
    std::string error;
    REQUIRE(convolved.loadConvolverIr(deltaIr(2.0), error));
    CHECK(convolved.convolverInfo().peakGainDb == doctest::Approx(linToDb(2.0)));
    auto a = noise(24000, 0.1, 41), b = a;
    processInBuffers(plain, a, 480);
    processInBuffers(convolved, b, 480);
    double maxErr = 0.0;
    for (std::size_t i = 2 * 12000; i < a.size(); ++i) maxErr = std::max(maxErr, static_cast<double>(std::fabs(b[i] - a[i])));
    CHECK(maxErr < 1e-6);
}

TEST_CASE("convolver: the IR's left channel is the car's left, through the hardware swap") {
    // The convolver sees logical L/R; processFrameBack() then swaps L/R for the reversed harness,
    // so logical L leaves on output index 1.
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
    // The source's duration at 48 kHz, plus at most the filter's ringing, minus the silence trimmed
    // after the Hann window's zero end.
    const double sourceFrames = 2000.0 * 48000.0 / 44100.0;
    CHECK(static_cast<double>(resampled.frames()) > sourceFrames - 8);
    CHECK(static_cast<double>(resampled.frames()) < sourceFrames + 64 * 48000.0 / 44100.0 + 2);
    for (const double f : {2500.0, 3000.0, 3500.0}) {
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

TEST_CASE("convolver: resampling stays accurate across ratios, up to the highest source rate") {
    // A 1 kHz windowed tone burst at each source rate: its response at 1 kHz survives the trip to
    // 48 kHz (downsampling 8x from 384 kHz included), within the resampler's table accuracy.
    for (const double rate : {22050.0, 44100.0, 96000.0, 192000.0, 384000.0}) {
        ConvolverIr ir;
        ir.sampleRate = rate;
        ir.channels = 1;
        ir.left.resize(static_cast<std::size_t>(rate * 0.02));
        const std::size_t n = ir.left.size();
        for (std::size_t i = 0; i < n; ++i) {
            const double hann = 0.5 - 0.5 * std::cos(2.0 * kPi * i / (n - 1));
            ir.left[i] = 0.001 * hann * std::sin(2.0 * kPi * 1000.0 * i / rate);
        }
        auto response = [](const ConvolverIr& h, double f) {
            double re = 0.0, im = 0.0;
            for (std::size_t i = 0; i < h.left.size(); ++i) {
                re += h.left[i] * std::cos(2.0 * kPi * f * i / h.sampleRate);
                im -= h.left[i] * std::sin(2.0 * kPi * f * i / h.sampleRate);
            }
            return std::hypot(re, im);
        };
        ConvolverIr out;
        std::string error;
        REQUIRE(NativeBmwDsp::resampleConvolverIr(ir, 48000.0, out, error));
        CAPTURE(rate);
        CHECK(linToDb(response(out, 1000.0)) == doctest::Approx(linToDb(response(ir, 1000.0))).epsilon(0.001));
    }
    ConvolverIr tooFast = deltaIr(1.0, 0, 768000.0);
    ConvolverIr out;
    std::string error;
    CHECK_FALSE(NativeBmwDsp::resampleConvolverIr(tooFast, 48000.0, out, error));
}

TEST_CASE("convolver: a load racing a sample-rate change ends at the current rate") {
    for (int round = 0; round < 20; ++round) {
        NativeBmwDspProcessor proc;
        proc.setSampleRate(48000.f);
        std::string error;
        std::thread loader([&] { proc.loadConvolverIr(randomIr(4000, true, round, 44100.0), error); });
        proc.setSampleRate(round % 2 ? 44100.f : 32000.f);
        loader.join();
        const auto info = proc.convolverInfo();
        CAPTURE(round);
        REQUIRE(info.loaded);
        CHECK(info.streamRate == (round % 2 ? 44100.0 : 32000.0));
        CHECK(info.streamFrames > 0);
    }
}

TEST_CASE("convolver: a maximum-length IR loads on a small-stack thread") {
    // Android control threads have ~1 MiB stacks: nothing on the load path may put the large
    // FFT scratch (1 MiB for the peak-response transform of a 65536-tap IR) on the stack.
    struct Job {
        NativeBmwDspProcessor proc;
        bool ok = false;
    } job;
    job.proc.setSampleRate(kSampleRate);
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 512 * 1024);
    pthread_t thread;
    REQUIRE(pthread_create(&thread, &attr, [](void* arg) -> void* {
        auto* j = static_cast<Job*>(arg);
        std::string error;
        j->ok = j->proc.loadConvolverIr(randomIr(NativeBmwDsp::kConvolverMaxIrFrames, true, 77), error);
        return nullptr;
    }, &job) == 0);
    pthread_join(thread, nullptr);
    pthread_attr_destroy(&attr);
    CHECK(job.ok);
    CHECK(job.proc.convolverInfo().partitions == NativeBmwDsp::kConvolverMaxIrFrames / 64);
}

TEST_CASE("convolver: resampling keeps the filter's ringing past the last tap") {
    // A tap at the very end of a 24 kHz IR: upsampled to 48 kHz its band-limited ringing runs on
    // past the source's last sample, and cutting the output at the source length dropped it.
    ConvolverIr ir = deltaIr(1.0, 999, 24000.0);
    ConvolverIr out;
    std::string error;
    REQUIRE(NativeBmwDsp::resampleConvolverIr(ir, 48000.0, out, error));
    CHECK(out.frames() > 2000);
    double sum = 0.0;
    for (double v : out.left) sum += v;
    // Its DC gain is the tap's (1.0) up to the sinc's acausal half, which lies inside the IR here.
    CHECK(sum == doctest::Approx(1.0).epsilon(1e-3));
}

TEST_CASE("convolver: the measured IR peak catches a resonance between FFT bins") {
    // A 1000-tap tone burst whose frequency sits half-way between the bins of a 2x-padded FFT:
    // the cap is 1 / peak, so an under-read peak lets the stage lift that frequency above unity.
    // The true peak, from a dense direct scan around the tone, must be read within 0.1 dB.
    ConvolverIr ir;
    ir.sampleRate = 48000.0;
    ir.channels = 1;
    ir.left.resize(1000);
    const double f0 = 48000.0 * 21.5 / 2048.0;
    for (std::size_t n = 0; n < ir.left.size(); ++n) ir.left[n] = 0.01 * std::sin(2.0 * kPi * f0 * n / 48000.0);
    double truePeak = 0.0;
    for (double f = f0 - 60.0; f <= f0 + 60.0; f += 0.05) {
        double re = 0.0, im = 0.0;
        for (std::size_t n = 0; n < ir.left.size(); ++n) {
            re += ir.left[n] * std::cos(2.0 * kPi * f * n / 48000.0);
            im -= ir.left[n] * std::sin(2.0 * kPi * f * n / 48000.0);
        }
        truePeak = std::max(truePeak, std::hypot(re, im));
    }
    CHECK(NativeBmwDsp::convolverIrPeakGainDb(ir) == doctest::Approx(linToDb(truePeak)).epsilon(0.1 / std::fabs(linToDb(truePeak))));
    CHECK(NativeBmwDsp::convolverIrPeakGainDb(ir) <= linToDb(truePeak) + 1e-9);
    CHECK(NativeBmwDsp::convolverIrPeakGainDb(ir) > linToDb(truePeak) - 0.1);
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

TEST_CASE("convolver: an IR that raises peaks through phase alone is held by the master limiter") {
    // A chirp all-pass FIR: flat magnitude (so the unity cap leaves it alone), group delay swept
    // 0 .. N/2 across the band. The input is that chirp time-reversed, repeated: the IR folds it
    // back into a train of clicks, raising the crest factor by more than 20 dB without raising
    // the magnitude anywhere. Ahead of the master limiter, those peaks must still be held at its
    // threshold.
    constexpr std::size_t N = 2048;
    ConvolverIr ir;
    ir.sampleRate = kSampleRate;
    ir.channels = 1;
    ir.left.assign(N, 0.0);
    for (std::size_t n = 0; n < N; ++n) {
        double acc = 1.0;  // DC, phase 0
        for (std::size_t k = 1; k < N / 2; ++k) {
            const double phase = -kPi * static_cast<double>(k * k) / static_cast<double>(N);
            acc += 2.0 * std::cos(2.0 * kPi * static_cast<double>(k * n) / N + phase);
        }
        acc += std::cos(kPi * static_cast<double>(n) - kPi * N / 4.0);  // Nyquist bin, real
        ir.left[n] = acc / N;
    }
    double tapPeak = 0.0, energy = 0.0;
    for (double v : ir.left) {
        tapPeak = std::max(tapPeak, std::fabs(v));
        energy += v * v;
    }
    CHECK(energy == doctest::Approx(1.0).epsilon(1e-6));
    // Flat to within the ripple between the DFT bins (the cap takes ~2 dB off), while folding
    // the reversed chirp back up raises its peak by energy / tap peak: over 20 dB.
    CHECK(std::fabs(NativeBmwDsp::convolverIrPeakGainDb(ir)) < 3.0);
    CHECK(linToDb(energy / tapPeak) > 20.0);

    // The time-reversed chirp, every 4096 frames, at ~-12 dBFS sample peak.
    constexpr std::size_t period = 4096, frames = 24 * period;
    const double scale = 0.25 / tapPeak;
    std::vector<float> input(2 * frames, 0.f);
    for (std::size_t f = 0; f < frames; ++f) {
        const std::size_t m = f % period;
        const float s = m < N ? static_cast<float>(scale * ir.left[N - 1 - m]) : 0.f;
        input[2 * f] = s;
        input[2 * f + 1] = s;
    }
    auto run = [&](bool limiter) {
        auto c = config(true, 0.f);
        c[5] = 0.f;                               // headroom 0 dB
        c[8] = c[9] = 0.f;                        // mid gain 0 dB
        c[25] = 0.f;                              // tilt off
        c[28] = c[35] = 0.f;                      // legacy global compressors off
        c[93] = c[106] = c[119] = c[132] = 0.f;  // legacy per-output compressors off
        c[sch::kMasterLimiterEnabled] = limiter ? 1.f : 0.f;
        c[sch::kMasterLimiterThreshold] = -1.f;
        NativeBmwDspProcessor proc;
        proc.setSampleRate(kSampleRate);
        REQUIRE(proc.configure(c.data(), kConfigSize));
        std::string error;
        REQUIRE(proc.loadConvolverIr(ir, error));
        auto buf = input;
        processInBuffers(proc, buf, 480);
        float peak = 0.f;
        for (float v : buf) peak = std::max(peak, std::fabs(v));
        return peak;
    };
    const float threshold = static_cast<float>(std::pow(10.0, -1.0 / 20.0));
    const float unlimited = run(false);
    const float limited = run(true);
    INFO("peak, limiter off = ", unlimited, "  on = ", limited, "  threshold = ", threshold);
    // The IR really does raise the peaks past the threshold (the guard clips them at 1.0)...
    CHECK(unlimited > threshold * 1.1f);
    // ...and with the limiter on, nothing gets over it.
    CHECK(limited <= threshold * 1.001f);
    CHECK(limited > 0.5f * threshold);  // still limiting, not gating to silence
}

TEST_CASE("convolver: the IR's bulk latency is its largest sample, at the stream rate") {
    // Largest on the right channel, at 300: the latency is taken across both channels.
    ConvolverIr ir = deltaIr(0.5, 100);
    ir.left.resize(1000, 0.0);
    ir.right.assign(1000, 0.0);
    ir.right[300] = -0.9;
    ir.channels = 2;
    CHECK(NativeBmwDsp::convolverIrLatencyFrames(ir) == 300);
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    std::string error;
    REQUIRE(proc.loadConvolverIr(ir, error));
    auto info = proc.convolverInfo();
    CHECK(info.latencyFrames == 300);
    CHECK(info.latencyMs == doctest::Approx(300.0 * 1000.0 / 48000.0));
    // A 24 kHz stream: the kernel is rebuilt at half the rate, the peak lands at ~150.
    proc.setSampleRate(24000.f);
    info = proc.convolverInfo();
    CHECK(info.streamRate == 24000.0);
    CHECK(info.latencyFrames >= 149);
    CHECK(info.latencyFrames <= 151);
    CHECK(info.latencyMs == doctest::Approx(1000.0 * info.latencyFrames / 24000.0));
    proc.clearConvolverIr();
    info = proc.convolverInfo();
    CHECK(info.latencyFrames == 0);
    CHECK(info.latencyMs == 0.0);
}
