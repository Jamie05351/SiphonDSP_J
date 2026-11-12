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
        pffftd_aligned_free(ch_[c].tail);
        pffftd_aligned_free(ch_[c].work);
    }
    pffftd_aligned_free(fftWork_);
    pffftd_aligned_free(spectrum_);
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
    constexpr std::size_t N = kConvolverFftSize, B = kConvolverBlockFrames;
    k->stereo_ = ir.stereo();
    k->partitions_ = (ir.frames() + B - 1) / B;
    k->setup_ = pffftd_new_setup(static_cast<int>(N), PFFFT_REAL);
    k->fftWork_ = allocAligned(N);
    k->spectrum_ = allocAligned(N);
    bool ok = k->setup_ != nullptr && k->fftWork_ != nullptr && k->spectrum_ != nullptr;
    const std::size_t lineSize = k->partitions_ * N;
    for (int c = 0; c < 2 && ok; ++c) {
        Channel& ch = k->ch_[c];
        ch.spectra = (c == 0 || k->stereo_) ? allocAligned(lineSize) : k->ch_[0].spectra;
        ch.fdl = allocAligned(lineSize);
        ch.input = allocAligned(N);
        ch.tail = allocAligned(N);
        ch.work = allocAligned(N);
        ok = ch.spectra && ch.fdl && ch.input && ch.tail && ch.work;
    }
    if (!ok) {
        error = "Out of memory building the convolver";
        return nullptr;
    }
    // Partition k of the IR in the first half, zeros in the second: overlap-save then keeps the
    // second half of each inverse transform. The 1/N of the inverse FFT is folded in here.
    double* buf = k->ch_[0].work;
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
    constexpr std::size_t N = kConvolverFftSize;
    for (auto& c : ch_) {
        std::memset(c.input, 0, N * sizeof(double));
        std::memset(c.tail, 0, N * sizeof(double));
    }
    fill_ = 0;
    head_ = 0;
    validPartitions_ = 0;
}

void ConvolverKernel::accumulateTail(Channel& c) {
    constexpr std::size_t N = kConvolverFftSize;
    std::memset(c.tail, 0, N * sizeof(double));
    // Y_(t+1) = H_0 X_(t+1) + sum_(k>=1) H_k X_(t+1-k); X_t sits at head_, older blocks behind it.
    const std::size_t count = std::min(validPartitions_, partitions_ - 1);
    std::size_t slot = head_;
    for (std::size_t k = 1; k <= count; ++k) {
        pffftd_zconvolve_accumulate(setup_, c.fdl + slot * N, c.spectra + k * N, c.tail, 1.0);
        slot = slot == 0 ? partitions_ - 1 : slot - 1;
    }
}

void ConvolverKernel::processChannel(Channel& c, const double* in, double* out,
                                     std::size_t frames, bool completesBlock) {
    constexpr std::size_t N = kConvolverFftSize, B = kConvolverBlockFrames;
    std::memcpy(c.input + B + fill_, in, frames * sizeof(double));
    // X: [previous block | block so far, zero-padded]. Kept in work until the block completes.
    pffftd_transform(setup_, c.input, c.work, fftWork_, PFFFT_FORWARD);
    // A completing block's spectrum is kept in the delay line's next slot (accumulateTail()
    // reads it from there for the following blocks).
    if (completesBlock) {
        const std::size_t next = head_ + 1 == partitions_ ? 0 : head_ + 1;
        std::memcpy(c.fdl + next * N, c.work, N * sizeof(double));
    }
    // Y = tail + H_0 X, in a separate buffer: pffft's convolve routines declare their pointers
    // restrict, so never alias them.
    std::memcpy(spectrum_, c.tail, N * sizeof(double));
    pffftd_zconvolve_accumulate(setup_, c.work, c.spectra, spectrum_, 1.0);
    pffftd_transform(setup_, spectrum_, spectrum_, fftWork_, PFFFT_BACKWARD);
    for (std::size_t i = 0; i < frames; ++i) {
        out[i] = spectrum_[B + fill_ + i];
    }
}

