#ifndef SIPHONDSP_NATIVE_BMW_SELF_TEST_H
#define SIPHONDSP_NATIVE_BMW_SELF_TEST_H

// The measurement self-test: an impulse through an offline copy of the running processor, its
// response compared with a wire (unity gain, zero phase, zero latency, no crosstalk).
//
// The copy is a fresh NativeBmwDspProcessor built from what the live one last accepted (its
// config as sent, before measurement neutral is applied, its PEQ, its sample rate and its running
// IR), so it runs exactly the code and settings the car hears, on the calling thread, without
// touching the live audio. Only three things differ, so the result describes the chain and not
// the moment: the built-in signal generator and channel mute are off, and a master mute is lifted.
// The master volume runs as set and is divided back out: it is the only volume the car can change
// while playing, so it is reported, not counted as a deviation (and Deep bass, which follows it,
// is measured at the volume it really runs at).
//
// An impulse goes into Left, then (after a stream start) into Right, each through kSelfTestSeconds
// of the chain. Left in is compared with the car's Left speaker, which is the hardware output's
// right channel (see the deliberate swap in processFrameBack()). Over 20 Hz .. 20 kHz (or 0.45 x
// the rate, if lower) the test reports the worst magnitude deviation in dB, the worst phase
// deviation in degrees (bulk latency taken out first, and reported on its own), and the worst
// crosstalk. It passes ("verified") when the magnitude is within kSelfTestMagnitudeTolDb, the
// phase within kSelfTestPhaseTolDeg, the latency is 0 and the crosstalk is below
// kSelfTestCrosstalkDb.
//
// When it doesn't pass, every active stage is tried alone: the config with measurement neutral on
// and only that stage allowed (a band's own stages together with the crossover, and compared with
// the crossover alone). A stage that then alters the signal by more than the tolerances is listed
// in alteringStages. An impulse is a linear, low-level probe: the compressors, limiters and the
// virtual stages may be running without touching it, so they are listed in levelDependentStages
// whenever active. Allocates; never call it on the audio thread.

#include <cstdint>

#include "NativeBmwDspProcessor.h"

namespace NativeBmwDsp {

inline constexpr double kSelfTestMagnitudeTolDb = 0.01;
inline constexpr double kSelfTestPhaseTolDeg = 0.1;
inline constexpr double kSelfTestCrosstalkDb = -80.0;
inline constexpr double kSelfTestSeconds = 1.0;
inline constexpr double kSelfTestLowHz = 20.0;
inline constexpr double kSelfTestHighHz = 20000.0;

struct SelfTestResult {
    bool ran = false;
    bool verified = false;
    double sampleRate = 0.0;
    double lowHz = 0.0, highHz = 0.0;
    // Of the two direct paths (Left -> car Left, Right -> car Right), the larger.
    std::int64_t latencyFrames = 0;
    double magnitudeDevDb = 0.0;
    double phaseDevDeg = 0.0;
    // Worst crosstalk (one input into the other side), dB re the input; -inf as -400.
    double crosstalkDb = -400.0;
    // nbschema::kStage* bits.
    std::uint32_t activeStages = 0;
    std::uint32_t alteringStages = 0;
    std::uint32_t levelDependentStages = 0;
    // The chain doesn't pass, but no single stage alters it on its own (stages that only alter
    // it together, or something outside the stage list).
    bool unexplained = false;
    bool measurementNeutral = false;
    double masterVolumeDb = 0.0;
    double durationMs = 0.0;
};

SelfTestResult runSelfTest(const NativeBmwDspProcessor::SelfTestSource& source);

}  // namespace NativeBmwDsp

#endif
