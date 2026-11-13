#include "NativeBmwSelfTest.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <complex>
#include <memory>
#include <string>
#include <vector>

#include "NativeBmwDspSchema.h"

namespace NativeBmwDsp {
namespace {

namespace sch = nbschema;
using Complex = std::complex<double>;
constexpr double kPi = 3.14159265358979323846;
// The impulse's level: -20 dBFS, low enough that no stage clips it, high enough to sit far above
// float rounding.
constexpr float kImpulse = 0.1f;

void fft(std::vector<Complex>& a) {
    const std::size_t n = a.size();
    for (std::size_t i = 1, j = 0; i < n; ++i) {
        std::size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) {
            std::swap(a[i], a[j]);
        }
    }
    for (std::size_t len = 2; len <= n; len <<= 1) {
        const double angle = -2.0 * kPi / static_cast<double>(len);
        const Complex wlen(std::cos(angle), std::sin(angle));
        for (std::size_t i = 0; i < n; i += len) {
            Complex w(1.0, 0.0);
            for (std::size_t k = 0; k < len / 2; ++k) {
                const Complex u = a[i + k], v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wlen;
            }
        }
    }
}

// One side's response: the direct path (into the car's own side) and the crosstalk path.
struct Path {
    std::vector<Complex> direct, cross;
    std::int64_t latency = 0;
};
struct Response {
    Path left, right;
    std::uint32_t activeStages = 0;
};

class Bench {
public:
    explicit Bench(const NativeBmwDspProcessor::SelfTestSource& source) : source_(source) {
        const double rate = source.sampleRate;
        const double irFrames = source.ir ? static_cast<double>(source.ir->frames()) : 0.0;
        const double want = std::max(rate * kSelfTestSeconds, 2.0 * irFrames + 0.25 * rate);
        frames_ = 1;
        while (static_cast<double>(frames_) < want) {
            frames_ <<= 1;
        }
        const double gainDb = source.config[sch::kMasterVolumeDb];
        const bool muted = source.config[sch::kMasterMute] >= .5f;
        volumeDb_ = muted ? 0.0 : std::clamp(gainDb, -80.0, 0.0);
    }
    std::size_t frames() const { return frames_; }
    double volumeDb() const { return volumeDb_; }

    // The config as the copy runs it (see NativeBmwSelfTest.h): generator and channel mute off,
    // a master mute lifted.
    std::vector<float> baseConfig() const {
        std::vector<float> c = source_.config;
        c[sch::kEnabled] = 1.f;
        c[sch::kMeasGenType] = 0.f;
        c[sch::kChannelMute] = 0.f;
        if (c[sch::kMasterMute] >= .5f) {
            c[sch::kMasterMute] = 0.f;
            c[sch::kMasterVolumeDb] = 0.f;
        }
        return c;
    }

    bool measure(const std::vector<float>& config, Response& out) const {
        auto p = std::make_unique<NativeBmwDspProcessor>();
        p->setSampleRate(static_cast<float>(source_.sampleRate));
        if (!p->configure(config.data(), config.size())) {
            return false;
        }
        if (source_.peqValid) {
            const auto& b = source_.peqBanks;
            if (!p->configurePeq(source_.peqEnabled, source_.peqPreampDb, b[0].data(), b[0].size(),
                                 b[1].data(), b[1].size(), b[2].data(), b[2].size(), b[3].data(),
                                 b[3].size())) {
                return false;
            }
        }
        if (source_.ir) {
            std::string error;
            ConvolverIr ir = *source_.ir;
            (void)p->loadConvolverIr(std::move(ir), error);
        }
        // Warm-up: installs everything and lets an IR's arrival fade finish.
        std::vector<float> buffer(2 * frames_, 0.f);
        const auto warm = static_cast<std::size_t>(0.25 * source_.sampleRate);
        p->process(buffer.data(), 2 * std::min(warm, frames_));
        float status[NativeBmwDspProcessor::kEngineStatusSize] = {};
        p->readEngineStatus(status, NativeBmwDspProcessor::kEngineStatusSize);
        out.activeStages = static_cast<std::uint32_t>(status[1]);
        const double unVolume = std::pow(10.0, -volumeDb_ / 20.0) / kImpulse;
        for (int side = 0; side < 2; ++side) {
            std::fill(buffer.begin(), buffer.end(), 0.f);
            buffer[side] = kImpulse;
            // Starts the chain from silence with every ramp on its setting: what a fresh stream gets.
            p->notifyStreamStart();
            p->process(buffer.data(), buffer.size());
            Path& path = side == 0 ? out.left : out.right;
            // The car's Left speaker is the hardware output's right channel (processFrameBack()).
            const int directChannel = side == 0 ? 1 : 0;
            path.direct.assign(frames_, Complex());
            path.cross.assign(frames_, Complex());
            double peak = -1.0;
            for (std::size_t i = 0; i < frames_; ++i) {
                const double d = buffer[2 * i + directChannel] * unVolume;
                path.direct[i] = d;
                path.cross[i] = buffer[2 * i + (1 - directChannel)] * unVolume;
                if (std::fabs(d) > peak) {
                    peak = std::fabs(d);
                    path.latency = static_cast<std::int64_t>(i);
                }
            }
            fft(path.direct);
            fft(path.cross);
        }
        return true;
    }

private:
    const NativeBmwDspProcessor::SelfTestSource& source_;
    std::size_t frames_ = 0;
    double volumeDb_ = 0.0;
};

struct Deviation {
    double magDb = 0.0, phaseDeg = 0.0, crosstalkDb = -400.0;
    std::int64_t latency = 0;
    bool passes() const {
        return magDb <= kSelfTestMagnitudeTolDb && phaseDeg <= kSelfTestPhaseTolDeg && latency == 0 &&
               crosstalkDb <= kSelfTestCrosstalkDb;
    }
};

// response against reference (null: a wire) over [lowHz, highHz].
Deviation compare(const Response& r, const Response* ref, std::size_t frames, double rate, double lowHz,
                  double highHz) {
    Deviation dev;
    const auto first = static_cast<std::size_t>(std::ceil(lowHz * frames / rate));
    const auto last = static_cast<std::size_t>(std::floor(highHz * frames / rate));
    for (int side = 0; side < 2; ++side) {
        const Path& p = side == 0 ? r.left : r.right;
        const Path* q = ref ? (side == 0 ? &ref->left : &ref->right) : nullptr;
        const std::int64_t refLatency = q ? q->latency : 0;
        const std::int64_t latency = p.latency - refLatency;
        dev.latency = std::max<std::int64_t>(dev.latency, std::llabs(latency));
        for (std::size_t k = std::max<std::size_t>(first, 1); k <= last && k < frames / 2; ++k) {
            const Complex refDirect = q ? q->direct[k] : Complex(1.0, 0.0);
            const double refMag = std::abs(refDirect);
            if (refMag < 1e-6) {
                continue;  // a null in the reference: nothing to compare against
            }
            // The latency difference taken out, so a delay shows as latency, not phase.
            const double turn = 2.0 * kPi * static_cast<double>(k) * static_cast<double>(latency) /
                                static_cast<double>(frames);
            const Complex h = p.direct[k] / refDirect * Complex(std::cos(turn), std::sin(turn));
            const double mag = std::abs(h);
            dev.magDb = std::max(dev.magDb, std::fabs(20.0 * std::log10(std::max(mag, 1e-20))));
            if (mag > 1e-2) {
                dev.phaseDeg = std::max(dev.phaseDeg, std::fabs(std::arg(h)) * 180.0 / kPi);
            }
            const Complex cross = p.cross[k] - (q ? q->cross[k] : Complex());
            const double crossDb = 20.0 * std::log10(std::max(std::abs(cross) / refMag, 1e-20));
            dev.crosstalkDb = std::max(dev.crosstalkDb, crossDb);
        }
    }
    return dev;
}

bool bandStage(int bit) {
    switch (bit) {
        case sch::kStageBandEq:
        case sch::kStageBandGain:
        case sch::kStageBandDelay:
        case sch::kStagePolarity:
        case sch::kStageAllPass:
        case sch::kStageSubsonic:
        case sch::kStageRouting:
        case sch::kStageBusLimiter:
        case sch::kStageMeasurementMute:
            return true;
        default:
            return false;
    }
}

}  // namespace

