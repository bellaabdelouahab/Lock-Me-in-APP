#!/usr/bin/env bash
# Fast check on the server (about a minute): logic tests + everything compiles, app and device tests.
# The device-owner tests themselves still need the emulator on GitHub. Needs ~/android-sdk (platform 34) and the
# Ubuntu `aapt` package; the server's aapt2 reads android-34 at most, so this links against 34.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f local.properties ] || echo "sdk.dir=$HOME/android-sdk" > local.properties
./gradlew -q --console=plain -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2 -PcompileSdkOverride=34 \
  :core:test :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin "$@"
echo "check: ok"
