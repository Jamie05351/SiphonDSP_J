# native-tests

Host-side unit tests for the custom BMW DSP core (`NativeBmwDspProcessor`).

The app has ~130 JVM tests, but they only cover the Kotlin side and the *frequency-domain
mirror* of the DSP (`BmwResponseCalculator`). The actual per-sample C++ — compressor detectors,
the multiband compressor, the master and per-bus limiters, the Mono Bass recombination, the
routing matrix and the deliberate L/R swap — had no automated coverage. Every recent bug in
that layer (Mono Bass stereo cancellation, the makeup-slider zipper) lived exactly there.

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

## Layout

| file | |
|---|---|
| `CMakeLists.txt` | host executable; links `NativeBmwDspProcessor.cpp` from the app tree |
| `test_main.cpp` | doctest entry point |
| `drwav_impl.cpp` | the one TU that defines `DR_WAV_IMPLEMENTATION` (only `exportCaptureWav` needs it) |
| `test_support.h` | `defaultConfig()` (mirrors `NativeBmwDspValues.DEFAULTS`), signal generators, a windowed single-frequency magnitude probe |
| `default_config_test.cpp` | config accepted / size guard; LR4 crossover sums flat through the handoff |
| `mono_bass_test.cpp` | Mono Bass is all-pass on the sum for correlated content; leaves decorrelated stereo untouched above its corner (the PR #218 fix), including through the Low/Mid crossover handoff band itself |
| `limiter_test.cpp` | master limiter never exceeds −1 dBFS and doesn't touch a quiet signal; per-bus limiter GR engages only when hot / reads 0 when disabled |
| `mbc_test.cpp` | MBC band GR follows the soft-knee gain computer and is monotone; meter idle while globally disabled |
| `third_party/doctest/` | vendored doctest 2.4.11 single header |

## Adding tests

Add a `*.cpp`, list it in `CMakeLists.txt`, `#include "test_support.h"`. Keep the
`defaultConfig()` array in step with `NativeBmwDspValues.DEFAULTS` — the `static_assert` in
`test_support.h` guards the length; the field layout is on you.
