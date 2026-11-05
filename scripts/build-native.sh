#!/usr/bin/env bash
# Build the static uinput_x360 binary for every supported ABI.
# Usage: ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-native.sh
#        OUT_DIR=/tmp/out bash scripts/build-native.sh   (verification only)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/uinput_x360.c"
OUT_DIR="${OUT_DIR:-$ROOT/app/src/main/assets/bin}"
# API 34 sysroot is required for static arm64 binaries: older sysroots produce
# a PT_TLS segment aligned to 8, which Android's Bionic loader rejects on
# ARM64 ("TLS segment is underaligned, needs at least 64").
API="${ANDROID_API:-34}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  echo "please set ANDROID_NDK_HOME to an Android NDK (r26d or newer)" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux*)  HOST_TAG=linux-x86_64 ;;
  Darwin*) HOST_TAG=darwin-x86_64 ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG=windows-x86_64 ;;
  *) echo "unsupported host" >&2; exit 1 ;;
esac

CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/clang"
[[ -x "${CLANG}.exe" ]] && CLANG="${CLANG}.exe"
if [[ ! -x "$CLANG" ]]; then
  echo "clang not found: $CLANG" >&2
  exit 1
fi

declare -a TARGETS=(
  "arm64-v8a:aarch64-linux-android"
  "armeabi-v7a:armv7a-linux-androideabi"
  "x86_64:x86_64-linux-android"
)

for spec in "${TARGETS[@]}"; do
  ABI="${spec%%:*}"
  TRIPLE="${spec##*:}"
  DEST="$OUT_DIR/$ABI"
  mkdir -p "$DEST"
  echo "building $ABI ($TRIPLE$API)"
  "$CLANG" --target="$TRIPLE$API" -O2 -static -Wall -Wextra -Wno-unused-parameter \
    -o "$DEST/uinput_x360" "$SRC"
done

# Legacy fallback path used by older app versions.
if [[ "$OUT_DIR" == "$ROOT/app/src/main/assets/bin" ]]; then
  cp "$OUT_DIR/arm64-v8a/uinput_x360" "$ROOT/app/src/main/assets/uinput_x360"
fi

echo "done: $OUT_DIR"
