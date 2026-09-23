#!/usr/bin/env bash
# Bit-exact parity check for the native DSP (see native-tests/neon_parity/neon_parity.cpp).
#
# Builds the parity harness twice from the working tree -- as shipped ("current": NEON on arm64)
# and with -DSIPHON_DISABLE_NEON ("scalar") -- plus, with BASELINE_REF set, once more from that
# git ref's source (e.g. master before a DSP change or refactor). Fails unless every scenario
# hash matches across all builds, i.e. the outputs are bit-identical.
#
# Two modes:
#   default  arm64 build via the Android NDK, run on the one connected adb device (head unit,
#            arm64 phone, or an x86_64 emulator image with ARM translation). Covers NEON.
#   HOST=1   build + run on this machine with the host compiler (HOST_CXX, default c++). No
#            device needed, but the host isn't arm64, so only the scalar path is exercised --
#            this is what CI runs to prove a change doesn't alter the output.
#
#   scripts/run-neon-parity.sh
#   BASELINE_REF=origin/master scripts/run-neon-parity.sh
#   HOST=1 BASELINE_REF=origin/master scripts/run-neon-parity.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WRAPPER="$ROOT/app/src/main/cpp/libjamesdsp-wrapper"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

if [ -n "${HOST:-}" ]; then
    CXX="${HOST_CXX:-c++}"
    TARGET_FLAGS=()
else
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
    BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
    CXX="$BIN/clang++"
    [ -x "$CXX" ] || CXX="$BIN/clang++.exe"
    TARGET_FLAGS=(--target=aarch64-linux-android26 -static-libstdc++)
    ADB="${ADB:-adb}"
fi
# Git Bash on Windows: never rewrite the /data/... device paths, and hand adb Windows-form local
# paths. Both are no-ops on Linux/macOS.
adb_() { MSYS_NO_PATHCONV=1 "$ADB" "$@"; }
host_path() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

# Same optimisation flags the app ships with (app/src/main/cpp/CMakeLists.txt). Compiles every
# NativeBmw*.cpp in the source dir except the JNI glue, so it works for any revision's file split.
build() {  # <output> <wrapper dir> [extra flags...]
    local out="$1" src="$2"
    shift 2
    local sources=()
    for f in "$src"/NativeBmw*.cpp; do
        [ "$(basename "$f")" = NativeBmwDspJni.cpp ] || sources+=("$f")
    done
    "$CXX" "${TARGET_FLAGS[@]}" -std=c++17 -O3 -ftree-vectorize -I"$src" "$@" -o "$out" \
        "$ROOT/native-tests/neon_parity/neon_parity.cpp" "${sources[@]}" \
        "$ROOT/native-tests/drwav_impl.cpp" -I"$ROOT/native-tests/third_party"
}

echo "building current + scalar"
build "$OUT/parity_current" "$WRAPPER"
build "$OUT/parity_scalar" "$WRAPPER" -DSIPHON_DISABLE_NEON
VARIANTS=(current scalar)
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

if [ -n "${HOST:-}" ]; then
    for v in "${VARIANTS[@]}"; do
        echo "running $v"
        "$OUT/parity_$v" "$ROOT/native-tests/default_config.txt" > "$OUT/$v.txt"
    done
else
    DEV=/data/local/tmp/neon_parity
    adb_ shell mkdir -p "$DEV" >/dev/null
    adb_ push "$(host_path "$ROOT/native-tests/default_config.txt")" "$DEV/default_config.txt" >/dev/null
    for v in "${VARIANTS[@]}"; do
        adb_ push "$(host_path "$OUT/parity_$v")" "$DEV/parity_$v" >/dev/null
        adb_ shell chmod 755 "$DEV/parity_$v"
        echo "running $v"
        adb_ shell "$DEV/parity_$v" "$DEV/default_config.txt" | tr -d '\r' > "$OUT/$v.txt"
    done
fi

status=0
for v in "${VARIANTS[@]:1}"; do
    if diff -u "$OUT/current.txt" "$OUT/$v.txt"; then
        echo "current == $v: bit-identical ($(wc -l < "$OUT/current.txt") scenarios)"
    else
        echo "current != $v: OUTPUT DIFFERS"
        status=1
    fi
done
cat "$OUT/current.txt"
exit $status
