#include "NativeBmwConvolver.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
#include "third_party/dr_wav.h"
#include "third_party/pffft/pffft_double.h"

namespace NativeBmwDsp {

namespace {

constexpr double kPi = 3.14159265358979323846;

double* allocAligned(std::size_t count) {
    auto* p = static_cast<double*>(pffftd_aligned_malloc(count * sizeof(double)));
    if (p != nullptr) {
        std::memset(p, 0, count * sizeof(double));
    }
    return p;
}

// Last frame whose magnitude, on either channel, is above -120 dB re. the IR's peak.
std::size_t trimmedLength(const ConvolverIr& ir) {
    double peak = 0.0;
    for (double v : ir.left) peak = std::max(peak, std::fabs(v));
    for (double v : ir.right) peak = std::max(peak, std::fabs(v));
    if (peak <= 0.0) {
        return 0;
    }
    const double floor = peak * 1e-6;
    std::size_t last = 0;
    for (std::size_t i = 0; i < ir.frames(); ++i) {
        if (std::fabs(ir.left[i]) > floor || (ir.stereo() && std::fabs(ir.right[i]) > floor)) {
            last = i + 1;
        }
    }
    return last;
}

// Zeroth-order modified Bessel function of the first kind (Kaiser window).
double besselI0(double x) {
    double sum = 1.0, term = 1.0;
    const double q = x * x / 4.0;
    for (int k = 1; k < 64; ++k) {
        term *= q / (static_cast<double>(k) * k);
        sum += term;
        if (term < sum * 1e-17) {
            break;
        }
    }
    return sum;
}

// The resampler's kernel, k(u) = sinc(u) * kaiser(u / kZeroCrossings), u in zero crossings of
// the cutoff, tabulated once per resample at kTablePerCrossing points per crossing and linearly
// interpolated: a table lookup per tap instead of a Bessel series and a sine. Interpolation
// error ~(pi / 1024)^2 / 8 ~ -120 dB, below the window's own ~-100 dB sidelobes.
constexpr double kZeroCrossings = 64.0, kBeta = 10.0;
constexpr std::size_t kTablePerCrossing = 1024;

std::vector<double> resampleKernelTable() {
    const std::size_t size = static_cast<std::size_t>(kZeroCrossings) * kTablePerCrossing + 2;
    std::vector<double> table(size, 0.0);
    const double i0Beta = besselI0(kBeta);
    for (std::size_t i = 0; i < size; ++i) {
        const double u = static_cast<double>(i) / kTablePerCrossing;
        if (u >= kZeroCrossings) {
            break;  // window edge and the guard point past it stay 0
        }
        const double sinc = i == 0 ? 1.0 : std::sin(kPi * u) / (kPi * u);
        const double r = u / kZeroCrossings;
        table[i] = sinc * besselI0(kBeta * std::sqrt(1.0 - r * r)) / i0Beta;
    }
    return table;
}

void resampleChannel(const std::vector<double>& in, double ratio, const std::vector<double>& table,
                     std::vector<double>& out) {
    // ratio = target / source. Kaiser-windowed sinc, 64 zero crossings of the (lower) cutoff each
    // side, beta 10; cutoff at the lower Nyquist so a downsample can't alias.
    const double cutoff = std::min(1.0, ratio);
    const double halfWidth = kZeroCrossings / cutoff;
    const double step = 1.0 / ratio;  // source samples per output sample
    const double scale = cutoff * static_cast<double>(kTablePerCrossing);  // source samples -> table index
    // Up to the last tap's ringing (halfWidth source samples past it), not just the source's own
    // length: cutting there drops the band-limited tail of the final taps. The caller trims what
    // falls below -120 dB.
    const auto outFrames = static_cast<std::size_t>(
        std::floor((static_cast<double>(in.size()) - 1.0 + halfWidth) * ratio)) + 1;
    out.assign(outFrames, 0.0);
    const auto n = static_cast<long long>(in.size());
    const auto last = static_cast<double>(table.size() - 2);
    for (std::size_t o = 0; o < outFrames; ++o) {
        const double t = static_cast<double>(o) * step;
        const auto first = std::max<long long>(0, static_cast<long long>(std::ceil(t - halfWidth)));
        const auto end = std::min<long long>(n - 1, static_cast<long long>(std::floor(t + halfWidth)));
        double acc = 0.0;
        for (long long m = first; m <= end; ++m) {
            const double pos = std::min(last, std::fabs(t - static_cast<double>(m)) * scale);
            const auto i = static_cast<std::size_t>(pos);
            const double frac = pos - static_cast<double>(i);
            acc += in[static_cast<std::size_t>(m)] * (table[i] + frac * (table[i + 1] - table[i]));
        }
        // x cutoff for the band limit; h'[n] = g(n / fs2) / fs2 = (fs1 / fs2) * g(n / fs2) / fs1
        // keeps the response's level.
        out[o] = acc * cutoff * step;
    }
}

}  // namespace

bool loadConvolverIrWav(const char* path, ConvolverIr& out, std::string& error) {
    out = ConvolverIr{};
    if (path == nullptr) {
        error = "No file";
        return false;
    }
    drwav wav;
    if (!drwav_init_file(&wav, path, nullptr)) {
        error = "Not a readable WAV file";
        return false;
    }
    const unsigned channels = wav.channels;
    const auto frames = static_cast<std::size_t>(wav.totalPCMFrameCount);
    const double rate = static_cast<double>(wav.sampleRate);
    if (channels != 1 && channels != 2) {
        drwav_uninit(&wav);
        error = "The IR must be mono or stereo (this file has " + std::to_string(channels) +
                " channels)";
        return false;
    }
    if (rate < 8000.0 || rate > kConvolverMaxSourceRate) {
        drwav_uninit(&wav);
        error = "Unsupported sample rate " + std::to_string(wav.sampleRate) + " Hz";
        return false;
    }
    if (frames == 0 || frames > kConvolverMaxSourceFrames) {
        drwav_uninit(&wav);
        error = frames == 0 ? "The file holds no audio"
                            : "The file is too long to be an impulse response";
        return false;
    }
    // dr_wav decodes to float at most; every integer PCM format up to 24 bits and 32-bit float
    // survive that exactly.
    std::vector<float> interleaved;
    try {
        interleaved.resize(frames * channels);
    } catch (const std::bad_alloc&) {
        drwav_uninit(&wav);
        error = "Out of memory reading the IR";
        return false;
    }
    const auto read = static_cast<std::size_t>(
        drwav_read_pcm_frames_f32(&wav, frames, interleaved.data()));
    drwav_uninit(&wav);
    if (read == 0) {
        error = "The WAV data could not be decoded";
        return false;
    }
    out.sampleRate = rate;
    out.channels = static_cast<int>(channels);
    out.left.resize(read);
    if (channels == 2) {
        out.right.resize(read);
    }
    for (std::size_t i = 0; i < read; ++i) {
        const double l = interleaved[i * channels];
        if (!std::isfinite(l) || (channels == 2 && !std::isfinite(interleaved[i * 2 + 1]))) {
            out = ConvolverIr{};
            error = "The IR contains invalid (NaN/infinite) samples";
            return false;
        }
        out.left[i] = l;
        if (channels == 2) {
            out.right[i] = interleaved[i * 2 + 1];
        }
    }
    const std::size_t length = trimmedLength(out);
    if (length == 0) {
        out = ConvolverIr{};
        error = "The IR is silent";
        return false;
    }
    out.left.resize(length);
    if (out.stereo()) {
        out.right.resize(length);
    }
    return true;
}

bool resampleConvolverIr(const ConvolverIr& in, double targetRate, ConvolverIr& out,
                         std::string& error) {
    if (in.frames() == 0 || in.sampleRate <= 0.0 || targetRate < 8000.0) {
        error = "No IR to resample";
        return false;
    }
    if (in.sampleRate > kConvolverMaxSourceRate) {
        error = "Unsupported IR sample rate " + std::to_string(static_cast<long long>(in.sampleRate)) + " Hz";
        return false;
    }
    const double ratio = targetRate / in.sampleRate;
    const double outFrames = std::ceil(static_cast<double>(in.frames()) * ratio);
    if (outFrames > static_cast<double>(kConvolverMaxIrFrames)) {
        error = "The IR is too long: " + std::to_string(static_cast<long long>(outFrames)) +
                " samples at " + std::to_string(static_cast<long long>(targetRate)) +
                " Hz, the limit is " + std::to_string(kConvolverMaxIrFrames);
        return false;
    }
    out = ConvolverIr{};
    out.channels = in.channels;
    out.sampleRate = targetRate;
    if (std::fabs(ratio - 1.0) < 1e-9) {
        out.left = in.left;
        out.right = in.right;
        return true;
    }
    const std::vector<double> table = resampleKernelTable();
    resampleChannel(in.left, ratio, table, out.left);
    if (in.stereo()) {
        resampleChannel(in.right, ratio, table, out.right);
    }
    // The ringing past the last tap, down to -120 dB, and never past the length limit (the check
    // above held for the source's own duration; what the limit cuts is the far end of that ringing).
    const std::size_t length = std::min<std::size_t>(std::max<std::size_t>(trimmedLength(out), 1),
                                                     kConvolverMaxIrFrames);
    out.left.resize(length);
    if (out.stereo()) {
        out.right.resize(length);
    }
    return true;
}

double convolverIrPeakGainDb(const ConvolverIr& ir) {
    if (ir.frames() == 0) {
        return -200.0;
    }
    // 8x zero-padded: the response is sampled finely enough that a sharp resonance between bins
    // is read within a few hundredths of a dB (2x under-read it by up to ~1 dB, loosening the
    // unity cap that 1 / peak sets). A one-off transform on the control thread.
    int n = 1024;
    while (static_cast<std::size_t>(n) < 8 * ir.frames()) {
        n *= 2;
    }
    PFFFTD_Setup* setup = pffftd_new_setup(n, PFFFT_REAL);
    double* buf = allocAligned(static_cast<std::size_t>(n));
    double* spec = allocAligned(static_cast<std::size_t>(n));
    // On the heap: with a null work buffer PFFFT puts n doubles of scratch on the stack -- 1 MiB
    // for a 65536-tap IR, an overflow on an Android control thread.
    double* work = allocAligned(static_cast<std::size_t>(n));
    // NaN when it couldn't be measured (allocation failed): never mistaken for a quiet IR, whose
    // -200 dB would lift the stage's unity cap.
    double peak = std::numeric_limits<double>::quiet_NaN();
    if (setup != nullptr && buf != nullptr && spec != nullptr && work != nullptr) {
        peak = 0.0;
        for (int c = 0; c < (ir.stereo() ? 2 : 1); ++c) {
            const auto& h = c == 0 ? ir.left : ir.right;
            std::memset(buf, 0, sizeof(double) * static_cast<std::size_t>(n));
            std::copy(h.begin(), h.end(), buf);
            pffftd_transform_ordered(setup, buf, spec, work, PFFFT_FORWARD);
            // Ordered real output: [DC, Nyquist, re1, im1, re2, im2, ...].
            peak = std::max({peak, std::fabs(spec[0]), std::fabs(spec[1])});
            for (int k = 1; k < n / 2; ++k) {
                peak = std::max(peak, std::hypot(spec[2 * k], spec[2 * k + 1]));
            }
        }
    }
    pffftd_aligned_free(work);
    pffftd_aligned_free(spec);
    pffftd_aligned_free(buf);
    if (setup != nullptr) {
        pffftd_destroy_setup(setup);
    }
    if (std::isnan(peak)) {
        return peak;
    }
    return peak > 0.0 ? 20.0 * std::log10(peak) : -200.0;
}

std::size_t convolverIrLatencyFrames(const ConvolverIr& ir) {
    std::size_t at = 0;
    double peak = -1.0;
    for (std::size_t i = 0; i < ir.frames(); ++i) {
        const double m = std::max(std::fabs(ir.left[i]), ir.stereo() ? std::fabs(ir.right[i]) : 0.0);
        if (m > peak) {
            peak = m;
            at = i;
        }
    }
    return at;
}

void convolverIrResponse(const std::vector<double>& h, double sampleRate, std::size_t latencyFrames,
                         const double* freqs, std::size_t count, double* out) {
    for (std::size_t i = 0; i < count; ++i) {
        const double w = 2.0 * kPi * freqs[i] / sampleRate;
        // H(w) e^(jwL) = sum_n h[n] e^(-jw(n - L)): a unit phasor rotated tap by tap, renormalised
        // every 1024 taps so rounding never grows its length over a 65536-tap IR.
        const double stepRe = std::cos(w), stepIm = -std::sin(w);
        const double start = w * static_cast<double>(latencyFrames);
        double pr = std::cos(start), pi = std::sin(start);
        double re = 0.0, im = 0.0;
        for (std::size_t n = 0; n < h.size(); ++n) {
            re += h[n] * pr;
            im += h[n] * pi;
            const double nr = pr * stepRe - pi * stepIm;
            pi = pr * stepIm + pi * stepRe;
            pr = nr;
            if ((n & 1023u) == 1023u) {
                const double len = std::hypot(pr, pi);
                pr /= len;
                pi /= len;
            }
        }
        out[2 * i] = re;
        out[2 * i + 1] = im;
    }
}

// ---- ConvolverKernel -----------------------------------------------------------------------

ConvolverKernel::~ConvolverKernel() {
    for (int c = 0; c < 2; ++c) {
        if (c == 0 || stereo_) {
            pffftd_aligned_free(ch_[c].spectra);
        }
        pffftd_aligned_free(ch_[c].fdl);
        pffftd_aligned_free(ch_[c].input);
        pffftd_aligned_free(ch_[c].output);
    }
    pffftd_aligned_free(fftWork_);
    pffftd_aligned_free(spectrum_);
    pffftd_aligned_free(sum_);
    if (setup_ != nullptr) {
        pffftd_destroy_setup(setup_);
    }
}

std::unique_ptr<ConvolverKernel> ConvolverKernel::build(const ConvolverIr& ir, std::string& error) {
    if (ir.frames() == 0) {
        error = "No IR loaded";
        return nullptr;
    }
    if (ir.frames() > kConvolverMaxIrFrames) {
        error = "The IR is too long";
        return nullptr;
    }
    std::unique_ptr<ConvolverKernel> k(new (std::nothrow) ConvolverKernel());
    if (!k) {
        error = "Out of memory building the convolver";
        return nullptr;
    }
    constexpr std::size_t N = kConvolverFftSize, B = kConvolverPartitionFrames;
    k->stereo_ = ir.stereo();
    k->partitions_ = (ir.frames() + B - 1) / B;
    k->setup_ = pffftd_new_setup(static_cast<int>(N), PFFFT_REAL);
    k->fftWork_ = allocAligned(N);
    k->spectrum_ = allocAligned(N);
    k->sum_ = allocAligned(N);
    bool ok = k->setup_ != nullptr && k->fftWork_ != nullptr && k->spectrum_ != nullptr && k->sum_ != nullptr;
    const std::size_t lineSize = k->partitions_ * N;
    for (int c = 0; c < 2 && ok; ++c) {
        Channel& ch = k->ch_[c];
        ch.spectra = (c == 0 || k->stereo_) ? allocAligned(lineSize) : k->ch_[0].spectra;
        ch.fdl = allocAligned(lineSize);
        ch.input = allocAligned(N);
        ch.output = allocAligned(B);
        ok = ch.spectra && ch.fdl && ch.input && ch.output;
    }
    if (!ok) {
        error = "Out of memory building the convolver";
        return nullptr;
    }
    // Partition k of the IR in the first half, zeros in the second: overlap-save then keeps the
    // second half of each inverse transform. The 1/N of the inverse FFT is folded in here.
    double* buf = k->sum_;
    for (int c = 0; c < (k->stereo_ ? 2 : 1); ++c) {
        const auto& h = c == 0 ? ir.left : ir.right;
        for (std::size_t p = 0; p < k->partitions_; ++p) {
            std::memset(buf, 0, N * sizeof(double));
            const std::size_t start = p * B;
            const std::size_t count = std::min(B, h.size() - start);
            for (std::size_t i = 0; i < count; ++i) {
                buf[i] = h[start + i] / static_cast<double>(N);
            }
            pffftd_transform(k->setup_, buf, k->ch_[c].spectra + p * N, k->fftWork_, PFFFT_FORWARD);
        }
    }
    k->peakGainDb_ = convolverIrPeakGainDb(ir);
    if (std::isnan(k->peakGainDb_)) {
        // Without the peak there is no unity cap: refuse rather than run uncapped.
        error = "Out of memory measuring the IR";
        return nullptr;
    }
    k->maxGain_ = std::pow(10.0, -k->peakGainDb_ / 20.0);
    k->latencyFrames_ = convolverIrLatencyFrames(ir);
    k->reset();
    return k;
}

void ConvolverKernel::reset() {
    constexpr std::size_t N = kConvolverFftSize, B = kConvolverPartitionFrames;
    for (auto& c : ch_) {
        std::memset(c.input, 0, N * sizeof(double));
        std::memset(c.output, 0, B * sizeof(double));
    }
    fill_ = 0;
    head_ = 0;
    validPartitions_ = 0;
}

void ConvolverKernel::completeBlock() {
    constexpr std::size_t N = kConvolverFftSize, B = kConvolverPartitionFrames;
    const std::size_t next = head_ + 1 == partitions_ ? 0 : head_ + 1;
    const std::size_t count = std::min(validPartitions_ + 1, partitions_);
    for (auto& c : ch_) {
        // X_t: [previous block | this block]. Kept in the delay line's next slot for the blocks
        // after it.
        pffftd_transform(setup_, c.input, c.fdl + next * N, fftWork_, PFFFT_FORWARD);
        // Y_t = sum over k of H_k X_(t-k): X_t at next, older blocks behind it.
        std::memset(sum_, 0, N * sizeof(double));
        std::size_t slot = next;
        for (std::size_t k = 0; k < count; ++k) {
            pffftd_zconvolve_accumulate(setup_, c.fdl + slot * N, c.spectra + k * N, sum_, 1.0);
            slot = slot == 0 ? partitions_ - 1 : slot - 1;
        }
        pffftd_transform(setup_, sum_, spectrum_, fftWork_, PFFFT_BACKWARD);
        std::memcpy(c.output, spectrum_ + B, B * sizeof(double));
        std::memcpy(c.input, c.input + B, B * sizeof(double));
    }
    head_ = next;
    validPartitions_ = count;
}

void ConvolverKernel::process(const double* inL, const double* inR, double* outL, double* outR,
                              std::size_t frames) {
    constexpr std::size_t B = kConvolverPartitionFrames;
    while (frames > 0) {
        const std::size_t count = std::min(frames, B - fill_);
        const double* in[2] = {inL, inR};
        double* out[2] = {outL, outR};
        for (int c = 0; c < 2; ++c) {
            // In before out: they may alias. Out is the previous block's result, one block late.
            std::memcpy(ch_[c].input + B + fill_, in[c], count * sizeof(double));
            std::memcpy(out[c], ch_[c].output + fill_, count * sizeof(double));
        }
        fill_ += count;
        if (fill_ == B) {
            completeBlock();
            fill_ = 0;
        }
        inL += count;
        inR += count;
        outL += count;
        outR += count;
        frames -= count;
    }
}

// ---- ConvolverStage ------------------------------------------------------------------------

void ConvolverStage::publish(std::unique_ptr<ConvolverKernel> kernel) {
    Update& slot = handoff_.back();
    // Whatever the audio thread retired into this slot is freed here, on the control thread.
    slot.kernel = std::move(kernel);
    handoff_.publish();
}

void ConvolverStage::setSampleRate(float sampleRate) {
    if (sampleRate < 8000.f) {
        return;
    }
    fadeStep_ = 1.0 / (static_cast<double>(kFadeSeconds) * sampleRate);
    dipStep_ = 1.0 / (static_cast<double>(kDipSeconds) * sampleRate);
    gainCoef_ = 1.0 - std::exp(-1.0 / (static_cast<double>(kGainRampSeconds) * sampleRate));
}

void ConvolverStage::clearHistory() {
    if (active_) {
        active_->reset();
    }
    // The previous kernel's history is as suspect as the active one's: drop the swap fade rather
    // than keep convolving with it.
    swapMix_ = 0.0;
    swapHold_ = 0;
}

void ConvolverStage::snap(bool enabled, float gainDb) {
    gain_ = std::pow(10.0, static_cast<double>(gainDb) / 20.0);
    // Wherever it was, it lands on the path the setting asks for at full level; the convolved
    // path starts from silence, as after reset().
    path_ = enabled && active_ ? Path::Convolved : Path::Direct;
    if (active_) {
        active_->reset();
    }
    level_ = 1.0;
    hold_ = 0;
    swapMix_ = 0.0;
    swapHold_ = 0;
}

void ConvolverStage::process(float* left, float* right, std::size_t frames, bool enabled,
                             float gainDb) {
    constexpr std::size_t L = kConvolverLatencyFrames;
    // A new kernel (or an unload) is taken when no swap is in progress.
    if (swapMix_ <= 0.0 && swapHold_ == 0) {
        if (Update* update = handoff_.take()) {
            std::unique_ptr<ConvolverKernel> incoming = std::move(update->kernel);
            // The kernel retired two swaps ago goes back to the writer, which frees it.
            update->kernel = std::move(previous_);
            previous_ = std::move(active_);
            active_ = std::move(incoming);
            kernelActive_.store(active_ != nullptr, std::memory_order_relaxed);
            if (active_) {
                active_->reset();
            }
            if (path_ == Path::Convolved && previous_ && active_) {
                // IR to IR while convolving: the old one plays on alone until the new one's first
                // block is out, then they crossfade (both are one block late, so in step).
                swapMix_ = 1.0;
                swapHold_ = L;
            } else if (path_ == Path::Convolved && previous_ && !active_) {
                // Unloaded while convolving: the old one plays out through the dip to direct.
                swapMix_ = 1.0;
            }
            // previous_ is never freed here (the audio thread): it goes back to the writer with
            // the next swap.
        }
    }
    const double targetGain = std::pow(10.0, static_cast<double>(gainDb) / 20.0);
    const bool wantConvolved = enabled && active_ != nullptr;
    // Direct and staying direct at full level: a true bypass, the chain's samples untouched.
    if (path_ == Path::Direct && !wantConvolved && level_ >= 1.0) {
        gain_ = targetGain;
        return;
    }
    for (std::size_t i = 0; i < frames; ++i) {
        dry_[0][i] = left[i];
        dry_[1][i] = right[i];
    }
    if (path_ == Path::Convolved) {
        if (active_) {
            active_->process(dry_[0], dry_[1], wet_[0], wet_[1], frames);
        }
        if (swapMix_ > 0.0 && previous_) {
            previous_->process(dry_[0], dry_[1], old_[0], old_[1], frames);
        }
    }
    for (std::size_t i = 0; i < frames; ++i) {
        gain_ += (targetGain - gain_) * gainCoef_;
        const bool onWantedPath = (path_ == Path::Convolved) == wantConvolved;
        if (!onWantedPath) {
            // Down to silence on the path playing now, then switch. The new path starts clean;
            // the convolved one stays silent until its first block is out.
            level_ = std::max(0.0, level_ - dipStep_);
            if (level_ <= 0.0) {
                path_ = wantConvolved ? Path::Convolved : Path::Direct;
                if (path_ == Path::Convolved) {
                    // It sat unused: its history is stale. It is fed from the next call on, so
                    // the hold covers the rest of this chunk and then its first full block.
                    hold_ = L + (frames - i - 1);
                    active_->reset();
                    for (int c = 0; c < 2; ++c) {
                        wet_[c][i] = 0.0;
                    }
                } else {
                    swapMix_ = 0.0;
                    swapHold_ = 0;
                }
            }
        } else if (hold_ > 0) {
            --hold_;
        } else {
            level_ = std::min(1.0, level_ + fadeStep_);
        }
        for (int c = 0; c < 2; ++c) {
            double y = dry_[c][i];
            if (path_ == Path::Convolved) {
                const double cur = active_ ? std::min(gain_, active_->maxGain()) * wet_[c][i] : 0.0;
                y = cur;
                if (swapMix_ > 0.0) {
                    const double prev = previous_ ? std::min(gain_, previous_->maxGain()) * old_[c][i] : 0.0;
                    y = swapMix_ * prev + (1.0 - swapMix_) * cur;
                }
            }
            (c == 0 ? left : right)[i] = static_cast<float>(level_ * y);
        }
        if (swapHold_ > 0) {
            --swapHold_;
        } else if (swapMix_ > 0.0 && active_) {
            swapMix_ = std::max(0.0, swapMix_ - fadeStep_);
        }
    }
    if (std::fabs(gain_ - targetGain) < 1e-9) {
        gain_ = targetGain;
    }
}

}  // namespace NativeBmwDsp
