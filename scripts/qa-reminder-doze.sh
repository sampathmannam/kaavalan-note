#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_BIN="${ADB_BIN:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
JAVA_RUNTIME="${JAVA_RUNTIME:-${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}}"
QA_PACKAGE="com.kaavalan.note.debug.physicalqa"
QA_TEST_PACKAGE="${QA_PACKAGE}.test"
SERIAL="${ANDROID_SERIAL:-}"

if [[ -z "$SERIAL" ]]; then
  SERIAL="$($ADB_BIN devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "No connected Android device."
  exit 2
fi

adb_cmd() {
  "$ADB_BIN" -s "$SERIAL" "$@"
}

cleanup() {
  adb_cmd shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb_cmd shell dumpsys battery reset >/dev/null 2>&1 || true
  adb_cmd shell appops set "$QA_PACKAGE" SCHEDULE_EXACT_ALARM default >/dev/null 2>&1 || true
  adb_cmd uninstall "$QA_TEST_PACKAGE" >/dev/null 2>&1 || true
  adb_cmd uninstall "$QA_PACKAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$ROOT_DIR"
JAVA_HOME="$JAVA_RUNTIME" ./gradlew \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  -Pkaavalan.debugApplicationIdSuffix=.debug.physicalqa >/dev/null

adb_cmd install -r -t app/build/outputs/apk/debug/app-universal-debug.apk >/dev/null
adb_cmd install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb_cmd shell pm grant "$QA_PACKAGE" android.permission.POST_NOTIFICATIONS
adb_cmd shell appops set "$QA_PACKAGE" SCHEDULE_EXACT_ALARM allow

SETUP_OUTPUT="$(adb_cmd shell am instrument -w \
  -e clearPackageData false \
  -e hostDozeProbe true \
  -e class com.kaavalan.note.ReminderDozeDeliveryTest \
  "$QA_TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner")"
if ! rg -q 'OK \(1 test\)' <<<"$SETUP_OUTPUT"; then
  echo "$SETUP_OUTPUT"
  echo "Reminder setup instrumentation failed."
  exit 1
fi

# The setup process has exited. Force idle now so only the manifest receiver +
# exact AlarmManager wake-up can deliver; WorkManager is the secondary backstop.
adb_cmd shell am kill "$QA_PACKAGE" >/dev/null 2>&1 || true
adb_cmd shell dumpsys deviceidle force-idle >/dev/null

for _ in $(seq 1 50); do
  if adb_cmd shell dumpsys notification --noredact | rg -q 'Doze delivery check'; then
    echo "PASS: reminder posted after process death while the device was forced into Doze."
    exit 0
  fi
  sleep 1
done

echo "FAIL: no Kaavalan reminder appeared within 50 seconds under forced Doze."
exit 1
