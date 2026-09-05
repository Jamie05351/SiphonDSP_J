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

inline constexpr std::size_t kSize = 192;  // == NativeBmwDspValues.SIZE

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

inline constexpr int kMonoBassEnabled = 42;
inline constexpr int kMonoBassFreq = 43;
inline constexpr int kMonoBassBlend = 44;
inline constexpr int kMonoBassMakeup = 45;

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

// --- Mid-band independent LPF (reclaimed inert slots 141/142) ------------------------------
// Global enable + corner (Hz); configure() fans them onto every OutputConfig, Mid outputs only.
inline constexpr int kMidLpfEnabled = 141;
inline constexpr int kMidLpfFreq = 142;

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

}  // namespace nbschema

#endif  // SIPHONDSP_NATIVE_BMW_DSP_SCHEMA_H
