# native-tests

Host-side unit tests for the custom BMW DSP core (`NativeBmwDspProcessor`).

The app has ~130 JVM tests, but they only cover the Kotlin side and the *frequency-domain
mirror* of the DSP (`BmwResponseCalculator`). The actual per-sample C++ — compressor detectors,
the multiband compressor, the master and per-bus limiters, the routing matrix and the deliberate
L/R swap — had no automated coverage. Every recent bug in that layer (the old Mono Bass stereo
cancellation, since removed; the makeup-slider zipper) lived exactly there.

`NativeBmwDspProcessor.cpp` / `NativeBmwRouting.h` include only the C++ standard library and the
in-repo single-header `dr_wav.h`, so they build and run on the dev host / CI with no Android, no
NDK, no JNI. This target links the **real** translation unit, so the tests exercise what ships.

## Run

```
./scripts/run-native-tests.sh          # needs cmake + a host C++17 compiler (g++/clang++)
```

or by hand:

```
cmake -S native-tests -B build/native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native-tests --parallel
ctest --test-dir build/native-tests --output-on-failure
```

CI runs it on every push/PR (`.github/workflows/build.yml`, "Run native DSP unit tests").

## Sanitizers and fuzzing

`.github/workflows/native-sanitizers.yml` runs four checks the plain build can't make on every
PR that touches the engine or these tests. All need Clang 20+ (`apt install clang-20
libclang-rt-20-dev`), with `CXX=clang++-20`:

| check | what it catches | run locally |
|---|---|---|
| ASan + UBSan | memory errors and undefined behaviour anywhere in the suite | `cmake -S native-tests -B build/asan -DCMAKE_CXX_FLAGS="-O1 -fsanitize=address,undefined -fno-sanitize-recover=undefined" -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address,undefined"` then build and run `native_tests` |
| TSan | data races in the audio / control-thread handoff | same with `-fsanitize=thread`; run `native_tests -sf="*concurrency_test.cpp"` |
| RTSan | allocation, locks, sleeps or other blocking calls on the audio thread | `cmake ... -DSIPHON_RTSAN=ON`, run `rtsan_check` |
| libFuzzer | crashes, sanitizer errors, or non-finite / over-range output for any `configure()` / `configurePeq()` input | `cmake ... -DSIPHON_FUZZ=ON`, run `fuzz_config corpus -max_total_time=120 -max_len=1024` |

`rtsan/rtsan_check.cpp` runs `process()` (float and int16) as a `[[clang::nonblocking]]`
function on an audio thread while the main thread pushes configs with every optional stage on,
every measurement generator, PEQ updates, truth snapshots and capture start / stop / take. A
blocking call anywhere under `process()` aborts with its stack.

`fuzz/fuzz_config.cpp` applies fuzzer-chosen (slot, value) edits to the canonical default config,
optionally with PEQ bands, then processes a loud sine with whatever was accepted and checks every
output sample is finite and within ±1.

## Layout

| file | |
|---|---|
| `CMakeLists.txt` | host executable; links `NativeBmwDspProcessor.cpp` from the app tree |
| `test_main.cpp` | doctest entry point |
| `drwav_impl.cpp` | the one TU that defines `DR_WAV_IMPLEMENTATION` (only `exportCaptureWav` needs it) |
| `test_support.h` | `defaultConfig()` (parses `default_config.txt`), signal generators, a windowed single-frequency magnitude probe |
| `default_config.txt` | canonical flat-config default, generated from `app/src/main/schema/native_bmw_dsp.schema` by `scripts/gen-dsp-schema.py` (with the C++ header and the Kotlin defaults); don't edit it by hand |
| `default_config_test.cpp` | config accepted / size guard; LR4 crossover sums flat through the handoff |
| `limiter_test.cpp` | master limiter never exceeds −1 dBFS and doesn't touch a quiet signal; per-bus limiter GR engages only when hot / reads 0 when disabled |
| `mbc_test.cpp` | MBC band GR follows the soft-knee gain computer and is monotone; meter idle while globally disabled |
| `truth_snapshot_test.cpp` | `captureTruthSnapshot()` reports the actual installed crossover topology (not the requested enum) per slope, changes across a type switch, and never adopts a rejected PEQ configure() call |
| `third_party/doctest/` | vendored doctest 2.4.11 single header |

## Adding tests

Add a `*.cpp`, list it in `CMakeLists.txt`, `#include "test_support.h"`. When
`NativeBmwDspValues.DEFAULTS` changes, edit `default_config.txt` to match — one value per
line, index in the comment. `NativeBmwSchemaAgreementTest.nativeTestDefaultConfigMatchesKotlinDefaults()`
fails CI if the two diverge.