SelfTestResult runSelfTest(const NativeBmwDspProcessor::SelfTestSource& source) {
    const auto started = std::chrono::steady_clock::now();
    SelfTestResult result;
    if (source.config.size() != NativeBmwDspProcessor::kConfigSize || !(source.sampleRate >= 8000.f)) {
        return result;
    }
    const Bench bench(source);
    const double rate = source.sampleRate;
    result.sampleRate = rate;
    result.lowHz = kSelfTestLowHz;
    result.highHz = std::min(kSelfTestHighHz, 0.45 * rate);
    result.masterVolumeDb = bench.volumeDb();
    result.measurementNeutral = source.config[sch::kMeasurementNeutral] >= .5f;
    const std::size_t frames = bench.frames();

    const std::vector<float> base = bench.baseConfig();
    Response chain;
    if (!bench.measure(base, chain)) {
        return result;
    }
    result.ran = true;
    result.activeStages = chain.activeStages;
    const Deviation dev = compare(chain, nullptr, frames, rate, result.lowHz, result.highHz);
    result.verified = dev.passes();
    result.latencyFrames = dev.latency;
    result.magnitudeDevDb = dev.magDb;
    result.phaseDevDeg = dev.phaseDeg;
    result.crosstalkDb = dev.crosstalkDb;
    constexpr std::uint32_t kLevelDependent =
        (1u << sch::kStageMbc) | (1u << sch::kStageBusLimiter) | (1u << sch::kStageMasterLimiter) |
        (1u << sch::kStageVirtualCentre) | (1u << sch::kStageVirtualBass);
    result.levelDependentStages = result.activeStages & kLevelDependent;

    if (!result.verified) {
        // Each active stage alone, under measurement neutral.
        auto isolated = [&base](std::uint32_t allow) {
            std::vector<float> c = base;
            c[sch::kMeasurementNeutral] = 1.f;
            c[sch::kNeutralAllow] = static_cast<float>(allow);
            return c;
        };
        const std::uint32_t crossoverBit = 1u << sch::kStageCrossover;
        Response crossoverOnly;
        bool haveCrossoverOnly = false;
        for (int bit = 0; bit < sch::kStageNeutralCount; ++bit) {
            const std::uint32_t mask = 1u << bit;
            if (!(result.activeStages & mask)) {
                continue;
            }
            const bool band = bandStage(bit);
            if (band && !haveCrossoverOnly) {
                haveCrossoverOnly = bench.measure(isolated(crossoverBit), crossoverOnly);
                if (!haveCrossoverOnly) {
                    continue;
                }
            }
            Response alone;
            if (!bench.measure(isolated(band ? mask | crossoverBit : mask), alone)) {
                continue;
            }
            const Deviation d =
                compare(alone, band ? &crossoverOnly : nullptr, frames, rate, result.lowHz, result.highHz);
            if (!d.passes()) {
                result.alteringStages |= mask;
            }
        }
        result.unexplained = result.alteringStages == 0;
    }
    result.durationMs =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    return result;
}

}  // namespace NativeBmwDsp
