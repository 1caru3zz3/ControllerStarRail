#!/usr/bin/env bash
# One-shot local build. Gradle is the supported path; native binaries can be
# rebuilt first with ANDROID_NDK_HOME=... bash scripts/build-native.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  bash scripts/build-native.sh
fi

if [[ -x ./gradlew ]]; then
  exec ./gradlew --no-daemon assembleRelease
fi

echo "gradlew not found; run 'gradle wrapper --gradle-version 8.9' first" >&2
exit 1
