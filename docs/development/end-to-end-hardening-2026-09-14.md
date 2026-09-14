# KaavalanNote end-to-end hardening and polish — 2026-09-14

## Status

The original pass on branch `feat/end-to-end-hardening-and-polish` was published as
v2.7.2/code 55. The branch now contains the v2.7.4/code 57 release candidate, based
on the released v2.7.3 source. It retains the application identity, Room schema,
manual-backup schema, dependencies and pinned signing identity. This follow-up source
is verified and ready for the guarded signed-release procedure.

The cloud Penpot file is named **KaavalanNote field notebook UI v2.7**. It contains
Foundations, Components and Screens & states pages, all 27 screen/state boards,
light and dark component sheets, and the light, dark, spacing, radius, size and
typography token sets. The repository source remains under `design/penpot-v2.7/`.

## Post-v2.7.3 reliability strengthening

- Instruction mutations and capture/outbox writes now commit in single Room
  transactions. Regression tests inject SQLite trigger failures and prove that the
  primary row rolls back when its outbox write fails.
- Read-only projections isolate malformed legacy instruction values instead of
  blanking an entire screen. Paths that may overwrite data remain strict, and restore
  and plain import reject unsupported enums atomically. Legacy OCR remains compatible
  and maps to the current photo source.
- User-controlled restore/import input is limited to 64 MiB before parsing. Local
  backups use unique names, a mutex, same-directory temporary files, `fsync`, atomic
  publication where supported, cleanup and deterministic seven-file retention.
- Reminder Done/Snooze action failures no longer crash the app process; the visible
  notification remains available for retry. Home, Today, search-result and person
  detail mutations now surface safe, non-sensitive Snackbar errors without replacing
  healthy screen content.
- Expired captures now remove their legacy capture outbox markers, preventing orphaned
  queue growth. Existing capture payload cleanup remains covered by retention tests.

### Follow-up verification

| Gate | Result |
|---|---|
| JVM unit tests | 886/886 passed across 156 suites; 0 skipped, 0 failed, 0 errors |
| Android-native tests | 12 former JVM skips now run against Android Keystore, Argon2 and SQLCipher; all pass on both API 34 and API 37 |
| Android lint | Passed with 0 errors/fatals, 348 warnings and 562 informational findings |
| Debug APK | Passed; universal and four per-ABI APKs assembled |
| Optimized release APK | Passed with R8 and resource shrinking; unsigned universal and four per-ABI APKs assembled |
| Android 14/API 34 device suite | 39 passed, 2 intentional environment skips, 0 failures/errors on task-owned `kaavalan-test`, isolated as `com.kaavalan.note.debug.officer` |
| Physical Android 17/API 37 suite | 39 passed, 2 intentional harness skips, 0 failures/errors on the connected Motorola, isolated as `com.kaavalan.note.debug.physicalqa` |
| Forced-Doze reminder delivery | Passed on both the API 34 emulator and Motorola after the isolated app process exited |
| Gitleaks 8.30.1 | Current tracked and unignored working-tree snapshot: 0 findings |
| Trivy 0.74.0 | Current tracked source snapshot: 0 reported vulnerabilities, secrets or misconfigurations; dependencies are unchanged from the prior exact-runtime scan |

The final universal debug APK is 96,182,601 bytes with SHA-256
`7bb42949bda6a5927c68c2df719e775e9637f354aed2416ac9a746d410532a4b`.
The optimized unsigned universal release APK is 71,929,685 bytes with SHA-256
`7fa5d87ea9f1e60f61a918e2442b159fbd8873a80d4374dfe6be46152a990956`.
It is an unpublished development artifact, not a production release.

The expanded native suite exposed two real defects before going green: Argon2 memory
and iteration costs were passed in the wrong positional order, and migration 10→11
created its FTS columns in an order Room rejected. Both are fixed and pinned by the
new device tests. The reminder pass also found an Android 12 API-level lint failure,
which is now represented with a min-SDK-safe broadcast action string.

