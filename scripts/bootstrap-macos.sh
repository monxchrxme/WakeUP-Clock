#!/bin/bash
# Project-local tools only. Run from any directory on an Apple Silicon Mac.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
mkdir -p "$TOOLS/downloads" "$TOOLS/jdk" "$TOOLS/android-sdk/cmdline-tools"
if [ ! -x "$TOOLS/jdk/Contents/Home/bin/java" ]; then
  curl -fL --retry 3 'https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse' -o "$TOOLS/downloads/jdk.tar.gz"
  tar -xzf "$TOOLS/downloads/jdk.tar.gz" -C "$TOOLS/jdk" --strip-components=1
fi
export JAVA_HOME="$TOOLS/jdk/Contents/Home"
export ANDROID_HOME="$TOOLS/android-sdk"
export GRADLE_USER_HOME="$TOOLS/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL --retry 3 'https://dl.google.com/android/repository/commandlinetools-mac_arm64-15859902_latest.zip' -o "$TOOLS/downloads/android-cli.zip"
  python3 -c 'import hashlib,sys; assert hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest() == "835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e"' "$TOOLS/downloads/android-cli.zip"
  unzip -q "$TOOLS/downloads/android-cli.zip" -d "$TOOLS/downloads/android-cli"
  mv "$TOOLS/downloads/android-cli/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
if [ ! -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
  curl -fL --retry 3 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -o "$ROOT/gradle/wrapper/gradle-wrapper.jar"
fi
python3 -c 'import hashlib,sys; assert hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest() == "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"' "$ROOT/gradle/wrapper/gradle-wrapper.jar"
# License acceptance remains interactive; review Google's terms when prompted.
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
echo "Tools ready. Run: bash scripts/android.sh testDebugUnitTest assembleDebug"
