#ifndef SIPHONDSP_NATIVE_BMW_CONVOLVER_H
#define SIPHONDSP_NATIVE_BMW_CONVOLVER_H

// Stereo FIR convolver: right after the band sum (polarity/mute, tilt, post gain, channel mute,
// measurement bus) and ahead of the master limiter, volume, Deep bass and the hardware swap (see
// NativeBmwDspProcessor::processChunk()). Ahead of the limiter because a phase-correcting IR
// raises the crest factor without raising the magnitude: its peaks must pass through the limiter.
//
// Uniformly partitioned overlap-save (UPOLS) with a frequency-domain delay line, partition size
// kConvolverPartitionFrames (1024), FFT size twice that. The convolved signal is one partition
// late (kConvolverLatencyFrames, 21.3 ms at 48 kHz): each completed block of input is transformed
// once, convolved with every partition at once and played out during the next block. Larger
// partitions trade that latency (fine for music) for far less work per sample than the 64-frame,
// zero-latency partitions this replaced -- about 6x less for an 8192-tap IR, 13x at the maximum
// length. The processor feeds it any chunk size (up to kConvolverBlockFrames per call).
//
// The stage keeps the latency out of everything else: switched off it is a true, zero-latency
// bypass (so measurement neutral and the crossover bypass stay a wire), and every change between
// the convolved and the direct path dips the output to silence, switches, and fades back in, so
// the two never mix out of step (see ConvolverStage::process()).
//
// Double precision throughout: the IR spectra, the delay line, the FFTs (PFFFT's double build:
// NEON on arm64, SSE2 on x86-64 hosts) and the gain/crossfade arithmetic. The chain's float
// samples are widened on the way in and narrowed once on the way out.
//
// Threading. Everything that allocates -- reading the WAV, resampling, transforming the IR into
// partition spectra, allocating the delay line -- happens in ConvolverKernel::build() on the
// calling (control) thread. The audio thread receives a finished kernel through a lock-free
// triple buffer (ConvolverStage::publish() / process()), crossfades from the old kernel to the
// new one and hands the old one back through the same buffer, so it is freed by the next
// publish() on the control thread, never on the audio thread.

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include "NativeBmwHandoff.h"

struct PFFFTD_Setup;

