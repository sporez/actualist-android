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
# A loaded CI emulator can raise "isn't responding" dialogs for system apps (the launcher)
# that cover the app under test. Hide them; they are not part of what is being tested.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
adb shell settings put global show_first_crash_dialog 0 || true
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

# Process death: open the app on the budget the last test left selected, send it to the
# background, kill the process as the system would, and relaunch. The persisted selection
# must reopen the budget (not onboarding); the screenshot records what came back.
adb shell am start -W -n "${APP_ID}/com.sporez.actualist.android.MainActivity" > /dev/null
sleep 8
# If the last test left no budget selected, open the demo through the UI first.
adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb pull /sdcard/window.xml "$OUT/before-process-death-window.xml" > /dev/null 2>&1 || true
bounds=$(grep -o 'text="Explore the demo budget"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/before-process-death-window.xml" 2>/dev/null | grep -o 'bounds="[^"]*"' | head -1)
if [ -n "$bounds" ]; then
  nums=$(echo "$bounds" | grep -o '[0-9]*')
  set -- $nums
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 25
fi
adb exec-out screencap -p > "$OUT/screenshots/30-before-process-death.png"
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am kill "$APP_ID"
sleep 2
if adb shell pidof "$APP_ID" > /dev/null; then
  echo "process still alive after am kill" | tee "$OUT/process-death.txt"
fi
adb shell am start -W -n "${APP_ID}/com.sporez.actualist.android.MainActivity" | tee -a "$OUT/process-death.txt"
sleep 10
adb exec-out screencap -p > "$OUT/screenshots/31-after-process-death.png"
adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb pull /sdcard/window.xml "$OUT/after-process-death-window.xml" > /dev/null 2>&1 || true
if grep -q "Explore the demo budget" "$OUT/after-process-death-window.xml" 2>/dev/null; then
  echo "FAIL: relaunch after process death showed onboarding instead of the selected budget" | tee -a "$OUT/process-death.txt"
  restore_failed=1
else
  echo "Relaunch after process death reopened the selected budget" | tee -a "$OUT/process-death.txt"
fi

# Screenshots the tests saved in the app's external files directory.
adb pull "/sdcard/Android/data/${APP_ID}/files/screenshots/." "$OUT/screenshots/" || true

python3 scripts/instrumentation-summary.py "$OUT/instrumentation-raw.txt" > "$OUT/summary.txt"
status=$?
cat "$OUT/summary.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$OUT/summary.txt" >> "$GITHUB_STEP_SUMMARY"
fi
cat "$OUT/process-death.txt" >> "$OUT/summary.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo; echo "### Process death"; cat "$OUT/process-death.txt"; } >> "$GITHUB_STEP_SUMMARY"
fi
ls -la "$OUT/screenshots"
if [ "${restore_failed:-0}" = "1" ]; then
  exit 1
fi
exit $status
