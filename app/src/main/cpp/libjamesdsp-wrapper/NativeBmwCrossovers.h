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
    CompressorParams compressor{};
    // Mid's optional upper (Mid/High) bandpass corner -- only meaningful when the owning
    // output is Mid; carried-but-ignored for Low/High, same as subsonicEnabled/subsonicFreq
    // above being carried-but-ignored outside Low. Appended at the end of this struct (not
    // grouped with crossoverFreq/crossoverType above) so the existing positional aggregate
    // initializers in the processor's constructor keep working unchanged -- default-initializes
    // to disabled for all three of them. See buildMidCrossover() and
    // docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    bool upperCrossoverEnabled = false;
    float upperCrossoverFreq = 3000;
};

struct OutputRuntime {
    NativeBmwRouting::OutputId id = NativeBmwRouting::OutputId::LowLeft;
    bool isLeftSide = true;
    bool muted = false;
    bool polarityInverted = false;
    float gain = 1.0f;
    Biquad subsonic1;
    Biquad crossover1, crossover2;
    // Second filter-pair slot, only consumed by Mid's bandpass (an LPF cascade at a second
    // corner, cascaded after crossover1/2's HPF) -- unused by Low/High, which only ever need
    // one filter direction. Inert (identity pass-through, Biquad's default) unless Mid's upper
    // corner is enabled. See docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    Biquad crossover3, crossover4;
    Delay delay;
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

// Coefficient (re)builds for one output, from its config. Each clears the stages it writes.
void buildSubsonic(OutputRuntime& out, const OutputConfig& cfg, float sampleRate);
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

// Output-bus tilt EQ: two low shelves (+g) and two high shelves (-g) per channel at one corner.
class Tilt {
public:
    void rebuild(float amountDb, float freqHz, float sampleRate);
    void process(float& left, float& right);

private:
    Biquad loL1_, loL2_, hiL1_, hiL2_;
    Biquad loR1_, loR2_, hiR1_, hiR2_;
};

}  // namespace NativeBmwDsp

#endif
