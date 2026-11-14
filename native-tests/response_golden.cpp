// Measured frequency response of the native engine, for checking the app's graphs against it.
//
// The tuning graphs draw their curves with a Kotlin model of the signal chain
// (BmwResponseCalculator). This tool measures what the real engine does instead -- a steady sine
// through NativeBmwDspProcessor at each frequency, both output channels -- for a set of
// scenarios, and writes the results to native-tests/golden/response_curves.txt:
//
//   response_golden --write   regenerate the file (after an intended change to the sound)
//   response_golden --check   fail if the engine no longer matches the file (registered with
//                             ctest, so it runs with the native tests)
//
// app/src/test/.../ResponseGoldenAgreementTest.kt reads the same file and fails if the Kotlin
// model disagrees with the measured engine. Together: the graphs can't silently drift from what
// plays, from either side.
//
// Every scenario turns the dynamics off (master limiter, bus limiters, MBC) and plays well below
// full scale, so the chain is linear and the steady-state magnitude is its frequency response.
// Channels are reported as the internal Left/Right chains, the app's BmwOutputChannel LEFT/RIGHT
// (the engine's final hardware swap puts the internal Left chain on output channel 1).
#define DOCTEST_CONFIG_DISABLE
#include "test_support.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

using namespace nbtest;
namespace sch = nbschema;

