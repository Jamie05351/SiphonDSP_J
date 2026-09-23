#!/usr/bin/env bash
# NEON parity check for NativeBmwDspProcessor (see native-tests/neon_parity/neon_parity.cpp).
#
# Builds the parity harness for arm64 twice -- NEON (as shipped) and -DSIPHON_DISABLE_NEON
# (scalar) -- plus, with BASELINE_REF set, once more from that git ref's source (e.g. master
# before a DSP change). Runs each on the connected adb device and fails unless every scenario
# hash matches, i.e. the outputs are bit-identical.
#
# Needs: an Android NDK (ANDROID_NDK_HOME, or the newest under $ANDROID_HOME/ndk) and one adb
# device that can run arm64 binaries -- the head unit, an arm64 phone, or an x86_64 emulator
# image with ARM translation.
#
#   scripts/run-neon-parity.sh
#   BASELINE_REF=origin/master scripts/run-neon-parity.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WRAPPER="$ROOT/app/src/main/cpp/libjamesdsp-wrapper"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
CXX="$BIN/clang++"
[ -x "$CXX" ] || CXX="$BIN/clang++.exe"
ADB="${ADB:-adb}"
# Git Bash on Windows: never rewrite the /data/... device paths, and hand adb Windows-form local
# paths. Both are no-ops on Linux/macOS.
adb_() { MSYS_NO_PATHCONV=1 "$ADB" "$@"; }
host_path() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Same optimisation flags the app ships with (app/src/main/cpp/CMakeLists.txt).
build() {  # <output> <wrapper dir> [extra flags...]
    local out="$1" src="$2"
    shift 2
    "$CXX" --target=aarch64-linux-android26 -std=c++17 -O3 -ftree-vectorize -static-libstdc++ \
        -I"$src" "$@" -o "$out" \
        "$ROOT/native-tests/neon_parity/neon_parity.cpp" "$src/NativeBmwDspProcessor.cpp" \
        "$ROOT/native-tests/drwav_impl.cpp" -I"$ROOT/native-tests/third_party"
}

echo "building neon + scalar"
build "$OUT/parity_neon" "$WRAPPER"
build "$OUT/parity_scalar" "$WRAPPER" -DSIPHON_DISABLE_NEON
VARIANTS=(neon scalar)
if [ -n "${BASELINE_REF:-}" ]; then
    echo "building baseline from $BASELINE_REF"
    mkdir -p "$OUT/base/libjamesdsp-wrapper" "$OUT/base/libjdspimptoolbox"
    for f in $(git -C "$ROOT" ls-tree --name-only "$BASELINE_REF" app/src/main/cpp/libjamesdsp-wrapper/); do
        git -C "$ROOT" show "$BASELINE_REF:$f" > "$OUT/base/libjamesdsp-wrapper/$(basename "$f")"
    done
    cp "$ROOT/app/src/main/cpp/libjdspimptoolbox/dr_wav.h" "$OUT/base/libjdspimptoolbox/"
    build "$OUT/parity_baseline" "$OUT/base/libjamesdsp-wrapper"
    VARIANTS+=(baseline)
fi

DEV=/data/local/tmp/neon_parity
adb_ shell mkdir -p "$DEV" >/dev/null
adb_ push "$(host_path "$ROOT/native-tests/default_config.txt")" "$DEV/default_config.txt" >/dev/null
for v in "${VARIANTS[@]}"; do
    adb_ push "$(host_path "$OUT/parity_$v")" "$DEV/parity_$v" >/dev/null
    adb_ shell chmod 755 "$DEV/parity_$v"
    echo "running $v"
    adb_ shell "$DEV/parity_$v" "$DEV/default_config.txt" | tr -d '\r' > "$OUT/$v.txt"
done

status=0
for v in "${VARIANTS[@]:1}"; do
    if diff -u "$OUT/neon.txt" "$OUT/$v.txt"; then
        echo "neon == $v: bit-identical ($(wc -l < "$OUT/neon.txt") scenarios)"
    else
        echo "neon != $v: OUTPUT DIFFERS"
        status=1
    fi
done
cat "$OUT/neon.txt"
exit $status
