#include "test_support.h"

#include "NativeBmwDspSchema.h"

using namespace nbtest;
namespace sch = nbschema;

// Regression for a real bug: processFrame() used to discard the routing matrix's output (and any
// per-output polarity/mute already applied to it) whenever BOTH crossover-bypass flags (lpfPass,
// hpfPass) were set, falling back to the raw pre-routing signal instead. A user's custom routing
// (e.g. mixing both front channels into one output only) silently had no effect in that one flag
// combination, with no error or indication why.
//
// Decisive test: zero out every routing gain (nothing feeds any of the 4 logical channels) with
// both bypass flags on. If routing is respected, the output must be silent -- there is nothing
// left to reach it. If routing is bypassed (the bug), the output stays at full level regardless,
// since the fallback path never looked at the routing matrix at all.
TEST_CASE("Routing matrix is respected even when both crossover bypass flags are set") {
    NativeBmwDspProcessor proc;
    auto c = defaultConfig();
    c[sch::kLpfPass] = 1.f;
    c[sch::kHpfPass] = 1.f;
    // Zero every routing gain: Low L/R, Mid L/R each x [from Front L, from Front R].
    for (int i = 0; i < 8; ++i) {
        c[sch::kRoutingBase + i] = 0.f;
    }

    auto out = renderSteadyState(proc, c, 1000.0, 0.1);
    const float pk = peakAbs(out);
    INFO("peak output with zeroed routing + both bypass flags = ", pk);
    CHECK(pk < 1e-4f);
}