namespace {
// The Kotlin model's own axis (BmwResponseCalculator.configureAxis): log-spaced, inclusive.
constexpr int kPoints = 24;
constexpr double kMinHz = 40.0, kMaxHz = 12000.0;
constexpr double kAmplitude = 0.02;  // -34 dBFS: far below any dynamics, linear throughout
constexpr double kCheckToleranceDb = 0.05;

struct Band {
    const char* bank;  // full, low, mid, high
    double freq, gain, q;
    int type, channel;  // ParametricEqFilterType.code, ParametricEqChannel.code
};
struct Scenario {
    const char* name;
    std::vector<std::pair<int, float>> set;  // slot overrides on the canonical defaults
    std::vector<Band> bands;
    float preampDb = 0.f;
};

int outField(int output, int field) {
    return output < 4 ? sch::kOutputConfigBase + output * sch::kOutputConfigWidth + field
                      : sch::kHighOutputConfigBase + (output - 4) * sch::kOutputConfigWidth + field;
}

std::vector<std::pair<int, float>> quietDynamics() {
    return {{sch::kMasterLimiterEnabled, 0.f}, {sch::kBusLimLowEnabled, 0.f},
            {sch::kBusLimMidEnabled, 0.f},     {sch::kBusLimHighEnabled, 0.f},
            {sch::kMbcEnabled, 0.f},           {sch::kDeepBassEnabled, 0.f}};
}

std::vector<Scenario> scenarios() {
    auto with = [](std::vector<std::pair<int, float>> extra) {
        auto s = quietDynamics();
        s.insert(s.end(), extra.begin(), extra.end());
        return s;
    };
    return {
        {"defaults", with({}), {}},
        {"bw2_crossover_mid_inverted",
         with({{outField(0, sch::kOutCrossoverType), 0.f}, {outField(1, sch::kOutCrossoverType), 0.f},
               {outField(2, sch::kOutCrossoverType), 0.f}, {outField(3, sch::kOutCrossoverType), 0.f},
               {outField(0, sch::kOutCrossoverFreq), 120.f}, {outField(1, sch::kOutCrossoverFreq), 120.f},
               {outField(2, sch::kOutCrossoverFreq), 120.f}, {outField(3, sch::kOutCrossoverFreq), 120.f},
               {outField(2, sch::kOutPolarityInverted), 1.f}, {outField(3, sch::kOutPolarityInverted), 1.f}}),
         {}},
        {"lr4_300_tilt_post_gain",
         with({{outField(0, sch::kOutCrossoverFreq), 300.f}, {outField(1, sch::kOutCrossoverFreq), 300.f},
               {outField(2, sch::kOutCrossoverFreq), 300.f}, {outField(3, sch::kOutCrossoverFreq), 300.f},
               {sch::kTiltEnabled, 1.f}, {sch::kTiltAmount, 3.f}, {sch::kTiltFreq, 1000.f},
               {sch::kPostGainL, 3.f}}),
         {}},
        {"peq_banks",
         with({}),
         {{"full", 1000.0, 6.0, 2.0, 0, 0},
          {"low", 60.0, 4.0, 0.7, 1, 0},
          {"mid", 3000.0, -5.0, 1.5, 0, 1},
          {"full", 8000.0, -4.0, 0.7, 2, 2}},
         -2.f},
        {"three_way_high_on",
         with({{sch::kHighXoPass, 0.f},
               {outField(4, sch::kOutMuted), 0.f}, {outField(5, sch::kOutMuted), 0.f},
               {sch::kMidUpperXo + sch::kMidUpperXoEnabled, 1.f},
               {sch::kMidUpperXo + sch::kMidUpperXoWidth + sch::kMidUpperXoEnabled, 1.f}}),
         {}},
        {"asymmetric_gains_headroom",
         with({{sch::kHeadroom, -10.f}, {sch::kLowGainL, -3.f}, {sch::kMidGainR, 2.f},
               {sch::kLowDelayL, 1.f}, {sch::kMidDelayR, 0.5f}}),
         {}},
        {"subsonic_bw2",
         with({{sch::kSubsonicSlope, 1.f}, {outField(0, sch::kOutSubsonicFreq), 45.f},
               {outField(1, sch::kOutSubsonicFreq), 45.f}}),
         {}},
        // The band split removed: one full-range path, so none of the band settings below may
        // reach the output; the full-range PEQ and the summed-bus stages still do.
        {"crossover_bypass",
         with({{sch::kCrossoverBypass, 1.f}, {sch::kLowGainL, -3.f}, {sch::kMidGainR, 2.f},
               {sch::kLowDelayL, 1.f}, {outField(2, sch::kOutPolarityInverted), 1.f},
               {sch::kTiltEnabled, 1.f}}),
         {{"full", 1000.0, 3.0, 1.0, 0, 0}, {"low", 80.0, 6.0, 1.0, 0, 0}, {"mid", 2000.0, -6.0, 1.0, 0, 0}}},
        // Measurement neutral with only tilt let back on: everything else configured below
        // (headroom, gains, PEQ, the band split, the DC blocker) must be gone.
        {"measurement_neutral_tilt",
         with({{sch::kMeasurementNeutral, 1.f}, {sch::kNeutralAllow, float(1u << sch::kStageTilt)},
               {sch::kTiltEnabled, 1.f}, {sch::kTiltAmount, 4.f}, {sch::kHeadroom, -8.f}, {sch::kLowGainL, -3.f},
               {sch::kPostGainR, 2.f}}),
         {{"full", 1000.0, 3.0, 1.0, 0, 0}, {"low", 80.0, 6.0, 1.0, 0, 0}},
         -2.f},
        // Neutral with the crossover and band EQ let back on: the split and its band EQ run, the
        // Pre EQ, band gains and the summed-bus stages don't.
        {"measurement_neutral_crossover_band_eq",
         with({{sch::kMeasurementNeutral, 1.f},
               {sch::kNeutralAllow, float((1u << sch::kStageCrossover) | (1u << sch::kStageBandEq))},
               {sch::kTiltEnabled, 1.f}, {sch::kLowGainL, -3.f}, {sch::kMidDelayR, 1.f}}),
         {{"full", 1000.0, 3.0, 1.0, 0, 0}, {"low", 80.0, 6.0, 1.0, 0, 0}, {"mid", 2000.0, -6.0, 1.0, 0, 0}},
         -2.f},
    };
}

double axisHz(int i) {
    return std::pow(10.0, std::log10(kMinHz) + (std::log10(kMaxHz) - std::log10(kMinHz)) * i / (kPoints - 1));
}

// Steady-state magnitude (dB) of each internal chain at freqHz.
std::pair<double, double> measure(const Scenario& sc, double freqHz) {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = defaultConfig();
    for (const auto& [slot, value] : sc.set) {
        cfg[slot] = value;
    }
    if (!proc.configure(cfg.data(), cfg.size())) {
        std::fprintf(stderr, "%s: config rejected\n", sc.name);
        std::exit(2);
    }
    std::vector<double> banks[4];
    for (const auto& b : sc.bands) {
        const int bank = !std::strcmp(b.bank, "full") ? 0 : !std::strcmp(b.bank, "low") ? 1
                         : !std::strcmp(b.bank, "mid") ? 2 : 3;
        banks[bank].insert(banks[bank].end(), {b.freq, b.gain, b.q, double(b.type), double(b.channel)});
    }
    if (!sc.bands.empty() || sc.preampDb != 0.f) {
        if (!proc.configurePeq(true, sc.preampDb, banks[0].data(), banks[0].size(), banks[1].data(),
                               banks[1].size(), banks[2].data(), banks[2].size(), banks[3].data(),
                               banks[3].size())) {
            std::fprintf(stderr, "%s: PEQ rejected\n", sc.name);
            std::exit(2);
        }
    }
    // One continuous sine: long enough for the slowest filter (the subsonic HPF) and every ramp
    // to settle, then a window measured on its tail.
    const std::size_t warm = 48000, window = 32768;
    std::vector<float> buf = stereoSine(freqHz, kAmplitude, warm + window);
    proc.process(buf.data(), buf.size());
    std::vector<float> tail(buf.end() - static_cast<std::ptrdiff_t>(2 * window), buf.end());
    const double internalLeft = channelMagnitudeAt(tail, 1, freqHz) / kAmplitude;
    const double internalRight = channelMagnitudeAt(tail, 0, freqHz) / kAmplitude;
    return {linToDb(internalLeft), linToDb(internalRight)};
}

std::string render() {
    std::ostringstream out;
    out.setf(std::ios::fixed);
    out.precision(3);
    out << "# Measured frequency response of the native engine. Generated by\n"
           "# native-tests/response_golden.cpp (response_golden --write); do not edit by hand.\n"
           "# Checked against the engine (response_golden --check, a ctest) and against the app's\n"
           "# graph model (ResponseGoldenAgreementTest.kt).\n"
           "#\n"
           "# scenario <name>; set <slot> <value> (overrides on the canonical defaults);\n"
           "# peq <preampDb>; band <bank> <freqHz> <gainDb> <q> <typeCode> <channelCode>;\n"
           "# freq <Hz...>; left / right <dB...> (internal Left / Right chain); end\n";
    out << "axis " << kPoints << ' ' << kMinHz << ' ' << kMaxHz << ' ' << kSampleRate << '\n';
    for (const auto& sc : scenarios()) {
        out << "\nscenario " << sc.name << '\n';
        out.precision(6);
        for (const auto& [slot, value] : sc.set) out << "set " << slot << ' ' << value << '\n';
        if (!sc.bands.empty() || sc.preampDb != 0.f) out << "peq " << sc.preampDb << '\n';
        for (const auto& b : sc.bands)
            out << "band " << b.bank << ' ' << b.freq << ' ' << b.gain << ' ' << b.q << ' ' << b.type << ' ' << b.channel << '\n';
        out.precision(3);
        std::string freq = "freq", left = "left", right = "right";
        for (int i = 0; i < kPoints; ++i) {
            const double hz = axisHz(i);
            const auto [l, r] = measure(sc, hz);
            char buf[64];
            std::snprintf(buf, sizeof buf, " %.3f", hz); freq += buf;
            std::snprintf(buf, sizeof buf, " %.3f", l); left += buf;
            std::snprintf(buf, sizeof buf, " %.3f", r); right += buf;
        }
        out << freq << '\n' << left << '\n' << right << "\nend\n";
    }
    return out.str();
}

std::vector<double> numbers(const std::string& text, const char* key) {
    std::vector<double> all;
    std::istringstream in(text);
    for (std::string line; std::getline(in, line);) {
        std::istringstream ls(line);
        std::string head;
        ls >> head;
        if (head != key) continue;
        for (double v; ls >> v;) all.push_back(v);
    }
    return all;
}
}  // namespace

