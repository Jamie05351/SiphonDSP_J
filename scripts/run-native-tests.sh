#!/usr/bin/env bash
# Configure, build and run the host-side NativeBmwDspProcessor unit tests (native-tests/).
# Needs cmake and a host C++17 compiler (g++/clang++) on PATH -- NOT the Android NDK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${ROOT}/build/native-tests"

cmake -S "${ROOT}/native-tests" -B "${BUILD_DIR}" -DCMAKE_BUILD_TYPE=Debug
cmake --build "${BUILD_DIR}" --parallel
ctest --test-dir "${BUILD_DIR}" --output-on-failure "$@"
