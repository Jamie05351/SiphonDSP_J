# libsiphondsp-engine.so: the DSP engine (NativeBmw*), its JNI layer (NativeBmwDspJni.cpp), the
# dr_wav bodies for the measurement capture's WAV export and the convolver's IR reader, and
# PFFFT's double-precision FFT for the convolver. Nothing else is linked in.
file(GLOB SOURCE_FILES CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/*.cpp)
include(${CMAKE_CURRENT_LIST_DIR}/third_party/pffft/pffft.cmake)

add_library(siphondsp-engine SHARED ${SOURCE_FILES} ${PFFFT_DOUBLE_SOURCES})

target_compile_features(siphondsp-engine PRIVATE cxx_std_17)
target_include_directories(siphondsp-engine PRIVATE ${CMAKE_CURRENT_LIST_DIR})

# libandroid: ATrace_* for the Perfetto trace sections.
find_library(android-lib android)
target_link_libraries(siphondsp-engine ${android-lib})
