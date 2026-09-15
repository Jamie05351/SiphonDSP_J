#include "test_support.h"
#include "../app/src/main/cpp/libjdspimptoolbox/dr_wav.h"

#include <chrono>
#include <filesystem>

namespace {
struct CaptureFiles {
    std::filesystem::path dir = std::filesystem::temp_directory_path() /
        ("siphondsp-capture-test-" + std::to_string(
            std::chrono::steady_clock::now().time_since_epoch().count()));
    CaptureFiles() { REQUIRE(std::filesystem::create_directory(dir)); }
    ~CaptureFiles() { std::error_code ec; std::filesystem::remove_all(dir, ec); }
    std::string input() const { return (dir / "input.wav").string(); }
    std::string output() const { return (dir / "output.wav").string(); }
};

void checkWav(const std::string& path, float sampleRate, const std::vector<float>& expected) {
    drwav wav{};
    REQUIRE(drwav_init_file(&wav, path.c_str(), nullptr));
    CHECK(wav.channels == 2);
    CHECK(wav.sampleRate == static_cast<unsigned>(sampleRate));
    CHECK(wav.totalPCMFrameCount == expected.size() / 2);
    std::vector<float> actual(expected.size());
    CHECK(drwav_read_pcm_frames_f32(&wav, expected.size() / 2, actual.data()) == expected.size() / 2);
    drwav_uninit(&wav);
    CHECK(actual == expected);
}
}

TEST_CASE("detached capture exports correctly after the processor is destroyed") {
    std::unique_ptr<NativeBmwDspProcessor::CaptureSnapshot> take;
    const std::vector<float> input{.1f, -.2f, .3f, -.4f, .2f, -.1f};
    auto output = input;
    {
        NativeBmwDspProcessor processor;
        const auto config = nbtest::defaultConfig();
        REQUIRE(processor.configure(config.data(), config.size()));
        processor.startCapture();
        processor.process(output.data(), output.size());
        take = processor.takeCaptureSnapshot();
    }
    REQUIRE(take);
    CHECK(take->frames == input.size() / 2);
    CaptureFiles files;
    NativeBmwDspProcessor::CaptureExportResult result;
    REQUIRE(take->exportWav(files.input().c_str(), files.output().c_str(), result));
    checkWav(files.input(), 48000.f, input);
    checkWav(files.output(), 48000.f, output);
    CHECK(result.peakInDb == doctest::Approx(20.f * std::log10(.4f)));
}

TEST_CASE("a new capture and sample-rate change cannot alter a detached take") {
    NativeBmwDspProcessor processor;
    const auto config = nbtest::defaultConfig();
    REQUIRE(processor.configure(config.data(), config.size()));
    processor.startCapture();
    std::vector<float> first{.1f, .2f, .3f, .4f};
    const auto expected = first;
    processor.process(first.data(), first.size());
    auto take = processor.takeCaptureSnapshot();
    REQUIRE(take);
    CHECK(processor.takeCaptureSnapshot()->frames == 0);
    processor.setSampleRate(44100.f);
    processor.startCapture();
    std::vector<float> second(200, -.5f);
    processor.process(second.data(), second.size());
    CHECK(processor.captureFrameCount() == 100);

    CaptureFiles files;
    NativeBmwDspProcessor::CaptureExportResult result;
    // A failed write can be retried without needing the engine or its lock.
    CHECK_FALSE(take->exportWav((files.dir / "missing" / "input.wav").string().c_str(),
                               files.output().c_str(), result));
    REQUIRE(take->exportWav(files.input().c_str(), files.output().c_str(), result));
    checkWav(files.input(), 48000.f, expected);
    CHECK(processor.captureFrameCount() == 100);
}
