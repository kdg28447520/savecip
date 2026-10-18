#!/usr/bin/env bash
# GitHub Actions에 묶이지 않는 로컬/CI 공통 빌드.
# ANDROID_HOME 또는 android/local.properties 의 sdk.dir 가 필요합니다.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${ANDROID_SDK_ROOT:-}" && -z "${ANDROID_HOME:-}" ]]; then
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
fi
exec ./gradlew assembleDebug testDebugUnitTest --stacktrace "$@"
