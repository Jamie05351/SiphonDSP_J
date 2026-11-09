# Assessment follow-up — round 1 checklist

Source: the code assessment of `master` at `074ef37` (serious bugs, conflicts, better approach,
missing tools). Decisions taken with the owner: **remove the convolver** (no rewrite) and do the
**full Compose migration** this round.

## How the PRs are organised

- Items that touch the same code go in the same PR.
- Each PR is opened only after the previous one is green (Build APKs, NEON parity, review
  addressed) and only needs merging.
- The PRs are **stacked**: each branch starts from the previous PR's branch, so they share no
  conflicting hunks. **Merge them in order (1 → 7) with merge commits** (not squash). After a
  PR merges, the next one's diff shrinks to just its own changes.
- A box is ticked when its PR is green and ready to merge.

| PR | Area | Branch | Status |
|----|------|--------|--------|
| 1 | Engine core: JamesDSP removed | `ccr-efec0dfc-bhwizi` | #577 — merged |
| 2 | Native processor: real-time safety, block processing | `ccr-efec0dfc-rt-block` | #578 — merged |
| 3 | Native verification tooling | `ccr-efec0dfc-native-tooling` | #579 — merged |
| 4 | Config schema single source of truth | `ccr-efec0dfc-schema-gen` | #580 |
| 5 | Graphs checked against the native engine | `ccr-efec0dfc-native-curves` | in progress |
| 6 | Dependencies and static analysis | `ccr-efec0dfc-deps` | — |
| 7 | Full Compose migration + Baseline Profile | `ccr-efec0dfc-compose` | — |

## PR 1 — Engine core: JamesDSP removed

Native wrapper, CMake, `interop/`, the service's audio loop, the Settings cards that drove the
JamesDSP core.

- [x] 1.1 **Bug:** JamesDSP's limiter (−0.1 dBFS, 60 ms) and post gain no longer run after the
      BMW master limiter on every buffer. "Master limiter off" becomes a true bypass.
- [x] 1.2 **Bug:** the measurement capture records exactly what goes to the speakers (nothing
      runs after the capture tap any more).
- [x] 1.3 Remove the legacy Output-control Settings card (the second post gain / limiter).
- [x] 1.4 Remove the convolver: Settings card, impulse-response loading, IR file library,
      bundled impulse responses, convolver messages.
- [x] 1.5 Remove the `libjamesdsp` submodule, the EEL/liveprog VM and callbacks, convolver
      benchmarks, the ASRC path, `libjdspimptoolbox` (except `dr_wav.h`), the crashlytics
      connector and the macro-patched double wrapper. One slim JNI layer around
      `NativeBmwDspProcessor`. Also closes the possible global EEL teardown race.
- [x] 1.6 Remove the dead root/plugin path (`RootAudioProcessorService`,
      `JamesDspRemoteEngine`, root session tracking) — `isRoot()` is always false.
- [x] 1.7 Remove unused JNI entry points (`isHandleValid`, `processInt24Packed`,
      `processInt8U24`, …).
- [x] 1.8 Audio loop uses direct `ByteBuffer`s into native: no per-buffer array pin/copy.
- [x] 1.9 Spectrum feed handed to the analyser without a lock on the audio thread.
- [x] 1.10 Trace markers (`ATrace` / `android.os.Trace`) around read / process / write so
      dropouts can be found with Perfetto.
- [x] 1.11 **Bug (found in review):** an output guard (non-finite → 0, clip at full scale)
      replaces the bound the JamesDSP limiter used to give the float output.

## PR 2 — Native processor: real-time safety and block processing

`NativeBmwDspProcessor` and its modules.

- [x] 2.1 The audio thread takes no mutex: sample-rate changes, capture start/stop and the
      truth snapshot are handed over lock-free.
- [x] 2.2 The truth snapshot no longer allocates while blocking the audio thread.
- [x] 2.3 Block-based processing: buffers run in 64-frame chunks, each band chain over the
      whole chunk instead of the whole chain per sample; bit-identical to today (parity job).
      No measurable speed-up on an x86 host (≈440 ms per 20 s of audio either way); needs a
      measurement on the head unit.

## PR 3 — Native verification tooling

`native-tests/`, `scripts/`, new CI workflow.

- [x] 3.1 RealtimeSanitizer job: `process()` checked for allocation / locks / syscalls.
- [x] 3.2 AddressSanitizer + UndefinedBehaviorSanitizer and ThreadSanitizer jobs over the
      native tests.
- [x] 3.3 libFuzzer harness for `configure()` / `configurePeq()`, with a short CI run.

## PR 4 — Config schema single source of truth

`NativeBmwDspValues.kt`, `NativeBmwDspSchema.h`, the processor's index comments, a generator.

- [x] 4.1 One schema file generates the Kotlin indices/defaults and the C++ schema header.
- [x] 4.2 Kotlin-only marker slots are flagged in the schema (array layout unchanged, so saved
      tunings stay compatible).
- [x] 4.3 The hand-kept index comments (already drifted) are replaced by the generated table.
- [x] 4.4 A native test proves the schema's reader column: every slot marked native is the one
      `configure()` validates (a NaN there rejects the update), every other slot is ignored.

## PR 5 — Graphs checked against the native engine

**Changed approach (was: move the graph maths into native).** Moving `BmwResponseCalculator`
into C++ would cut the JVM tests off from the graphs' code (Robolectric can't load the native
library) and replace a well-tested model with an untested port. The same guarantee — the graphs
can't disagree with what plays — comes from checking the two against each other instead:

- [x] 5.1 `native-tests/response_golden.cpp` measures the real engine's frequency response
      (steady sines, both channels) for seven scenarios — defaults, BW2 crossover with Mid
      inverted, LR4 300 Hz with tilt and post gain, all four PEQ banks, 3-way with High on,
      asymmetric gains/headroom/delays, BW2 subsonic with LPF bypass — into
      `native-tests/golden/response_curves.txt`. Its `--check` mode is a ctest: the native
      tests fail if the engine stops matching the file.
- [x] 5.2 `ResponseGoldenAgreementTest.kt` runs the graph model on the same scenarios and axis
      and fails if it differs from the measured engine by more than 0.5 dB (above −40 dB).

## PR 6 — Dependencies and static analysis

Gradle files.

- [ ] 6.1 Remove unused dependencies (Retrofit, the `codeview` module, …).
- [ ] 6.2 Gson → kotlinx.serialization (one library for JSON).
- [ ] 6.3 Dependency-analysis plugin and detekt, wired into CI (report-only baseline).

## PR 7 — Full Compose migration + Baseline Profile

- [ ] 7.1 One Activity with Compose Navigation.
- [ ] 7.2 Settings pages, Onboarding, Blocklist, App compatibility, Measurement capture and the
      remaining screens in Compose.
- [ ] 7.3 Settings stored in DataStore (migrated from SharedPreferences on first launch).
- [ ] 7.4 Remove Fragments, `ViewPager2`, view/data binding, LiveData, `LocalBroadcastManager`,
      the AndroidX Preference library and the hand-painted View drawables.
- [ ] 7.5 Baseline Profile module and a CI job that generates it on an emulator.

## Deferred (not this round)

- Oboe/AAudio native output callback — only worth doing if the trace markers from 1.10 show
  dropouts the direct-buffer loop doesn't fix. Needs measurements from the head unit first.
