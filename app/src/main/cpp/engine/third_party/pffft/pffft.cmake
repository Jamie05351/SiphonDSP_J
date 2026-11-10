# PFFFT (https://github.com/marton78/pffft, tag v1.1.0, FFTPACK/BSD-style licence: LICENSE.txt),
# double-precision build only, used by the convolver (NativeBmwConvolver.cpp). Shared by the app
# (app/src/main/cpp/engine/CMakeLists.txt) and the host tests (native-tests/CMakeLists.txt).
#
# Sets PFFFT_DOUBLE_SOURCES. SIMD: NEON (4-wide, from pffft's AVX emulation) on arm64, SSE2 on
# x86-64; PFFFT's own scalar code elsewhere. Unmodified upstream files apart from this one.
set(PFFFT_DIR ${CMAKE_CURRENT_LIST_DIR})
set(PFFFT_DOUBLE_SOURCES ${PFFFT_DIR}/pffft_double.c ${PFFFT_DIR}/pffft_common.c)
set(PFFFT_C_FLAGS "")
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$" OR ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND PFFFT_C_FLAGS -DPFFFT_ENABLE_NEON)
endif()
# pffft announces its SIMD choice with #pragma message; keep build logs about real warnings.
if(CMAKE_C_COMPILER_ID MATCHES "Clang")
    list(APPEND PFFFT_C_FLAGS -Wno-\#pragma-messages)
endif()
# Never fast-math: pffft is IEEE code and the engine relies on non-finite checks downstream.
list(APPEND PFFFT_C_FLAGS -fno-fast-math)
set_source_files_properties(${PFFFT_DOUBLE_SOURCES} PROPERTIES
    LANGUAGE C
    COMPILE_OPTIONS "${PFFFT_C_FLAGS}"
    INCLUDE_DIRECTORIES "${PFFFT_DIR}")