void ConvolverKernel::process(const double* inL, const double* inR, double* outL, double* outR,
                              std::size_t frames) {
    constexpr std::size_t B = kConvolverBlockFrames;
    while (frames > 0) {
        const std::size_t count = std::min(frames, B - fill_);
        const bool completes = fill_ + count == B;
        processChannel(ch_[0], inL, outL, count, completes);
        processChannel(ch_[1], inR, outR, count, completes);
        if (completes) {
            head_ = head_ + 1 == partitions_ ? 0 : head_ + 1;
            validPartitions_ = std::min(validPartitions_ + 1, partitions_);
            for (auto& c : ch_) {
                std::memcpy(c.input, c.input + B, B * sizeof(double));
                std::memset(c.input + B, 0, B * sizeof(double));
            }
            fill_ = 0;
            accumulateTail(ch_[0]);
            accumulateTail(ch_[1]);
        } else {
            fill_ += count;
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
    gainCoef_ = 1.0 - std::exp(-1.0 / (static_cast<double>(kGainRampSeconds) * sampleRate));
}

void ConvolverStage::clearHistory() {
    if (active_) {
        active_->reset();
    }
    // The previous kernel's history is as suspect as the active one's: drop the swap fade rather
    // than keep convolving with it.
    swapMix_ = 0.0;
}

void ConvolverStage::snap(bool enabled, float gainDb) {
    enableMix_ = enabled ? 1.0 : 0.0;
    gain_ = std::pow(10.0, static_cast<double>(gainDb) / 20.0);
}

void ConvolverStage::process(float* left, float* right, std::size_t frames, bool enabled,
                             float gainDb) {
    // A kernel swap waits until the previous one has faded out.
    if (swapMix_ <= 0.0) {
        if (Update* update = handoff_.take()) {
            std::unique_ptr<ConvolverKernel> incoming = std::move(update->kernel);
            // The kernel retired two swaps ago goes back to the writer, which frees it.
            update->kernel = std::move(previous_);
            previous_ = std::move(active_);
            active_ = std::move(incoming);
            kernelActive_.store(active_ != nullptr, std::memory_order_relaxed);
            if (active_) {
                active_->reset();
                // Only audible while the stage is switched on; a bypassed load changes nothing.
                kernelArrived_ = enabled;
            }
            swapMix_ = (enableMix_ > 0.0 && !needsReset_) ? 1.0 : 0.0;
        }
    }
    const double targetGain = std::pow(10.0, static_cast<double>(gainDb) / 20.0);
    const double target = enabled ? 1.0 : 0.0;
    // Fully bypassed, or nothing to convolve with: the chain's samples pass untouched.
    if ((enableMix_ <= 0.0 && target <= 0.0) || (!active_ && swapMix_ <= 0.0)) {
        // With no kernel the stage is dry either way; holding the mix at 0 makes a kernel that
        // arrives while enabled fade in rather than cut in.
        if (!active_) {
            enableMix_ = 0.0;
        }
        gain_ = targetGain;
        needsReset_ = true;
        return;
    }
    if (needsReset_) {
        // Coming out of bypass: the delay lines hold stale history.
        if (active_) {
            active_->reset();
        }
        swapMix_ = 0.0;
        needsReset_ = false;
    }
    for (std::size_t i = 0; i < frames; ++i) {
        dry_[0][i] = left[i];
        dry_[1][i] = right[i];
    }
    if (active_) {
        active_->process(dry_[0], dry_[1], wet_[0], wet_[1], frames);
    }
    const bool fadingSwap = swapMix_ > 0.0;
    if (fadingSwap && previous_) {
        previous_->process(dry_[0], dry_[1], old_[0], old_[1], frames);
    }
    for (std::size_t i = 0; i < frames; ++i) {
        gain_ += (targetGain - gain_) * gainCoef_;
        enableMix_ = target > enableMix_ ? std::min(target, enableMix_ + fadeStep_)
                                         : std::max(target, enableMix_ - fadeStep_);
        for (int c = 0; c < 2; ++c) {
            const double dry = dry_[c][i];
            const double cur = active_ ? std::min(gain_, active_->maxGain()) * wet_[c][i] : dry;
            double mixed = cur;
            if (fadingSwap) {
                const double prev = previous_ ? std::min(gain_, previous_->maxGain()) * old_[c][i] : dry;
                mixed = swapMix_ * prev + (1.0 - swapMix_) * cur;
            }
            const double y = enableMix_ * mixed + (1.0 - enableMix_) * dry;
            (c == 0 ? left : right)[i] = static_cast<float>(y);
        }
        if (fadingSwap) {
            swapMix_ = std::max(0.0, swapMix_ - fadeStep_);
        }
    }
    if (std::fabs(gain_ - targetGain) < 1e-9) {
        gain_ = targetGain;
    }
}

}  // namespace NativeBmwDsp
