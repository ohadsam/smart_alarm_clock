#!/usr/bin/env bash
#
# Runs the instrumented suite on the connected emulator, keeping a full copy of the
# Gradle output so a failure can actually be read afterwards.
#
# Why this exists: reactivecircus/android-emulator-runner executes its `script:` input
# one line at a time, each through its own `sh -c`. That gives no pipefail, no
# multi-line redirection, and no way to react to a failure — so when the suite went red
# the only record was ~900 lines of job log, of which only the tail is retrievable from
# the environment these changes are authored in. A compile error in an androidTest
# source produces no JUnit XML at all, so the test-report dump cannot see it either:
# the Gradle output itself has to be captured and re-printed at the end.
set -uo pipefail

LOG="${RUNNER_TEMP:-/tmp}/instrumented-test.log"

echo "── Installing app + test APKs ──"
./gradlew installDebug installDebugAndroidTest --stacktrace --no-daemon 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"
if [ "$STATUS" -ne 0 ]; then
  echo "::error::Installing the debug APKs failed."
  tail -120 "$LOG"
  exit "$STATUS"
fi

# POST_NOTIFICATIONS became a runtime permission in API 33. Ungranted, it turns into a
# system dialog sitting over the app in the middle of a UI test. `pm grant` cannot run
# before the package exists, and a reinstall keeps the grant, so this belongs between
# the install and the test run. Below 33 the permission is install-time and `pm grant`
# refuses it — not a failure, just a level with nothing to grant.
for PKG in com.smartring.app com.smartring.app.debug; do
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null \
    && echo "granted POST_NOTIFICATIONS to $PKG" \
    || echo "POST_NOTIFICATIONS not granted to $PKG (expected below API 33, or that id is unused)"
done

echo "── Running the instrumented suite ──"
./gradlew connectedDebugAndroidTest --stacktrace --no-daemon 2>&1 | tee -a "$LOG"
STATUS="${PIPESTATUS[0]}"
if [ "$STATUS" -ne 0 ]; then
  echo "::error::The instrumented suite failed. Gradle output tail below; JUnit XML (if any tests reported) is dumped by the workflow's last step."
  echo "── gradle output (tail) ──"
  tail -150 "$LOG"
  exit "$STATUS"
fi

echo "Instrumented suite passed."
