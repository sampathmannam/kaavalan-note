# Officer workspace activity lifecycle investigation

Date: 2026-09-09. Baseline: `378a88641f7181532c083b94f2a0f7a2837b8a65`.

## Status

The intermittent activity-close crash is **not yet a confirmed fix**. This
change adds lifecycle coverage; it does not change production code, dependencies,
signing, application data, or visual design. Keep the redesign unmerged and the
release on hold until the remaining investigation and CI validation are complete.

## Observed failure

[CI run 34265120010, attempt 2](https://github.com/sampathmannam/kaavalan-note/actions/runs/34265120010)
passed 12 of 13 instrumented tests. `BottomNavTabSwitchTest` failed while
`ActivityScenarioRule.after` closed the activity:

```text
ArrayIndexOutOfBoundsException: length=1505; index=-1221
SlotTableKt.dataAnchor
SlotWriter.moveSlotGapTo / removeSlots / removeGroup
CompositionImpl.dispose
WrappedComposition.onStateChanged
LifecycleRegistry.backwardPass
Activity.performDestroy
```

This identifies where the crash surfaced, not what corrupted the composition.
There is no app-level composition disposal override in the failing path.

The earlier CI attempt's two keyboard timeouts had a separate, confirmed cause:
an emulator System UI ANR dialog held focus before tests began. A similar startup
ANR occurred during this investigation; the synthetic test process was stopped
and the project emulator rebooted before recording app-test results. An emulator
startup failure is not evidence that the application disposal crash is resolved.

The resolved Compose runtime is 1.7.5 (BOM 2024.10.01). Inspection of the published
1.7.5 and 1.7.8 source archives found identical `SlotTable.kt`, `Composer.kt`, and
`Composition.kt` files. A patch-version bump alone is therefore not a demonstrated
fix for this stack. No speculative dependency upgrade was made.

## Added coverage

`WorkspaceLifecycleTest` runs against the real activity, Hilt, and Room:

- Switch Today → Instructions → Contacts, open/dismiss Settings, return to Today,
  then recreate the activity ten times. Assert the selected tab after recreation.
- Recreate five times with the Settings dialog still attached, then verify the
  Contacts tab remains reachable.
- Explicitly close the activity in both test bodies and assert `DESTROYED`.

There is deliberately no extra idle wait between the final navigation click and
recreation. Android can destroy an activity with a transition in progress.
Failures are not caught, retried, ignored, or converted into passes. Existing
tests and their assertions are unchanged. Following the Impeccable hardening
guidance, the new coverage targets lifecycle interruption, not cosmetic changes.

## Verification

- Debug app and test APK assembly: passed.
- `testDebugUnitTest`: explicitly rerun after the device checks; 723 tests,
  711 passed, 12 pre-existing skips, no failures or errors.
- `lintDebug`: passed, including analysis of the new instrumented test. No errors
  or fatal findings; the existing 334 warnings remain.
- Direct Android 14 lifecycle run: 2/2 passed in 68.549 seconds, comprising 15
  recreations and two explicit closes.
- Full Android Test Orchestrator run: 15/15 passed, no failures or skips, on the
  project-owned Android 14 emulator. Gradle completed in 10m 52s. The two new tests
  again exercised 15 recreations and two explicit closes with fresh per-test data.
  The three pre-existing source-excluded legacy test files were not executed.
- `assembleRelease`: passed with signing disabled, producing unsigned universal
  and ABI-specific APKs for version 2.4.0 / code 49. R8 reused its unchanged-input
  result. These are verification artifacts, not installable signed updates.

Earlier isolated navigation checks also passed, including five fresh-data
repeats. Passing reproduction attempts do not prove an intermittent defect fixed.

Local CI evidence is preserved under the ignored `app/build/ui-qa/` directory:
`ci-34265120010-attempt1/`, `ci-34265120010-attempt2/`, and
`ci-merge-readiness.md`. Raw logcat is not committed.

## Development-only reproduction

Run from the development project with JDK 17 and the Android SDK configured.
Use only the project-owned emulator, never a physical phone:

```sh
GRADLE_USER_HOME="$PWD/.gradle-user-home" \
ANDROID_USER_HOME="$PWD/.android-dev" \
ANDROID_SERIAL=emulator-5556 \
./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkaavalan.debugApplicationIdSuffix=.debug.officer
```

The orchestrator gives each test a fresh synthetic application state. Before
starting, verify the emulator has finished booting, the launcher has window
focus, and no System UI ANR dialog is present. Do not disable failure reporting
or add retries to obtain a green result.

Next: after approval to push this coverage, run it in PR #35's CI environment and
retain the failure artifacts if the crash returns. A release still requires its
own approval and an authorized signing handoff outside assistant access.