int main(int argc, char** argv) {
    const std::string path = NBTEST_GOLDEN_RESPONSE_PATH;
    const bool write = argc > 1 && !std::strcmp(argv[1], "--write");
    const std::string fresh = render();
    if (write) {
        std::ofstream(path) << fresh;
        std::printf("wrote %s\n", path.c_str());
        return 0;
    }
    std::ifstream in(path);
    std::stringstream stored;
    stored << in.rdbuf();
    int failures = 0;
    for (const char* key : {"left", "right"}) {
        const auto want = numbers(stored.str(), key), got = numbers(fresh, key);
        if (want.size() != got.size()) {
            std::printf("%s: %zu values in %s, engine gives %zu -- regenerate with --write\n", key,
                        want.size(), path.c_str(), got.size());
            return 1;
        }
        for (std::size_t i = 0; i < want.size(); ++i) {
            if (std::fabs(want[i] - got[i]) > kCheckToleranceDb) {
                std::printf("%s[%zu]: file %.3f dB, engine %.3f dB\n", key, i, want[i], got[i]);
                ++failures;
            }
        }
    }
    if (failures) {
        std::printf("%d points differ from %s. If the sound change is intended, run "
                    "response_golden --write and commit the file.\n", failures, path.c_str());
        return 1;
    }
    std::printf("engine matches %s\n", path.c_str());
    return 0;
}
