# J_DSP

Private BMW factory-DSP tuning suite for Android. Fork of
[RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP), stripped of the
general effect stack and rebuilt around a purpose-made output-tuning core.

Display label **J_DSP**, package `app.siphondsp` (`.debug` suffix on debug builds),
arm64-v8a only. Not the upstream Play/F-Droid app — build from this repo.

## What it is

J_DSP keeps RootlessJamesDSP's audio path — Android internal audio capture routed
through a JNI DSP core and back out through `AudioTrack` — but replaces the effect
chain with a single tuning suite aimed at driving a BMW factory amplifier from an
Android head unit (developed against a 1280×480 landscape unit).

The general JamesDSP features are gone: graphic EQ, AutoEQ, compander, liveprog/EEL,
stereo widen, DDC, device profiles, and the generic preset system have all been
removed. What survives from upstream is the capture→process→playback service,
session tracking / app blocklist, the Convolver (dormant), and the settings and
backup scaffolding.

## Tuning suite

All processing runs in a custom native core (`NativeBmwDspProcessor`), configured
from Kotlin through one flat float array. Workspace screens:

- **Parametric EQ** — three-bank PEQ (Full Range pre-crossover, Low Band and Mid
  Band post-crossover), up to 16 peaking/shelf filters per bank, per-filter Left /
  Right / both targeting, drag-to-edit graph with undo/redo, import/export, presets.
  See [docs/THREE_BANK_PEQ.md](docs/THREE_BANK_PEQ.md).
- **Crossovers & Tilt** — LR4 low/mid split, subsonic HPF on the low branch,
  tonality tilt (amount + pivot).
- **Gains & Delay** — per-channel delay, polarity and gain across four car cards,
  L/R delay link, and an Output page with headroom, post gain, and the master
  limiter (enable + threshold + gain-reduction meter; off = true bypass).
- **Compressor** — four-band pre-crossover multiband compressor with a live
  transfer-curve visualiser, plus per-bus limiters.
- **All-pass** — up to two all-pass sections per output for phase alignment.
- **Measurement capture** — records the processed output to WAV for offline
  measurement.

Save/restore is `PrivatePeqBackup` / `BmwPeqPreset` only.

### Signal path

```text
stereo capture (L, R)
  → DC blocker
  → input-correction preamp + Full Range PEQ (Left/Right)
  → headroom
  → routing matrix (Front L/R → Low L/R, Mid L/R)
  → per output: subsonic LR4 HPF (low only) → crossover LPF/HPF
  → mono-bass blend (low outputs)
  → per-band PEQ (Low / Mid banks)
  → per-output all-pass → delay
  → per-band multiband compressor → per-bus limiter
  → per-output gain / polarity / mute
  → sum: Final L = Low L + Mid L, Final R = Low R + Mid R
  → tilt → post gain → channel mute → master limiter
  → deliberate L/R swap
```

The internal "Left"/"Right" chains are named for the L-suffixed config indices, not
the physical output side — `processFrame` ends `l = oR; r = oL`, so the
internal-left chain lands on the physical right output. This is intentional; do not
"fix" it. Full detail in
[docs/NATIVE_BMW_OUTPUT_ARCHITECTURE.md](docs/NATIVE_BMW_OUTPUT_ARCHITECTURE.md).

## Running it

Audio routing is still RootlessJamesDSP's: grant the app internal-audio-capture
through Shizuku or ADB (or run the root variant). Caveats that still apply:

- Apps that block internal capture stay unprocessed.
- Latency is higher than a native/root audio effect.
- Android 15+ may need screen-share protection disabled in Developer options, or the
  projection permission granted through Shizuku/ADB.

## Building

Requirements: JDK 17, Android SDK (compile SDK 37), Android NDK + CMake (via Android
Studio or `sdkmanager`).

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assemblePreview    # release build type, preview flag
```

APKs land under `app/build/outputs/apk/<buildType>/`.

## Tests

```bash
./gradlew :app:testDebugUnitTest    # ~135 JVM tests (Kotlin + BmwResponseCalculator)
./scripts/run-native-tests.sh       # host-side per-sample C++ tests (cmake + C++17, doctest/ctest)
```

The native suite links the real `NativeBmwDspProcessor.cpp` on the host — no
NDK/JNI — and covers the compressor detectors, limiters, mono-bass recombination and
Kotlin↔C++ schema agreement. Both run in CI on every push/PR. See
[native-tests/README.md](native-tests/README.md).

Private build and recovery flow: [PRIVATE_BUILD_AND_RECOVERY.md](PRIVATE_BUILD_AND_RECOVERY.md).

## Upstream & credits

Fork of [RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP) by Tim
Schneeberger — rootless routing, UI foundation, backup and theming scaffolding. DSP
engine: [JamesDSP / libjamesdsp](https://github.com/james34602/JamesDSPManager) by
James Fung. Theming and backup foundations from Tachiyomi, via upstream.

## License

GPL-3.0, following upstream. See [LICENSE](LICENSE).
