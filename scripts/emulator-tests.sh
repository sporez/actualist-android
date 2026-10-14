#!/usr/bin/env bash
# Installs the debug and test APKs on the running emulator, launches the app, runs the
# instrumentation suite with `am instrument`, and collects results, screenshots and
# diagnostics into emulator-output/. Exits nonzero when any test fails or none ran.
set -u

APP_ID="com.sporez.actualist.android.experimental"
TEST_ID="${APP_ID}.test"
RUNNER="${TEST_ID}/androidx.test.runner.AndroidJUnitRunner"
OUT="emulator-output"
mkdir -p "$OUT/screenshots"

adb wait-for-device
adb shell getprop ro.build.version.release > "$OUT/device-android-version.txt"
adb shell getprop ro.product.model >> "$OUT/device-android-version.txt"

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Smoke launch: cold start the real app (demo mode needs no server) and capture it.
adb logcat -c
adb shell am start -W -n "${APP_ID}/com.sporez.actualist.android.MainActivity" | tee "$OUT/launch.txt"
sleep 6
adb exec-out screencap -p > "$OUT/screenshots/00-cold-launch.png"
if ! adb shell pidof "$APP_ID" > /dev/null; then
  echo "App process is not running after launch" | tee -a "$OUT/launch.txt"
  adb logcat -d > "$OUT/logcat-launch.txt"
  exit 1
fi
adb shell am force-stop "$APP_ID"

# Instrumentation and Compose UI tests.
adb logcat -c
adb shell am instrument -w -r "$RUNNER" | tee "$OUT/instrumentation-raw.txt"
adb logcat -d > "$OUT/logcat.txt"

# Screenshots the tests saved in the app's external files directory.
adb pull "/sdcard/Android/data/${APP_ID}/files/screenshots/." "$OUT/screenshots/" || true

python3 scripts/instrumentation-summary.py "$OUT/instrumentation-raw.txt" > "$OUT/summary.txt"
status=$?
cat "$OUT/summary.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$OUT/summary.txt" >> "$GITHUB_STEP_SUMMARY"
fi
ls -la "$OUT/screenshots"
exit $status
