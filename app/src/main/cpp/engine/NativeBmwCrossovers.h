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
// 1.128 -> 0.49 dB). Opt-in per boundary (v[kLowMidAutoPair], v[kMidHighAutoPair], both off by
// default): off, the pair runs exactly as set. Mirrored by Kotlin's CrossoverPairing.
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
// The same over a block of frames in place, bit-identical to calling the per-frame ones frame by
// frame: the band's sections as one chain through runBiquadPairChainBlock() (three at a time,
// skewed by one sample, coefficients and state in registers).
void processLowCrossoverBlock(OutputRuntime& left, const OutputConfig& leftConfig, OutputRuntime& right,
                              const OutputConfig& rightConfig, float* xl, float* xr, std::size_t frames);
void processMidCrossoverBlock(OutputRuntime& left, OutputRuntime& right, float* xl, float* xr, std::size_t frames);
void processHighCrossoverBlock(OutputRuntime& left, OutputRuntime& right, float* xl, float* xr, std::size_t frames);

// Deep bass: a volume-dependent bass boost. As the master volume drops below full, one broad bell
// lifts the lowest octave the ear loses first at low level, and slides deeper as it grows: 0 dB at
// 50 Hz at full volume, +0.3 dB per dB of cut, the centre gliding down to 32 Hz at a 40 dB cut.
// The gain is capped at the Max boost setting and scaled by Strength; the frequency follows the
// volume alone. The lift is always smaller than the cut itself -- it runs after the master volume,
// so it can never push the output above what full volume would produce.
class DeepBass {
public:
    static constexpr float kFullVolumeHz = 50.f, kQuietHz = 32.f;
    static constexpr float kGlideCutDb = 40.f;  // the cut at which the centre reaches kQuietHz
    static constexpr float kPerDb = 0.3f;       // dB of boost per dB of volume cut
    static constexpr float kQ = 0.8f;
    static constexpr float kMinMaxDb = 3.f, kMaxMaxDb = 15.f;
    // The bell for a given volume (dB, <= 0), strength (0..1) and cap (dB): centre Hz and gain dB.
    static void curve(float volumeDb, float strength, float maxDb, float& centreHz, float& gainDb);
    // Retunes the bell for this volume/strength/cap, keeping its state.
    void rebuild(float volumeDb, float strength, float maxDb, float sampleRate);
    void clear();
    void process(float& left, float& right);

private:
    Biquad bellL_, bellR_;
};

// Output-bus tilt EQ: two low shelves (+g) and two high shelves (-g) per channel at one corner.
class Tilt {
public:
    // Retunes the shelves, keeping their state (see Biquad::retune).
    void rebuild(float amountDb, float freqHz, float sampleRate);
    void clear();
    void process(float& left, float& right);
    // process() over a block in place, bit-identical, as one skewed chain (runBiquadPairChainBlock()).
    void processBlock(float* left, float* right, std::size_t frames);

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

}  // namespace NativeBmwDsp

#endif
