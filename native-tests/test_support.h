// Shared helpers for the host-side NativeBmwDspProcessor tests: a canonical default config
// (kept in step with NativeBmwDspValues.DEFAULTS), signal generation, and a windowed
// single-frequency magnitude probe for LTI-flatness checks.
#pragma once

#include "doctest/doctest.h"

#include "NativeBmwDspProcessor.h"
#include "NativeBmwDspSchema.h"

#include <array>
#include <cmath>
#include <cstddef>
#include <vector>

namespace nbtest {

constexpr double kPi = 3.14159265358979323846;
constexpr float kSampleRate = 48000.0f;
constexpr std::size_t kConfigSize = nbschema::kSize;  // == NativeBmwDspValues.SIZE
static_assert(kConfigSize == NativeBmwDspProcessor::kConfigSize,
              "NativeBmwDspSchema.h kSize is out of step with NativeBmwDspProcessor::kConfigSize");

// --- config -------------------------------------------------------------------------------

// Byte-for-byte NativeBmwDspValues.DEFAULTS. If the Kotlin DEFAULTS change, this must follow;
// defaultConfigMatchesSchemaSize() in default_config_test.cpp guards the length at least.
inline std::array<float, kConfigSize> defaultConfig() {
    return {{
        // 0..4  enabled, lpfPass, hpfPass, channelMute, measurementMute
        1.f, 0.f, 0.f, 0.f, 0.f,
        // 5..11 headroom, lowGain L/R, midGain L/R, postGain L/R
        -6.f, 0.f, 0.f, -1.f, -1.f, 0.f, 0.f,
        // 12..13 subsonic enabled, freq
        1.f, 32.f,
        // 14..16 lowMute, lowXo, lowLr4
        0.f, 150.f, 0.f,
        // 17..18 midMute, midXo
        0.f, 150.f,
        // 19..20 lowInvert, midInvert
        0.f, 0.f,
        // 21..24 mid/low delay L/R
        0.f, 0.f, 0.f, 0.f,
        // 25..27 tilt enabled, amount, freq
        1.f, 3.f, 550.f,
        // 28..34 legacy low compressor 7-tuple (retired path)
        1.f, -12.f, 2.f, 8.f, 40.f, 250.f, 1.5f,
        // 35..41 legacy mid compressor 7-tuple
        0.f, -10.f, 1.5f, 6.f, 10.f, 180.f, 0.f,
        // 42..45 mono bass enabled, freq, blend %, makeup dB
        0.f, 80.f, 100.f, 0.f,
        // 46..53 routing Low L / Low R / Mid L / Mid R x [FrontL, FrontR]
        1.f, 0.f, 0.f, 1.f, 1.f, 0.f, 0.f, 1.f,
        // 54..85 eight all-pass sections x [enabled, order, freq, q]
        0.f, 2.f, 150.f, 0.70710677f, 0.f, 2.f, 150.f, 0.70710677f,
        0.f, 2.f, 150.f, 0.70710677f, 0.f, 2.f, 150.f, 0.70710677f,
        0.f, 2.f, 150.f, 0.70710677f, 0.f, 2.f, 150.f, 0.70710677f,
        0.f, 2.f, 150.f, 0.70710677f, 0.f, 2.f, 150.f, 0.70710677f,
        // 86 output schema marker
        0.f,
        // 87..99 Low Left: xo, lr4, sub on, sub Hz, mute, invert, comp 7-tuple
        150.f, 0.f, 1.f, 32.f, 0.f, 0.f, 1.f, -12.f, 2.f, 8.f, 40.f, 250.f, 1.5f,
        // 100..112 Low Right
        150.f, 0.f, 1.f, 32.f, 0.f, 0.f, 1.f, -12.f, 2.f, 8.f, 40.f, 250.f, 1.5f,
        // 113..125 Mid Left
        150.f, 1.f, 0.f, 32.f, 0.f, 0.f, 0.f, -10.f, 1.5f, 6.f, 10.f, 180.f, 0.f,
        // 126..138 Mid Right
        150.f, 1.f, 0.f, 32.f, 0.f, 0.f, 0.f, -10.f, 1.5f, 6.f, 10.f, 180.f, 0.f,
        // 139..142 meas-mute stopband oct, migration marker, 2x inert
        1.f, 1.f, 0.f, 0.f,
        // 143 link L/R delay (UI only)
        0.f,
        // 144..148 MBC enabled, mix %, xo0, xo1, xo2
        0.f, 100.f, 120.f, 500.f, 4000.f,
        // 149..180 MBC bands 0..3 x [enabled, threshold, ratio, knee, attack, release, makeup, stereoLink]
        0.f, -24.f, 2.f, 6.f, 15.f, 150.f, 0.f, 1.f,
        0.f, -20.f, 2.f, 6.f, 20.f, 180.f, 0.f, 1.f,
        0.f, -18.f, 2.f, 6.f, 15.f, 150.f, 0.f, 1.f,
        0.f, -24.f, 2.f, 6.f, 5.f, 80.f, 0.f, 1.f,
        // 181 MBC/limiter migration marker
        0.f,
        // 182..187 Low bus / Mid bus limiter x [enabled, threshold dBFS, release ms]
        0.f, -3.f, 120.f, 0.f, -3.f, 120.f,
        // 188..191 legacy-comp-disabled marker + 3 reserved
        0.f, 0.f, 0.f, 0.f,
    }};
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
