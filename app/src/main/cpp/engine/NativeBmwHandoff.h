#ifndef SIPHONDSP_NATIVE_BMW_HANDOFF_H
#define SIPHONDSP_NATIVE_BMW_HANDOFF_H

#include <array>
#include <atomic>
#include <cstdint>

namespace NativeBmwDsp {

// Lock-free, wait-free, allocation-free handoff of the latest value from one writer to one reader
// (a triple buffer). The writer fills back() and publish()es it; the reader take()s the newest
// published value, if there is one it hasn't taken yet. A value published before the reader got
// to it is simply replaced by the next one: only the latest matters.
//
// One writer and one reader at a time -- the caller serialises each side with its own lock (or
// thread). Neither side ever waits for the other.
template <class T>
class TripleBuffer {
public:
    // Writer: the slot to fill. It is the writer's alone until publish().
    T& back() { return slots_[back_]; }
    // Writer: hands back() to the reader; back() is then a different, free slot.
    void publish() {
        back_ = static_cast<std::uint8_t>(middle_.exchange(back_ | kFresh, std::memory_order_acq_rel) & kIndex);
    }
    // Reader: the newest published value not taken before, or nullptr. Valid until the next take().
    T* take() {
        if ((middle_.load(std::memory_order_acquire) & kFresh) == 0) {
            return nullptr;
        }
        front_ = static_cast<std::uint8_t>(middle_.exchange(front_, std::memory_order_acq_rel) & kIndex);
        return &slots_[front_];
    }

private:
    static constexpr std::uint8_t kIndex = 3, kFresh = 4;
    std::array<T, 3> slots_{};
    std::uint8_t back_ = 0;                // writer-owned
    std::atomic<std::uint8_t> middle_{1};  // shared: slot index | kFresh once published
    std::uint8_t front_ = 2;               // reader-owned
};

}  // namespace NativeBmwDsp

#endif
