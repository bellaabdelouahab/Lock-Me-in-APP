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

# The shrunk release build, made test-only so it can be removed again: this is the file that ships, so this is what is tested.
FLAGS="-PtestOnly=true -PtestRelease=true"
./gradlew :app:installRelease $FLAGS --console=plain || exit 1
adb shell dpm set-device-owner app.lockmein/.AdminReceiver || exit 1

./gradlew :app:connectedReleaseAndroidTest $FLAGS --console=plain
status=$?

if [ "$status" -ne 0 ]; then
  echo "=== Android's private DNS log ==="
  adb logcat -d | grep -iE "privatedns|PrivateDns|DnsManager|dnsresolver" | tail -40
  echo "=== connectivity state ==="
  adb shell dumpsys connectivity | grep -iE "private dns|validated|dns" | head -30
  adb shell settings get global private_dns_mode
  adb shell settings get global private_dns_specifier
fi
exit "$status"
