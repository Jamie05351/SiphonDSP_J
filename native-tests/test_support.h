// Shared helpers for the host-side NativeBmwDspProcessor tests: the canonical default config
// (loaded from native-tests/default_config.txt), signal generation, and a windowed
// single-frequency magnitude probe for LTI-flatness checks.
#pragma once

#include "doctest/doctest.h"

#include "NativeBmwDspProcessor.h"
#include "NativeBmwDspSchema.h"

#include <array>
#include <cmath>
#include <cstddef>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

// Absolute path to native-tests/default_config.txt, baked in by CMakeLists.txt. The fallback
// keeps a bare compile (no -D) working when the binary is run from the repo root.
#ifndef NBTEST_DEFAULT_CONFIG_PATH
#define NBTEST_DEFAULT_CONFIG_PATH "native-tests/default_config.txt"
#endif

namespace nbtest {

constexpr double kPi = 3.14159265358979323846;
constexpr float kSampleRate = 48000.0f;
constexpr std::size_t kConfigSize = nbschema::kSize;  // == NativeBmwDspValues.SIZE
static_assert(kConfigSize == NativeBmwDspProcessor::kConfigSize,
              "NativeBmwDspSchema.h kSize is out of step with NativeBmwDspProcessor::kConfigSize");

// --- config -------------------------------------------------------------------------------

// The canonical default config, parsed from native-tests/default_config.txt -- the single
// source of truth shared with Kotlin. One numeric value per line; '#' starts a comment; blank
// lines ignored. NativeBmwSchemaAgreementTest.nativeTestDefaultConfigMatchesKotlinDefaults()
// asserts that same file equals NativeBmwDspValues.DEFAULTS, so this stays in step with the
// Kotlin side without a hand-transcribed copy here.
inline std::array<float, kConfigSize> defaultConfig() {
    std::ifstream in(NBTEST_DEFAULT_CONFIG_PATH);
    if (!in.is_open()) {
        throw std::runtime_error(
            std::string("cannot open canonical default config: ") + NBTEST_DEFAULT_CONFIG_PATH);
    }
    std::array<float, kConfigSize> cfg{};
    std::size_t count = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (const auto hash = line.find('#'); hash != std::string::npos) {
            line.erase(hash);
        }
        const auto first = line.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) {
            continue;
        }
        const auto last = line.find_last_not_of(" \t\r\n");
        if (count >= kConfigSize) {
            throw std::runtime_error("canonical default config has more than the expected value count");
        }
        cfg[count++] = std::stof(line.substr(first, last - first + 1));
    }
    if (count != kConfigSize) {
        throw std::runtime_error("canonical default config has " + std::to_string(count) +
                                 " values, expected " + std::to_string(kConfigSize));
    }
    return cfg;
}

// --- signal generation ------------------------------------------------------------------

// Interleaved stereo (L,R,L,R,...) sine, same content on both channels.
inline std::vector<float> stereoSine(double freqHz, double amplitude, std::size_t frames,
                                     double sampleRate = kSampleRate) {
    std::vector<float> buf(frames * 2);
    const double w = 2.0 * kPi * freqHz / sampleRate;
    for (std::size_t n = 0; n < frames; ++n) {
        const float s = static_cast<float>(amplitude * std::sin(w * static_cast<double>(n)));
        buf[2 * n] = s;
        buf[2 * n + 1] = s;
    }
    return buf;
}

// Interleaved stereo sine with independent per-channel phase -- a decorrelated (non-mono)
// bass signal, which is what exposes the Mono Bass Mid-compensation.
inline std::vector<float> stereoSinePhased(double freqHz, double amplitude, std::size_t frames,
                                           double phaseL, double phaseR,
                                           double sampleRate = kSampleRate) {
    std::vector<float> buf(frames * 2);
    const double w = 2.0 * kPi * freqHz / sampleRate;
    for (std::size_t n = 0; n < frames; ++n) {
        buf[2 * n] = static_cast<float>(amplitude * std::sin(w * static_cast<double>(n) + phaseL));
        buf[2 * n + 1] = static_cast<float>(amplitude * std::sin(w * static_cast<double>(n) + phaseR));
    }
    return buf;
}

// --- measurement ----------------------------------------------------------------------

inline float peakAbs(const std::vector<float>& interleaved) {
    float pk = 0.f;
    for (float v : interleaved) pk = std::max(pk, std::fabs(v));
    return pk;
}

// Hann-windowed DFT bin magnitude of one channel of an interleaved buffer, in linear units,
// scaled so a pure sine of amplitude A at freqHz reads back ~A.
inline double channelMagnitudeAt(const std::vector<float>& interleaved, int channel,
                                 double freqHz, double sampleRate = kSampleRate) {
    const std::size_t frames = interleaved.size() / 2;
    double re = 0.0, im = 0.0, winSum = 0.0;
    const double w = 2.0 * kPi * freqHz / sampleRate;
    for (std::size_t n = 0; n < frames; ++n) {
        const double hann = 0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(n) /
                                                 static_cast<double>(frames - 1));
        const double x = interleaved[2 * n + channel] * hann;
        re += x * std::cos(w * static_cast<double>(n));
        im -= x * std::sin(w * static_cast<double>(n));
        winSum += hann;
    }
    return 2.0 * std::sqrt(re * re + im * im) / winSum;
}

inline double linToDb(double lin) { return 20.0 * std::log10(std::max(lin, 1e-12)); }

// Configure `proc`, run `warmupFrames` of the signal to flush filter/smoothing transients,
// then process `measureFrames` more and return that trailing window (interleaved).
inline std::vector<float> renderSteadyState(NativeBmwDspProcessor& proc,
                                            const std::array<float, kConfigSize>& cfg,
                                            double freqHz, double amplitude,
                                            std::size_t warmupFrames = 24000,
                                            std::size_t measureFrames = 16384) {
    proc.setSampleRate(kSampleRate);
    REQUIRE(proc.configure(cfg.data(), cfg.size()));

    std::vector<float> warm = stereoSine(freqHz, amplitude, warmupFrames);
    proc.process(warm.data(), warm.size());

    std::vector<float> window = stereoSine(freqHz, amplitude, measureFrames);
    proc.process(window.data(), window.size());
    return window;
}

}  // namespace nbtest
