#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -d "$ROOT/.tools/jdk/Contents/Home" ]; then
  export JAVA_HOME="$ROOT/.tools/jdk/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -d "$ROOT/.tools/android-sdk/platforms" ]; then
  export ANDROID_HOME="$ROOT/.tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.tools/gradle-home}"
cd "$ROOT"
exec ./gradlew "$@"