namespace NativeBmwDsp {

// A decoded impulse response. Mono IRs keep right empty and are applied to both channels.
struct ConvolverIr {
    std::vector<double> left, right;
    double sampleRate = 0.0;
    // Channels in the source file (1 or 2) -- reported to the UI.
    int channels = 0;
    std::size_t frames() const { return left.size(); }
    bool stereo() const { return !right.empty(); }
};

enum : std::size_t {
    // Most frames one ConvolverStage::process() / ConvolverKernel::process() call takes: the
    // processor's chunk.
    kConvolverBlockFrames = 64,
    kConvolverPartitionFrames = 1024,
    kConvolverLatencyFrames = kConvolverPartitionFrames,
    kConvolverFftSize = 2 * kConvolverPartitionFrames,
    // Longest IR accepted, in frames at the stream rate, after trailing silence is trimmed:
    // 1024 partitions, ~1.37 s at 48 kHz. The per-block cost grows linearly with it.
    kConvolverMaxIrFrames = 65536,
    // Longest source file read at all (frames at the file's own rate), so a stray huge file is
    // rejected before a large allocation.
    kConvolverMaxSourceFrames = 1u << 20,
};

// Highest IR file rate accepted. Bounds the resampler's work (its taps per output sample grow
// with source / stream rate).
constexpr double kConvolverMaxSourceRate = 384000.0;

// Reads a 1- or 2-channel WAV (any PCM/float format dr_wav decodes) into out and trims trailing
// silence (below -120 dB re. the IR's peak). False with a user-facing message in error.
bool loadConvolverIrWav(const char* path, ConvolverIr& out, std::string& error);
// The IR at targetRate: a copy when the rates already match, otherwise a band-limited
// (Kaiser-windowed sinc) resample that keeps the IR's frequency response, level included.
bool resampleConvolverIr(const ConvolverIr& in, double targetRate, ConvolverIr& out,
                         std::string& error);
// Peak magnitude of the IR's frequency response across both channels, dB (0 = unity at its
// loudest frequency; -200 for a silent IR). NaN if it couldn't be measured (allocation failed).
// Off the audio thread; allocates.
double convolverIrPeakGainDb(const ConvolverIr& ir);
// The IR's bulk latency: the index of its largest-magnitude sample across both channels (the
// first, on a tie), in frames at the IR's own rate. 0 for an empty IR.
std::size_t convolverIrLatencyFrames(const ConvolverIr& ir);
// The response of taps h (at sampleRate) at each of count frequencies (Hz), with latencyFrames of
// bulk delay taken out so the phase shows the correction rather than a pure delay wrapping:
// out[2i], out[2i+1] = re, im of H(f_i) * e^(+j 2 pi f_i latencyFrames / sampleRate). A direct
// DTFT, O(taps x count): off the audio thread only.
void convolverIrResponse(const std::vector<double>& h, double sampleRate, std::size_t latencyFrames,
                         const double* freqs, std::size_t count, double* out);

// An IR transformed into partition spectra plus the running convolution state for both
// channels. Built off the audio thread; process() and reset() are allocation-free.
class ConvolverKernel {
public:
    ~ConvolverKernel();
    ConvolverKernel(const ConvolverKernel&) = delete;
    ConvolverKernel& operator=(const ConvolverKernel&) = delete;
    // ir must already be at the stream rate. Null with error set on failure.
    static std::unique_ptr<ConvolverKernel> build(const ConvolverIr& ir, std::string& error);
    // Forgets the input history: the delay line's spectra are treated as silent until they are
    // overwritten (validPartitions_), so no memset of the whole line on the audio thread. The
    // first kConvolverLatencyFrames of output after it are silent.
    void reset();
    // Convolves any number of frames of each channel, kConvolverLatencyFrames late; in and out
    // may alias.
    void process(const double* inL, const double* inR, double* outL, double* outR,
                 std::size_t frames);
    std::size_t partitions() const { return partitions_; }
    // The IR's loudest frequency, dB, and the largest linear gain the stage applies to it
    // (1 / peak: the gain that brings that frequency to unity). See ConvolverStage::process().
    double peakGainDb() const { return peakGainDb_; }
    double maxGain() const { return maxGain_; }
    // The IR's bulk latency in frames at the stream rate (convolverIrLatencyFrames()).
    std::size_t latencyFrames() const { return latencyFrames_; }

private:
    ConvolverKernel() = default;
    struct Channel {
        double* spectra = nullptr;  // partitions_ x kConvolverFftSize: H_k, pre-scaled by 1/N
        double* fdl = nullptr;      // partitions_ x kConvolverFftSize: past input spectra (ring)
        double* input = nullptr;    // kConvolverFftSize: [previous block | block in progress]
        double* output = nullptr;   // kConvolverPartitionFrames: the last block's result, playing
    };
    // The block in progress is complete: transform it, convolve, queue its result.
    void completeBlock();
    Channel ch_[2];
    double* fftWork_ = nullptr;
    double* spectrum_ = nullptr;  // kConvolverFftSize: the block's input spectrum X
    double* sum_ = nullptr;       // kConvolverFftSize: Y = sum over k of H_k X_(t-k), then its inverse
    PFFFTD_Setup* setup_ = nullptr;
    std::size_t partitions_ = 0;
    // Samples of the block in progress received so far (0 .. kConvolverPartitionFrames - 1).
    std::size_t fill_ = 0;
    // Ring position of the newest complete block's spectrum.
    std::size_t head_ = 0;
    // Delay-line slots holding real history (0 after reset(), up to partitions_).
    std::size_t validPartitions_ = 0;
    bool stereo_ = false;
    double peakGainDb_ = 0.0;
    double maxGain_ = 1.0;
    std::size_t latencyFrames_ = 0;
};

// The stage the processor runs: the current kernel, the one it is fading away from, the IR
// gain and the enable/bypass crossfade.
class ConvolverStage {
public:
    // Control thread (one caller at a time): hands the audio thread a new kernel, or null to
    // unload. Frees whatever kernels the audio thread has retired since the last call.
    void publish(std::unique_ptr<ConvolverKernel> kernel);
    // Audio thread: one chunk (frames <= kConvolverBlockFrames) of logical L/R, in place.
    // enabled/gainDb are the stage's settings (p_.convolverEnabled / convolverGainDb). Off (or
    // with no IR) the samples pass untouched, with no latency; on, the convolved signal is
    // kConvolverLatencyFrames late. The gain
    // applied is min(gainDb, -kernel peak dB), so the stage never lifts any frequency above unity
    // -- an IR that boosts somewhere is brought down to 0 dB at its loudest frequency, and the
    // trim can only go below that. The master limiter after it catches the time-domain peaks a
    // phase-only change raises; the cap keeps the IR from eating into the limiter's headroom (or
    // driving it hard) through magnitude boost.
    void process(float* left, float* right, std::size_t frames, bool enabled, float gainDb);
    // Sample-rate dependent ramp constants.
    void setSampleRate(float sampleRate);
    // Snap the gain to its target, and the path to the one the setting asks for when that needs
    // no history (start-up / full rebuild / a cleared chain).
    void snap(bool enabled, float gainDb);
    // True while a kernel is installed on the audio side (relaxed; diagnostics only).
    bool kernelActive() const { return kernelActive_.load(std::memory_order_relaxed); }
    // Audio thread: forgets the running kernel's input history and ends any kernel-swap fade
    // (the processor clearing the whole chain). The kernel itself stays. Like a fresh start, the
    // convolved path is then silent for its first kConvolverLatencyFrames.
    void clearHistory();
    // True while the stage plays the convolved (latent) path. Audio thread.
    bool convolving() const { return path_ == Path::Convolved; }

