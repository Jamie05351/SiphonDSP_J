#ifndef SIPHONDSP_NATIVE_BMW_DSP_PARAMS_H
#define SIPHONDSP_NATIVE_BMW_DSP_PARAMS_H

// The processor's parsed scalar config (everything configure() reads that isn't per-output).

#include "NativeBmwDynamics.h"

namespace NativeBmwDsp {

struct Params {
    bool enabled = true, lpfPass = false, hpfPass = false, tilt = true;
    int channelMute = 0, measurementMute = 0;
    float headroom = -6, lowGainL = 0, lowGainR = 0, midGainL = -1, midGainR = -1,
          postGainL = 0, postGainR = 0;
    float midDelayL = 0, midDelayR = 0, lowDelayL = 0, lowDelayR = 0;
    // High band (v[210..214], added in the 210 -> 262 growth). Unlike lpfPass/hpfPass
    // (which only bypass the crossover *filter*, letting the raw routed signal through --
    // see processFrame()'s own comment on this for Low/Mid), highXoPass=true fully silences
    // High: a tweeter with no HPF ahead of it is a real speaker-damage risk from raw bass,
    // not just an audio-quality one, so this flag alone is sufficient to silence it, with no
    // reliance on outputConfigs_[High*].muted also being set correctly. See
    // docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    bool highXoPass = false;
    float highGainL = 0, highGainR = 0, highDelayL = 0, highDelayR = 0;
    // Stage-centering L/R alignment delay (ms), applied to the summed stereo bus after the
    // master limiter -- the last thing before the deliberate hardware L/R swap. A separate
    // correction layer from the per-output crossover/driver-alignment delays above (v[141]
    // / v[142]). Clamped to kStageDelayMaxMs.
    float stageDelayL = 0, stageDelayR = 0;
    float tiltAmount = 3, tiltFreq = 550;
    // Octaves to shift the measurement-mute bus brick-wall off the crossover, into the
    // stopband (v[139]). Default matches NativeBmwDspValues.DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES.
    float measBusStopbandOctaves = 1;
    // Measurement signal generator (v[192..199]). 0 = off; nonzero replaces the real input
    // entirely, upstream of the capture tap -- see applyMeasurementGenerator()
    // (NativeBmwMeasurement.h). Ships off.
    int measGenType = 0;
    float measGenSweepStartHz = 20, measGenSweepEndHz = 20000, measGenSweepDurationS = 10,
          measGenSweepLevelDb = -12;
    float measGenPinkPeriodS = 2, measGenPinkLevelDb = -12;
    // v[199..204]: only meaningful while measGenType == 1. Wraps the sweep in REW's Acoustic
    // Timing Reference cycle (Mid sweep, Low sweep, Mid sweep, bracketed by timing chirps)
    // instead of the bare continuously-looping single sweep -- see
    // NativeBmwMeasurementGenerator::configureTimingRef()/nextTimingRefSample().
    bool measGenTimingRefEnabled = false;
    bool measGenTimingRefSplitChannels = false;
    float measGenTimingRefMidStartHz = 100, measGenTimingRefMidEndHz = 20000;
    float measGenTimingRefLowStartHz = 20, measGenTimingRefLowEndHz = 400;
    // Pre-crossover multiband compressor (v[144..180]). Ships disabled.
    bool mbcEnabled = false;
    float mbcMix = 1.f;  // 0..1 dry/wet (v[145] is percent)
    float mbcXo[3] = {80.f, 500.f, 4000.f};
    MbcBands mbcBand;
    // Per-bus output limiter (Low/Mid v[182..187], High v[262..264]). Additive to -- not
    // a replacement for -- the per-output processCompressor path. Ships disabled.
    bool busLimLowEnabled = false, busLimMidEnabled = false, busLimHighEnabled = false;
    float busLimLowThreshDb = -3.f, busLimLowReleaseMs = 120.f;
    float busLimMidThreshDb = -3.f, busLimMidReleaseMs = 120.f;
    float busLimHighThreshDb = -3.f, busLimHighReleaseMs = 120.f;
    // Master brick-wall limiter on the summed output. enabled == false is a true bypass
    // (MasterLimiter::process is not called); the threshold defaults to -1 dBFS, the fixed
    // ceiling this stage always used before it was made adjustable.
    bool limiterEnabled = true;
    float limiterThreshDb = -1.f;
};

}  // namespace NativeBmwDsp

#endif
