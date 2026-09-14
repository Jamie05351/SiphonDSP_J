#include "test_support.h"

using namespace nbtest;

// Regression for a real self-deadlock: setSampleRate() takes stateMutex_, then calls
// rebuildAll(), whose last statement called configurePeq() -- which takes the same
// non-recursive std::mutex again on the same thread. Any genuine post-construction sample-rate
// change (setSampleRate() only rebuilds when the new rate actually differs from the current one
// by >0.5 Hz) hung the calling thread forever. Fixed by splitting configurePeq() into a public
// locking wrapper and a private configurePeqLocked() that assumes the lock is already held;
// rebuildAll() now calls the locked variant directly.
//
// A real deadlock here would hang this test (and the whole binary) rather than fail an
// assertion -- CI's own job timeout is the backstop if this regresses. What this test actually
// asserts is that the call returns AND that rebuildAll()'s reapplied PEQ bands are still in
// effect afterward, proving configurePeqLocked() actually ran to completion, not just that some
// path happened to return early.
TEST_CASE("setSampleRate() to a genuinely different rate does not deadlock and reapplies PEQ") {
    // NativeBmwDspProcessor's default sampleRate_ is already 48000 (kSampleRate), so the
    // constructor alone wouldn't exercise the bug -- setSampleRate() only rebuilds (and only
    // the rebuild path ever called configurePeq() re-entrantly) when the new rate genuinely
    // differs from the current one by more than 0.5 Hz. Start somewhere else so the deadlock
    // trigger below is a real, genuine rate change.
    constexpr float kStartRate = 44100.f;
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kStartRate);

    auto cfg = defaultConfig();
    cfg[5] = 0.f;  // headroom 0 dB, so the PEQ boost below is the only source of gain
    REQUIRE(proc.configure(cfg.data(), cfg.size()));

    // A +6 dB bell at 1 kHz, Q=2 -- same band shape the PEQ regression tests elsewhere use.
    constexpr double kFreq = 1000.0, kGainDb = 6.0, kQ = 2.0;
    const double band[5] = {kFreq, kGainDb, kQ, 0.0, 0.0};
    REQUIRE(proc.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0));

    // The actual deadlock trigger: a second setSampleRate() call to a rate that genuinely
    // differs from the current one -- exactly the path a real sample-rate change takes in the
    // app. Deliberately not using test_support.h's renderSteadyState() below (it calls its own
    // fixed-kSampleRate setSampleRate() internally, which would silently change the rate again
    // here); driving process() directly keeps the rate pinned at exactly what this test set.
    proc.setSampleRate(kSampleRate);

    // If we get here at all, the deadlock is gone. Confirm rebuildAll()'s reapply actually
    // worked: the PEQ band configured above should still be audible at the new sample rate, not
    // silently dropped or left built for the stale rate.
    const double amp = 0.05;
    auto warm = stereoSine(kFreq, amp, 24000, kSampleRate);
    proc.process(warm.data(), warm.size());
    auto window = stereoSine(kFreq, amp, 16384, kSampleRate);
    proc.process(window.data(), window.size());

    const double measuredDb = linToDb(channelMagnitudeAt(window, 0, kFreq, kSampleRate) / amp);
    INFO("Post-setSampleRate PEQ gain at fc=", kFreq, " Hz: ", measuredDb, " dB (want ~", kGainDb, ")");
    CHECK(std::fabs(measuredDb - kGainDb) < 0.5);
}
