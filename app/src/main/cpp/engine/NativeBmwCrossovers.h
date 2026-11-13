#ifndef SIPHONDSP_NATIVE_BMW_CROSSOVERS_H
#define SIPHONDSP_NATIVE_BMW_CROSSOVERS_H

// Per-output crossover chains (Low / Mid / High + Low's subsonic HPF), the per-output all-pass
// sections, and the output-bus tilt EQ. Also owns the per-output config/runtime structs, since
// those are mostly the crossover chain.

#include <array>
#include <cstddef>
#include <cstdint>
#include "NativeBmwDelayGain.h"
#include "NativeBmwDynamics.h"
#include "NativeBmwFilters.h"
#include "NativeBmwRouting.h"

namespace NativeBmwDsp {

struct OutputConfig {
    float crossoverFreq = 150;
    // BW2 = one 2nd-order Butterworth stage (12 dB/oct). BW3 = a 1st-order stage cascaded
    // with a 2nd-order Q=1 stage (18 dB/oct total -- Q=1 is the exact factor of the 3rd-order
    // Butterworth polynomial's quadratic term, s^2+s+1). LinkwitzRiley4 = two cascaded
    // 2nd-order Butterworth (Q=1/sqrt(2)) stages (24 dB/oct), unchanged from before this was
    // selectable. Butterworth4 = two cascaded 2nd-order stages at the 4th-order Butterworth
    // polynomial's Qs (0.5412 / 1.3066): 24 dB/oct like LR4 but -3 dB (not -6 dB) at the
    // corner. See buildLowCrossover/buildMidCrossover.
    enum class CrossoverType : std::uint8_t {
        Butterworth2 = 0,
        Butterworth3 = 1,
        LinkwitzRiley4 = 2,
        Butterworth1 = 3, // single first-order stage, 6 dB/oct
        Butterworth4 = 4, // two 2nd-order stages, 24 dB/oct, -3 dB at the corner
    };
    CrossoverType crossoverType = CrossoverType::LinkwitzRiley4;
    bool subsonicEnabled = false;
    float subsonicFreq = 32;
    bool muted = false;
    bool polarityInverted = false;
    // Mid's optional upper (Mid/High) bandpass corner -- only meaningful when the owning
    // output is Mid; carried-but-ignored for Low/High, same as subsonicEnabled/subsonicFreq
    // above being carried-but-ignored outside Low. Appended at the end of this struct (not
    // grouped with crossoverFreq/crossoverType above) so the existing positional aggregate
    // initializers in the processor's constructor keep working unchanged -- default-initializes
    // to disabled for all three of them. See buildMidCrossover() and
    // docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    bool upperCrossoverEnabled = false;
    float upperCrossoverFreq = 3000;
    // The upper corner's own slope (v[288]/v[289], added in the 288 -> 291 growth), independent
    // of crossoverType above, which only shapes the lower (Low/Mid) highpass.
    CrossoverType upperCrossoverType = CrossoverType::LinkwitzRiley4;
    // The subsonic high-pass's slope (v[304], global, added in the 304 -> 305 growth): false =
    // LR4, two Butterworth sections, 24 dB/oct; true = BW2, one section, 12 dB/oct. Low only, like
    // subsonicEnabled/subsonicFreq. Appended for the same aggregate-initializer reason as above.
    bool subsonicBw2 = false;
};

struct OutputRuntime {
    NativeBmwRouting::OutputId id = NativeBmwRouting::OutputId::LowLeft;
    bool isLeftSide = true;
    bool muted = false;
    bool polarityInverted = false;
    float gain = 1.0f;
    // Polarity and mute as one multiplier: +1 normal, -1 inverted, 0 muted. outputGain ramps to
    // outputGainTarget over a few ms (the processor's outputRampCoef_), so a mute or polarity
    // toggle fades -- through zero, for a flip -- instead of stepping the waveform (a click).
    float outputGainTarget = 1.0f;
    float outputGain = 1.0f;
    // Subsonic HPF: two cascaded Butterworth sections = LR4, 24 dB/oct, -6 dB at the corner.
    // (One section's 12 dB/oct let too much below-corner excursion through to small woofers.)
    Biquad subsonic1, subsonic2;
    Biquad crossover1, crossover2;
    // Second filter-pair slot, only consumed by Mid's bandpass (an LPF cascade at a second
    // corner, cascaded after crossover1/2's HPF) -- unused by Low/High, which only ever need
    // one filter direction. Inert (identity pass-through, Biquad's default) unless Mid's upper
    // corner is enabled. See docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    Biquad crossover3, crossover4;
    AlignmentDelay<kDelayLineCapacity> delay;  // time alignment (see AlignmentDelay)
    std::array<NativeBmwRouting::AllPassSection, NativeBmwRouting::kAllPassSectionsPerOutput>
        allPass{};
    std::array<Biquad, NativeBmwRouting::kAllPassSectionsPerOutput> allPassState{};

