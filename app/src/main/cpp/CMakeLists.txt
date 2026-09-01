# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

# Declares and names the project. One project() call with both languages -- this used to be
# two calls (one per language), which just made the second project name win; every target
# below is named explicitly (add_library(jamesdsp ...), the wrapper's own CMakeLists), so the
# project name itself is immaterial.
project(siphondsp-native LANGUAGES C CXX)

# -O3, NOT -Ofast. -Ofast implies -ffast-math, and in particular -ffinite-math-only, under
# which the compiler is free to assume no value is ever NaN/Inf and fold away std::isfinite()
# checks. The native DSP relies on exactly those checks (ftz(), makePeq()'s a0 guard,
# NativeBmwRouting::AllPassSection::rebuild, configure()'s input validation) to catch a bad
# coefficient/sample and flush it rather than let a NaN propagate to the speakers. -O3 keeps
# the vectorisation and speed without weakening IEEE semantics. A later -O2 on the jamesdsp
# target does NOT turn -ffast-math back off, so this has to be right at the source.
set(SIPHON_NATIVE_FLAGS "-ffunction-sections -fdata-sections -O3 -ftree-vectorize")

# DEBUG was defined unconditionally for every build variant. In the upstream libjamesdsp C it
# only gates __android_log_print() calls, so shipping it just adds logcat noise (and it also
# contradicts the NDK's -DNDEBUG on release). Keep it for debuggable builds only -- AGP sets
# CMAKE_BUILD_TYPE=Debug for those and RelWithDebInfo for release/preview.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(SIPHON_NATIVE_FLAGS "${SIPHON_NATIVE_FLAGS} -DDEBUG")
endif()

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${SIPHON_NATIVE_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${SIPHON_NATIVE_FLAGS}")

include(libcrashlytics-connector/CMakeLists.txt)
include(libjdspimptoolbox/CMakeLists.txt)
include(libjamesdsp-wrapper/CMakeLists.txt)

file(GLOB_RECURSE LIBJAMESDSP_SOURCE_FILES CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.c
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.h)

add_library( # Sets the name of the library.
        jamesdsp

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        ${LIBJAMESDSP_SOURCE_FILES}
        EELStdOutExtension.c
        EELStdOutExtension.h)

target_compile_options(jamesdsp PRIVATE -O2)
target_compile_options(jamesdsp PRIVATE -std=gnu11)
target_include_directories(jamesdsp PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/)

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

target_link_libraries( # Specifies the target library.
        jamesdsp

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib})
