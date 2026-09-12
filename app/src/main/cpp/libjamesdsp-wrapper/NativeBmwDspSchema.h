// The flat float[] layout that Kotlin (NativeBmwDspValues) marshals through configureNativeBmwDsp
// and NativeBmwDspProcessor::configure() reads back. The indices below MUST match
// NativeBmwDspValues.kt's INDEX_* / FIELD_* / MBC_FIELD_* constants -- that agreement is enforced
// two ways:
//
//   * NativeBmwSchemaAgreementTest.kt (JVM) parses this header and asserts every value equals the
//     matching NativeBmwDspValues constant, and that kSize == SIZE == DEFAULTS.size.
//   * schema_agreement_test.cpp (native-tests) static_asserts the structural values against
//     NativeBmwDspProcessor::kConfigSize and probes the consequential fields behaviourally --
//     set slot N, configure(), observe the effect -- so the header index is proven to be the one
//     configure() actually consumes.
//
// Slots configure() deliberately never reads (Kotlin-only migration markers, reserved words, the
// global crossover/mute/invert/subsonic mirrors that the per-output block supersedes, the legacy
// global compressor tuple, FIELD_CROSSOVER_LR4) are not listed here.
#ifndef SIPHONDSP_NATIVE_BMW_DSP_SCHEMA_H
#define SIPHONDSP_NATIVE_BMW_DSP_SCHEMA_H

#include <cstddef>

namespace nbschema {

inline constexpr std::size_t kSize = 215;  // == NativeBmwDspValues.SIZE

// --- global scalars -------------------------------------------------------------------------
inline constexpr int kEnabled = 0;
inline constexpr int kLpfPass = 1;
inline constexpr int kHpfPass = 2;
inline constexpr int kChannelMute = 3;
inline constexpr int kMeasurementMute = 4;
inline constexpr int kHeadroom = 5;
inline constexpr int kLowGainL = 6;
inline constexpr int kLowGainR = 7;
inline constexpr int kMidGainL = 8;
inline constexpr int kMidGainR = 9;
inline constexpr int kPostGainL = 10;
inline constexpr int kPostGainR = 11;

inline constexpr int kMidDelayL = 21;
inline constexpr int kMidDelayR = 22;
inline constexpr int kLowDelayL = 23;
inline constexpr int kLowDelayR = 24;

inline constexpr int kTiltEnabled = 25;
inline constexpr int kTiltAmount = 26;
inline constexpr int kTiltFreq = 27;

// 42..45, formerly Mono Bass enabled/freq/blend/makeup, were removed (unused feature, no native
// processing left) and are not listed here -- configure() no longer reads them.

// --- routing matrix: 4 outputs (Low L, Low R, Mid L, Mid R) x [fromFrontL, fromFrontR] -----
inline constexpr int kRoutingBase = 46;
inline constexpr int kRoutingStride = 2;

// --- output all-pass: 4 outputs x kAllPassSectionsPerOutput x [enabled, order, freq, q] -----
inline constexpr int kAllPassBase = 54;
inline constexpr int kAllPassSectionWidth = 4;
inline constexpr int kAllPassSectionsPerOutput = 2;

// --- per-output config: 4 outputs x kOutputConfigWidth, from kOutputConfigBase --------------
inline constexpr int kOutputConfigBase = 87;
inline constexpr int kOutputConfigWidth = 13;
// field offsets within one output's block:
inline constexpr int kOutCrossoverFreq = 0;
// 1 = FIELD_CROSSOVER_LR4, never read (always LR4)
inline constexpr int kOutSubsonicEnabled = 2;
inline constexpr int kOutSubsonicFreq = 3;
inline constexpr int kOutMuted = 4;
inline constexpr int kOutPolarityInverted = 5;
inline constexpr int kOutCompressor = 6;  // start of the 7-value compressor tuple

// --- measurement-mute bus brick-wall stopband offset (octaves) -----------------------------
inline constexpr int kMeasMuteStopbandOctaves = 139;
// 140 is a Kotlin-only migration marker (0 unseeded / 1 meas-mute seeded / 2 stage-delay
// reclaim done). 141/142, formerly a removed Mid-band LPF, are now the stage-centering L/R
// alignment delay (ms) applied to the summed stereo bus after the master limiter.
inline constexpr int kStageDelayLeftMs = 141;
inline constexpr int kStageDelayRightMs = 142;

// --- multiband compressor -----------------------------------------------------------------
inline constexpr int kMbcEnabled = 144;
inline constexpr int kMbcMix = 145;
inline constexpr int kMbcXo0 = 146;
inline constexpr int kMbcXo1 = 147;
inline constexpr int kMbcXo2 = 148;
inline constexpr int kMbcBandsBase = 149;
inline constexpr int kMbcBandWidth = 8;
inline constexpr int kMbcBandCount = 4;
// field offsets within one MBC band's block:
inline constexpr int kMbcBandEnabled = 0;
inline constexpr int kMbcBandThreshold = 1;
inline constexpr int kMbcBandRatio = 2;
inline constexpr int kMbcBandKnee = 3;
inline constexpr int kMbcBandAttack = 4;
inline constexpr int kMbcBandRelease = 5;
inline constexpr int kMbcBandMakeup = 6;
inline constexpr int kMbcBandStereoLink = 7;

// --- per-bus output limiter -------------------------------------------------------------
inline constexpr int kBusLimLowEnabled = 182;
inline constexpr int kBusLimLowThreshold = 183;
inline constexpr int kBusLimLowRelease = 184;
inline constexpr int kBusLimMidEnabled = 185;
inline constexpr int kBusLimMidThreshold = 186;
inline constexpr int kBusLimMidRelease = 187;

// --- master brick-wall limiter (189/190; 191 is a Kotlin-only migration marker) ------------
inline constexpr int kMasterLimiterEnabled = 189;
inline constexpr int kMasterLimiterThreshold = 190;

// --- subharmonic synthesizer -- global enable/ceiling, then 3 bands x kSubBandWidth --------
inline constexpr int kSubEnabled = 192;
inline constexpr int kSubCeilingDb = 193;
inline constexpr int kSubBandsBase = 194;
inline constexpr int kSubBandWidth = 7;
inline constexpr int kSubBandCount = 3;
// field offsets within one band's block:
inline constexpr int kSubBandEnabled = 0;
inline constexpr int kSubBandFreqLo = 1;
inline constexpr int kSubBandFreqHi = 2;
inline constexpr int kSubBandLevelDb = 3;
inline constexpr int kSubBandGateMode = 4;
inline constexpr int kSubBandGateDepthPct = 5;
inline constexpr int kSubBandGateHoldMs = 6;

}  // namespace nbschema

#endif  // SIPHONDSP_NATIVE_BMW_DSP_SCHEMA_H
