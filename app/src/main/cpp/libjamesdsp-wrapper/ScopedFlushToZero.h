#ifndef SIPHONDSP_SCOPED_FLUSH_TO_ZERO_H
#define SIPHONDSP_SCOPED_FLUSH_TO_ZERO_H

#include <cstdint>

#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

// Flushes denormals to zero on this thread for the lifetime of the object, then puts the
// floating-point control register back exactly as it was.
//
// Denormal (subnormal) arithmetic can be many times slower than normal arithmetic, and a decaying
// filter or convolver tail sits in that range during fades and silence. The BMW chain flushes its
// own recursive state by hand (ftz()/ftzd()), but JamesDSP's convolver and limiter don't. Setting
// the mode for the duration of a process call covers everything that call runs.
//
// It must be restored on the way out: the audio thread is a Java thread, and Java code on it
// relies on IEEE denormals. Reading and writing the control register costs a few cycles, twice
// per buffer.
//
// The compiler doesn't know the mode affects floating-point instructions, so it may move pure
// register arithmetic across the switch. The "memory" clobbers make each switch a barrier for
// anything that touches memory or calls out -- which is how the guarded code runs: whole calls
// into the DSP, never inline arithmetic in the guard's own scope.
class ScopedFlushToZero {
public:
    ScopedFlushToZero() {
#if defined(__aarch64__)
        __asm__ __volatile__("mrs %0, fpcr" : "=r"(saved_));
        const std::uint64_t flushing = saved_ | kFpcrFz;
        changed_ = flushing != saved_;
        if (changed_) {
            __asm__ __volatile__("msr fpcr, %0" : : "r"(flushing) : "memory");
        }
#elif defined(__x86_64__) || defined(__i386__)
        saved_ = _mm_getcsr();
        const auto flushing = static_cast<unsigned>(saved_) | kMxcsrFtz | kMxcsrDaz;
        changed_ = flushing != saved_;
        if (changed_) {
            _mm_setcsr(flushing);
        }
#endif
    }

    ~ScopedFlushToZero() {
        if (!changed_) {
            return;
        }
#if defined(__aarch64__)
        __asm__ __volatile__("msr fpcr, %0" : : "r"(saved_) : "memory");
#elif defined(__x86_64__) || defined(__i386__)
        _mm_setcsr(static_cast<unsigned>(saved_));
#endif
    }

    ScopedFlushToZero(const ScopedFlushToZero&) = delete;
    ScopedFlushToZero& operator=(const ScopedFlushToZero&) = delete;

private:
    // AArch64 FPCR.FZ: flush single- and double-precision denormal inputs and results to zero.
    static constexpr std::uint64_t kFpcrFz = std::uint64_t{1} << 24;
    // x86 MXCSR: flush denormal results (FTZ) and treat denormal inputs as zero (DAZ).
    static constexpr unsigned kMxcsrFtz = 1u << 15;
    static constexpr unsigned kMxcsrDaz = 1u << 6;

    std::uint64_t saved_ = 0;
    bool changed_ = false;
};

#endif
