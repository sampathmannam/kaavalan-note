# KaavalanNote end-to-end hardening and polish — 2026-09-14

## Status

Completed on branch `feat/end-to-end-hardening-and-polish`, based on the released
v2.7.1 commit `ae268fbffa8d21e8732be3f24f076251bbaf01a3`. This pass does not change the
application version, sign an APK, create a tag, push a branch or publish a release.

The cloud Penpot file is named **KaavalanNote field notebook UI v2.7**. It contains
Foundations, Components and Screens & states pages, all 27 screen/state boards,
light and dark component sheets, and the light, dark, spacing, radius, size and
typography token sets. The repository source remains under `design/penpot-v2.7/`.

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

## Verification

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

## Artifacts and remaining release boundary

- Debug universal APK: `app/build/outputs/apk/debug/app-universal-debug.apk`
- Unsigned optimized universal APK:
  `app/build/outputs/apk/release/app-universal-release-unsigned.apk`
- Android lint report: `app/build/reports/lint-results-debug.html`
- Device-test report: `app/build/reports/androidTests/connected/debug/index.html`

The unsigned release APK is a build-validation artifact only. It cannot safely update
the installed `com.kaavalan.note` application and must not be published. A real release
still requires an explicit release decision, a version/code increment, the existing
pinned signing identity, signed-upgrade validation, commit/tag/push and publication.

This pass does not claim a physical-phone matrix, TalkBack certification, live Google
Drive account exchange, or real speech-recognition accuracy across OEM recognizers.
The emulator verifies the app-side contracts; those external/device-dependent checks
remain release acceptance work.