The first expanded emulator run then exposed a product UX defect: selecting a reminder
could automatically open Android's Alarms & reminders settings while the user was still
editing the note. Exact timing is now an explicit Settings action. Without that access,
the app retains its allow-while-idle AlarmManager delivery plus durable WorkManager
fallback. The final uninterrupted API 34 aggregate passed with only the host-driven
Doze and physical speech-provider checks skipped in-process; both were exercised in
their correct environments.

The final Motorola aggregate passed. Its two skips are deliberate: synthetic
ContactsProvider injection is emulator-only, and forced idle must be driven outside
Android Test Orchestrator. The phone's real speech-recognition provider was visible.
No physical contact was created, modified or deleted. Temporary animation settings
were restored, forced idle was released, disposable QA packages were removed, and
neither the production nor existing debug package was replaced or cleared.

Google Drive OAuth is not configured in this checkout, so a live account round trip
could not be truthfully exercised. The app now detects that state, removes the broken
sign-in action, explains that Drive is unavailable in this build, and keeps local
encrypted backup available. OAuth sign-in, token refresh and background Drive work are
also guarded against placeholder configuration.

CI no longer silently skips Android tests when its persistent AVD is stopped. It starts
the designated `kaavalan-test` AVD, fails if it cannot boot, clears only stale isolated
QA identities, runs the aggregate suite, and then runs the external forced-Doze probe.

## Product polish confirmed

- Reminder capture is day-first: Today, Tomorrow, This week and This month lead to
  practical time choices, with exact date/time as the secondary path.
- Instruction reminders use a private high-importance channel, heads-up priority,
  one short notification sound, gentle vibration, Snooze 1 hour and Done actions.
  Android and the user still control whether a particular device permits banners
  and sound.
- Voice capture streams partial recognition, replaces prior partial text, requests
  the device recognizer's offline path first and allows pauses between field-note
  phrases.
- Today and Instructions cards are compact and use restrained status colour families
  for open, in-progress, waiting and closed states without red overdue treatment.
- Phone contact import supports checkbox multi-selection across searches, one selected
  number per source contact, Select all results, Clear selection and one batch import.
  The Android contacts provider remains read-only.

## Hardening changes

- OAuth callbacks now require the exact configured action, scheme, host, port and
  path. PKCE state comparison uses constant-work byte comparison; mismatches cannot
  erase an active login; matched verifier state is consumed before code exchange so
  callbacks cannot be replayed.
- Google Drive file IDs, page sizes and filenames are bounded. Multipart filenames
  cannot inject headers, server error bodies are not exposed, and encrypted downloads
  remain raw bytes instead of being corrupted by UTF-8 decode/re-encode.
- Backup and restore key material is cleared from mutable character arrays. Workers
  propagate coroutine cancellation and expose generic user-safe failures.
- Crash logs exclude exception messages and retain only exception types and stack
  structure. Other raw throwable and server-message logging was removed from the
  reviewed paths.
- Cleartext traffic is disabled. OAuth history/recents behavior is restricted,
  widget receivers are non-exported, and device-transfer rules exclude files,
  databases, preferences and external storage as well as root data.
- Supabase administrative bearer comparison is constant-work, edge-function inputs
  are bounded, database errors are not returned to clients, resource IDs are UUID
  validated, free-text search no longer builds a raw PostgREST filter string, and IST
  day boundaries use fixed UTC arithmetic.
- Every third-party GitHub Action is pinned to an immutable 40-character upstream
  commit. Step outputs are passed through environment variables instead of being
  interpolated directly into shell scripts.
- A stable reminder-chip test tag replaced an ambiguous text-only device-test selector.

## Earlier v2.7.2 baseline verification

