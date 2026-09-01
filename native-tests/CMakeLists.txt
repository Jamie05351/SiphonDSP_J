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
project(siphondsp-native-tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The whole point of these tests is that ftz() / makePeq() / AllPassSection::rebuild() /
# configure() actually run their std::isfinite() guards, so never build the code under test
# with -ffast-math here (see app/src/main/cpp/CMakeLists.txt for why the app dropped -Ofast).
add_compile_options(-O2 -g -Wall -Wextra -fno-fast-math)

set(WRAPPER_DIR ${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp/libjamesdsp-wrapper)

add_executable(native_tests
    test_main.cpp
    drwav_impl.cpp
    ${WRAPPER_DIR}/NativeBmwDspProcessor.cpp
    default_config_test.cpp
    mono_bass_test.cpp
    limiter_test.cpp
    mbc_test.cpp
)

target_include_directories(native_tests PRIVATE
    ${WRAPPER_DIR}
    ${CMAKE_CURRENT_LIST_DIR}/third_party
)

enable_testing()
add_test(NAME native_tests COMMAND native_tests)
