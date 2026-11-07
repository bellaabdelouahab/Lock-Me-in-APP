#!/usr/bin/env bash
# Runs inside the emulator job: network on, app installed as device owner, tests, and Android's DNS log when they fail.
set -u

adb shell cmd connectivity airplane-mode disable
adb shell svc wifi enable
adb shell svc data enable
for _ in $(seq 1 30); do
  adb shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1 && break
  sleep 2
done
adb shell "echo | toybox nc -w 5 8.8.8.8 853; echo dot_rc=\$?"

./gradlew :app:installDebug --console=plain || exit 1
adb shell dpm set-device-owner app.lockmein/.AdminReceiver || exit 1

./gradlew :app:connectedDebugAndroidTest --console=plain
status=$?

if [ "$status" -ne 0 ]; then
  echo "=== Android's private DNS log ==="
  adb logcat -d | grep -iE "privatedns|PrivateDns|DnsManager|dnsresolver" | tail -40
  echo "=== connectivity state ==="
  adb shell dumpsys connectivity | grep -iE "private dns|validated|dns" | head -30
  adb shell settings get global private_dns_mode
  adb shell settings get global private_dns_specifier
fi

# The shipped file is the shrunk release build, which the debug tests above do not cover. Install it as the test build, open it,
# and fail on a crash. A screenshot is kept so the first screen can be looked at.
mkdir -p app/build/reports/androidTests
adb uninstall app.lockmein >/dev/null 2>&1
./gradlew :app:installRelease -PtestOnly=true --console=plain || exit 1
adb logcat -c
adb shell am start -W -n app.lockmein/.MainActivity
sleep 8
adb exec-out screencap -p > app/build/reports/androidTests/first-screen.png
if ! adb shell pidof app.lockmein >/dev/null; then
  echo "=== the shrunk build is not running after launch ==="
  adb logcat -d | grep -E "AndroidRuntime|FATAL|app.lockmein" | tail -60
  status=1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "=== crash in the shrunk build ==="
  adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -60
  status=1
fi

# The test build's USB way out: as device owner, one broadcast from the shell must end device ownership.
adb shell dpm set-device-owner app.lockmein/.AdminReceiver || status=1
adb shell am broadcast -n app.lockmein/.UsbEscapeReceiver
sleep 5
if adb shell dpm list-owners | grep -q app.lockmein; then
  echo "=== the USB way out did not end device ownership ==="
  status=1
fi

# Photograph every screen of the preview build (sample data, nothing applied) so the design can be looked at.
adb uninstall app.lockmein >/dev/null 2>&1
./gradlew :app:installRelease -Ppreview=true --console=plain || exit 1
mkdir -p app/build/reports/androidTests/shots
shot() { # name, extras...
  name=$1; shift
  adb shell am force-stop app.lockmein
  adb shell am start -W -n app.lockmein/.MainActivity "$@" >/dev/null
  sleep 3
  adb exec-out screencap -p > "app/build/reports/androidTests/shots/$name.png"
}
for n in 0 1 2 3 4 5 6; do shot "setup-$n" --ei step $n; done
for s in HOME HEALTH WINDOW LISTS BROWSERS EMERGENCY REPORT SETTINGS REMOVE ABOUT; do shot "$(echo $s | tr A-Z a-z)" --es screen $s --ez demo true; done
shot "lists-window-apps" --es screen LISTS --ei tab 2 --ez demo true
shot "code" --es screen EMERGENCY --ez demo true
shot "drawer" --es screen HOME --ez demo true --ez openDrawer true
exit "$status"
