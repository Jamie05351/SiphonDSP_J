// RealtimeSanitizer check for the audio path (Clang 20+, -fsanitize=realtime).
//
// An "audio" thread runs NativeBmwDspProcessor::process() inside a [[clang::nonblocking]]
// function, so RTSan aborts on any allocation, deallocation, lock, sleep or other blocking call
// made anywhere under it. Meanwhile the main thread does what the app's control side does while
// audio plays: config and PEQ pushes (every optional stage switched on in turn), truth snapshots,
// and capture start / stop / take -- the requests the audio thread serves itself.
//
// Exit 0: nothing blocking ran on the audio thread. RTSan prints the offending call and stack and
// exits non-zero otherwise.
// Standalone (no doctest runner): only the helpers from test_support.h.
#define DOCTEST_CONFIG_DISABLE
#include "test_support.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

using namespace nbtest;
namespace sch = nbschema;

namespace {
std::atomic<bool> stopAudio{false};
std::atomic<std::uint64_t> buffers{0};

void processFloat(NativeBmwDspProcessor& proc, std::vector<float>& buf) noexcept [[clang::nonblocking]] {
    proc.process(buf.data(), buf.size());
}
void processInt16(NativeBmwDspProcessor& proc, std::vector<std::int16_t>& buf) noexcept [[clang::nonblocking]] {
    proc.process(buf.data(), buf.size());
}

void waitBuffers(std::uint64_t n) {
    const auto until = buffers.load() + n;
    while (buffers.load() < until) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

// Every optional stage on, so its process path runs under the sanitizer.
std::array<float, kConfigSize> everythingOn() {
    auto c = defaultConfig();
    c[sch::kMbcEnabled] = 1.f;
    for (int b = 0; b < sch::kMbcBandCount; ++b) {
        c[sch::kMbcBandsBase + b * sch::kMbcBandWidth + sch::kMbcBandEnabled] = 1.f;
    }
    c[sch::kBusLimLowEnabled] = c[sch::kBusLimMidEnabled] = c[sch::kBusLimHighEnabled] = 1.f;
    c[sch::kMasterLimiterEnabled] = 1.f;
    c[sch::kTiltEnabled] = 1.f;
    c[sch::kVirtualEnabled] = 1.f;
    c[sch::kMonoBassEnabled] = 1.f;
    c[sch::kVirtualBassEnabled] = 1.f;
    c[sch::kDeepBassEnabled] = 1.f;
    c[sch::kMasterVolumeDb] = -20.f;
    c[sch::kHighXoPass] = 0.f;
    c[sch::kConvolverEnabled] = 1.f;
    c[sch::kConvolverGain] = -3.f;
    return c;
}
}  // namespace

int main() {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    if (!proc.configure(cfg.data(), cfg.size())) {
        std::puts("default config rejected");
        return 2;
    }

    std::thread audio([&] {
        std::vector<float> f = stereoSine(220.0, 0.5, 512, kSampleRate);
        std::vector<std::int16_t> s(f.size());
        for (std::size_t i = 0; i < f.size(); ++i) {
            s[i] = static_cast<std::int16_t>(f[i] * 16000.f);
        }
        bool useInt = false;
        while (!stopAudio.load()) {
            if (useInt) {
                processInt16(proc, s);
            } else {
                processFloat(proc, f);
            }
            useInt = !useInt;
            buffers.fetch_add(1);
            std::this_thread::sleep_for(std::chrono::microseconds(300));
        }
    });
    waitBuffers(8);

    // Config and PEQ pushes, installed by the audio thread.
    auto on = everythingOn();
    if (!proc.configure(on.data(), on.size())) {
        std::puts("everything-on config rejected");
        return 2;
    }
    const double bands[10] = {80.0, 4.0, 1.0, 0.0, 0.0, 3000.0, -3.0, 2.0, 0.0, 1.0};
    proc.configurePeq(true, -3.f, bands, 10, bands, 5, bands, 10, bands, 5);
    waitBuffers(32);

    // Each measurement generator.
    for (float type : {1.f, 2.f, 0.f}) {
        on[sch::kMeasGenType] = type;
        proc.configure(on.data(), on.size());
        waitBuffers(16);
    }

    // Convolver IRs loaded, swapped (crossfaded on the audio thread), toggled and unloaded: the
    // kernels are built here and freed here, never on the audio thread.
    for (int i = 0; i < 6; ++i) {
        NativeBmwDsp::ConvolverIr ir;
        ir.sampleRate = (i % 2 == 0) ? 48000.0 : 44100.0;  // odd loads go through the resampler
        ir.channels = 1 + i % 2;
        ir.left.assign(500 + 700 * static_cast<std::size_t>(i), 0.0);
        ir.left[0] = 0.5;
        if (ir.channels == 2) {
            ir.right = ir.left;
        }
        std::string error;
        if (!proc.loadConvolverIr(std::move(ir), error)) {
            std::printf("convolver IR rejected: %s\n", error.c_str());
            return 2;
        }
        waitBuffers(12);
        on[sch::kConvolverEnabled] = (i % 3 == 2) ? 0.f : 1.f;
        proc.configure(on.data(), on.size());
        waitBuffers(12);
    }
    proc.clearConvolverIr();
    waitBuffers(12);

    // Control requests the audio thread serves.
    for (int i = 0; i < 3; ++i) {
        const auto truth = proc.captureTruthSnapshot();
        if (truth.empty()) {
            std::puts("empty truth snapshot");
            return 2;
        }
        proc.startCapture();
        waitBuffers(8);
        proc.stopCapture();
        auto take = proc.takeCaptureSnapshot();
        if (!take || take->frames == 0) {
            std::puts("empty capture");
            return 2;
        }
    }

    stopAudio.store(true);
    audio.join();
    std::printf("rtsan_check: %llu buffers, nothing blocking on the audio thread\n",
                static_cast<unsigned long long>(buffers.load()));
    return 0;
}
