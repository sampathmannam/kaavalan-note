# Complete phone-contact import

## Report and cause

The v2.4.0 picker queried only phone-number rows and stopped after 50 entries. A contact with several numbers consumed several of those entries. Contacts later in the address book were unreachable, contacts without numbers were excluded, and a shared phone number was used as a Compose item key. Provider reads also ran on the UI thread, while denial and provider failure could appear as an empty directory.

## Fix

- Read the full accessible `Contacts` directory and associate `Phone` rows by Android contact ID. No account filter or hard result limit is applied. Name-only contacts remain selectable.
- Keep different people who share a number separate. Deduplicate formatting variants within one contact; retain distinct numbers as separate choices because the app's existing contact schema stores one phone number.
- Perform provider reads on the IO dispatcher and search on the Default dispatcher. Render results using a lazy list, without loading contact photos.
- Search by name (including non-Latin names) or formatted/unformatted phone number. Refresh explicitly or on returning to the app.
- Distinguish loading, denied access, empty directory, no search matches and retryable provider failure. Provide optional app-settings recovery after denial. Cancelled work is not converted into a failed load.
- Import only the selected name/number into the current workspace. Preserve the existing schema, privacy mode, and success-only dismissal. Store a missing phone as null.

The Impeccable hardening guidance informed the distinct failure/empty/loading states, readable native controls, scrolling large-text layout and search for large directories. The existing Material theme and three-tab navigation are unchanged.

## Privacy and limits

The importer never writes to Android contacts, never logs their names/numbers or provider exception details, and adds no network calls. It can read only contacts Android makes available to this app in its current profile. Unsynced contacts and contacts isolated in another work profile are not silently bypassed. This is a picker, not an automatic bulk-copy or ongoing sync feature.

Android references: [Contacts Provider](https://developer.android.com/identity/providers/contacts-provider) and [runtime permission handling](https://developer.android.com/training/permissions/requesting).

## Regression verification

- `ContactSyncServiceTest`: 2,000-entry directory, final contact, stable keys, multiple/shared numbers, formatting duplicates, missing names/numbers, orphan rows, permission denial/revocation, unavailable/crashing provider, cancellation and Unicode/number search.
- `WorkspaceViewModelTest`: selected number and null-number persistence, hidden workspace scope and retaining the picker after a failed import.
- `PhoneContactImportTest`: isolated emulator-only synthetic Android provider → actual picker → ViewModel → encrypted Room. Searches beyond the old limit, imports a number and a name-only contact, verifies persistence through recreation, checks shared-number/no-results UI, and compares the provider directory before/after import. Cleanup targets only the raw contact IDs created by that test. No WRITE_CONTACTS permission is added to the application.

### Development gate results

On branch `fix/complete-phone-contact-import`:

- Full JVM unit suite: 740 tests, 728 passed, 12 existing skips, zero failures or errors across 145 suites. All 12 new contact-service tests and both new workspace import tests passed.
- Android lint: zero fatal findings or errors; 334 warnings and 550 informational findings remain in the project report. No lint checks were suppressed to pass this change.
- Debug APK, debug instrumentation APK and unsigned release APK (including shrinking) built successfully. Release signing was explicitly disabled. These development artifacts are not a signed phone update.

The Gradle-connected instrumentation attempt could not launch tests because Android Test Services' `speak_easy` provider was unavailable on the unstable emulator; it reported zero tests and is not counted as a pass. The focused test is therefore run directly with AndroidJUnitRunner against the isolated `.debug.officer` package on the task-owned Android 14 emulator. The normal CI Orchestrator configuration remains unchanged.

The focused end-to-end test passed on the task-owned Android 14 emulator: `OK (1 test)`, 16.159 seconds. It exercised 68 synthetic contacts / 69 selectable number-or-name rows, including the contact beyond the old 50-row limit. The selected phone and name-only contact were saved through the real ViewModel and encrypted Room database. The numbered contact retained its saved ID through activity recreation, and the Android provider directory remained unchanged by import. Provider cleanup was checked afterward: no synthetic raw contacts remained.

The direct invocation was `adb -s emulator-5596 shell am instrument -w -r -e class com.kaavalan.note.PhoneContactImportTest com.kaavalan.note.debug.officer.test/androidx.test.runner.AndroidJUnitRunner`. For a fresh repeat, use a clean isolated QA app database (or the normal per-test Orchestrator reset); do not clear or test the production phone app.

Both diagnostic screenshots were captured and visually inspected with the keyboard open: `app/build/ui-qa/contact-import-review/contact-import-beyond-fifty.png` and `contact-import-shared-number.png`. Search, Close and matching contact rows are visible and unobscured. Screenshot capture is diagnostic only; an unavailable emulator capture is logged instead of preventing the functional assertions from running.

Additional manual emulator verification: revoke contact access, cold-launch the app, verify both saved synthetic app contacts remain, open the picker, deny the Android permission prompt, verify denial guidance and the app-settings action, request permission again and allow it. The picker reloaded into the correctly distinguished empty-directory state after the synthetic provider contacts had been cleaned up. Native screenshots of the permission rationale, denial and empty states are saved beside the automated screenshots.

Earlier unsuccessful attempts included an obsolete test button label (corrected), a null emulator screenshot, and an emulator-process exit. The successful run followed a cold restart with Vulkan disabled; these environment failures were not treated as successful tests.

Development verification did not access the production checkout or signing material and did not modify the physical phone. Signing, publication and phone installation are separate release steps.

### v2.4.1 release preparation

After approval to commit, push and release, the version was raised to `2.4.1` / code `50`, retaining `com.kaavalan.note`. CI's connected-device command now uses `.debug.officer`, matching the instrumentation test's safety guard. Device selection matches the designated AVD name (`kaavalan-test` by default, configurable with repository variable `KAAVALAN_CI_AVD_NAME`) before any wake or test commands; an unrelated emulator is not selected merely because it is first in `adb devices`.

The workflow YAML parsed successfully. Five isolated shell fixtures passed: no devices, physical phone only, unrelated emulator only, designated emulator after an unrelated emulator, and an offline designated emulator. No real device was mutated by these fixtures. Release signing remains subject to the original-key access boundary; an unsigned validation artifact must never be published as an update.
