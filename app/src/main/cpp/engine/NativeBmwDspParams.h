#ifndef SIPHONDSP_NATIVE_BMW_DSP_PARAMS_H
#define SIPHONDSP_NATIVE_BMW_DSP_PARAMS_H

// The processor's parsed scalar config (everything configure() reads that isn't per-output).

#include <cstdint>

#include "NativeBmwDynamics.h"
#include "NativeBmwVirtual.h"

namespace NativeBmwDsp {

struct Params {
    bool enabled = true, tilt = true;
    int channelMute = 0, measurementMute = 0;
    float headroom = -6, lowGainL = 0, lowGainR = 0, midGainL = -1, midGainR = -1,
          postGainL = 0, postGainR = 0;
    float midDelayL = 0, midDelayR = 0, lowDelayL = 0, lowDelayR = 0;
    // High band (v[210..214], added in the 210 -> 262 growth). highXoPass=true fully silences
    // High: a tweeter with no HPF ahead of it is a real speaker-damage risk from raw bass, so this
    // flag alone is sufficient to silence it, with no reliance on outputConfigs_[High*].muted
    // also being set correctly. See docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    bool highXoPass = false;
    // v[309]: the band split removed -- the input stages feed the summed-bus stages directly as
    // one full-range stereo path: no routing, crossovers, band EQ, all-pass, delay, gain,
    // polarity, mute or bus limiter, and no added latency. (Retired v[1], v[2] and v[291] were
    // per-band passthroughs that still split, delayed and summed the bands.) Effective value:
    // measurement neutral forces it on unless the crossover is allowed.
    bool crossoverBypass = false;
    // v[310..311]: measurement neutral and the stages kept running under it (kStage* bits).
    // Neutral is applied to a copy of the config before parsing (see neutralConfig()), so the
    // fields above already hold the effective, neutralised values; these three cover the stages
    // that are not config slots.
    bool measurementNeutral = false;
    std::uint32_t neutralAllow = 0;
    bool preEqOn = true, bandEqOn = true, dcBlockerOn = true;
    // v[292..293]: master volume -- the DSP as the car's volume control. Applied last, after
    // the limiter, ramped so a step never clicks.
    float masterVolumeDb = 0.f;
    bool masterMute = false;
    // Loudness compensation (v[294..295]): on/off and strength 0..1 (v[295] is percent).
    bool loudnessEnabled = false;
    float loudnessStrength = 1.f;
    // Mono Bass (v[297..298]): on/off and the split frequency, Hz.
    bool monoBassEnabled = false;
    float monoBassFreq = 90.f;
    // Virtual Bass (v[299..302]): on/off, cut Hz, harmonics level 0..1 (v[301] is percent), and
    // whether the original sub below the cut is high-passed away.
    bool virtualBassEnabled = false;
    float virtualBassFreq = 50.f;
    float virtualBassAmount = .5f;
    bool virtualBassRemoveSub = false;
    // v[303]: the harmonic mix, Warm 0 .. Punchy 1 (v[303] is percent).
    float virtualBassCharacter = .5f;
    // v[305]: harmonics kept up to this x the cut; v[306]: detector release, ms.
    float virtualBassRange = 5.f;
    float virtualBassResponseMs = 200.f;
    // Convolver (v[307..308]): on/off (off is a crossfaded bypass) and the IR gain trim, dB.
    bool convolverEnabled = false;
    float convolverGainDb = 0.f;
    float highGainL = 0, highGainR = 0, highDelayL = 0, highDelayR = 0;
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
    // Per-bus output limiter (Low/Mid v[182..187], High v[262..264]). Ships disabled.
    bool busLimLowEnabled = false, busLimMidEnabled = false, busLimHighEnabled = false;
    float busLimLowThreshDb = -3.f, busLimLowReleaseMs = 120.f;
    float busLimMidThreshDb = -3.f, busLimMidReleaseMs = 120.f;
    float busLimHighThreshDb = -3.f, busLimHighReleaseMs = 120.f;
    // Master brick-wall limiter on the summed output. enabled == false is a true bypass
    // (MasterLimiter::process is not called); the threshold defaults to -1 dBFS, the fixed
    // ceiling this stage always used before it was made adjustable.
    bool limiterEnabled = true;
    float limiterThreshDb = -1.f;
    // Virtual-source stage (v[266..286]): the virtual centre and its per-side feeds, run between
    // the MBC and routing. Ships disabled; see docs/NATIVE_BMW_VIRTUAL_CHANNELS.md.
    VirtualConfig virtualStage;
};

}  // namespace NativeBmwDsp

#endif
