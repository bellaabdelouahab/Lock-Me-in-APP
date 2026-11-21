#!/usr/bin/env bash
# Draws every screen of the preview build to PNG files on this computer, no emulator or phone: about a minute after a change.
# Output: build/shots/*.png (or the folder given as the first argument). Only for looking at the design; it checks nothing,
# and the ship gate (CI: logic tests, emulator tests, release) is unchanged. Same SDK setup as scripts/check.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f local.properties ] || echo "sdk.dir=$HOME/android-sdk" > local.properties
out=$(realpath -m "${1:-build/shots}")
rm -rf "$out"; mkdir -p "$out"
./gradlew -q --console=plain -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2 -PcompileSdkOverride=34 -Ppreview=true \
  -Dshots.dir="$out" :app:testDebugUnitTest --tests 'app.lockmein.ScreenShots' --rerun
echo "shots: $(ls "$out" | wc -l) screens in $out"