    // A path change: down to silence over kDipSeconds, switch, then up over kFadeSeconds (after
    // the convolved path's first block when switching to it). A kernel swap: crossfade over
    // kFadeSeconds once the new kernel's first block is out.
    static constexpr float kDipSeconds = 0.005f;
    static constexpr float kFadeSeconds = 0.02f;
    static constexpr float kGainRampSeconds = 0.01f;

private:
    struct Update {
        std::unique_ptr<ConvolverKernel> kernel;
    };
    TripleBuffer<Update> handoff_;
    // Audio-thread state.
    enum class Path : std::uint8_t { Direct, Convolved };
    std::unique_ptr<ConvolverKernel> active_, previous_;
    Path path_ = Path::Direct;
    double level_ = 1.0;        // the output level through a path change (dip, switch, fade in)
    std::size_t hold_ = 0;      // frames to stay silent: the convolved path's first block
    double swapMix_ = 0.0;      // weight of previous_ during a kernel swap, 1 -> 0
    std::size_t swapHold_ = 0;  // frames previous_ stays alone: the new kernel's first block
    double gain_ = 1.0;         // linear IR gain, one-pole toward the setting
    double fadeStep_ = 1.0 / (0.02 * 48000.0);
    double dipStep_ = 1.0 / (0.005 * 48000.0);
    double gainCoef_ = 0.0020811617;  // 1 - exp(-1 / (10 ms * 48 kHz))
    std::atomic<bool> kernelActive_{false};
    double dry_[2][kConvolverBlockFrames]{};
    double wet_[2][kConvolverBlockFrames]{};
    double old_[2][kConvolverBlockFrames]{};
};

}  // namespace NativeBmwDsp

#endif
