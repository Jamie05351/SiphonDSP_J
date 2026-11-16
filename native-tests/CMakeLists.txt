# Host-side (no Android, no JNI) unit tests for the custom BMW DSP core.
#
# NativeBmwDspProcessor.cpp / NativeBmwRouting.h include only the C++ standard library and
# the in-repo single-header dr_wav.h, so they compile and run on the build host. This target
# links the real translation unit -- not a copy -- so the tests exercise exactly what ships.
#
#   cmake -S native-tests -B build/native-tests
#   cmake --build build/native-tests
#   ctest --test-dir build/native-tests --output-on-failure
#
# Deliberately NOT built by Gradle / AGP: it is a plain host binary run from CI (build.yml)
# and scripts/run-native-tests.sh.

cmake_minimum_required(VERSION 3.22)
project(siphondsp-native-tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The whole point of these tests is that ftz() / makePeq() / AllPassSection::rebuild() /
# configure() actually run their std::isfinite() guards, so never build the code under test
# with -ffast-math here (see app/src/main/cpp/CMakeLists.txt for why the app dropped -Ofast).
add_compile_options(-O2 -g -Wall -Wextra -fno-fast-math)
# NativeBmwDspProcessor.cpp packs several statements per line; GCC's -Wmisleading-indentation
# then fires on the app code, not the tests. Reformatting that file is its own task -- silence
# it here so a test run's output is about the tests.
add_compile_options(-Wno-misleading-indentation)

set(WRAPPER_DIR ${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp/engine)

include(${WRAPPER_DIR}/third_party/pffft/pffft.cmake)

# The engine as it ships (everything but the JNI file), shared by every target here.
set(ENGINE_SOURCES
    drwav_impl.cpp
    ${PFFFT_DOUBLE_SOURCES}
    ${WRAPPER_DIR}/NativeBmwConvolver.cpp
    ${WRAPPER_DIR}/NativeBmwDspProcessor.cpp
    ${WRAPPER_DIR}/NativeBmwDspProcessorConfig.cpp
    ${WRAPPER_DIR}/NativeBmwFilters.cpp
    ${WRAPPER_DIR}/NativeBmwCrossovers.cpp
    ${WRAPPER_DIR}/NativeBmwDynamics.cpp
    ${WRAPPER_DIR}/NativeBmwDelayGain.cpp
    ${WRAPPER_DIR}/NativeBmwMeasurement.cpp
    ${WRAPPER_DIR}/NativeBmwVirtual.cpp
    ${WRAPPER_DIR}/NativeBmwSelfTest.cpp
)

add_executable(native_tests
    test_main.cpp
    ${ENGINE_SOURCES}
    concurrency_test.cpp
    capture_test.cpp
    crossover_test.cpp
    default_config_test.cpp
    input_validation_test.cpp
    limiter_test.cpp
    mbc_test.cpp
    schema_agreement_test.cpp
    truth_snapshot_test.cpp
    virtual_test.cpp
    alignment_delay_test.cpp
    retune_continuity_test.cpp
    deep_bass_test.cpp
    mono_bass_test.cpp
    virtual_bass_test.cpp
    flush_to_zero_test.cpp
    ramps_test.cpp
    output_guard_test.cpp
    convolver_test.cpp
    stream_start_guard_test.cpp
    measurement_neutral_test.cpp
    profiler_test.cpp
    peq_block_test.cpp
    block_chain_test.cpp
)

# concurrency_test.cpp runs process() on a second thread.
find_package(Threads REQUIRED)
target_link_libraries(native_tests PRIVATE Threads::Threads)

target_include_directories(native_tests PRIVATE
    ${WRAPPER_DIR}
    ${CMAKE_CURRENT_LIST_DIR}/third_party
)

# Absolute path to the canonical default config, read at test time by test_support.h's
# defaultConfig(). Baked in so the binary finds it regardless of the working directory.
target_compile_definitions(native_tests PRIVATE
    NBTEST_DEFAULT_CONFIG_PATH="${CMAKE_CURRENT_LIST_DIR}/default_config.txt"
    # Scratch files (convolver_test.cpp's WAV IRs).
    NBTEST_TMP_DIR="${CMAKE_CURRENT_BINARY_DIR}"
)

# The engine's measured frequency response for the app's graph-model check (see
# response_golden.cpp). --check runs as a test; --write regenerates the committed file.
add_executable(response_golden response_golden.cpp ${ENGINE_SOURCES})
target_include_directories(response_golden PRIVATE
    ${WRAPPER_DIR} ${CMAKE_CURRENT_LIST_DIR} ${CMAKE_CURRENT_LIST_DIR}/third_party)
target_compile_definitions(response_golden PRIVATE
    NBTEST_DEFAULT_CONFIG_PATH="${CMAKE_CURRENT_LIST_DIR}/default_config.txt"
    NBTEST_GOLDEN_RESPONSE_PATH="${CMAKE_CURRENT_LIST_DIR}/golden/response_curves.txt")

enable_testing()
add_test(NAME native_tests COMMAND native_tests)
add_test(NAME response_golden COMMAND response_golden --check)

# ---- Optional verification targets (Clang 20+; see native-tests/README.md and
# .github/workflows/native-sanitizers.yml) ---------------------------------------------------

# RealtimeSanitizer: runs process() as a [[clang::nonblocking]] function on an audio thread while
# another thread pushes configs and control requests, and fails on any allocation, lock or
# blocking call made on the audio thread.
option(SIPHON_RTSAN "Build the RealtimeSanitizer check (rtsan_check)" OFF)
if(SIPHON_RTSAN)
    add_executable(rtsan_check rtsan/rtsan_check.cpp ${ENGINE_SOURCES})
    target_include_directories(rtsan_check PRIVATE ${WRAPPER_DIR} ${CMAKE_CURRENT_LIST_DIR} ${CMAKE_CURRENT_LIST_DIR}/third_party)
    target_compile_definitions(rtsan_check PRIVATE
        NBTEST_DEFAULT_CONFIG_PATH="${CMAKE_CURRENT_LIST_DIR}/default_config.txt")
    target_compile_options(rtsan_check PRIVATE -fsanitize=realtime)
    target_link_options(rtsan_check PRIVATE -fsanitize=realtime)
    target_link_libraries(rtsan_check PRIVATE Threads::Threads)
endif()

# libFuzzer over configure() / configurePeq(): every input, accepted or not, must leave the
# processor producing finite output within full scale.
option(SIPHON_FUZZ "Build the libFuzzer config harness (fuzz_config)" OFF)
if(SIPHON_FUZZ)
    add_executable(fuzz_config fuzz/fuzz_config.cpp ${ENGINE_SOURCES})
    target_include_directories(fuzz_config PRIVATE ${WRAPPER_DIR} ${CMAKE_CURRENT_LIST_DIR} ${CMAKE_CURRENT_LIST_DIR}/third_party)
    target_compile_definitions(fuzz_config PRIVATE
        NBTEST_DEFAULT_CONFIG_PATH="${CMAKE_CURRENT_LIST_DIR}/default_config.txt")
    target_compile_options(fuzz_config PRIVATE -fsanitize=fuzzer,address,undefined)
    target_link_options(fuzz_config PRIVATE -fsanitize=fuzzer,address,undefined)
endif()
