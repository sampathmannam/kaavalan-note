# Officer workspace architecture

## Why it changed

The former Home/People screen owned contact creation, global search, inbound/outbound instructions, shared text, camera, microphone, capture and dispatch. Today separately projected a dated brief and performed its own mutations. This made features depend on which tab was composed, misclassified every raw note as outgoing, and duplicated detail state.

## Active boundaries

- `MainActivity`: Android lifecycle, share/widget/tile/reminder intent ingress and first-run gating.
- `OfficerAppRoot`: navigation, app-scoped workspace and capture view models, ID-based live instruction detail, settings/backup routes, undo and save feedback.
- `WorkspaceViewModel`: one mode-scoped reactive Room read model, retryable load state, serialized mutations with visible success/failure.
- `WorkspaceModel`: pure projections for Today, search and filters. Takes date/time zone explicitly. Includes all open lifecycle states; excludes closed work from attention; does not hide old work by age.
- `WorkspaceScreen`: stateless destinations and shared instruction cards. UI events are callbacks rather than new repository writes.
- `CaptureHost`: one lifecycle owner for media permissions, shared text, quick capture, calendar handoff and post-save feedback. Exists outside navigation destinations.
- `CaptureViewModel`: saved draft, responsibility/contact/reminder selection and save state. `createWithAudience` commits direction, contact, due ISO and due milliseconds together inside the existing Room transaction. Legacy create callers retain their outgoing default.
- `PersonDetailScreen`: contact-specific records, shared detail/reminder UI and the same app-level capture host. Relationships, important dates, sharing and privacy remain available as secondary actions.
- `SettingsCategory`: single selected settings section with back navigation; existing secure backup/recovery implementations remain intact.

## Privacy and data compatibility

No database migration or destructive conversion. Existing IDs, notes, contacts, tags, reminder work and backup formats are preserved. Legacy screens/helpers remain compiled for compatibility with existing isolated feature tests but are not application navigation destinations.

Workspace projections filter sensitive instructions and contacts outside the selected vault before search or rendering. By-person audience pointers are also scoped. Private-mode captures require a private contact because instructions do not independently carry a vault mode. Invalid or stale contact links fail visibly before a write. No UUID placeholder is presented as a person name.

Date projections refresh on resume and while the app remains open across midnight. Selected detail uses an ID, not a stale copy of an entity. Reminder notifications resolve into the same live workspace detail.

Cold startup no longer automatically loads or replaces synthetic fixtures, even in debug builds. Sample data remains an explicit developer action. Existing legacy `OCR` source values are read as photo captures without rewriting or discarding their records; this prevents one older photo row from failing the entire workspace projection.

Contacts uses a single scrolling directory, including its introductory controls. Empty/error content can scroll at larger text sizes. Capture and contact forms keep their save action outside the scrolling body. App-selected light/dark system-bar appearance also reaches the separate Material sheet windows.

In landscape, capture omits the drag-handle chrome. While the keyboard is open it also reduces vertical padding and hides the redundant heading, without replacing the focused editor or shrinking the user's text. Dismissing the keyboard restores the full heading and form. The large-text device check requires an editor viewport of at least 80dp alongside a visible, enabled Save action.

## Development safety

Debug configuration does not resolve local release signing properties. Resolving release signing requires the release operator to explicitly pass `-Pkaavalan.enableReleaseSigning=true`; do not enable this for ordinary development.

Use a repo-local Gradle/Android user home and `-Pkaavalan.debugApplicationIdSuffix=.debug.officer` for isolated redesign verification. This avoids replacing either a public release or an existing debug installation. No pushes, tags, release publication or production signing are part of this redesign without a new approval.

## Verification

Unit coverage includes responsibility/lifecycle projections, local midnight, malformed legacy dates, visibility filtering, draft/dedup behavior and contact-import persistence.

### Unit, lint and debug gates — 2026-09-08–09

- `testDebugUnitTest`: **723 total; 711 passed, 12 pre-existing skips, 0 failures/errors**. The old automatic-fixture-reseed expectations were replaced with startup-no-reseed guards; legacy OCR mapping has explicit compatibility tests.
- `lintDebug`: **passed, 0 errors/fatals, 334 warnings**. This is not a warning-free project; no lint failure was suppressed to pass this redesign.
- `assembleDebug` and `assembleDebugAndroidTest`: **passed**. The isolated arm64 debug APK is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`; version remains `2.4.0` / code `49`.
- `compileReleaseKotlin` and `lintVitalRelease`: **passed**, with release signing disabled.
- Final fresh-daemon `assembleRelease`: **passed in 4m 36s** on 2026-09-09. R8 minification, resource shrinking, four ABI APKs and the universal unsigned APK completed. The arm64 artifact is approximately 24 MiB, within the CI 40 MiB budget. APKs are in `app/build/outputs/apk/release/`; the R8 mapping is in `app/build/outputs/mapping/release/mapping.txt`.
- `git diff --check`: passed. No database schema/version change, commit, push, tag or release publication.

Artifact SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| `app-arm64-v8a-debug.apk` (isolated development install) | `2e0f671da4d3183a9b452e079012e96b0fb96fa39e276e0ed1abd56aca2feece` |
| `app-arm64-v8a-release-unsigned.apk` (build verification only; not installable until signed) | `c65f35f0ff1a14f369994c01526b2d9378fe29fffb75d746eb6ca46276f49635` |

### Device and visual acceptance — 2026-09-08

- Final `connectedDebugAndroidTest`: **13 passed, 0 failed, 0 skipped**, Android 14 Google APIs arm64 Pixel 6 emulator, isolated package `com.kaavalan.note.debug.officer`.
- Real Compose → ViewModel → SQLCipher Room → WorkManager coverage verifies contact-linked responsibility and reminder persistence, activity recreation, completion cancelling reminder work, completion/reopen, cross-tab drafts, shared-text ingress, settings, onboarding and photo/voice caption hit targets. Notification coverage checks private visibility and Done/Snooze actions.
- Two visual tests also passed independently. Eleven native screenshots cover light/dark themes, first use, Today, Instructions, Contacts, settings, keyboard capture, 150% system text size and landscape. Screenshots wait for both Compose and native window animations. Actual IME visibility is checked before keyboard screenshots.
- Cold force-stop/relaunch preserved three synthetic notes and two contacts. Evidence: `app/build/ui-qa/officer-cold-start.xml` and `officer-cold-contacts.xml`. No startup fixture replacement or workspace load failure appeared.
- Reviewed screenshots: `app/build/ui-qa/ui-review/officer-*.png`. These are generated local artifacts, not officer data or committed fixtures.

The configured device suite retains its pre-existing source exclusions. This is not a physical-phone test, a full OS/device compatibility matrix, a TalkBack audit or a field capture-speed benchmark. Voice recognition, camera quality and Google Drive account integration remain device/account-dependent. Localized strings outside English retain the existing partial translation coverage.
