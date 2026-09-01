// NativeBmwDspProcessor.cpp includes dr_wav.h for declarations only (exportCaptureWav) and
// relies on the drwav_* bodies being resolved at link time -- in the app from
// jdspimprestoolbox, here from this one translation unit.
#define DR_WAV_IMPLEMENTATION
#include "../app/src/main/cpp/libjdspimptoolbox/dr_wav.h"
