#ifndef SIPHONDSP_NATIVE_BMW_CONVOLVER_H
#define SIPHONDSP_NATIVE_BMW_CONVOLVER_H

// Stereo FIR convolver: the last stage of the chain, after the master limiter, volume and
// loudness (see NativeBmwDspProcessor::processChunk()).
//
// Uniformly partitioned overlap-save (UPOLS) with a frequency-domain delay line, partition size
// kBlockFrames == the processor's chunk (kChunkFrames, 64 frames), FFT size 2 * kBlockFrames.
// Zero added latency: every call transforms the block in progress (the samples received so far,
// zero-padded) together with the previous block, so the output for a sample is ready in the same
// call that delivered it. With the processor's full 64-frame chunks each call completes exactly
// one block, so nothing is transformed twice; a short trailing chunk costs one extra FFT pair.
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
    kConvolverBlockFrames = 64,
    kConvolverFftSize = 2 * kConvolverBlockFrames,
    // Longest IR accepted, in frames at the stream rate, after trailing silence is trimmed:
    // 1024 partitions, ~1.37 s at 48 kHz. The per-block cost grows linearly with it.
    kConvolverMaxIrFrames = 65536,
    // Longest source file read at all (frames at the file's own rate), so a stray huge file is
    // rejected before a large allocation.
    kConvolverMaxSourceFrames = 1u << 20,
};

// Reads a 1- or 2-channel WAV (any PCM/float format dr_wav decodes) into out and trims trailing
// silence (below -120 dB re. the IR's peak). False with a user-facing message in error.
bool loadConvolverIrWav(const char* path, ConvolverIr& out, std::string& error);
// The IR at targetRate: a copy when the rates already match, otherwise a band-limited
// (Kaiser-windowed sinc) resample that keeps the IR's frequency response, level included.
bool resampleConvolverIr(const ConvolverIr& in, double targetRate, ConvolverIr& out,
                         std::string& error);
// Peak magnitude of the IR's frequency response across both channels, dB (0 = unity at its
// loudest frequency). Off the audio thread; allocates.
double convolverIrPeakGainDb(const ConvolverIr& ir);

// An IR transformed into partition spectra plus the running convolution state for both
// channels. Built off the audio thread; process() and reset() are allocation-free.
class ConvolverKernel {
public:
    ~ConvolverKernel();
    ConvolverKernel(const ConvolverKernel&) = delete;
    ConvolverKernel& operator=(const ConvolverKernel&) = delete;
    // ir must already be at the stream rate. Null with error set on failure.
    static std::unique_ptr<ConvolverKernel> build(const ConvolverIr& ir, std::string& error);
    // Forgets the input history in O(1): the delay line's spectra are treated as silent until
    // they are overwritten (validPartitions_), so no memset of the whole line on the audio thread.
    void reset();
    // Convolves frames (<= kConvolverBlockFrames) of each channel; in and out may alias.
    void process(const double* inL, const double* inR, double* outL, double* outR,
                 std::size_t frames);
    std::size_t partitions() const { return partitions_; }

private:
    ConvolverKernel() = default;
    struct Channel {
        double* spectra = nullptr;  // partitions_ x kConvolverFftSize: H_k, pre-scaled by 1/N
        double* fdl = nullptr;      // partitions_ x kConvolverFftSize: past input spectra (ring)
        double* input = nullptr;    // kConvolverFftSize: [previous block | block in progress]
        double* tail = nullptr;     // kConvolverFftSize: sum over k >= 1 of H_k X_(t-k)
        double* work = nullptr;     // kConvolverFftSize: the current block's input spectrum X
    };
    void processChannel(Channel& c, const double* in, double* out, std::size_t frames,
                        bool completesBlock);
    void accumulateTail(Channel& c);
    Channel ch_[2];
    double* fftWork_ = nullptr;
    double* spectrum_ = nullptr;  // kConvolverFftSize: Y = tail + H_0 X, then its inverse
    PFFFTD_Setup* setup_ = nullptr;
    std::size_t partitions_ = 0;
    // Samples of the block in progress received so far (0 .. kConvolverBlockFrames - 1).
    std::size_t fill_ = 0;
    // Ring position of the newest complete block's spectrum.
    std::size_t head_ = 0;
    // Delay-line slots holding real history (0 after reset(), up to partitions_).
    std::size_t validPartitions_ = 0;
    bool stereo_ = false;
};

// The stage the processor runs: the current kernel, the one it is fading away from, the IR
// gain and the enable/bypass crossfade.
class ConvolverStage {
public:
    // Control thread (one caller at a time): hands the audio thread a new kernel, or null to
    // unload. Frees whatever kernels the audio thread has retired since the last call.
    void publish(std::unique_ptr<ConvolverKernel> kernel);
    // Audio thread: one chunk (frames <= kConvolverBlockFrames) of logical L/R, in place.
    // enabled/gainDb are the stage's settings (p_.convolverEnabled / convolverGainDb).
    void process(float* left, float* right, std::size_t frames, bool enabled, float gainDb);
    // Sample-rate dependent ramp constants.
    void setSampleRate(float sampleRate);
    // Snap the enable mix and gain to their targets (start-up / full rebuild).
    void snap(bool enabled, float gainDb);
    // True while a kernel is installed on the audio side (relaxed; diagnostics only).
    bool kernelActive() const { return kernelActive_.load(std::memory_order_relaxed); }

    static constexpr float kFadeSeconds = 0.02f;
    static constexpr float kGainRampSeconds = 0.01f;

private:
    struct Update {
        std::unique_ptr<ConvolverKernel> kernel;
    };
    TripleBuffer<Update> handoff_;
    // Audio-thread state.
    std::unique_ptr<ConvolverKernel> active_, previous_;
    double enableMix_ = 0.0;   // 0 = dry, 1 = convolved; linear ramp over kFadeSeconds
    double swapMix_ = 0.0;     // weight of previous_ during a kernel swap, 1 -> 0
    double gain_ = 1.0;        // linear IR gain, one-pole toward the setting
    double fadeStep_ = 1.0 / (0.02 * 48000.0);
    double gainCoef_ = 0.0020811617;  // 1 - exp(-1 / (10 ms * 48 kHz))
    bool needsReset_ = false;  // the stage sat fully bypassed: history is stale
    std::atomic<bool> kernelActive_{false};
    double dry_[2][kConvolverBlockFrames]{};
    double wet_[2][kConvolverBlockFrames]{};
    double old_[2][kConvolverBlockFrames]{};
};

}  // namespace NativeBmwDsp

#endif
