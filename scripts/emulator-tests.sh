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
# Let a freshly booted emulator finish starting up before anything is measured: an activity
# launched while the system is still settling was once recreated mid-test.
for _ in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell input keyevent 82 || true
# Fixed portrait (tests that need landscape request it themselves).
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 0 || true
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

# A short settle after the smoke launch, so the suite does not start during background dexopt.
sleep 15

# Instrumentation and Compose UI tests. When the job runs a disposable Actual server, expose
# it on the device's own loopback (the only place the app allows plain http) and pass it on.
SERVER_ARGS=()
if [ -n "${ACTUAL_TEST_SERVER_URL:-}" ]; then
  adb reverse tcp:5006 tcp:5006
  SERVER_ARGS=(-e actualServerUrl "http://127.0.0.1:5006" -e actualServerPassword "$ACTUAL_TEST_SERVER_PASSWORD")
  echo "Real Actual server exposed to the device at 127.0.0.1:5006" | tee "$OUT/real-server.txt"
fi
# Warm-up: the first test after boot has twice hung on a cold emulator (and a timeout can poison
# the next test). Pay that cost on a throwaway smoke run whose result is ignored.
timeout 300 adb shell am instrument -w -e class com.sporez.actualist.android.LaunchSmokeTest "$RUNNER" > "$OUT/warmup.txt" 2>&1 || true
adb logcat -c
FILTER_ARGS=()
if [ -n "${TEST_FILTER:-}" ]; then
  FILTER_ARGS=(-e class "$TEST_FILTER")
  echo "Running only $TEST_FILTER" | tee "$OUT/filter.txt"
fi
# Bounded, so logcat and results are still collected if something hangs outside a single test.
timeout 2700 adb shell am instrument -w -r "${SERVER_ARGS[@]}" "${FILTER_ARGS[@]}" "$RUNNER" | tee "$OUT/instrumentation-raw.txt"
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
