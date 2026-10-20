#include "doctest/doctest.h"

#include "ScopedFlushToZero.h"

#include <cfloat>

// The guard the JNI process calls take (JamesDspWrapperHardened.cpp): denormals flush to zero
// inside the scope, and the thread's floating-point mode is exactly restored after it.

namespace {
// volatile so the products are computed at run time, under whatever mode is current.
volatile float gTiny = FLT_MIN;  // smallest normal float
volatile float gHalf = 0.5f;
volatile double gTinyD = DBL_MIN;

// Out of line, like the DSP calls the guard wraps: the compiler can't move pure register
// arithmetic in the guard's own scope across the mode switch reliably (see ScopedFlushToZero.h).
[[gnu::noinline]] float halfOfTiny() { return gTiny * gHalf; }
[[gnu::noinline]] double halfOfTinyD() { return gTinyD * static_cast<double>(gHalf); }
}  // namespace

TEST_CASE("ScopedFlushToZero flushes denormal results inside its scope only") {
#if defined(__aarch64__) || defined(__x86_64__) || defined(__i386__)
    const float before = halfOfTiny();
    REQUIRE(before != 0.f);  // a denormal under the default IEEE mode
    {
        const ScopedFlushToZero flush;
        CHECK(halfOfTiny() == 0.f);
        CHECK(halfOfTinyD() == 0.0);
        {
            const ScopedFlushToZero nested;  // already flushing: a no-op that restores nothing
            CHECK(halfOfTiny() == 0.f);
        }
        CHECK(halfOfTiny() == 0.f);  // the inner scope left the outer one's mode alone
    }
    CHECK(halfOfTiny() == before);
    CHECK(halfOfTinyD() != 0.0);
#else
    MESSAGE("no flush-to-zero control on this architecture; ScopedFlushToZero is a no-op");
#endif
}
