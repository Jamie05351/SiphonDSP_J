# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

cmake_minimum_required(VERSION 3.22.1)

project(siphondsp-native LANGUAGES C CXX)

# -O3, NOT -Ofast. -Ofast implies -ffast-math, and in particular -ffinite-math-only, under
# which the compiler is free to assume no value is ever NaN/Inf and fold away std::isfinite()
# checks. The native DSP relies on exactly those checks (ftz(), makePeq()'s a0 guard,
# NativeBmwRouting::AllPassSection::rebuild, configure()'s input validation) to catch a bad
# coefficient/sample and flush it rather than let a NaN propagate to the speakers. -O3 keeps
# the vectorisation and speed without weakening IEEE semantics.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -O3 -ftree-vectorize")

add_subdirectory(engine)
