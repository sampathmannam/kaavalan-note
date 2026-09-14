# Plum notebook theme — 2026-09-14

The user approved muted plum instead of blue. This is a local UI change on
`feat/end-to-end-hardening-and-polish`, following v2.7.2. No version bump, database
migration, production signing, push, tag or release publication is included.

The user subsequently requested a release. The separate v2.7.3 release record
documents that authorized version bump, signing, upgrade verification and
publication; the scope and results below describe the earlier palette work.

## Applied

- Light: plum `#633F5A`, neutral background `#F8F7F8`, white cards, charcoal `#27232A`.
- Dark: light plum accents on charcoal surfaces, with independently chosen text,
  container, outline and inverse roles.
- Open/Carried over are neutral; In progress is plum; Waiting is amber;
  Ready to verify is muted green; Done/Closed without action are grey.
  Written labels remain, and only verified Done gets a checkmark.
- Shared instruction cards and detail sheets use the same status tokens. Card tints
  are opaque and restrained; Priority is no longer green like Ready to verify.
- Legacy tag/identity accents, Glance widgets and native window colours follow the
  palette. The existing launcher artwork is unchanged.
- Data, voice, reminders, backups, navigation and existing theme-choice behaviour
  are unchanged. No inference service or dependency was added.

## Build and unit verification

- `testDebugUnitTest`: 885 discovered, 873 passed, 12 existing skips, no failures/errors.
- Expanded contrast checks cover text roles, every status badge, regular/featured
  card body and metadata in both themes, plus distinct Waiting/Ready/Closed roles.
  Tested text/background pairs meet 4.5:1; this is not a full accessibility audit.
- `lintDebug`: passed, zero errors/fatals, 341 warnings.
- `assembleDebug` and `assembleDebugAndroidTest`: passed.
- `git diff --check`: passed.

The first combined build encountered an incremental generated-output fault:
`KaavalanApplication_GeneratedInjector.class` existed in javac output but was missing
from the transformed runtime jar, causing three API-26 voice-service tests to fail.
The generated transform directory was moved to a temporary backup and rebuilt with
the build cache disabled. The class returned and the full suite passed without
changing voice code or suppressing tests.

Verification used JDK 17, repository-local Gradle/Android/test homes, one Gradle worker,
a 1536 MiB build heap and `-Pkaavalan.debugApplicationIdSuffix=.debug.plum`.
The initial emulator was stopped after Android system ANR under host memory pressure;
device checks run after build checks, with no unrelated processes stopped.

## Native visual verification

- Final `connectedDebugAndroidTest` subset: **6 passed, 0 failed, 0 skipped**, using
  Android Test Orchestrator on Android 14/API 34 (`kaavalan-officer-ui`, emulator-5596).
  Only `com.kaavalan.note.debug.plum` and its test package were used.
- `PlumPaletteVisualReviewTest`: labelled Open/In progress/Waiting/Ready/Closed
  cards in light and dark mode; screenshots require the app window to have focus.
- `OfficerVisualReviewTest`: Today, Instructions, Contacts, settings, dark capture
  with the real keyboard, 150% text, landscape and reachable Save.
- `FollowUpWorkspaceEndToEndTest`: editing, dated updates, verification, Undo,
  search, activity recreation, contact metadata and landscape update capture.

Native screenshots were also retained from the diagnostic direct runner at
`app/build/ui-qa/plum/ui-review/`. Reviewed files include `plum-light-active.png`,
`plum-dark-closed.png`, `officer-02-today-light.png`,
`officer-08-capture-keyboard-dark.png` and
`officer-11-landscape-large-text-capture.png`. These are unmodified captures of
synthetic data, not mocked screens or officer records.

The initial device setup hit a debug-signature mismatch after the Android user-home
configuration changed. The test runner removed only the temporary plum QA install;
the retry installed successfully. Three subsequent keyboard checks timed out because
a lingering Android **System UI** ANR dialog covered the app. Dismissing that system
dialog restored keyboard focus without changing capture code. A new screenshot
window-focus assertion prevents that overlay from producing a false passing visual
review. A diagnostic direct multi-test run exposed shared-state interference; the
final six-scenario run above used the project's normal per-test orchestration and
passed. No assertions or existing tests were disabled.

Production installations are untouched. This is not a complete device matrix,
physical-phone/TalkBack audit or signed-release verification. The broader 27-scenario
device suite was not rerun for this palette change. Launcher artwork remains unchanged.