| Gate | Result |
|---|---|
| JVM unit tests | 882 discovered; 870 passed, 12 intentional skips, 0 failed, 0 errors |
| Android lint | Passed; 0 errors (remaining warnings are dependency/update/resource hygiene, not security blockers) |
| Debug APK | Passed; universal and per-ABI APKs assembled |
| Optimized release APK | Passed with R8/resource shrinking; unsigned universal and per-ABI APKs assembled |
| Android 14/API 34 device suite | All 26 scenarios passed on task-owned `kaavalan-test` using isolated `com.kaavalan.note.debug.officer` |
| Deno edge tests | 2 passed, 0 failed; all 9 function files pass formatter check |
| Penpot generator | 27 screen boards and 3 reference sheets generated successfully |

The first device attempt executed no tests because an obsolete QA-package signature
conflicted with the current debug key; the production package was not changed. An
intermediate complete run passed 25 scenarios and exposed one ambiguous text-only test
selector. After adding a stable reminder-chip tag, that scenario passed on a focused
rerun and the final aggregate run passed all 26 scenarios with 0 failures and 0 skips.

## Security-tool results

| Tool | Result |
|---|---|
| Gitleaks 8.30.1 | Current working tree: 0 findings. Full history: 16 matches, all in deleted test fixtures with fake Supabase-shaped tokens or removed vendored workflow content. |
| TruffleHog | Full Git history with `--only-verified`: 0 verified secrets. |
| Trivy 0.74.0 | Tracked source (excluding ignored local emulator state): 0 vulnerabilities, secrets or misconfigurations. Exact release runtime archive scan identified 48 Java packages and 0 vulnerabilities. |
| Semgrep 1.177.0 | 82 relevant official security rules over 457 tracked targets: 12 reviewed findings — 3 required exported entry points and 9 AES-GCM review notices. |
| OpenGrep 1.30.0 | Same rule set and same 12 reviewed findings; one pre-existing Kotlin parser limitation on `fun interface`. |
| SonarQube Community Build 26.9 | 0 bugs, 0 vulnerabilities and 0 security hotspots. Remaining findings are 154 maintainability smells, dominated by complexity/parameter-count and repeated design-fixture literals. |
| OWASP ZAP baseline | Promo page: 0 failures, 8 warnings and 2 informational alerts. Warnings are deployment-header behavior from the temporary Python static server, not Android app endpoints. |
| OWASP Dependency-Check 13.0.0 | Could not produce a valid scan because the local NVD database contained no documents and no NVD API key was available. Exact-runtime Trivy scanning supplies the completed SCA result for this pass. |
| SQLmap | Not run: the product has no HTTP parameter backed by a SQL endpoint. Its database is local encrypted Room/SQLCipher; inventing a target would not test the app. |

Semgrep's exported-component findings were manually reviewed: `MainActivity` is the
launcher, the OAuth activity is the browser callback with exact URI/state validation,
and the share alias is the user-selected Android share target. AES-GCM encryption paths
generate fresh random 12-byte nonces (and fresh salts where key derivation is used);
decrypt paths consume the nonce stored with the authenticated ciphertext.

## Validation artifacts and release boundary

- Debug universal APK: `app/build/outputs/apk/debug/app-universal-debug.apk`
- Unsigned optimized universal APK:
  `app/build/outputs/apk/release/app-universal-release-unsigned.apk`
- Android lint report: `app/build/reports/lint-results-debug.html`
- Device-test report: `app/build/reports/androidTests/connected/debug/index.html`

The unsigned release APK is a build-validation artifact only. It cannot safely update
the installed `com.kaavalan.note` application and must not be published. Only the final
v2.7.2 universal APK produced by the release process with the existing pinned signing
identity was published to the Obtainium release channel. Its SHA-256 is
`93127f7cee27c4b798b1bf01280e962643ceeaf3f29d4e4731d2415903dcc098`; the public asset
digest matches the locally verified file.

This pass covers one Motorola physical phone plus an API 34 emulator, not a physical
OEM matrix. It does not claim TalkBack certification, a completed 7–14 day elapsed-time
soak, live Google Drive account exchange, or speech-recognition accuracy across OEMs and
languages. Those external, time-dependent and additional-hardware checks remain release
acceptance work; the build now fails safely where configuration is absent and the CI
device gate can no longer disappear silently.
