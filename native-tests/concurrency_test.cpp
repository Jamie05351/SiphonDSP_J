#include "test_support.h"
#include <atomic>
#include <chrono>
#include <limits>
#include <thread>

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

    // Flattened the same way default_config_test.cpp's flatConfig() does: headroom 0 dB, mid
    // gain 0 dB (default is -1), tilt off (default is on, 3 dB @ 550 Hz -- a real, sizeable
    // contribution at 1 kHz that has nothing to do with what this test is actually checking),
    // legacy/per-output compressors off. The first version of this test only zeroed headroom,
    // so the measured level included tilt's and mid gain's contributions alongside the PEQ
    // band's -- the assertion below was checking the wrong number entirely (measured ~1.3 dB
    // wanting ~6 dB, not because the fix was broken, but because -3.8 dB of tilt and -1 dB of
    // mid gain were baked into what "measured" meant here).
    auto cfg = defaultConfig();
    cfg[5] = 0.f;
    cfg[8] = cfg[9] = 0.f;
    cfg[12] = 0.f;
    cfg[25] = 0.f;
    cfg[28] = cfg[35] = 0.f;
    cfg[89] = cfg[102] = 0.f;
    cfg[93] = cfg[106] = 0.f;
    cfg[119] = cfg[132] = 0.f;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));

    // A +6 dB bell at 1 kHz, Q=2 -- same band shape the PEQ regression tests elsewhere use.
    constexpr double kFreq = 1000.0, kGainDb = 6.0, kQ = 2.0;
    const double band[5] = {kFreq, kGainDb, kQ, 0.0, 0.0};
    REQUIRE(proc.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0, nullptr, 0));

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

namespace {
// A host stage that parks the audio thread inside process() -- while it holds the audio lock --
// until the test releases it, or gives up after a few seconds.
struct ParkedBuffer {
    std::atomic<bool> entered{false}, released{false}, timedOut{false};
    static void hook(void* context, float*, std::size_t) {
        auto* self = static_cast<ParkedBuffer*>(context);
        self->entered.store(true);
        const auto giveUp = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (!self->released.load()) {
            if (std::chrono::steady_clock::now() > giveUp) {
                self->timedOut.store(true);
                return;
            }
            std::this_thread::yield();
        }
    }
};

std::array<float, kConfigSize> flatConfig() {
    auto c = defaultConfig();
    c[5] = 0.f;
    c[8] = c[9] = 0.f;
    c[12] = 0.f;
    c[25] = 0.f;
    c[28] = c[35] = 0.f;
    c[89] = c[102] = 0.f;
    c[93] = c[106] = 0.f;
    c[119] = c[132] = 0.f;
    return c;
}

double levelDbAt(NativeBmwDspProcessor& proc, double freq, double amp) {
    auto warm = stereoSine(freq, amp, 24000, kSampleRate);
    proc.process(warm.data(), warm.size());
    auto window = stereoSine(freq, amp, 16384, kSampleRate);
    proc.process(window.data(), window.size());
    return linToDb(channelMagnitudeAt(window, 0, freq, kSampleRate) / amp);
}
}  // namespace

// The C++ half of review item C4: configure()/configurePeq() used to take the same lock process()
// holds for its whole buffer, so a config push waited out the buffer and the audio thread could
// wait behind a push. Now a push never touches that lock. Before the fix this test hung in
// configure() until the parked buffer gave up, and failed on timedOut.
TEST_CASE("Config pushes return while the audio thread is mid-buffer") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = flatConfig();
    REQUIRE(proc.configure(cfg.data(), cfg.size()));

    ParkedBuffer parked;
    std::vector<float> buffer = stereoSine(1000.0, 0.05, 256, kSampleRate);
    std::thread audio([&] { proc.process(buffer.data(), buffer.size(), &ParkedBuffer::hook, &parked); });
    while (!parked.entered.load()) {
        std::this_thread::yield();
    }

    cfg[5] = -6.f;  // headroom
    const bool configured = proc.configure(cfg.data(), cfg.size());
    const double band[5] = {1000.0, 6.0, 2.0, 0.0, 0.0};
    const bool peqConfigured = proc.configurePeq(true, 0.f, band, 5, nullptr, 0, nullptr, 0, nullptr, 0);
    const bool returnedWhileParked = !parked.timedOut.load();
    parked.released.store(true);
    audio.join();

    CHECK(configured);
    CHECK(peqConfigured);
    CHECK(returnedWhileParked);
    CHECK_FALSE(parked.timedOut.load());

    // Both pushes are in effect from the next buffer: -6 dB headroom, +6 dB bell at 1 kHz.
    const double measuredDb = levelDbAt(proc, 1000.0, 0.05);
    INFO("Level at 1 kHz after both pushes: ", measuredDb, " dB (want ~0)");
    CHECK(std::fabs(measuredDb) < 0.5);
}

TEST_CASE("Only the latest of several pushes is installed, and a rejected push changes nothing") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    auto cfg = flatConfig();
    cfg[5] = -6.f;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    cfg[5] = -3.f;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    double measuredDb = levelDbAt(proc, 1000.0, 0.05);
    INFO("After -6 then -3 dB headroom: ", measuredDb, " dB");
    CHECK(std::fabs(measuredDb + 3.0) < 0.2);

    auto bad = cfg;
    bad[5] = -12.f;
    bad[6] = std::numeric_limits<float>::quiet_NaN();
    CHECK_FALSE(proc.configure(bad.data(), bad.size()));
    measuredDb = levelDbAt(proc, 1000.0, 0.05);
    INFO("After a rejected push: ", measuredDb, " dB");
    CHECK(std::fabs(measuredDb + 3.0) < 0.2);

    // A rejected push doesn't become the base of the next one either.
    cfg[5] = 0.f;
    REQUIRE(proc.configure(cfg.data(), cfg.size()));
    measuredDb = levelDbAt(proc, 1000.0, 0.05);
    CHECK(std::fabs(measuredDb) < 0.2);
}

TEST_CASE("The truth snapshot reports a push before any audio has run") {
    NativeBmwDspProcessor proc;
    proc.setSampleRate(kSampleRate);
    const double band[5] = {1000.0, 6.0, 2.0, 0.0, 0.0};
    REQUIRE(proc.configurePeq(true, -2.f, band, 5, nullptr, 0, nullptr, 0, nullptr, 0));
    const auto snapshot = proc.captureTruthSnapshot();
    REQUIRE(snapshot.size() > 3);
    CHECK(snapshot[1] == 1.0);
    CHECK(snapshot[2] == doctest::Approx(-2.0));
}