    // Runs each output's enabled all-pass sections in order, for both channels of an output
    // pair at once. Section i runs as a NEON pair (Biquad::runPair) when it's enabled on both
    // sides, otherwise scalar on whichever side has it -- same result as running each side
    // alone.
    static void processAllPassPair(OutputRuntime& l, OutputRuntime& r, float& xl, float& xr) {
        for (std::size_t i = 0; i < l.allPass.size(); ++i) {
            const bool onL = l.allPass[i].enabled, onR = r.allPass[i].enabled;
            if (onL && onR) {
                Biquad::runPair(l.allPassState[i], r.allPassState[i], xl, xr);
            } else if (onL) {
                xl = l.allPassState[i].run(xl);
            } else if (onR) {
                xr = r.allPassState[i].run(xr);
            }
        }
    }
    void clearState() {
        subsonic1.clear();
        subsonic2.clear();
        crossover1.clear();
        crossover2.clear();
        crossover3.clear();
        crossover4.clear();
        delay.clear();
        for (auto& section : allPassState) {
            section.clear();
        }
    }
};

// Coefficient (re)builds for one output, from its config. A stage whose topology stays the same
// keeps its state (Biquad::retune), so moving a corner doesn't click; a slope change that swaps a
// stage's topology starts that stage clean.
void buildSubsonic(OutputRuntime& out, const OutputConfig& cfg, float sampleRate);
// Auto-correction for the crossover types that can't sum flat as a plain pair. Bands are summed
// back together, so each boundary's lowpass + highpass must add up flat. LR4, BW3 and BW1 do;
// a BW2 pair nulls at the corner (or +3 dB with one side inverted) and a BW4 pair peaks +3 dB.
// When both sides of a boundary use the same BW2 or BW4 type at the same corner (within
// kPairCornerTolerance -- a deliberate gap or overlap is left alone), the two corners are spread
// apart around it (lowpass at f / k, highpass at f * k, in the bilinear-prewarped domain the
// filters are designed in -- see spreadCorner) and, for BW2, the band above is inverted: within
// about +/-0.5 dB at any corner and sample rate, and the set frequency stays the crossover point.
// The factors minimise the pair's worst deviation (BW2 inverted: 1.304 -> 0.47 dB; BW4:
// 1.128 -> 0.49 dB). Mirrored by Kotlin's CrossoverPairing.
constexpr float kBw2PairSpread = 1.304f;
constexpr float kBw4PairSpread = 1.128f;
// How far apart (as a ratio) the two corners can be and still count as one boundary.
constexpr float kPairCornerTolerance = 1.02f;
// fc moved by factor k in the prewarped domain: tan(pi f' / fs) = k tan(pi fc / fs). The filters
// are designed there, so the spread holds right up to Nyquist; k == 1 returns fc exactly.
float spreadCorner(float fc, float k, float sampleRate);
struct CrossoverPairing {
    // Each corner's spread factor, applied at build time by spreadCorner().
    float lowLowpass = 1.f, midHighpass = 1.f, midLowpass = 1.f, highHighpass = 1.f;
    // Polarity flips added to the user's own (XOR) by rebuildPolarityAndMute().
    bool midInverted = false, highInverted = false;
    bool operator==(const CrossoverPairing& o) const {
        return lowLowpass == o.lowLowpass && midHighpass == o.midHighpass &&
               midLowpass == o.midLowpass && highHighpass == o.highHighpass &&
               midInverted == o.midInverted && highInverted == o.highInverted;
    }
    bool operator!=(const CrossoverPairing& o) const { return !(*this == o); }
};
// One side's pairing. lowMidRuns: both the Low lowpass and the Mid highpass run; midHighRuns:
// Mid's highpass runs and High's chain runs (no highXoPass) -- Mid's upper corner must also be
// enabled.
CrossoverPairing pairCrossovers(const OutputConfig& low, const OutputConfig& mid,
                                const OutputConfig& high, bool lowMidRuns, bool midHighRuns);

void buildLowCrossover(OutputRuntime& out, const OutputConfig& cfg, float sampleRate);
void buildMidCrossover(OutputRuntime& out, const OutputConfig& cfg, float sampleRate);
void buildHighCrossover(OutputRuntime& out, const OutputConfig& cfg, float sampleRate);
// Recomputes every all-pass section's coefficients and reloads its state from them.
void rebuildAllPass(OutputRuntime& out, float sampleRate);

// Each runs one band's crossover chain on its left and right outputs together (NEON-paired
// stage by stage where both sides share a topology) -- identical to running each side's
// chain on its own, since the two channels share no state.
void processLowCrossover(OutputRuntime& left, const OutputConfig& leftConfig, OutputRuntime& right,
                         const OutputConfig& rightConfig, float& xl, float& xr);
void processMidCrossover(OutputRuntime& left, OutputRuntime& right, float& xl, float& xr);
void processHighCrossover(OutputRuntime& left, OutputRuntime& right, float& xl, float& xr);

// Loudness compensation: as the master volume drops below full, a bass shelf and a small treble
// shelf lift the ends of the spectrum the ear loses first at low level (equal-loudness contours),
// so quiet listening keeps its body. Zero lift at full volume. Each lift is a fixed fraction of the
// volume cut, capped, and always smaller than the cut itself -- it runs after the master volume,
// so it can never push the output above what full volume would produce.
class Loudness {
public:
    static constexpr float kBassHz = 100.f, kTrebleHz = 8000.f;
    static constexpr float kBassPerDb = 0.3f, kTreblePerDb = 0.1f;  // dB of lift per dB of cut
    static constexpr float kBassMaxDb = 12.f, kTrebleMaxDb = 4.f;
    // The lift for a given volume (dB, <= 0) and strength (0..1), as [bass, treble] dB.
    static void liftDb(float volumeDb, float strength, float& bassDb, float& trebleDb);
    // Retunes the shelves for this volume/strength, keeping their state.
    void rebuild(float volumeDb, float strength, float sampleRate);
    void clear();
    void process(float& left, float& right);

private:
    Biquad bassL_, bassR_, trebleL_, trebleR_;
};

// Output-bus tilt EQ: two low shelves (+g) and two high shelves (-g) per channel at one corner.
class Tilt {
public:
    // Retunes the shelves, keeping their state (see Biquad::retune).
    void rebuild(float amountDb, float freqHz, float sampleRate);
    void clear();
    void process(float& left, float& right);

private:
    Biquad loL1_, loL2_, hiL1_, hiL2_;
    Biquad loR1_, loR2_, hiR1_, hiR2_;
};

// Mono Bass: a complementary Linkwitz-Riley 4th-order split at one frequency. Below it both
// channels carry the mid signal (L+R)/2; above it each keeps its own:
//   L' = LP4((L+R)/2) + HP4(L)    R' = LP4((L+R)/2) + HP4(R)
// LP4 + HP4 is a 2nd-order all-pass, so a centred signal (L == R) comes through at exactly its
// own magnitude -- only the all-pass phase around the split. Side content below the split is
// folded to mono. Every filter is a fixed member: nothing allocates.
class MonoBass {
public:
    static constexpr float kMinHz = 60.f, kMaxHz = 120.f, kDefaultHz = 90.f;
    // Retunes the split, keeping the filter state (see Biquad::retune).
    void rebuild(float freqHz, float sampleRate);
    void clear();
    // mix crossfades from the input (0) to the split (1): the on/off transition.
    void process(float& left, float& right, float mix);

private:
    Biquad lp1_, lp2_;  // on (L+R)/2
    Biquad hpL1_, hpL2_, hpR1_, hpR2_;
};

// Virtual Bass: psychoacoustic bass extension (the "missing fundamental"). Notes below the
// woofers' reach -- the "cut", set to the lowest note they really play, e.g. where the subsonic
// HPF starts -- are rebuilt as their 2nd..4th harmonics, which the woofers can play and from which
// the ear infers the fundamental: a 30 Hz note is heard as 30 Hz from its 60 / 90 / 120 Hz
// harmonics. Notes the woofers can play are left alone and generate nothing.
//
//   m    = (L+R)/2, band-limited to the octave below the reach: HP2 at cut / 2, and an LR8
//          lowpass at kInputRatio * cut (48 dB/oct from just under the reach, so a note a third
//          above it is ~27 dB down and barely feeds the harmonics -- that's what keeps bass lines
//          the woofers can play from turning to mud)
//   u    = m / env(m), clamped to [-1, 1]   (env: instant-attack peak follower, Response release)
//   h    = env * (w2 T2(u) + w3 T3(u) + w4 T4(u)), band-limited to [cut .. range * cut],
//          the weights blended between a Warm and a Punchy set by Character
//          (w2 + w3 + w4 = 1, so |h| <= env before the band-limiting)
//   out  = base + gain * h on both channels, base = in, or, when the sub is removed, in through a
//          gentle HP2 at kRemovalRatio * cut: it only thins what lies below the reach (-1.6 dB at
//          the reach, -0.5 dB half an octave above it), never the band the woofers play
//
// T_n are Chebyshev polynomials: for a sine, T_n(cos t) = cos(n t) exactly, so a steady tone
// yields clean harmonics at the input's own amplitude, and normalising by the envelope keeps that
// true at any level (the harmonics scale linearly with the bass, no level-dependent distortion
// colour). Every filter is a fixed member: nothing allocates.
class VirtualBass {
public:
    static constexpr float kMinHz = 25.f, kMaxHz = 60.f, kDefaultHz = 40.f;
    // Range: the harmonics are kept from the cut up to this many times it. Lower keeps them on the
    // woofers; higher lets them reach further up (a stronger sense of bass, more of it on the mids).
    static constexpr float kMinRange = 3.f, kMaxRange = 8.f, kDefaultRange = 5.f;
    // Response: the detector envelope's release. Faster follows each note's decay (tighter, punchier
    // kicks); slower holds the harmonics through it (smoother, fuller lines). The floor stays above
    // the period of the lowest note the input band passes (an octave under the lowest cut, ~12.5 Hz
    // = 80 ms), so the envelope never sags inside a single cycle.
    static constexpr float kMinResponseMs = 100.f, kMaxResponseMs = 500.f, kDefaultResponseMs = 200.f;
    // Remove sub's HP2 sits this far below the reach (Kotlin mirrors it for the response graph).
    static constexpr float kRemovalRatio = .8f;
    // The most the harmonics may raise a peak, as 20 log10(1 + rise x gain), over the recent stereo
    // peak of the input and the base (the input, Remove sub's high-pass blended in). Auto
    // headroom allows exactly this (Kotlin VIRTUAL_BASS_PEAK_RISE_*). A lone note rises at most
    // 0.53 with the sub kept and 0.11 with it removed, so it plays untouched; a note whose own
    // harmonics line up with the added ones would go further, and there the stage turns its
    // harmonics down for that peak instead (see process()), so the allowance holds for any signal.
    static constexpr float kPeakRiseKept = .55f, kPeakRiseRemoved = .12f;
    // The input lowpass's corner, a little under the reach (notes at the reach the woofers still
    // play; it's those well below it that need rebuilding).
    static constexpr float kInputRatio = .85f;
    // The input highpass's corner: an octave under the reach, which at the lowest reach (25 Hz) is
    // below the 20 Hz the other filters stop at.
    static constexpr float kInputHpRatio = .5f;
    // Retunes every filter for the cut frequency and range, keeping their state (see
    // Biquad::retune), and sets the detector's release.
    void rebuild(float cutHz, float range, float responseMs, float sampleRate);
    void clear();
    // onMix crossfades from the input (0) to the processed signal (1): the on/off transition.
    // removeMix crossfades the base from the input (0) to the input high-passed below the cut (1).
    // gain is the harmonics' linear level (1 = the harmonics at the sub's own amplitude).
    // character blends the harmonic mix from Warm (0) to Punchy (1).
    void process(float& left, float& right, float onMix, float removeMix, float gain, float character);

private:
    // The peak cap's own release, fixed whatever Response is: the recent peak it measures against.
    static constexpr float kPeakReleaseSeconds = 0.2f;
    // Harmonic weights (2nd, 3rd, 4th) at the two ends of Character, each normalised so it sums
    // to 1 -- and so does any blend of them: at |u| = 1 every T_n is +-1, so the harmonics never
    // peak above the sub's own envelope, at any Character. Warm leans on the even harmonics (the
    // octave and two octaves: round, tube-like); Punchy brings up the 3rd (an octave and a fifth:
    // more edge and growl, and a stronger sense of the missing fundamental).
    static constexpr float kWarmSum = 1.f + .15f + .6f;
    static constexpr float kWarmW2 = 1.f / kWarmSum, kWarmW3 = .15f / kWarmSum, kWarmW4 = .6f / kWarmSum;
    static constexpr float kPunchySum = 1.f + .8f + .2f;
    static constexpr float kPunchyW2 = 1.f / kPunchySum, kPunchyW3 = .8f / kPunchySum, kPunchyW4 = .2f / kPunchySum;
    Biquad inHp_, inLpA1_, inLpB1_, inLpA2_, inLpB2_;  // the sub band of (L+R)/2 (LR8 = BW4 x BW4)
    Biquad outHp1_, outHp2_, outLp1_, outLp2_;  // the harmonics' band
    Biquad cutL_, cutR_;  // the original sub's removal, per channel
    float env_ = 0.f;
    float release_ = 0.99989584f;      // env_: exp(-1 / (Response * 48 kHz)), 0.2 s here
    float peakRelease_ = 0.99989584f;  // peak_: exp(-1 / (kPeakReleaseSeconds * 48 kHz))
    // The peak cap: the recent stereo peak (either channel, input or base, whichever is largest;
    // instant attack, kPeakReleaseSeconds), and the harmonics' gain under the cap -- it drops
    // at once to what a peak needs, then recovers over kCapRecoverySeconds.
    static constexpr float kCapRecoverySeconds = 0.03f;
    float peak_ = 0.f;
    float capGain_ = 1.f;
    float capRecovery_ = 0.00069420f;  // 1 - exp(-1 / (0.03 s * 48 kHz))
};

}  // namespace NativeBmwDsp

#endif
